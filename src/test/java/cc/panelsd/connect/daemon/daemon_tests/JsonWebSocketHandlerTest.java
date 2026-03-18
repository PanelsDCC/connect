package cc.panelsd.connect.daemon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JsonWebSocketHandlerTest {

    private JsonWebSocketHandler server;
    private FakeAccessoryService accessoryService;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
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
}

