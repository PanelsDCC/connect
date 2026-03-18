package cc.panelsd.connect.daemon;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonMessageHandlerTest {

    private JsonMessageHandler handler;
    private RecordingHandler recordingHandler;

    @BeforeEach
    void setUp() {
        handler = new JsonMessageHandler();
        recordingHandler = new RecordingHandler();
        handler.registerTypeHandler("things", recordingHandler);
    }

    @Test
    void dispatchesListToRegisteredHandler() {
        JsonObject message = new JsonObject();
        message.addProperty("list", "things");

        handler.handle(message);
        assertEquals("list", recordingHandler.lastMethod);
    }

    @Test
    void unknownTypeReturnsError() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "missing");

        JsonObject response = handler.handle(message);
        assertEquals("error", response.get("type").getAsString());
        assertEquals(404, response.getAsJsonObject("data").get("code").getAsInt());
    }

    @Test
    void unsupportedMethodIsRejected() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "things");
        message.addProperty("method", "delete");

        JsonObject response = handler.handle(message);
        assertEquals("error", response.get("type").getAsString());
        assertEquals(400, response.getAsJsonObject("data").get("code").getAsInt());
    }

    private static final class RecordingHandler implements JsonMessageHandler.TypeHandler {
        private String lastMethod;

        @Override
        public JsonObject handle(String method, JsonObject data) {
            lastMethod = method;
            JsonObject resp = new JsonObject();
            resp.addProperty("type", "things");
            resp.add("data", new JsonObject());
            return resp;
        }
    }
}

