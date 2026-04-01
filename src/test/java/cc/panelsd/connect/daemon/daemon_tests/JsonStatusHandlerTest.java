package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cc.panelsd.connect.core.CommandStationConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonStatusHandlerTest {

    @Test
    void returnsConnectionsWithRolesAndInfo() {
        FakeConnection c1 = new FakeConnection("c1", "xnet-elite", true, Map.of("version", "1.0"), "ON");
        FakeConnection c2 = new FakeConnection("c2", "dccpp", false, Map.of(), "OFF");

        JsonStatusHandler.StatusProvider provider = new JsonStatusHandler.StatusProvider() {
            @Override
            public Collection<CommandStationConnection> getConnections() {
                return List.of(c1, c2);
            }

            @Override
            public String getThrottleControllerId() {
                return "c1";
            }

            @Override
            public String getAccessoryControllerId() {
                return "c2";
            }
        };

        JsonStatusHandler handler = new JsonStatusHandler(provider);
        JsonObject response = handler.handle("get", new JsonObject());

        assertEquals("status", response.get("type").getAsString());
        JsonArray conns = response.getAsJsonObject("data").getAsJsonArray("connections");
        assertEquals(2, conns.size());

        JsonObject first = conns.get(0).getAsJsonObject();
        assertEquals("c1", first.get("id").getAsString());
        assertEquals("xnet-elite", first.get("systemType").getAsString());
        assertTrue(first.get("connected").getAsBoolean());
        assertEquals("1.0", first.getAsJsonObject("commandStation").get("version").getAsString());
        assertEquals("ON", first.get("powerStatus").getAsString());
        assertTrue(arrayContains(first.getAsJsonArray("roles"), "throttles"));

        JsonObject second = conns.get(1).getAsJsonObject();
        assertTrue(arrayContains(second.getAsJsonArray("roles"), "accessories"));
    }

    private boolean arrayContains(JsonArray array, String value) {
        if (array == null) return false;
        for (int i = 0; i < array.size(); i++) {
            if (value.equals(array.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static final class FakeConnection implements CommandStationConnection {
        private final String id;
        private final String systemType;
        private final boolean connected;
        private final Map<String, String> info;
        private final String power;

        FakeConnection(String id, String systemType, boolean connected, Map<String, String> info, String power) {
            this.id = id;
            this.systemType = systemType;
            this.connected = connected;
            this.info = info;
            this.power = power;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getSystemType() {
            return systemType;
        }

        @Override
        public void connect() throws IOException {
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public cc.panelsd.connect.core.ThrottleSession openThrottle(int address, boolean longAddress) throws IOException {
            return null;
        }

        @Override
        public cc.panelsd.connect.core.ProgrammerSession getProgrammer() {
            return null;
        }

        @Override
        public cc.panelsd.connect.core.AccessoryController getAccessoryController() {
            return null;
        }

        @Override
        public Map<String, String> getCommandStationInfo() {
            return info;
        }

        @Override
        public String getPowerStatus() {
            return power;
        }

        @Override
        public void setPower(String powerState) throws IOException {
            // Test implementation - no-op
        }

        @Override
        public void requestVersion() throws IOException {
        }

        @Override
        public void close() {
        }
    }
}

