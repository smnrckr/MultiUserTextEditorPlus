package client;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import common.ButtonTabComponent;
import common.Protocol;

public class EditorGUI {
    private String serverIP;
    private int serverPort;
    private WebSocketEditorClient client;
    private JFrame frame;

    private JComboBox<String> fileComboBox;
    private JComboBox<String> editComboBox;
    private DefaultListModel<String> activeUsersListModel;
    private JList<String> activeUsersList;
    private JButton updateEditorsButton;
    private Timer delayedSaveTimer;
    private String username;

    private JTabbedPane tabbedPane;
    private Map<String, EditorTab> openFiles = new HashMap<>();

    private static class EditorTab {
        JTextArea textArea;
        DefaultListModel<String> editorsListModel;
        JList<String> editorsList;
        boolean contentChanged;

        EditorTab() {
            textArea = new JTextArea();
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            editorsListModel = new DefaultListModel<>();
            editorsList = new JList<>(editorsListModel);
            contentChanged = false;
        }
    }

    public EditorGUI(String serverIP, int serverPort, String username) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        this.username = username;
    }

    public void start() {
        try {
            String serverUri = "ws://" + serverIP + ":" + serverPort;
            client = new WebSocketEditorClient(serverUri, this::handleServerMessage);
            client.connect();

            while (!client.isOpen()) {
                Thread.sleep(100);
            }

            String loginMessage = Protocol.login(username).serialize();
            System.out.println("CLIENT -> SERVER: " + loginMessage);
            client.send(loginMessage);
            SwingUtilities.invokeLater(this::initializeUI);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Sunucuya bağlanılamadı.", "Hata", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void handleServerMessage(String message) {
        System.out.println("🔴 CLIENT <- SERVER: " + message);
        Protocol msg = Protocol.deserialize(message);
        System.out.println("DEBUG: Received command: " + msg.getCommand() + ", Args: " + java.util.Arrays.toString(msg.getArgs()));
        SwingUtilities.invokeLater(() -> {
            switch (msg.getCommand()) {
                case "EDIT":
                    handleEditMessage(msg);
                    break;
                case "LIST_FILES_RESPONSE":
                    updateFileList(msg.getContent());
                    break;
                case "SUCCESS":
                    showInfo(msg.getContent());
                    break;
                case "EDITORS_LIST":
                    updateEditorsList(msg.getFileName(), msg.getContent());
                    break;
                case "ACTIVE_USERS":
                    updateActiveUsers(msg.getContent());
                    break;
                case "PERMISSION_GRANTED":
                    handlePermissionGranted(msg);
                    break;
                case "PERMISSION_DENIED":
                    System.out.println("DEBUG: Handling PERMISSION_DENIED command for file: " + msg.getFileName());
                    handlePermissionDenied(msg);
                    break;
            }
        });
    }

    private void initializeUI() {
        UIManager.put("Label.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("Button.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("ComboBox.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("List.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("TextArea.font", new Font("Consolas", Font.PLAIN, 13));

        frame = new JFrame("Çok Kullanıcılı Editör - " + username);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(1000, 650);
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                sendAllEdits();
                try {
                    Thread.sleep(500);
                    client.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                frame.dispose();
                System.exit(0);
            }
        });

        JPanel topPanel = createTopPanel();

        tabbedPane = new JTabbedPane();

        JPanel rightPanel = createRightPanel();

        JButton saveButton = new JButton("Kaydet");
        saveButton.addActionListener(e -> sendEdit());
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(saveButton);

        frame.setLayout(new BorderLayout(5, 5));
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(tabbedPane, BorderLayout.CENTER);
        frame.add(rightPanel, BorderLayout.EAST);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        setupDelayedSave();

        frame.setVisible(true);
    }

    private JPanel createTopPanel() {
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 5));

        editComboBox = new JComboBox<>(new String[]{"Düzenle", "Kes", "Kopyala", "Yapıştır"});
        editComboBox.addActionListener(e -> {
            EditorTab currentTab = getCurrentEditorTab();
            if (currentTab == null) return;
            switch ((String) editComboBox.getSelectedItem()) {
                case "Kes":
                    currentTab.textArea.cut();
                    break;
                case "Kopyala":
                    currentTab.textArea.copy();
                    break;
                case "Yapıştır":
                    currentTab.textArea.paste();
                    break;
            }
            editComboBox.setSelectedIndex(0);
        });

        fileComboBox = new JComboBox<>();
        fileComboBox.setPreferredSize(new Dimension(180, 25));
        fileComboBox.addActionListener(e -> {
            if (e.getSource() == fileComboBox && fileComboBox.getSelectedItem() != null) {
                String selectedFile = (String) fileComboBox.getSelectedItem();
                if (!selectedFile.isEmpty()) {
                    // Önce yetki kontrolü yap
                    String permissionMessage = Protocol.checkPermission(username, selectedFile).serialize();
                    System.out.println("CLIENT -> SERVER: " + permissionMessage);
                    client.send(permissionMessage);
                }
            }
        });

        JButton newFileButton = new JButton("Yeni Dosya");
        newFileButton.addActionListener(e -> {
            String newFileName = JOptionPane.showInputDialog(frame, "Yeni dosya adı:");
            if (newFileName != null && !newFileName.trim().isEmpty()) {
                if (!newFileName.toLowerCase().endsWith(".txt")) newFileName += ".txt";
                sendAllEdits();
                String createMessage = Protocol.createFile(username, newFileName).serialize();
                System.out.println("CLIENT -> SERVER: " + createMessage);
                client.send(createMessage);
                
                String listMessage = Protocol.listFilesRequest(username).serialize();
                System.out.println("CLIENT -> SERVER: " + listMessage);
                client.send(listMessage);
                openFileInTab(newFileName, "");
            }
        });

        JButton listFilesButton = new JButton("Dosya Listesi");
        listFilesButton.addActionListener(e -> {
            String listMessage = Protocol.listFilesRequest(username).serialize();
            System.out.println("CLIENT -> SERVER: " + listMessage);
            client.send(listMessage);
        });

        topPanel.add(editComboBox);
        topPanel.add(new JLabel("Dosya:"));
        topPanel.add(fileComboBox);
        topPanel.add(newFileButton);
        topPanel.add(listFilesButton);

        return topPanel;
    }

    private JPanel createRightPanel() {
        JPanel rightPanel = new JPanel();
        rightPanel.setPreferredSize(new Dimension(220, 0));
        rightPanel.setLayout(new BorderLayout(5, 5));

        JLabel activeUsersLabel = new JLabel("Aktif Kullanıcılar:");
        activeUsersLabel.setHorizontalAlignment(SwingConstants.CENTER);
        activeUsersLabel.setFont(activeUsersLabel.getFont().deriveFont(Font.BOLD, 14f));

        activeUsersListModel = new DefaultListModel<>();
        activeUsersList = new JList<>(activeUsersListModel);
        activeUsersList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JScrollPane usersScrollPane = new JScrollPane(activeUsersList);

        updateEditorsButton = new JButton("Düzenleyicileri Güncelle");
        updateEditorsButton.setEnabled(false);
        updateEditorsButton.addActionListener(e -> updateEditors());

        rightPanel.add(activeUsersLabel, BorderLayout.NORTH);
        rightPanel.add(usersScrollPane, BorderLayout.CENTER);
        rightPanel.add(updateEditorsButton, BorderLayout.SOUTH);

        tabbedPane.addChangeListener(e -> {
            updateEditorsButtonState();
        });

        return rightPanel;
    }

    private EditorTab getCurrentEditorTab() {
        int idx = tabbedPane.getSelectedIndex();
        if (idx < 0) return null;
        String fileName = tabbedPane.getTitleAt(idx);
        return openFiles.get(fileName);
    }

    private void openFileInTab(String fileName, String content) {
        if (openFiles.containsKey(fileName)) {
            tabbedPane.setSelectedIndex(tabbedPane.indexOfTab(fileName));
            return;
        }

        EditorTab editorTab = new EditorTab();
        editorTab.textArea.setText(content);
        editorTab.textArea.setEnabled(true);
        editorTab.contentChanged = false;
        editorTab.textArea.setEditable(true); 

        editorTab.textArea.getDocument().addDocumentListener(new DocumentListener() {
            private void scheduleSave() {
                if (!editorTab.contentChanged) {
                    editorTab.contentChanged = true;
                    return;
                }
                delayedSaveTimer.restart();
            }

            public void insertUpdate(DocumentEvent e) {
                scheduleSave();
            }

            public void removeUpdate(DocumentEvent e) {
                scheduleSave();
            }

            public void changedUpdate(DocumentEvent e) {
                scheduleSave();
            }
        });

        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        JScrollPane scrollPane = new JScrollPane(editorTab.textArea);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel editorsPanel = new JPanel(new BorderLayout());
        JLabel editorsLabel = new JLabel("Editörler:");
        editorsLabel.setHorizontalAlignment(SwingConstants.CENTER);
        editorsLabel.setFont(editorsLabel.getFont().deriveFont(Font.BOLD, 13f));
        editorsPanel.add(editorsLabel, BorderLayout.NORTH);
        editorsPanel.add(new JScrollPane(editorTab.editorsList), BorderLayout.CENTER);
        editorsPanel.setPreferredSize(new Dimension(160, 0));

        mainPanel.add(editorsPanel, BorderLayout.EAST);

        tabbedPane.addTab(fileName, mainPanel);
        tabbedPane.setSelectedComponent(mainPanel);
        tabbedPane.setTabComponentAt(tabbedPane.indexOfComponent(mainPanel),
                new ButtonTabComponent(tabbedPane, fileNameToRemove -> {
                    openFiles.remove(fileNameToRemove);
                    String leaveMessage = Protocol.leaveFile(username, fileNameToRemove).serialize();
                    System.out.println("CLIENT -> SERVER: " + leaveMessage);
                    client.send(leaveMessage);
                })
        );
        openFiles.put(fileName, editorTab);

        // Sunucuya katılma ve editörleri çekme mesajları
        String joinMessage = Protocol.edit(username, fileName, "").serialize();
        System.out.println("CLIENT -> SERVER: " + joinMessage);
        client.send(joinMessage);
        
        String getEditorsMessage = Protocol.getEditors(fileName).serialize();
        System.out.println("CLIENT -> SERVER: " + getEditorsMessage);
        client.send(getEditorsMessage);
    }

    private void sendEdit() {
        EditorTab editorTab = getCurrentEditorTab();
        if (editorTab == null || !editorTab.contentChanged) return;

        String fileName = tabbedPane.getTitleAt(tabbedPane.getSelectedIndex());
        String editMessage = Protocol.edit(username, fileName, editorTab.textArea.getText()).serialize();
        System.out.println("CLIENT -> SERVER: " + editMessage);
        client.send(editMessage);
    }

    private void sendAllEdits() {
        openFiles.forEach((fileName, editorTab) -> {
            if (editorTab.contentChanged) {
                String editMessage = Protocol.edit(username, fileName, editorTab.textArea.getText()).serialize();
                System.out.println("CLIENT -> SERVER: " + editMessage);
                client.send(editMessage);
                editorTab.contentChanged = false;
            }
        });
    }

    private void updateEditors() {
        EditorTab currentTab = getCurrentEditorTab();
        if (currentTab == null) return;

        String currentFile = tabbedPane.getTitleAt(tabbedPane.getSelectedIndex());
        List<String> selectedUsers = new ArrayList<>(activeUsersList.getSelectedValuesList());
        selectedUsers.remove(username); // Kendini listeden çıkar

        if (selectedUsers.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "En az bir kullanıcı seçmelisiniz.", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String editorsStr = String.join(",", selectedUsers);
        String setEditorsMessage = Protocol.setEditors(username, currentFile, editorsStr).serialize();
        System.out.println("CLIENT -> SERVER: " + setEditorsMessage);
        client.send(setEditorsMessage);
    }

    private void setupDelayedSave() {
        delayedSaveTimer = new Timer(1000, e -> sendEdit());
        delayedSaveTimer.setRepeats(false);
    }

    private void handleEditMessage(Protocol msg) {
        EditorTab editorTab = openFiles.get(msg.getFileName());
        if (editorTab != null) {
            SwingUtilities.invokeLater(() -> {
                if (!msg.getUsername().equals(username)) {
                    editorTab.contentChanged = false;
                    editorTab.textArea.setText(msg.getContent());
                    editorTab.contentChanged = false;
                }
                editorTab.textArea.setEditable(true);
                editorTab.textArea.setEnabled(true);
            });
        }
    }

    private void updateFileList(String fileListStr) {
        SwingUtilities.invokeLater(() -> {
            fileComboBox.removeAllItems();
            
            String[] files = fileListStr.split(",");
            Set<String> uniqueFiles = new HashSet<>();
            
            for (String file : files) {
                String trimmedFile = file.trim();
                if (!trimmedFile.isEmpty()) {
                    uniqueFiles.add(trimmedFile);
                }
            }
            
            // Her benzersiz dosya için yetki kontrolü yap
            for (String file : uniqueFiles) {
                String permissionMessage = Protocol.checkPermission(username, file).serialize();
                System.out.println("CLIENT -> SERVER: " + permissionMessage);
                client.send(permissionMessage);
            }
        });
    }

    private void handleErrorMessage(Protocol msg) {
        System.out.println("DEBUG: handleErrorMessage called with content: " + msg.getContent());
        SwingUtilities.invokeLater(() -> {
        JOptionPane.showMessageDialog(frame, msg.getContent(), "Hata", JOptionPane.ERROR_MESSAGE);
         });
    }

    private void showInfo(String info) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, info, "Bilgi", JOptionPane.INFORMATION_MESSAGE));
    }

    private void updateEditorsList(String fileName, String editorsStr) {
        EditorTab editorTab = openFiles.get(fileName);

        if (editorTab != null) {
            SwingUtilities.invokeLater(() -> {
                editorTab.editorsListModel.clear();
                System.out.println("DEBUG: Parsing editors string: " + editorsStr);

                if (editorsStr.contains(";")) {
                    String[] parts = editorsStr.split(";", 2);
                    String owner = parts[0].trim();
                    editorTab.editorsListModel.addElement(owner);

                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        String[] editors = parts[1].split(",");
                        for (String editor : editors) {
                            String trimmedEditor = editor.trim();
                            if (!trimmedEditor.isEmpty()) {
                                editorTab.editorsListModel.addElement(trimmedEditor);
                            }
                        }
                    }
                } else {
                    System.out.println("UYARI: ; bulunamadı! editorsStr: " + editorsStr);
                    editorTab.editorsListModel.addElement(editorsStr.trim());
                }

                updateEditorsButtonState();
            });
        }
    }

    private void updateEditorsButtonState() {
        EditorTab currentTab = getCurrentEditorTab();
        if (currentTab == null) {
            updateEditorsButton.setEnabled(false);
            return;
        }
        
        int idx = tabbedPane.getSelectedIndex();
        if (idx < 0) {
            updateEditorsButton.setEnabled(false);
            return;
        }
        
        String currentFile = tabbedPane.getTitleAt(idx);
        
        boolean isOwner = false;
        for (int i = 0; i < currentTab.editorsListModel.size(); i++) {
            String editor = currentTab.editorsListModel.getElementAt(i);
            if (editor.contains("(dosya sahibi)") && editor.startsWith(username)) {
                isOwner = true;
                break;
            }
        }
        
        System.out.println("DEBUG: User: " + username + ", File: " + currentFile + ", IsOwner: " + isOwner);
        updateEditorsButton.setEnabled(isOwner);
    }

    private void updateActiveUsers(String usersStr) {
        SwingUtilities.invokeLater(() -> {
            activeUsersListModel.clear();
            if (!usersStr.trim().isEmpty()) {
                String[] users = usersStr.split(",");
                for (String user : users) {
                    if (!user.trim().isEmpty())
                        activeUsersListModel.addElement(user.trim());
                }
            }
        });
    }

    private void handlePermissionGranted(Protocol msg) {
        SwingUtilities.invokeLater(() -> {
            boolean alreadyExists = false;
            for (int i = 0; i < fileComboBox.getItemCount(); i++) {
                if (fileComboBox.getItemAt(i).equals(msg.getFileName())) {
                    alreadyExists = true;
                    break;
                }
            }
            
            if (!alreadyExists) {
                fileComboBox.addItem(msg.getFileName());
            }
            
            if (fileComboBox.getSelectedItem() != null && 
                fileComboBox.getSelectedItem().equals(msg.getFileName())) {
                String content = msg.getContent();
                System.out.println("DEBUG: Opening file with content: " + content);
                openFileInTab(msg.getFileName(), content);
            }
        });
    }

    private void handlePermissionDenied(Protocol msg) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < fileComboBox.getItemCount(); i++) {
                if (fileComboBox.getItemAt(i).equals(msg.getFileName())) {
                    fileComboBox.removeItemAt(i);
                    break;
                }
            }
            
            if (fileComboBox.getSelectedItem() != null && 
                fileComboBox.getSelectedItem().equals(msg.getFileName())) {
                fileComboBox.setSelectedIndex(-1);
            }
        });
    }
}