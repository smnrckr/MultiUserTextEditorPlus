package client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.function.Consumer;

public class WebSocketEditorClient extends WebSocketClient {
    private Consumer<String> messageHandler;

    public WebSocketEditorClient(String serverUri, Consumer<String> messageHandler) throws Exception {
        super(new URI(serverUri));
        this.messageHandler = messageHandler;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("Bağlantı açıldı");
    }

    @Override
    public void onMessage(String message) {
        if (messageHandler != null) {
            messageHandler.accept(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("Bağlantı kapandı: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("WebSocket hatası:");
        ex.printStackTrace();
    }
} 