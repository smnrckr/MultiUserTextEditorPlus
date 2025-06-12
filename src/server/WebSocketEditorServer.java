package server;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import common.Protocol;

public class WebSocketEditorServer extends WebSocketServer {
    private static Map<WebSocket, String> clients = new HashMap<>();
    private static Map<String, String> fileContents = Collections.synchronizedMap(new HashMap<>());
    private static Set<String> fileNames = Collections.synchronizedSet(new HashSet<>());
    private static Map<String, String> fileOwners = Collections.synchronizedMap(new HashMap<>());
    private static Map<String, Set<String>> fileEditors = Collections.synchronizedMap(new HashMap<>());
    private static Set<String> activeUsers = Collections.synchronizedSet(new HashSet<>());

    public WebSocketEditorServer(int port) {
        super(new InetSocketAddress(port));
        loadFiles();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Yeni bağlantı: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username = clients.get(conn);
        if (username != null) {
            activeUsers.remove(username);
            broadcastActiveUsers();
            clients.remove(conn);
        }
        System.out.println("Bağlantı kapandı: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Protocol msg = Protocol.deserialize(message);
        
        if (msg.type.equals("MSG_HELLO")) {
            handleHello(conn, msg);
            return;
        }

        switch (msg.type) {
            case "MSG_CREATE":
                handleCreate(conn, msg);
                break;
            case "MSG_EDIT":
                handleEdit(conn, msg);
                break;
            case "MSG_JOIN":
                handleJoin(conn, msg);
                break;
            case "MSG_LIST":
                handleList(conn);
                break;
            case "MSG_SET_EDITORS":
                handleSetEditors(conn, msg);
                break;
            case "MSG_GET_EDITORS":
                handleGetEditors(conn, msg);
                break;
            case "MSG_LEAVE":
                handleLeave(conn, msg);
                break;
        }
    }

    private void handleHello(WebSocket conn, Protocol msg) {
        clients.put(conn, msg.username);
        activeUsers.add(msg.username);
        broadcastActiveUsers();
    }

    private void handleCreate(WebSocket conn, Protocol msg) {
        if (!fileNames.contains(msg.fileName)) {
            fileNames.add(msg.fileName);
            fileContents.put(msg.fileName, "");
            fileOwners.put(msg.fileName, msg.username);
            fileEditors.put(msg.fileName, new HashSet<>());

            Protocol response = new Protocol("MSG_INFO", "SERVER", msg.fileName, "Dosya oluşturuldu.");
            response.statusCode = "201 Created";
            conn.send(response.serialize());
        } else {
            Protocol response = new Protocol("MSG_DENY", "SERVER", msg.fileName, "Bu isimde bir dosya zaten var.");
            response.statusCode = "409 Conflict";
            conn.send(response.serialize());
        }
    }

    private void handleEdit(WebSocket conn, Protocol msg) {
        String editor = msg.username;
        String owner = fileOwners.get(msg.fileName);
        Set<String> editorsSet = fileEditors.get(msg.fileName);

        boolean isOwner = editor.equals(owner);
        boolean isEditor = editorsSet != null && editorsSet.contains(editor);

        if (isOwner || isEditor) {
            // Önce dosya içeriğini güncelle
            fileContents.put(msg.fileName, msg.content);
            
            // Dosyayı kaydet
            saveToFile(msg.fileName, msg.content);

            // Düzenleyicilere yeni içeriği gönder
            Protocol editMsg = new Protocol("MSG_EDIT", editor, msg.fileName, msg.content);
            editMsg.statusCode = "200 OK";
            
            // Düzenleyicilere ve dosya sahibine gönder
            for (Map.Entry<WebSocket, String> entry : clients.entrySet()) {
                WebSocket client = entry.getKey();
                String username = entry.getValue();
                
                // Kendisine gönderme
                if (username.equals(editor)) continue;
                
                // Dosya sahibi veya düzenleyiciyse gönder
                if (username.equals(owner) || (editorsSet != null && editorsSet.contains(username))) {
                    client.send(editMsg.serialize());
                }
            }
        } else {
            Protocol response = new Protocol("MSG_DENY", "SERVER", msg.fileName, "Dosyayı düzenleme yetkiniz yok!");
            response.statusCode = "403 Forbidden";
            conn.send(response.serialize());
        }
    }

    private void handleJoin(WebSocket conn, Protocol msg) {
        if (fileContents.containsKey(msg.fileName)) {
            Protocol contentMsg = new Protocol("MSG_EDIT", "SERVER", msg.fileName, fileContents.get(msg.fileName));
            contentMsg.statusCode = "200 OK";
            conn.send(contentMsg.serialize());
        }

        Set<String> editors = fileEditors.getOrDefault(msg.fileName, Collections.emptySet());
        String owner = fileOwners.getOrDefault(msg.fileName, "");
        String combinedMsg = owner + " (dosya sahibi)" + ";editors:" + String.join(",", editors);

        Protocol editorsMsg = new Protocol("MSG_EDITORS", "SERVER", msg.fileName, combinedMsg);
        editorsMsg.statusCode = "200 OK";
        conn.send(editorsMsg.serialize());
    }

    private void handleList(WebSocket conn) {
        String fileList = String.join(",", fileNames);
        Protocol response = new Protocol("MSG_LIST", "SERVER", "", fileList);
        response.statusCode = "200 OK";
        conn.send(response.serialize());
    }

    private void handleSetEditors(WebSocket conn, Protocol msg) {
        String owner = fileOwners.get(msg.fileName);
        if (!msg.username.equals(owner)) {
            Protocol response = new Protocol("MSG_DENY", "SERVER", msg.fileName, "Sadece dosya sahibi düzenleyicileri değiştirebilir.");
            response.statusCode = "403 Forbidden";
            conn.send(response.serialize());
            return;
        }

        // Mevcut editörleri al veya yeni set oluştur
        Set<String> editors = fileEditors.getOrDefault(msg.fileName, new HashSet<>());
        
        // Yeni editörleri ekle
        if (!msg.content.isEmpty()) {
            String[] newEditors = msg.content.split(",");
            for (String editor : newEditors) {
                String trimmedEditor = editor.trim();
                if (!trimmedEditor.isEmpty() && !trimmedEditor.equals(owner)) {
                    editors.add(trimmedEditor);
                }
            }
        }

        // Güncellenmiş listeyi kaydet
        fileEditors.put(msg.fileName, editors);

        String combinedMsg = owner + " (dosya sahibi)" + ";editors:" + String.join(",", editors);
        Protocol response = new Protocol("MSG_EDITORS", "SERVER", msg.fileName, combinedMsg);
        response.statusCode = "200 OK";
        broadcast(response.serialize());
    }

    private void handleGetEditors(WebSocket conn, Protocol msg) {
        Set<String> editors = fileEditors.getOrDefault(msg.fileName, Collections.emptySet());
        String owner = fileOwners.getOrDefault(msg.fileName, "");
        String combinedMsg = owner + " (dosya sahibi)" + ";editors:" + String.join(",", editors);

        Protocol response = new Protocol("MSG_EDITORS", "SERVER", msg.fileName, combinedMsg);
        response.statusCode = "200 OK";
        conn.send(response.serialize());
    }

    private void handleLeave(WebSocket conn, Protocol msg) {
        Set<String> editors = fileEditors.get(msg.fileName);
        if (editors != null) {
            editors.remove(msg.username);
        }

        Protocol response = new Protocol("MSG_INFO", "SERVER", msg.fileName, "Dosyadan çıkış yapıldı.");
        response.statusCode = "200 OK";
        conn.send(response.serialize());
    }

    private void broadcastActiveUsers() {
        String users = String.join(",", activeUsers);
        Protocol msg = new Protocol("MSG_ACTIVE_USERS", "SERVER", "", users);
        msg.statusCode = "200 OK";
        broadcast(msg.serialize());
    }

    private void broadcastToFileEditors(String fileName, String message, String excludeUser) {
        for (Map.Entry<WebSocket, String> entry : clients.entrySet()) {
            if (!entry.getValue().equals(excludeUser)) {
                Set<String> editors = fileEditors.get(fileName);
                String owner = fileOwners.get(fileName);
                if (entry.getValue().equals(owner) || (editors != null && editors.contains(entry.getValue()))) {
                    entry.getKey().send(message);
                }
            }
        }
    }

    private void loadFiles() {
        File dir = new File("sunucu_dosyalar");
        if (!dir.exists()) {
            System.out.println("Dosya klasörü bulunamadı, yeni klasör oluşturulacak.");
            dir.mkdir();
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()), "UTF-8");
                fileContents.put(file.getName(), content);
                fileNames.add(file.getName());
                System.out.println("Yüklendi: " + file.getName());
            } catch (IOException e) {
                System.err.println("Dosya yüklenirken hata: " + file.getName());
            }
        }
    }

    private void saveToFile(String fileName, String content) {
        File dir = new File("sunucu_dosyalar");
        if (!dir.exists()) dir.mkdir();

        try {
            Files.write(new File(dir, fileName).toPath(), content.getBytes("UTF-8"));
        } catch (IOException e) {
            System.err.println("Dosya kaydedilirken hata: " + fileName);
            e.printStackTrace();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("Hata oluştu:");
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket sunucusu başlatıldı. Port: " + getPort());
    }
} 