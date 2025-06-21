package common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public class Protocol {
    // FTP benzeri protokol yapısı
    private final String command;        // Mesaj komutu (LOGIN, EDIT, vb.)
    private final String[] args;         // Komut parametreleri
    private final String content;        // Mesaj içeriği
    private final String statusCode;     // Durum kodu
    private final long timestamp;        // Zaman damgası
    
    // Özel karakterler
    private static final String HEADER_SEPARATOR = ";";
    private static final String ARG_SEPARATOR = ",";
    private static final String CONTENT_MARKER = "CONTENT:";
    
    public Protocol(String command, String[] args, String content) {
        this.command = Objects.requireNonNull(command, "Command cannot be null");
        this.args = args != null ? args : new String[0];
        this.content = content != null ? content : "";
        this.statusCode = "200 OK";
        this.timestamp = System.currentTimeMillis();
    }
    
    public Protocol(String command, String[] args, String content, String statusCode) {
        this.command = Objects.requireNonNull(command, "Command cannot be null");
        this.args = args != null ? args : new String[0];
        this.content = content != null ? content : "";
        this.statusCode = statusCode != null ? statusCode : "200 OK";
        this.timestamp = System.currentTimeMillis();
    }
    
    // Timestamp parametresi ile constructor eklendi
    public Protocol(String command, String[] args, String content, String statusCode, long timestamp) {
        this.command = Objects.requireNonNull(command, "Command cannot be null");
        this.args = args != null ? args : new String[0];
        this.content = content != null ? content : "";
        this.statusCode = statusCode != null ? statusCode : "200 OK";
        this.timestamp = timestamp;
    }
    
    // Getter metodları
    public String getCommand() { return command; }
    public String[] getArgs() { return args; }
    public String getContent() { return content; }
    public String getStatusCode() { return statusCode; }
    public long getTimestamp() { return timestamp; }
    
    // Kolaylık metodları
    public String getUsername() { return args.length > 0 ? args[0] : ""; }
    public String getFileName() { return args.length > 1 ? args[1] : ""; }
    public String getArg(int index) { return index < args.length ? args[index] : ""; }
    
    // FTP benzeri serileştirme: HEADER|CONTENT:content
    public String serialize() {
        StringBuilder header = new StringBuilder();
        header.append(command);
        
        // Parametreleri ekle
        if (args.length > 0) {
            header.append(HEADER_SEPARATOR);
            header.append(String.join(ARG_SEPARATOR, args));
        }
        
        // Durum kodu ekle
        header.append(HEADER_SEPARATOR);
        header.append(statusCode);
        
        // Zaman damgası ekle
        header.append(HEADER_SEPARATOR);
        header.append(timestamp);
        
        // İçerik varsa ekle
        if (!content.isEmpty()) {
            String encodedContent = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            return header.toString() + HEADER_SEPARATOR + CONTENT_MARKER + encodedContent;
        }
        
        return header.toString();
    }
    
    // FTP benzeri deserileştirme
    public static Protocol deserialize(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new Protocol("UNKNOWN", new String[]{}, "");
        }
        
        try {
            String[] parts = message.split(HEADER_SEPARATOR, -1);
            
            if (parts.length < 1) {
                return new Protocol("UNKNOWN", new String[]{}, "");
            }
            
            String command = parts[0];
            String[] args = new String[0];
            String statusCode = "200 OK";
            long timestamp = System.currentTimeMillis();
            String content = "";
            
            // Parametreleri parse et
            if (parts.length > 1 && !parts[1].startsWith(CONTENT_MARKER)) {
                args = parts[1].split(ARG_SEPARATOR);
            }
            
            // Durum kodunu parse et
            if (parts.length > 2 && !parts[2].startsWith(CONTENT_MARKER)) {
                statusCode = parts[2];
            }
            
            // Zaman damgasını parse et
            if (parts.length > 3 && !parts[3].startsWith(CONTENT_MARKER)) {
                try {
                    timestamp = Long.parseLong(parts[3]);
                } catch (NumberFormatException e) {
                    // Varsayılan değer kullan - timestamp zaten System.currentTimeMillis() olarak ayarlandı
                }
            }
            
            // İçeriği parse et
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].startsWith(CONTENT_MARKER)) {
                    String encodedContent = parts[i].substring(CONTENT_MARKER.length());
                    if (!encodedContent.isEmpty()) {
                        try {
                            byte[] decodedBytes = Base64.getDecoder().decode(encodedContent);
                            content = new String(decodedBytes, StandardCharsets.UTF_8);
                        } catch (IllegalArgumentException e) {
                            // Base64 decode hatası durumunda boş content kullan
                            content = "";
                        }
                    }
                    break;
                }
            }
            
            return new Protocol(command, args, content, statusCode, timestamp);
            
        } catch (Exception e) {
            return Protocol.parseError(e.getMessage());
        }
    }
    
    // Statik factory metodları - kolaylık için
    public static Protocol login(String username) {
        return new Protocol("LOGIN", new String[]{username}, "");
    }
    
    public static Protocol edit(String username, String fileName, String content) {
        return new Protocol("EDIT", new String[]{username, fileName}, content);
    }
    
    public static Protocol listFilesRequest(String username) {
        return new Protocol("LIST_FILES_REQUEST", new String[]{username}, "");
    }
    
    public static Protocol listFilesResponse(String fileList) {
        return new Protocol("LIST_FILES_RESPONSE", new String[]{}, fileList);
    }
    
    public static Protocol createFile(String username, String fileName) {
        return new Protocol("CREATE_FILE", new String[]{username, fileName}, "");
    }
    
    public static Protocol checkPermission(String username, String fileName) {
        return new Protocol("CHECK_PERMISSION", new String[]{username, fileName}, "");
    }
    
    public static Protocol permissionGranted(String username, String fileName, String content) {
        return new Protocol("PERMISSION_GRANTED", new String[]{username, fileName}, content);
    }
    
    public static Protocol permissionDeniedResponse(String username, String fileName) {
        return new Protocol("PERMISSION_DENIED", new String[]{username, fileName}, "");
    }
    
    public static Protocol getEditors(String fileName) {
        return new Protocol("GET_EDITORS", new String[]{fileName}, "");
    }
    
    public static Protocol editorsList(String fileName, String editors) {
        return new Protocol("EDITORS_LIST", new String[]{"SERVER", fileName}, editors);
    }
    
    public static Protocol setEditors(String username, String fileName, String editors) {
        return new Protocol("SET_EDITORS", new String[]{username, fileName}, editors);
    }
    
    public static Protocol leaveFile(String username, String fileName) {
        return new Protocol("LEAVE_FILE", new String[]{username, fileName}, "");
    }
    
    public static Protocol activeUsers(String users) {
        return new Protocol("ACTIVE_USERS", new String[]{}, users);
    }
    
    public static Protocol success(String message) {
        return new Protocol("SUCCESS", new String[]{}, message);
    }
    
    public static Protocol error(String errorType, String errorMessage) {
        return new Protocol("ERROR", new String[]{errorType}, errorMessage, "400 Bad Request");
    }
    
    public static Protocol error(String errorType, String errorMessage, String statusCode) {
        return new Protocol("ERROR", new String[]{errorType}, errorMessage, statusCode);
    }
    
    // Özel hata türleri için factory metodları
    public static Protocol fileNotFound(String fileName) {
        return new Protocol("ERROR", new String[]{"FILE_NOT_FOUND"}, "Dosya bulunamadı: " + fileName, "404 Not Found");
    }
    
    public static Protocol fileExists(String fileName) {
        return new Protocol("ERROR", new String[]{"FILE_EXISTS"}, "Bu isimde bir dosya zaten var: " + fileName, "409 Conflict");
    }
    
    public static Protocol permissionDenied(String operation, String fileName) {
        return new Protocol("ERROR", new String[]{"PERMISSION_DENIED"}, operation + " yetkiniz yok: " + fileName, "403 Forbidden");
    }
    
    public static Protocol invalidRequest(String message) {
        return new Protocol("ERROR", new String[]{"INVALID_REQUEST"}, message, "400 Bad Request");
    }
    
    public static Protocol serverError(String message) {
        return new Protocol("ERROR", new String[]{"SERVER_ERROR"}, "Sunucu hatası: " + message, "500 Internal Server Error");
    }
    
    public static Protocol parseError(String message) {
        return new Protocol("ERROR", new String[]{"PARSE_ERROR"}, "Mesaj ayrıştırma hatası: " + message, "400 Bad Request");
    }
    
    // Geçerlilik kontrolü
    public boolean isValid() {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        
        switch (command) {
            case "LOGIN":
                return args.length >= 1 && !args[0].trim().isEmpty();
                
            case "EDIT":
                return args.length >= 2 && !args[0].trim().isEmpty() && !args[1].trim().isEmpty();
                
            case "LIST_FILES_REQUEST":
                return args.length >= 1 && !args[0].trim().isEmpty();
                
            case "CREATE_FILE":
                return args.length >= 2 && !args[0].trim().isEmpty() && !args[1].trim().isEmpty();
                
            case "CHECK_PERMISSION":
                return args.length >= 2 && !args[0].trim().isEmpty() && !args[1].trim().isEmpty();
                
            case "GET_EDITORS":
                return args.length >= 1 && !args[0].trim().isEmpty();
                
            case "SET_EDITORS":
                return args.length >= 2 && !args[0].trim().isEmpty() && !args[1].trim().isEmpty();
                
            case "LEAVE_FILE":
                return args.length >= 2 && !args[0].trim().isEmpty() && !args[1].trim().isEmpty();
                
            case "LIST_FILES_RESPONSE":
            case "SUCCESS":
            case "ERROR":
            case "ACTIVE_USERS":
            case "EDITORS_LIST":
            case "PERMISSION_GRANTED":
            case "PERMISSION_DENIED":
                return true;
                
            default:
                return false;
        }
    }
    
    // Debug için toString
    @Override
    public String toString() {
        return String.format("Protocol{command='%s', args=%s, contentLength=%d, statusCode='%s', timestamp=%d}",
                           command, java.util.Arrays.toString(args), content.length(), statusCode, timestamp);
    }
    
    // Eşitlik kontrolü
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        Protocol protocol = (Protocol) obj;
        return Objects.equals(command, protocol.command) &&
               java.util.Arrays.equals(args, protocol.args) &&
               Objects.equals(content, protocol.content) &&
               Objects.equals(statusCode, protocol.statusCode);
        // Timestamp karşılaştırması kaldırıldı çünkü aynı mesajın farklı zamanlarda gönderilmesi normal
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(command, java.util.Arrays.hashCode(args), content, statusCode);
        // Timestamp hash'e dahil edilmedi
    }
}