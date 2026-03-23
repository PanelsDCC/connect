package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cc.panelsd.connect.core.ThrottleSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class JsonThrottleHandlerTest {

    private FakeThrottleService service;
    private JsonThrottleHandler handler;
    private JsonMessageHandler messageHandler;
    private RecordingBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        service = new FakeThrottleService();
        // Use 0ms interval to disable throttling in tests (immediate execution)
        handler = new JsonThrottleHandler(service, 0);
        broadcaster = new RecordingBroadcaster();
        handler.setBroadcaster(broadcaster);
        messageHandler = new JsonMessageHandler();
        messageHandler.registerTypeHandler("throttle", handler);
        messageHandler.registerTypeHandler("throttles", handler);
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
    }

    @Test
    void postWithAddressCreatesThrottle() {
        JsonObject post = new JsonObject();
        post.addProperty("type", "throttle");
        post.addProperty("method", "post");
        post.addProperty("clientId", "client1");
        JsonObject data = new JsonObject();
        data.addProperty("address", 754);
        data.addProperty("longAddress", true);
        data.addProperty("speed", 0.5);
        post.add("data", data);

        JsonObject resp = messageHandler.handle(post);
        assertEquals("throttle", resp.get("type").getAsString());
        JsonObject respData = resp.getAsJsonObject("data");
        assertEquals(754, respData.get("address").getAsInt());
        assertTrue(respData.get("longAddress").getAsBoolean());
        assertNotNull(respData.get("throttle").getAsString());
    }

    @Test
    void listReturnsAllThrottles() {
        service.openThrottle(null, 10, false);
        service.openThrottle("connB", 20, true);

        JsonObject list = new JsonObject();
        list.addProperty("list", "throttles");

        JsonObject resp = messageHandler.handle(list);
        JsonArray arr = resp.getAsJsonArray("data");
        assertEquals(2, arr.size());
    }

    @Test
    void speedUpdateAppliesToSession() {
        // Create throttle first
        String id = service.openThrottle(null, 5, false);
        
        JsonObject post = new JsonObject();
        post.addProperty("type", "throttle");
        post.addProperty("method", "post");
        post.addProperty("clientId", "client1");
        JsonObject data = new JsonObject();
        data.addProperty("address", 5);
        data.addProperty("speed", 0.75);
        post.add("data", data);

        JsonObject resp = messageHandler.handle(post);
        JsonObject respData = resp.getAsJsonObject("data");
        if (respData.has("updated")) {
            assertTrue(respData.get("updated").getAsBoolean());
        }
        // Find the session by address
        FakeThrottleSession session = null;
        for (FakeThrottleSession s : service.sessions.values()) {
            if (s.getAddress() == 5 && !s.isLongAddress()) {
                session = s;
                break;
            }
        }
        assertNotNull(session);
        assertEquals(0.75f, session.speed);
        assertEquals("throttle", broadcaster.lastType);
        assertEquals("patch", broadcaster.lastMethod);
        assertEquals(5, broadcaster.lastData.get("address").getAsInt());
        assertEquals(0.75f, broadcaster.lastData.get("speed").getAsFloat());
    }

    @Test
    void functionUpdateWorksConcurrently() {
        // Create throttle first
        service.openThrottle(null, 42, false);
        
        JsonObject post1 = new JsonObject();
        post1.addProperty("type", "throttle");
        post1.addProperty("method", "post");
        post1.addProperty("clientId", "client1");
        JsonObject data1 = new JsonObject();
        data1.addProperty("address", 42);
        data1.addProperty("F1", true);
        post1.add("data", data1);

        JsonObject post2 = new JsonObject();
        post2.addProperty("type", "throttle");
        post2.addProperty("method", "post");
        post2.addProperty("clientId", "client2");
        JsonObject data2 = new JsonObject();
        data2.addProperty("address", 42);
        data2.addProperty("F2", true);
        post2.add("data", data2);

        // Both should succeed (functions don't require locking)
        JsonObject resp1 = messageHandler.handle(post1);
        JsonObject resp2 = messageHandler.handle(post2);
        assertEquals("throttle", resp1.get("type").getAsString());
        assertEquals("throttle", resp2.get("type").getAsString());
    }

    @Test
    void speedDirectionLockingPreventsConcurrentControl() {
        // Create throttle first
        service.openThrottle(null, 10, false);
        
        JsonObject post1 = new JsonObject();
        post1.addProperty("type", "throttle");
        post1.addProperty("method", "post");
        post1.addProperty("clientId", "client1");
        JsonObject data1 = new JsonObject();
        data1.addProperty("address", 10);
        data1.addProperty("speed", 0.5);
        post1.add("data", data1);

        JsonObject post2 = new JsonObject();
        post2.addProperty("type", "throttle");
        post2.addProperty("method", "post");
        post2.addProperty("clientId", "client2");
        JsonObject data2 = new JsonObject();
        data2.addProperty("address", 10);
        data2.addProperty("speed", 0.7);
        post2.add("data", data2);

        // First client should succeed
        JsonObject resp1 = messageHandler.handle(post1);
        assertEquals("throttle", resp1.get("type").getAsString());
        
        // Second client should get 409 Conflict
        JsonObject resp2 = messageHandler.handle(post2);
        assertEquals("error", resp2.get("type").getAsString());
        assertEquals(409, resp2.getAsJsonObject("data").get("code").getAsInt());
        assertTrue(resp2.getAsJsonObject("data").get("message").getAsString().contains("busy"));
    }

    @Test
    void functionUpdatesApplyWhenAnotherClientHoldsSpeedLockEvenIfSpeedAlsoSent() {
        service.openThrottle(null, 11, false);

        JsonObject post1 = new JsonObject();
        post1.addProperty("type", "throttle");
        post1.addProperty("method", "post");
        post1.addProperty("clientId", "client1");
        JsonObject data1 = new JsonObject();
        data1.addProperty("address", 11);
        data1.addProperty("speed", 0.5);
        post1.add("data", data1);
        assertEquals("throttle", messageHandler.handle(post1).get("type").getAsString());

        JsonObject post2 = new JsonObject();
        post2.addProperty("type", "throttle");
        post2.addProperty("method", "post");
        post2.addProperty("clientId", "client2");
        JsonObject data2 = new JsonObject();
        data2.addProperty("address", 11);
        data2.addProperty("speed", 0.9);
        JsonObject functions = new JsonObject();
        functions.addProperty("3", true);
        data2.add("functions", functions);
        post2.add("data", data2);

        JsonObject resp2 = messageHandler.handle(post2);
        assertEquals("throttle", resp2.get("type").getAsString());
        JsonObject respData = resp2.getAsJsonObject("data");
        assertTrue(respData.get("updated").getAsBoolean());
        assertTrue(respData.get("speedDirectionRejected").getAsBoolean());
        assertEquals(0.5f, respData.get("speed").getAsFloat());

        FakeThrottleSession session = null;
        for (FakeThrottleSession s : service.sessions.values()) {
            if (s.getAddress() == 11 && !s.isLongAddress()) {
                session = s;
                break;
            }
        }
        assertNotNull(session);
        assertTrue(session.getFunction(3));
        assertEquals(0.5f, session.speed);
    }

    private static final class FakeThrottleService implements JsonThrottleHandler.ThrottleService {
        private final Map<String, FakeThrottleSession> sessions = new ConcurrentHashMap<>();
        private int counter = 0;

        @Override
        public String openThrottle(String connectionId, int address, boolean longAddress) {
            String conn = connectionId == null ? "connA" : connectionId;
            // Use format that matches sessionId: "connectionId:address:longAddress"
            String id = conn + ":" + address + ":" + longAddress;
            // If throttle already exists for this address, return existing ID
            if (!sessions.containsKey(id)) {
                sessions.put(id, new FakeThrottleSession(conn, address, longAddress));
            }
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
        private boolean closed = false;

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
            closed = true;
        }
    }

    private static final class RecordingBroadcaster implements JsonBroadcaster {
        String lastType;
        String lastMethod;
        JsonObject lastData;

        @Override
        public void broadcast(JsonObject message) {
            lastType = message.get("type").getAsString();
            lastMethod = message.has("method") ? message.get("method").getAsString() : null;
            lastData = message.getAsJsonObject("data");
        }
    }
}

