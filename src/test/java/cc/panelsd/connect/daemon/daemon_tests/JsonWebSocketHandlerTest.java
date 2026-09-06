package cc.panelsd.connect.daemon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.panelsd.connect.core.ThrottleSession;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JsonWebSocketHandlerTest {

    private JsonWebSocketHandler server;
    private JsonThrottleHandler throttleHandler;
    private FakeAccessoryService accessoryService;
    private FakeThrottleService throttleService;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
        if (throttleHandler != null) {
            throttleHandler.shutdown();
        }
    }

    @Test
    void postAndGetAccessoriesOverWebSocket() throws Exception {
        accessoryService = new FakeAccessoryService();
        JsonMessageHandler handler = new JsonMessageHandler();
        handler.registerTypeHandler("accessories", new JsonAccessoriesHandler(accessoryService));
        int port = findFreePort();

        server = new JsonWebSocketHandler(port, "/json", handler);
        server.start();
        Thread.sleep(100); // allow server startup

        RecordingWebSocketClient client = new RecordingWebSocketClient(new URI("ws://localhost:" + port + "/json"));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));

        client.send("{\"type\":\"accessories\",\"method\":\"post\",\"data\":{\"accessories\":[{\"name\":\"A\",\"state\":\"closed\"}],\"commands\":[{\"number\":12,\"state\":\"closed\"}]}}");
        String postResponse = client.messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(postResponse);
        JsonObject postObj = JsonParser.parseString(postResponse).getAsJsonObject();
        assertEquals("accessories", postObj.get("type").getAsString());
        assertEquals(1, postObj.getAsJsonObject("data").getAsJsonArray("commands").size());
        assertEquals(12, accessoryService.lastAddress);
        assertTrue(accessoryService.lastClosed);

        client.send("{\"type\":\"accessories\",\"data\":{\"name\":\"A\"}}");
        String getResponse = client.messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(getResponse);
        JsonObject getObj = JsonParser.parseString(getResponse).getAsJsonObject();
        assertEquals("closed", getObj.getAsJsonObject("data").get("state").getAsString());

        client.closeBlocking();
    }

    @Test
    void twoWebSocketsCannotControlSpeedSimultaneouslyWhileLockHeld() throws Exception {
        int port = startThrottleWebSocketServer();

        RecordingWebSocketClient clientA = connectClient(port);
        RecordingWebSocketClient clientB = connectClient(port);
        try {
            JsonObject respA = sendAndAwait(clientA, "req-a-speed",
                    "{\"id\":\"req-a-speed\",\"type\":\"throttle\",\"method\":\"post\","
                            + "\"data\":{\"address\":10,\"longAddress\":false,\"speed\":0.5,\"forward\":true}}");
            assertEquals("throttle", respA.get("type").getAsString());
            assertEquals(0.5f, respA.getAsJsonObject("data").get("speed").getAsFloat());

            JsonObject respB = sendAndAwait(clientB, "req-b-speed",
                    "{\"id\":\"req-b-speed\",\"type\":\"throttle\",\"method\":\"post\","
                            + "\"data\":{\"address\":10,\"longAddress\":false,\"speed\":0.7}}");
            assertEquals("error", respB.get("type").getAsString());
            assertEquals(409, respB.getAsJsonObject("data").get("code").getAsInt());
            assertTrue(respB.getAsJsonObject("data").get("message").getAsString().contains("busy"));

            FakeThrottleSession session = requireSession(10, false);
            assertEquals(0.5f, session.speed);

            JsonObject respBDir = sendAndAwait(clientB, "req-b-dir",
                    "{\"id\":\"req-b-dir\",\"type\":\"throttle\",\"method\":\"post\","
                            + "\"data\":{\"address\":10,\"longAddress\":false,\"forward\":false}}");
            assertEquals("error", respBDir.get("type").getAsString());
            assertEquals(409, respBDir.getAsJsonObject("data").get("code").getAsInt());
            assertTrue(session.forward);
        } finally {
            clientA.closeBlocking();
            clientB.closeBlocking();
        }
    }

    @Test
    void functionControlDoesNotAcquireOrRequireSpeedDirectionLock() throws Exception {
        int port = startThrottleWebSocketServer();

        RecordingWebSocketClient clientA = connectClient(port);
        RecordingWebSocketClient clientB = connectClient(port);
        try {
            JsonObject respA = sendAndAwait(clientA, "req-a-lock",
                    "{\"id\":\"req-a-lock\",\"type\":\"throttle\",\"method\":\"post\","
                            + "\"data\":{\"address\":11,\"longAddress\":false,\"speed\":0.4}}");
            assertEquals("throttle", respA.get("type").getAsString());

            JsonObject respBFn = sendAndAwait(clientB, "req-b-fn",
                    "{\"id\":\"req-b-fn\",\"type\":\"throttle\",\"method\":\"post\","
                            + "\"data\":{\"address\":11,\"longAddress\":false,\"functions\":{\"1\":true,\"0\":false}}}");
            assertEquals("throttle", respBFn.get("type").getAsString());
            assertTrue(respBFn.getAsJsonObject("data").get("updated").getAsBoolean());
            assertFalse(respBFn.getAsJsonObject("data").has("speedDirectionRejected"));

            FakeThrottleSession session = requireSession(11, false);
            assertTrue(session.getFunction(1));
            assertFalse(session.getFunction(0));
            assertEquals(0.4f, session.speed);

            // Function interaction must not steal the throttle lock
            JsonObject respBSpeed = sendAndAwait(clientB, "req-b-speed-after-fn",
                    "{\"id\":\"req-b-speed-after-fn\",\"type\":\"throttle\",\"method\":\"post\","
                            + "\"data\":{\"address\":11,\"longAddress\":false,\"speed\":0.9}}");
            assertEquals("error", respBSpeed.get("type").getAsString());
            assertEquals(409, respBSpeed.getAsJsonObject("data").get("code").getAsInt());
            assertEquals(0.4f, session.speed);

            // Original throttle holder can still refresh the lock
            JsonObject respAAgain = sendAndAwait(clientA, "req-a-refresh",
                    "{\"id\":\"req-a-refresh\",\"type\":\"throttle\",\"method\":\"post\","
                            + "\"data\":{\"address\":11,\"longAddress\":false,\"speed\":0.55}}");
            assertEquals("throttle", respAAgain.get("type").getAsString());
            assertEquals(0.55f, session.speed);
        } finally {
            clientA.closeBlocking();
            clientB.closeBlocking();
        }
    }

    private int startThrottleWebSocketServer() throws Exception {
        throttleService = new FakeThrottleService();
        // Disable speed coalescing so assertions see immediate state
        throttleHandler = new JsonThrottleHandler(throttleService, 0);
        JsonMessageHandler handler = new JsonMessageHandler();
        handler.registerTypeHandler("throttle", throttleHandler);
        handler.registerTypeHandler("throttles", throttleHandler);

        int port = findFreePort();
        server = new JsonWebSocketHandler(port, "/json", handler);
        throttleHandler.setBroadcaster(server.getBroadcaster());
        server.start();
        Thread.sleep(100);
        return port;
    }

    private RecordingWebSocketClient connectClient(int port) throws Exception {
        RecordingWebSocketClient client = new RecordingWebSocketClient(new URI("ws://localhost:" + port + "/json"));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
        return client;
    }

    private JsonObject sendAndAwait(RecordingWebSocketClient client, String requestId, String payload) throws InterruptedException {
        client.send(payload);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                break;
            }
            String message = client.messages.poll(remainingMs, TimeUnit.MILLISECONDS);
            assertNotNull(message, "Timed out waiting for reply id=" + requestId);
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
            if (obj.has("id") && requestId.equals(obj.get("id").getAsString())) {
                return obj;
            }
            // Ignore broadcast patches that lack a matching request id
        }
        fail("Timed out waiting for reply id=" + requestId);
        return null;
    }

    private FakeThrottleSession requireSession(int address, boolean longAddress) {
        FakeThrottleSession session = null;
        for (FakeThrottleSession candidate : throttleService.sessions.values()) {
            if (candidate.getAddress() == address && candidate.isLongAddress() == longAddress) {
                session = candidate;
                break;
            }
        }
        assertNotNull(session);
        return session;
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static final class RecordingWebSocketClient extends WebSocketClient {

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        RecordingWebSocketClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
        }

        @Override
        public void onMessage(String message) {
            messages.add(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
        }
    }

    private static final class FakeAccessoryService implements JsonAccessoriesHandler.AccessoryService {
        private Integer lastAddress;
        private Boolean lastClosed;

        @Override
        public void setTurnout(int address, boolean closed) {
            this.lastAddress = address;
            this.lastClosed = closed;
        }
    }

    private static final class FakeThrottleService implements JsonThrottleHandler.ThrottleService {
        private final Map<String, FakeThrottleSession> sessions = new ConcurrentHashMap<>();

        @Override
        public String openThrottle(String connectionId, int address, boolean longAddress) {
            String conn = connectionId == null ? "connA" : connectionId;
            String id = conn + ":" + address + ":" + longAddress;
            sessions.putIfAbsent(id, new FakeThrottleSession(conn, address, longAddress));
            return id;
        }

        @Override
        public ThrottleSession getThrottle(String throttleId) {
            return sessions.get(throttleId);
        }

        @Override
        public Collection<ThrottleSession> getThrottles() {
            return java.util.Collections.unmodifiableCollection(sessions.values());
        }

        @Override
        public void closeThrottle(String throttleId) {
            FakeThrottleSession sess = sessions.get(throttleId);
            if (sess != null) {
                sess.close();
            }
        }
    }

    private static final class FakeThrottleSession implements ThrottleSession {
        private final String connectionId;
        private final int address;
        private final boolean longAddress;
        private float speed = 0f;
        private boolean forward = true;
        private final Map<Integer, Boolean> functions = new ConcurrentHashMap<>();

        private FakeThrottleSession(String connectionId, int address, boolean longAddress) {
            this.connectionId = connectionId;
            this.address = address;
            this.longAddress = longAddress;
        }

        @Override
        public String getConnectionId() {
            return connectionId;
        }

        @Override
        public int getAddress() {
            return address;
        }

        @Override
        public boolean isLongAddress() {
            return longAddress;
        }

        @Override
        public void setSpeed(float speed) {
            this.speed = speed;
        }

        @Override
        public void setDirection(boolean forward) {
            this.forward = forward;
        }

        @Override
        public void setFunction(int functionNumber, boolean on) {
            functions.put(functionNumber, on);
        }

        @Override
        public float getSpeed() {
            return speed;
        }

        @Override
        public boolean getDirection() {
            return forward;
        }

        @Override
        public boolean getFunction(int functionNumber) {
            return functions.getOrDefault(functionNumber, false);
        }

        @Override
        public void close() {
        }
    }
}

