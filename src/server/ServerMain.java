package server;

public class ServerMain {
    public static void main(String[] args) {
        WebSocketEditorServer server = new WebSocketEditorServer(12345);
        server.start();
        System.out.println("WebSocket Sunucusu başlatıldı...");
    }
}