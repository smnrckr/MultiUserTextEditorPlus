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
        System.out.println("🔴 SERVER <- CLIENT: " + message);
        Protocol msg = Protocol.deserialize(message);
        
        switch (msg.getCommand()) {
            case "LOGIN":
                handleLogin(conn, msg.getArgs()[0]);
                break;
            case "EDIT":
                handleEdit(conn, msg.getArgs()[0], msg.getArgs()[1], msg.getContent());
                break;
            case "LIST_FILES_REQUEST":
                handleListFilesRequest(conn, msg.getArgs()[0]);
                break;
            case "CREATE_FILE":
                handleCreateFile(conn, msg.getArgs()[0], msg.getArgs()[1]);
                break;
            case "CHECK_PERMISSION":
                handleCheckPermission(conn, msg.getArgs()[0], msg.getArgs()[1]);
                break;
            case "GET_EDITORS":
                handleGetEditors(conn, msg.getArgs()[0]);
                break;
            case "LEAVE_FILE":
                handleLeaveFile(conn, msg.getArgs()[0], msg.getArgs()[1]);
                break;
            case "SET_EDITORS":
                handleSetEditors(conn, msg.getArgs()[0], msg.getArgs()[1], msg.getContent());
                break;
        }
    }

    private void handleLogin(WebSocket conn, String username) {
        clients.put(conn, username);
        activeUsers.add(username);
        broadcastActiveUsers();
        
        // Login başarılı mesajı gönder
        Protocol response = Protocol.success("Başarıyla giriş yapıldı: " + username);
        String responseMessage = response.serialize();
        System.out.println("SERVER -> CLIENT: " + responseMessage);
        conn.send(responseMessage);
    }

    private void handleCreateFile(WebSocket conn, String username, String fileName) {
        if (!fileNames.contains(fileName)) {
            fileNames.add(fileName);
            fileContents.put(fileName, "");
            fileOwners.put(fileName, username);
            
            // Dosya sahibini editörlere EKLEME - sadece sahip olarak tut
            Set<String> editors = new HashSet<>();
            fileEditors.put(fileName, editors);

            Protocol response = Protocol.success("Dosya oluşturuldu: " + fileName);
            String responseMessage = response.serialize();
            System.out.println("SERVER -> CLIENT: " + responseMessage);
            conn.send(responseMessage);
            
            // Dosya sahibine editör listesini gönder
            String combinedMsg = username + " (dosya sahibi);";
            Protocol editorsResponse = Protocol.editorsList(fileName, combinedMsg);
            String editorsMessage = editorsResponse.serialize();
            System.out.println("SERVER -> CLIENT: " + editorsMessage);
            conn.send(editorsMessage);
        } else {
            Protocol response = Protocol.error("FILE_EXISTS", "Bu isimde bir dosya zaten var: " + fileName);
            String responseMessage = response.serialize();
            System.out.println("SERVER -> CLIENT: " + responseMessage);
            conn.send(responseMessage);
        }
    }

    private void handleEdit(WebSocket conn, String username, String fileName, String content) {
        String owner = fileOwners.get(fileName);
        Set<String> editorsSet = fileEditors.get(fileName);

        boolean isOwner = username.equals(owner);
        boolean isEditor = editorsSet != null && editorsSet.contains(username);

        if (isOwner || isEditor) {
            // Önce dosya içeriğini güncelle
            fileContents.put(fileName, content);
            
            // Dosyayı kaydet
            saveToFile(fileName, content);

            // Düzenleyicilere yeni içeriği gönder
            Protocol editMsg = Protocol.edit(username, fileName, content);
            String editMessage = editMsg.serialize();
            System.out.println("SERVER -> CLIENTS: " + editMessage);
            
            // Düzenleyicilere ve dosya sahibine gönder
            for (Map.Entry<WebSocket, String> entry : clients.entrySet()) {
                WebSocket client = entry.getKey();
                String clientUsername = entry.getValue();
                
                if (clientUsername.equals(username)) continue;
                
                // Dosya sahibi veya düzenleyiciyse gönder
                if (clientUsername.equals(owner) || (editorsSet != null && editorsSet.contains(clientUsername))) {
                    client.send(editMessage);
                }
            }
        } else {
            Protocol response = Protocol.error("PERMISSION_DENIED", "Dosyayı düzenleme yetkiniz yok: " + fileName);
            String responseMessage = response.serialize();
            System.out.println("SERVER -> CLIENT: " + responseMessage);
            conn.send(responseMessage);
        }
    }

    private void handleListFilesRequest(WebSocket conn, String username) {
        String fileList = String.join(",", fileNames);
        Protocol response = Protocol.listFilesResponse(fileList);
        String responseMessage = response.serialize();
        System.out.println("SERVER -> CLIENT: " + responseMessage);
        conn.send(responseMessage);
    }

    private void handleSetEditors(WebSocket conn, String username, String fileName, String editorsStr) {
        String owner = fileOwners.get(fileName);
        if (!username.equals(owner)) {
            Protocol response = Protocol.error("PERMISSION_DENIED", "Sadece dosya sahibi düzenleyicileri değiştirebilir: " + fileName);
            String responseMessage = response.serialize();
            System.out.println("SERVER -> CLIENT: " + responseMessage);
            conn.send(responseMessage);
            return;
        }

        Set<String> editors = fileEditors.getOrDefault(fileName, new HashSet<>());
        
        // Yeni editörleri ekle
        if (editorsStr != null && !editorsStr.isEmpty()) {
            String[] newEditors = editorsStr.split(",");
            for (String editor : newEditors) {
                String trimmedEditor = editor.trim();
                if (!trimmedEditor.isEmpty() && !trimmedEditor.equals(owner)) {
                    editors.add(trimmedEditor);
                }
            }
        }

        // Güncellenmiş listeyi kaydet
        fileEditors.put(fileName, editors);

        String combinedMsg = owner + " (dosya sahibi);" + String.join(",", editors);
        Protocol response = Protocol.editorsList(fileName, combinedMsg);
        String responseMessage = response.serialize();
        System.out.println("SERVER -> CLIENTS: " + responseMessage);
        broadcast(responseMessage);
    }

    private void handleGetEditors(WebSocket conn, String fileName) {
        Set<String> editors = fileEditors.getOrDefault(fileName, Collections.emptySet());
        String owner = fileOwners.getOrDefault(fileName, "");
        String combinedMsg = owner + " (dosya sahibi);" + String.join(",", editors);

        Protocol response = Protocol.editorsList(fileName, combinedMsg);
        String responseMessage = response.serialize();
        System.out.println("SERVER -> CLIENT: " + responseMessage);
        conn.send(responseMessage);
    }

    private void handleLeaveFile(WebSocket conn, String username, String fileName) {
        Set<String> editors = fileEditors.get(fileName);
        if (editors != null) {
            editors.remove(username);
        }

        Protocol response = Protocol.success("Dosyadan çıkış yapıldı: " + fileName);
        String responseMessage = response.serialize();
        System.out.println("SERVER -> CLIENT: " + responseMessage);
        conn.send(responseMessage);
    }

    private void handleCheckPermission(WebSocket conn, String username, String fileName) {
        String owner = fileOwners.get(fileName);
        Set<String> editorsSet = fileEditors.get(fileName);

        boolean isOwner = username.equals(owner);
        boolean isEditor = editorsSet != null && editorsSet.contains(username);

        if (isOwner || isEditor) {
            String content = fileContents.getOrDefault(fileName, "");
            Protocol response = Protocol.permissionGranted(username, fileName, content);
            String responseMessage = response.serialize();
            System.out.println("SERVER -> CLIENT: " + responseMessage);
            conn.send(responseMessage);
            
            String combinedMsg = owner + " (dosya sahibi);" + String.join(",", editorsSet != null ? editorsSet : Collections.emptySet());
            Protocol editorsResponse = Protocol.editorsList(fileName, combinedMsg);
            String editorsMessage = editorsResponse.serialize();
            System.out.println("SERVER -> CLIENT: " + editorsMessage);
            conn.send(editorsMessage);
        } else {
            Protocol response = Protocol.permissionDeniedResponse(username, fileName);
            String responseMessage = response.serialize();
            System.out.println("SERVER -> CLIENT: " + responseMessage);
            conn.send(responseMessage);
        }
    }

    private void broadcastActiveUsers() {
        String users = String.join(",", activeUsers);
        Protocol msg = Protocol.activeUsers(users);
        String message = msg.serialize();
        System.out.println("SERVER -> CLIENTS: " + message);
        broadcast(message);
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