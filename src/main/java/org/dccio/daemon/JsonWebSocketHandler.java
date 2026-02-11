package org.dccio.daemon;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket front-end that delegates JSON messages to {@link JsonMessageHandler}.
 */
public class JsonWebSocketHandler extends WebSocketServer {

    /**
     * Enable verbose WebSocket logging with:
     *   -Ddccio.ws.debug=true
     */
    private static final boolean DEBUG = Boolean.getBoolean("dccio.ws.debug");

    private final String path;
    private final JsonMessageHandler messageHandler;
    private final Gson gson = new Gson();
    private final Set<WebSocket> connections = ConcurrentHashMap.newKeySet();

    public JsonWebSocketHandler(int port, String path, JsonMessageHandler messageHandler) {
        super(new InetSocketAddress(port));
        this.path = path == null ? "/json" : path;
        this.messageHandler = messageHandler;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String resource = handshake.getResourceDescriptor();
        if (!resource.equals(path) && !resource.startsWith(path + "?")) {
            if (DEBUG) {
                System.out.println("[WS] Rejecting connection on invalid path: " + resource +
                        " from " + conn.getRemoteSocketAddress());
            }
            conn.close(1008, "Invalid path");
            return;
        }
        connections.add(conn);
        if (DEBUG) {
            System.out.println("[WS] Open " + conn.getRemoteSocketAddress() +
                    " path=" + resource);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
        if (DEBUG) {
            System.out.println("[WS] Close " + conn.getRemoteSocketAddress() +
                    " code=" + code + " reason=" + reason + " remote=" + remote);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            long start = DEBUG ? System.nanoTime() : 0L;
            if (DEBUG) {
                System.out.println("[WS] Message from " + conn.getRemoteSocketAddress() +
                        " length=" + message.length() + " payload=" + message);
            }
            // Parse message and add clientId (use connection's remote address as identifier)
            JsonObject messageObj = gson.fromJson(message, JsonObject.class);
            String clientId = conn.getRemoteSocketAddress().toString() + "-" + conn.hashCode();
            messageObj.addProperty("clientId", clientId);
            
            JsonObject response = messageHandler.handle(messageObj);
            String json = gson.toJson(response);
            if (DEBUG) {
                long durationMicros = (System.nanoTime() - start) / 1_000;
                System.out.println("[WS] Response to " + conn.getRemoteSocketAddress() +
                        " in " + durationMicros + " µs payload=" + json);
            }
            conn.send(json);
        } catch (com.google.gson.JsonSyntaxException e) {
            // If JSON parsing fails, let the message handler deal with it
            JsonObject response = messageHandler.handle(message);
            String json = gson.toJson(response);
            if (DEBUG) {
                System.out.println("[WS] Message JSON parse failed, delegating raw string. " +
                        "From=" + conn.getRemoteSocketAddress() +
                        " error=" + e.getMessage() +
                        " payload=" + message);
                System.out.println("[WS] Response (raw-handler) payload=" + json);
            }
            conn.send(json);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("WebSocket error: " + ex.getMessage());
        if (DEBUG) {
            ex.printStackTrace(System.err);
        }
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(60);
        if (DEBUG) {
            System.out.println("[WS] JsonWebSocketHandler started on " +
                    getAddress().getHostString() + ":" + getPort() +
                    " path=" + path);
        }
    }

    public JsonBroadcaster getBroadcaster() {
        return msg -> {
            String json = gson.toJson(msg);
            if (DEBUG) {
                System.out.println("[WS] Broadcast to " + connections.size() +
                        " clients payload=" + json);
            }
            for (WebSocket socket : connections) {
                if (socket.isOpen()) {
                    socket.send(json);
                }
            }
        };
    }

    public void shutdown() {
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

