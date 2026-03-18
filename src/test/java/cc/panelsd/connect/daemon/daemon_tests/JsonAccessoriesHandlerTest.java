package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonAccessoriesHandlerTest {

    private FakeAccessoryService service;
    private JsonMessageHandler handler;
    private RecordingBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        service = new FakeAccessoryService();
        handler = new JsonMessageHandler();
        broadcaster = new RecordingBroadcaster();
        JsonAccessoriesHandler accessoriesHandler = new JsonAccessoriesHandler(service);
        accessoriesHandler.setBroadcaster(broadcaster);
        handler.registerTypeHandler("accessories", accessoriesHandler);
    }

    @Test
    void postStoresAccessoriesAndRunsCommands() {
        JsonObject post = new JsonObject();
        post.addProperty("type", "accessories");
        post.addProperty("method", "post");
        JsonObject data = new JsonObject();
        JsonArray accessories = new JsonArray();
        JsonObject acc = new JsonObject();
        acc.addProperty("name", "yard1");
        acc.addProperty("state", "diverted");
        accessories.add(acc);
        JsonArray commands = new JsonArray();
        JsonObject cmd = new JsonObject();
        cmd.addProperty("number", 7);
        cmd.addProperty("state", "closed");
        commands.add(cmd);
        data.add("accessories", accessories);
        data.add("commands", commands);
        post.add("data", data);

        JsonObject postResponse = handler.handle(post);
        assertEquals("accessories", postResponse.get("type").getAsString());
        assertEquals(1, postResponse.getAsJsonObject("data").getAsJsonArray("accessories").size());
        assertEquals(1, service.calls.size());
        assertEquals(7, service.calls.get(0).address);
        assertTrue(service.calls.get(0).closed);
        assertEquals("accessories", broadcaster.lastType);
        assertEquals("patch", broadcaster.lastMethod);
        assertNotNull(broadcaster.lastData);
        assertEquals(1, broadcaster.lastData.getAsJsonArray().size());

        JsonObject get = new JsonObject();
        get.addProperty("type", "accessories");
        JsonObject getData = new JsonObject();
        getData.addProperty("name", "yard1");
        get.add("data", getData);

        JsonObject getResponse = handler.handle(get);
        assertEquals("accessories", getResponse.get("type").getAsString());
        assertEquals("diverted", getResponse.getAsJsonObject("data").get("state").getAsString());
    }

    @Test
    void getMissingAccessoryReturns404() {
        JsonObject get = new JsonObject();
        get.addProperty("type", "accessories");
        JsonObject getData = new JsonObject();
        getData.addProperty("name", "missing");
        get.add("data", getData);

        JsonObject response = handler.handle(get);
        assertEquals("error", response.get("type").getAsString());
        assertEquals(404, response.getAsJsonObject("data").get("code").getAsInt());
    }

    @Test
    void invalidCommandStateReturns400() {
        JsonObject post = new JsonObject();
        post.addProperty("type", "accessories");
        post.addProperty("method", "post");
        JsonObject data = new JsonObject();
        JsonArray commands = new JsonArray();
        JsonObject cmd = new JsonObject();
        cmd.addProperty("number", 1);
        cmd.addProperty("state", "bad");
        commands.add(cmd);
        data.add("commands", commands);
        post.add("data", data);

        JsonObject response = handler.handle(post);
        assertEquals("error", response.get("type").getAsString());
        assertEquals(400, response.getAsJsonObject("data").get("code").getAsInt());
    }

    private static final class FakeAccessoryService implements JsonAccessoriesHandler.AccessoryService {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public void setTurnout(int address, boolean closed) {
            calls.add(new Call(address, closed));
        }
    }

    private static final class Call {
        private final int address;
        private final boolean closed;

        private Call(int address, boolean closed) {
            this.address = address;
            this.closed = closed;
        }
    }

    private static final class RecordingBroadcaster implements JsonBroadcaster {
        String lastType;
        String lastMethod;
        com.google.gson.JsonElement lastData;

        @Override
        public void broadcast(JsonObject message) {
            lastType = message.get("type").getAsString();
            lastMethod = message.has("method") ? message.get("method").getAsString() : null;
            lastData = message.get("data");
        }
    }
}

