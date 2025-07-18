package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainInterface {

    //Version Number
    private static final String VERSION = loadVersion();

    // UI Components
    private JScrollPane lst_files;
    private JButton btn_clear;
    private JButton btn_add;
    private JButton btn_remove_item;
    private JTextField txt_tags;
    private JButton btn_tag_remove;
    private JLabel lbl_tags;
    private JButton btn_tag_add;
    private JLabel lbl_location;
    private JButton btn_search_location;
    private JButton btn_go;
    private JPanel pnl_main_interface;
    private JList<File> lst_file_contents;
    private JTextField txt_search_location;
    private JList<Location> lst_search_location_results;
    private JLabel lbl_date;
    private JComboBox<String> combo_month;
    private JComboBox<Integer> combo_day;
    private JComboBox<Integer> combo_year;
    private JCheckBox chk_show_preview;
    private JButton btn_help;
    private JCheckBox chk_copy_files;
    private JRadioButton rdo_finished;
    private JRadioButton rdo_broll;
    private JCheckBox chk_lega_approved;
    private JCheckBox chk_safety;
    private JButton btn_version;
    private JCheckBox chk_text_to_speech;
    private final JFrame frame;

    // Class members
    private JFileChooser file_chooser;
    private ArrayList<File> selectedFiles;
    private ArrayList<String> tags;
    private Map<File, String> transcripts;
    private Location selectedLocation;
    private Path resourceDir;
    private String pythonExecutablePath;
    private String ffmpegExecutablePath;
    private String ffprobeExecutablePath;
    private String hfToken; // --- ADDED: To hold the Hugging Face token
    private static final String[] VIDEO_PHOTO_EXTENSIONS = {".jpg", ".jpeg", ".mp4"};
    private Image appIcon;

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Media Tagger");
        new MainInterface();
    }

    private static String loadVersion() {
        Properties props = new Properties();
        try (InputStream is = MainInterface.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
                String version = props.getProperty("app.version", "unknown");
                String versionLink  = props.getProperty("version.link", "unknown");
                return version+","+versionLink;
            }
        } catch (IOException e) {
            System.err.println("Could not load version from application.properties: " + e.getMessage());
        }
        return "unknown";
    }

    private Image loadAppIcon() {
        URL iconURL = getClass().getClassLoader().getResource("app_icon.png");
        if (iconURL != null) {
            return new ImageIcon(iconURL).getImage();
        } else {
            System.err.println("Could not find app_icon.png in resources.");
            return null;
        }
    }

    public MainInterface() {
        this.appIcon = loadAppIcon();
        frame = new JFrame();
        if (this.appIcon != null) {
            frame.setIconImage(this.appIcon);
        }

        file_chooser = new JFileChooser();
        selectedFiles = new ArrayList<>();
        tags = new ArrayList<>();
        transcripts = new HashMap<>();

        try {
            setupResources();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Failed to initialize resources: " + e.getMessage(), "Initialization Error", JOptionPane.ERROR_MESSAGE, new ImageIcon(appIcon));
            System.exit(1);
        }

        // --- MODIFIED: Load token before running scripts that need it ---
        loadHfToken();
        runStartupScript();

        pythonExecutablePath = findCompatiblePython();
        ffmpegExecutablePath = findExecutable("ffmpeg");
        ffprobeExecutablePath = findExecutable("ffprobe");
        if (pythonExecutablePath == null || ffmpegExecutablePath == null || ffprobeExecutablePath == null) {
            String missing = "a required dependency";
            if(pythonExecutablePath == null) missing = "python3";
            else if(ffmpegExecutablePath == null) missing = "ffmpeg";
            else if(ffprobeExecutablePath == null) missing = "ffprobe";

            JOptionPane.showMessageDialog(frame,
                    "Error: Could not find '" + missing + "' in common paths (/opt/homebrew/bin, /usr/local/bin, /usr/bin) after running installer.\n" +
                            "Please ensure dependencies (python3, ffmpeg) are installed correctly.",
                    "Dependency Not Found", JOptionPane.ERROR_MESSAGE, new ImageIcon(appIcon));
            System.exit(1);
        }

        setupGUI();
        frame.setContentPane(pnl_main_interface);
        frame.setTitle("Media Tagger");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setSize(800, 800);
        frame.setVisible(true);
    }

    // --- ADDED: Method to load the Hugging Face token from the resource file ---
    private void loadHfToken() {
        Path tokenPath = resourceDir.resolve("HF_KEY.txt");
        try {
            this.hfToken = Files.readString(tokenPath).trim();
            if (this.hfToken.isEmpty() || this.hfToken.equals("REPLACE_WITH_YOUR_HUGGING_FACE_TOKEN")) {
                JOptionPane.showMessageDialog(frame,
                        "Hugging Face token is missing or is a placeholder.\n" +
                                "Please edit the file at ~/.mediatagger/HF_KEY.txt to include your valid token.",
                        "Token Not Configured",
                        JOptionPane.ERROR_MESSAGE,
                        new ImageIcon(appIcon));
                System.exit(1);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame,
                    "Could not read Hugging Face token file at:\n" + tokenPath + "\n\nError: " + e.getMessage(),
                    "Token File Error",
                    JOptionPane.ERROR_MESSAGE,
                    new ImageIcon(appIcon));
            System.exit(1);
        }
    }

    private void runStartupScript() {
        JDialog waitDialog = new JDialog(frame, "Startup Routine", true);
        if (this.appIcon != null) {
            waitDialog.setIconImage(this.appIcon);
        }
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(new JLabel("Performing first start routine, please wait..."), BorderLayout.CENTER);
        waitDialog.setContentPane(panel);
        waitDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        waitDialog.pack();
        waitDialog.setLocationRelativeTo(frame);

        SwingWorker<Integer, Void> worker = new SwingWorker<>() {
            private String errorOutput = "";

            @Override
            protected Integer doInBackground() throws Exception {
                Path scriptPath = resourceDir.resolve("install_dependencies.sh");
                // --- MODIFIED: Pass the loaded token as a command-line argument ---
                ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString(), hfToken);
                Process process = pb.start();

                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    errorOutput = errorReader.lines().collect(Collectors.joining("\n"));
                }

                return process.waitFor();
            }

            @Override
            protected void done() {
                waitDialog.dispose();
                try {
                    int exitCode = get();
                    if (exitCode != 0) {
                        String errorMessage = "The startup script failed (exit code: " + exitCode + ").\n\n";
                        if (!errorOutput.isBlank()) {
                            errorMessage += "Error output:\n" + errorOutput + "\n\n";
                        }
                        errorMessage += "Please ensure you have an internet connection and the necessary permissions, then restart the application.";
                        JOptionPane.showMessageDialog(frame, errorMessage, "Installation Failed", JOptionPane.ERROR_MESSAGE, new ImageIcon(appIcon));
                        System.exit(1);
                    }
                } catch (CancellationException | InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(frame,
                            "An unexpected error occurred during the startup routine:\n" + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE, new ImageIcon(appIcon));
                    System.exit(1);
                }
            }
        };

        worker.execute();
        waitDialog.setVisible(true);
    }

    private String findExecutable(String name) {
        String[] commonPaths = {"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"};
        for (String p : commonPaths) {
            Path fullPath = Paths.get(p, name);
            if (Files.isExecutable(fullPath)) {
                return fullPath.toString();
            }
        }
        return null;
    }

    private boolean isCompatiblePython(String path) {
        try {
            Process proc = new ProcessBuilder(path, "-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String ver = reader.readLine();
                proc.waitFor();
                if (ver == null) return false;
                String[] parts = ver.trim().split("\\.");
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major == 3 && minor < 13;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String findCompatiblePython() {
        String[] candidates = {"python3.11", "python3.10", "python3"};
        for (String name : candidates) {
            String path = findExecutable(name);
            if (path != null && isCompatiblePython(path)) {
                return path;
            }
        }
        return null;
    }

    private void setupResources() throws IOException {
        String userHome = System.getProperty("user.home");
        resourceDir = Paths.get(userHome, ".mediatagger");
        if (!Files.exists(resourceDir)) {
            Files.createDirectories(resourceDir);
        }
        // --- MODIFIED: Add HF_KEY.txt to the list of resources to copy ---
        String[] resourceFiles = {"video_tagger_CLI.py", "known_faces.index", "names.json", "install_dependencies.sh", "mount_server.sh", "detect_speech.py", "HF_KEY.txt"};
        for (String fileName : resourceFiles) {
            Path scriptPath = resourceDir.resolve(fileName);
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(fileName)) {
                if (in == null) throw new IOException("Resource not found in JAR: " + fileName);
                Files.copy(in, scriptPath, StandardCopyOption.REPLACE_EXISTING);
                if (fileName.endsWith(".sh")) {
                    new ProcessBuilder("chmod", "+x", scriptPath.toString()).start();
                }
            }
        }
    }

    private void setupGUI() {
        String[] version = VERSION.split(",");
        btn_version.setText("<html><div style='opacity:0.5;'>Version: " + version[0] + "</div></html>");
        btn_version.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    try {
                        Desktop.getDesktop().browse(new URI(version[1]));
                    } catch (IOException | URISyntaxException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });

        lst_file_contents.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component renderer = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (renderer instanceof JLabel && value instanceof File) {
                    ((JLabel) renderer).setText(((File) value).getName());
                }
                return renderer;
            }
        });

        btn_go.addActionListener(e -> processAllMedia());
        btn_help.addActionListener(e -> openHelpLink());
        btn_add.addActionListener(e -> {
            file_chooser.setDialogTitle("Select Photos and Videos to add");
            file_chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            file_chooser.setMultiSelectionEnabled(true);
            if (file_chooser.showOpenDialog(btn_add) == JFileChooser.APPROVE_OPTION) {
                for (File file : file_chooser.getSelectedFiles()) {
                    addFileOrDirectory(file);
                }
                lst_file_contents.setListData(selectedFiles.toArray(new File[0]));
            }
        });
        lst_file_contents.setDropTarget(new DropTarget(lst_file_contents, new DropTargetAdapter() {
            public void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> droppedFiles = (List<File>) evt.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : droppedFiles) {
                        addFileOrDirectory(file);
                    }
                    lst_file_contents.setListData(selectedFiles.toArray(new File[0]));
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }));
        btn_clear.addActionListener(e -> {
            selectedFiles.clear();
            lst_file_contents.setListData(new File[0]);
        });
        btn_remove_item.addActionListener(e -> {
            selectedFiles.removeAll(lst_file_contents.getSelectedValuesList());
            lst_file_contents.setListData(selectedFiles.toArray(new File[0]));
        });
        txt_tags.addActionListener(e -> btn_tag_add.doClick());
        btn_tag_add.addActionListener(e -> {
            String tag = txt_tags.getText().trim();
            String[] potentialTags = tag.split(",");

            for (String singleTag : potentialTags) {
                String trimmedTag = singleTag.trim();

                if (!trimmedTag.isEmpty()) {
                    tags.add(trimmedTag);
                }
            }
            if (!tag.trim().isEmpty()) {
                updateTagsLabel();
                txt_tags.setText("");
            }
        });
        btn_tag_remove.addActionListener(e -> {
            if (!tags.isEmpty()) {
                tags.remove(tags.size() - 1);
                updateTagsLabel();
            }
        });
        btn_search_location.addActionListener(this::searchLocationAction);
        txt_search_location.addActionListener(e -> btn_search_location.doClick());
        lst_search_location_results.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectedLocation = lst_search_location_results.getSelectedValue();
                if (selectedLocation != null) {
                    lbl_location.setText("<html><div style='width:350px;'>Location: " + selectedLocation.displayName + "</div></html>");
                }
            }
        });
        ButtonGroup copyGroup = new ButtonGroup();
        copyGroup.add(rdo_finished);
        copyGroup.add(rdo_broll);
        rdo_finished.setSelected(true);
        chk_copy_files.addActionListener(e -> {
            boolean selected = chk_copy_files.isSelected();
            rdo_finished.setEnabled(selected);
            rdo_broll.setEnabled(selected);
        });
        chk_copy_files.getActionListeners()[0].actionPerformed(null);
        initializeDatePicker();
    }

    private void openHelpLink() {
        String url = "https://drive.google.com/file/d/1bJFkvOxcsWTccpHfI38TtMkxgFCTPz8b/view?usp=sharing";
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (IOException | URISyntaxException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void addFileOrDirectory(File file) {
        if (file.isFile() && isVideoOrPhoto(file)) {
            selectedFiles.add(file);
        } else if (file.isDirectory()) {
            addFilesFromDirectory(file);
        }
    }

    private void processAllMedia() {
        if (tags.isEmpty() || selectedLocation == null) {
            JOptionPane.showMessageDialog(frame, "Please add at least one tag and select a location.", "Warning", JOptionPane.WARNING_MESSAGE, new ImageIcon(appIcon));
            return;
        }
        if (chk_lega_approved.isSelected()) {
            tags.add("✅ Reviewed by Legal");
        }
        if(chk_safety.isSelected()){
            tags.add("✅ Reviewed by Safety");
        }
        List<File> videosToProcess = selectedFiles.stream()
                .filter(f -> f.getName().toLowerCase().endsWith(".mp4"))
                .collect(Collectors.toList());
        Map<File, FaceData> faceDataMap = runAllFaceRecognition(videosToProcess);
        Map<File, List<String>> recognizedTags = new LinkedHashMap<>();
        if (faceDataMap != null) {
            faceDataMap.forEach((f, fd) -> recognizedTags.put(f, fd.names));
        }
        if (faceDataMap == null) {
            System.out.println("Face recognition was canceled or failed.");
            return;
        }

        Map<File, List<String>> confirmedTags = new LinkedHashMap<>();
        for (File file : selectedFiles) {
            List<String> peopleTags = recognizedTags.getOrDefault(file, new ArrayList<>());
            if (videosToProcess.contains(file)) {
                peopleTags = showTagReviewDialog(file, peopleTags);
            }
            confirmedTags.put(file, peopleTags);

            if (chk_text_to_speech.isSelected() && transcripts.containsKey(file)) {
                FaceData fd = faceDataMap.get(file);
                String finalText = showTranscriptReviewDialog(file, transcripts.get(file), fd);
                transcripts.put(file, finalText);
                File txtFile = new File(file.getParent(), file.getName().replaceFirst("\\.[^.]+$", ".txt"));
                try (BufferedWriter bw = Files.newBufferedWriter(txtFile.toPath())) {
                    bw.write(finalText);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
        runFinalEmbeddingProcess(confirmedTags);
    }

    private Map<File, FaceData> runAllFaceRecognition(List<File> videos) {
        final JDialog progressDialog = new JDialog(frame, "Processing Videos...", true);
        if (this.appIcon != null) {
            progressDialog.setIconImage(this.appIcon);
        }
        final JProgressBar overallProgressBar = new JProgressBar(0, videos.size() * 100);
        final JLabel overallLabel = new JLabel("Overall Progress");
        final JLabel statusLabel = new JLabel("Starting...");
        final JProgressBar frameProgressBar = new JProgressBar(0, 100);
        final JLabel frameLabel = new JLabel("Video Progress: 0%");
        final JProgressBar speechProgressBar = new JProgressBar(0, 100);
        final JLabel speechLabel = new JLabel("Speech Progress: 0%");
        final JButton cancelButton = new JButton("Cancel");
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        frameProgressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        frameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        overallProgressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        overallLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        speechProgressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        speechLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel progressPanel = new JPanel();
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
        progressPanel.add(statusLabel);
        progressPanel.add(Box.createVerticalStrut(5));
        progressPanel.add(frameProgressBar);
        progressPanel.add(frameLabel);
        if (chk_text_to_speech.isSelected()) {
            progressPanel.add(Box.createVerticalStrut(5));
            progressPanel.add(speechProgressBar);
            progressPanel.add(speechLabel);
        }
        progressPanel.add(Box.createVerticalStrut(10));
        progressPanel.add(overallProgressBar);
        progressPanel.add(overallLabel);
        panel.add(progressPanel, BorderLayout.CENTER);
        panel.add(cancelButton, BorderLayout.SOUTH);
        progressDialog.setContentPane(panel);
        int dlgHeight = chk_text_to_speech.isSelected() ? 250 : 200;
        progressDialog.setSize(400, dlgHeight);
        progressDialog.setLocationRelativeTo(frame);
        final Process[] processHolder = new Process[1];
        final int[] currentVideoIndex = {0};

        SwingWorker<Map<File, FaceData>, Void> worker = new SwingWorker<>() {
            @Override
            protected Map<File, FaceData> doInBackground() throws Exception {
                Map<File, FaceData> results = new HashMap<>();
                Path scriptPath = resourceDir.resolve("video_tagger_CLI.py");
                Path indexPath = resourceDir.resolve("known_faces.index");
                Path namesPath = resourceDir.resolve("names.json");
                Path speechScriptPath = resourceDir.resolve("detect_speech.py");

                for (int i = 0; i < videos.size(); i++) {
                    if (isCancelled()) break;
                    currentVideoIndex[0] = i;
                    File video = videos.get(i);
                    final String videoName = video.getName();
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Processing: " + videoName));

                    ArrayList<String> command = new ArrayList<>();
                    command.add(pythonExecutablePath);
                    command.add(scriptPath.toString());
                    command.add(video.getAbsolutePath());
                    command.add(indexPath.toString());
                    command.add(namesPath.toString());
                    command.add("--ffmpeg-path");
                    command.add(ffmpegExecutablePath);
                    command.add("--ffprobe-path");
                    command.add(ffprobeExecutablePath);
                    if (chk_show_preview.isSelected()) {
                        command.add("--preview");
                    }
                    ProcessBuilder pb = new ProcessBuilder(command);
                    processHolder[0] = pb.start();

                    final List<String> recognizedNamesForVideo = new ArrayList<>();
                    final List<Detection> detectionsForVideo = new ArrayList<>();

                    try (
                            BufferedReader reader = new BufferedReader(new InputStreamReader(processHolder[0].getInputStream()));
                            BufferedReader errorReader = new BufferedReader(new InputStreamReader(processHolder[0].getErrorStream()))
                    ) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("PROGRESS:")) {
                                setProgress(Integer.parseInt(line.substring(9)));
                            } else if (line.startsWith("RESULTS:")) {
                                String jsonOutput = line.substring(8);
                                JSONObject obj = new JSONObject(jsonOutput);
                                JSONArray namesArr = obj.getJSONArray("names");
                                for (int j = 0; j < namesArr.length(); j++) {
                                    recognizedNamesForVideo.add(namesArr.getString(j));
                                }
                                JSONArray detArr = obj.getJSONArray("detections");
                                for (int j = 0; j < detArr.length(); j++) {
                                    JSONObject d = detArr.getJSONObject(j);
                                    detectionsForVideo.add(new Detection(d.getDouble("time"), d.getString("name")));
                                }
                            } else {
                                System.out.println("Python stdout: " + line);
                            }
                        }

                        int exitCode = processHolder[0].waitFor();
                        if (exitCode != 0) {
                            final String errorOutput = errorReader.lines().collect(Collectors.joining("\n"));
                            final String errorMessage = "The face recognition failed for file '" + videoName + "' (exit code " + exitCode + ").\n\nError:\n" + errorOutput;
                            SwingUtilities.invokeLater(() ->
                                    JOptionPane.showMessageDialog(frame, errorMessage, "Face Recognition Error", JOptionPane.ERROR_MESSAGE, new ImageIcon(appIcon))
                            );
                            throw new IOException(errorMessage);
                        }
                    }

                    results.put(video, new FaceData(recognizedNamesForVideo, detectionsForVideo));

                    if (isCancelled()) break;

                    if (chk_text_to_speech.isSelected()) {
                        ArrayList<String> speechCmd = new ArrayList<>();
                        speechCmd.add(pythonExecutablePath);
                        speechCmd.add(speechScriptPath.toString());
                        speechCmd.add(video.getAbsolutePath());
                        speechCmd.add(hfToken);
                        ProcessBuilder speechPb = new ProcessBuilder(speechCmd);
                        Process speechProcess = speechPb.start();

                        try (
                                BufferedReader speechReader = new BufferedReader(new InputStreamReader(speechProcess.getInputStream()));
                                BufferedReader speechError = new BufferedReader(new InputStreamReader(speechProcess.getErrorStream()))
                        ) {
                            String line;
                            StringBuilder speechResult = new StringBuilder();
                            while ((line = speechReader.readLine()) != null) {
                                if (line.startsWith("PROGRESS:")) {
                                    int val = Integer.parseInt(line.substring(9));
                                    SwingUtilities.invokeLater(() -> {
                                        speechProgressBar.setValue(val);
                                        speechLabel.setText("Speech Progress: " + val + "%");
                                    });
                                } else if (line.startsWith("RESULTS:")) {
                                    speechResult.append(line.substring(8));
                                } else {
                                    System.out.println("Speech stdout: " + line);
                                }
                            }

                            int speechCode = speechProcess.waitFor();
                            if (speechCode != 0) {
                                final String errorOutput = speechError.lines().collect(Collectors.joining("\n"));
                                final String errorMessage = "The speech detection failed for file '" + videoName + "' (exit code " + speechCode + ").\n\nError:\n" + errorOutput;
                                SwingUtilities.invokeLater(() ->
                                        JOptionPane.showMessageDialog(frame, errorMessage, "Speech Detection Error", JOptionPane.ERROR_MESSAGE, new ImageIcon(appIcon))
                                );
                            } else if (speechResult.length() > 0) {
                                transcripts.put(video, speechResult.toString());
                            }
                        }
                    }
                }
                return results;
            }

            @Override
            protected void done() {
                progressDialog.dispose();
            }
        };

        cancelButton.addActionListener(e -> {
            Process p = processHolder[0];
            if (p != null) {
                p.destroyForcibly();
            }
            worker.cancel(true);
        });

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int frameProgress = (Integer) evt.getNewValue();
                frameProgressBar.setValue(frameProgress);
                frameLabel.setText("Video Progress: " + frameProgress + "%");
                int videoBaseProgress = currentVideoIndex[0] * 100;
                int overallProgress = videoBaseProgress + frameProgress;
                overallProgressBar.setValue(overallProgress);
                overallLabel.setText("Overall Progress: " + (currentVideoIndex[0] + 1) + " / " + videos.size());
            }
        });

        worker.execute();
        progressDialog.setVisible(true);

        try {
            return worker.get();
        } catch (CancellationException e) {
            System.out.println("Face recognition was canceled by the user.");
            return null;
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("An error occurred during face recognition execution.");
            e.printStackTrace();
            return null;
        }
    }

    private List<String> showTagReviewDialog(File video, List<String> initialNames) {
        JDialog dialog = new JDialog(frame, "Review Tags for " + video.getName(), true);
        if (this.appIcon != null) {
            dialog.setIconImage(this.appIcon);
        }
        dialog.setLayout(new BorderLayout(10, 10));
        DefaultListModel<String> listModel = new DefaultListModel<>();
        initialNames.forEach(listModel::addElement);
        JList<String> nameList = new JList<>(listModel);
        JScrollPane scrollPane = new JScrollPane(nameList);
        JTextField nameField = new JTextField(20);
        JButton addButton = new JButton("Add");
        JButton removeButton = new JButton("Remove Selected");
        JButton confirmButton = new JButton("Confirm Tags");
        JPanel inputPanel = new JPanel(new FlowLayout());
        inputPanel.add(new JLabel("Add Name:"));
        inputPanel.add(nameField);
        inputPanel.add(addButton);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(removeButton);
        buttonPanel.add(confirmButton);
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(inputPanel, BorderLayout.NORTH);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        final List<String> confirmedNames = new ArrayList<>();
        addButton.addActionListener(e -> {
            String newName = nameField.getText().trim();
            if (!newName.isEmpty() && !listModel.contains(newName)) {
                listModel.addElement(newName);
                nameField.setText("");
            }
        });
        nameField.addActionListener(e -> addButton.doClick());
        removeButton.addActionListener(e -> {
            int[] selectedIndices = nameList.getSelectedIndices();
            for (int i = selectedIndices.length - 1; i >= 0; i--) {
                listModel.removeElementAt(selectedIndices[i]);
            }
        });
        confirmButton.addActionListener(e -> {
            for (int i = 0; i < listModel.getSize(); i++) {
                confirmedNames.add(listModel.getElementAt(i));
            }
            dialog.dispose();
        });
        dialog.setSize(400, 500);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        return confirmedNames;
    }

    private String showTranscriptReviewDialog(File video, String json, FaceData faceData) {
        JSONObject obj = new JSONObject(json);
        JSONArray rawSegments = obj.getJSONArray("segments");
        JSONArray segments = new JSONArray();
        for (int i = 0; i < rawSegments.length(); i++) {
            JSONObject seg = rawSegments.getJSONObject(i);
            String text = seg.optString("text", "").trim();
            if (!text.isEmpty()) {
                segments.put(seg);
            }
        }

        Map<String, String> speakerNames = new LinkedHashMap<>();
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.getJSONObject(i);
            String spk = seg.optString("speaker", null);
            if (spk == null) {
                spk = "speaker" + i;
                seg.put("speaker", spk);
            }
            double start = seg.getDouble("start");
            double end = seg.getDouble("end");
            if (!speakerNames.containsKey(spk)) {
                Set<String> possible = new HashSet<>();
                for (Detection d : faceData.detections) {
                    if (d.time >= start && d.time <= end) {
                        possible.add(d.name);
                    }
                }
                if (possible.size() == 1) {
                    speakerNames.put(spk, possible.iterator().next());
                } else {
                    speakerNames.put(spk, spk);
                }
            }
        }

        JDialog dialog = new JDialog(frame, "Review Transcript for " + video.getName(), true);
        if (this.appIcon != null) dialog.setIconImage(this.appIcon);
        dialog.setLayout(new BorderLayout(10,10));

        JPanel namesPanel = new JPanel(new GridLayout(0,2,5,5));
        Map<String, JTextField> fields = new LinkedHashMap<>();
        for (Map.Entry<String,String> entry : speakerNames.entrySet()) {
            namesPanel.add(new JLabel(entry.getKey() + ":"));
            JTextField tf = new JTextField(entry.getValue());
            fields.put(entry.getKey(), tf);
            namesPanel.add(tf);
        }

        JTextArea transcriptArea = new JTextArea(20,60);
        transcriptArea.setEditable(false);

        Runnable updateArea = () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONObject seg = segments.getJSONObject(i);
                String text = seg.getString("text").trim();
                if (text.isEmpty()) {
                    continue;
                }
                String spk = seg.getString("speaker");
                String name = fields.get(spk).getText();
                double start = seg.getDouble("start");
                double end = seg.getDouble("end");
                sb.append(String.format("[%.2f-%.2f] %s: %s%n", start, end, name, text));
            }
            transcriptArea.setText(sb.toString());
        };

        fields.values().forEach(f -> f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){updateArea.run();}public void removeUpdate(javax.swing.event.DocumentEvent e){updateArea.run();}public void changedUpdate(javax.swing.event.DocumentEvent e){updateArea.run();}}));

        updateArea.run();

        JButton confirm = new JButton("Save Transcript");
        confirm.addActionListener(e -> dialog.dispose());

        dialog.add(namesPanel, BorderLayout.NORTH);
        dialog.add(new JScrollPane(transcriptArea), BorderLayout.CENTER);
        dialog.add(confirm, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);

        return transcriptArea.getText();
    }

    private void runFinalEmbeddingProcess(Map<File, List<String>> allFileTags) {
        JDialog progressDialog = new JDialog(frame, "Embedding Metadata...", true);
        if (this.appIcon != null) {
            progressDialog.setIconImage(this.appIcon);
        }
        JProgressBar progressBar = new JProgressBar(0, allFileTags.size());
        JLabel progressLabel = new JLabel("Embedding file 0 of " + allFileTags.size());
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(progressLabel, BorderLayout.SOUTH);
        progressDialog.setContentPane(panel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(frame);
        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                int count = 0;
                String selectedDate = String.format("%04d:%02d:%02d 00:00:00", (int) combo_year.getSelectedItem(), monthCodeToNumber((String) combo_month.getSelectedItem()), (int) combo_day.getSelectedItem());
                for (Map.Entry<File, List<String>> entry : allFileTags.entrySet()) {
                    File file = entry.getKey();
                    List<String> peopleForFile = entry.getValue();
                    String description = "Tags: " + String.join(", ", tags) + " - People: " + String.join(", ", peopleForFile);
                    try {
                        if (file.getName().toLowerCase().endsWith(".mp4")) {
                            embedVideoMetadata(file, description, selectedLocation, selectedDate);
                        } else {
                            embedJpegMetadata(file, description, selectedLocation, selectedDate);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    publish(++count);
                }
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                int current = chunks.get(chunks.size() - 1);
                progressBar.setValue(current);
                progressLabel.setText("Embedding file " + current + " of " + allFileTags.size());
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                if (chk_copy_files.isSelected()) {
                    copyFilesToServer();
                } else {
                    Object[] options = {"Show me where", "Close"};
                    int choice = JOptionPane.showOptionDialog(frame, "The files have been tagged. You may now copy them into the appropriate X Grid Folder", "Success", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, new ImageIcon(appIcon), options, options[1]);
                    if (choice == 0) {
                        openHelpLink();
                    }
                    btn_clear.doClick();
                }
            }
        };
        worker.execute();
        progressDialog.setVisible(true);
    }

    private void copyFilesToServer() {
        Path runMediaPath = Paths.get("/Volumes/RunMedia/");
        if (!Files.exists(runMediaPath)) {
            int response = JOptionPane.showConfirmDialog(frame, "RunMedia is not connected.\nWould you like to attempt to connect?", "Server Not Found", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, new ImageIcon(appIcon));
            if (response == JOptionPane.YES_OPTION) {
                try {
                    Process p = new ProcessBuilder("bash", resourceDir.resolve("mount_server.sh").toString()).start();
                    p.waitFor();
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            } else {
                return;
            }
        }
        if (!Files.exists(runMediaPath)) {
            JOptionPane.showMessageDialog(frame, "Could not copy files. The Run Media is not mounted.", "Error", JOptionPane.ERROR_MESSAGE, new ImageIcon(appIcon));
            return;
        }
        Path destinationPath;
        int year = (int) combo_year.getSelectedItem();
        if (rdo_finished.isSelected()) {
            destinationPath = Paths.get("/Volumes/RunMedia/XGridLibrary/" + year + "/");
        } else {
            String projectName = JOptionPane.showInputDialog(frame, "Enter the Project Name for the B-Roll folder:", "Project Name", JOptionPane.PLAIN_MESSAGE);
            if (projectName == null || projectName.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Copy canceled: Project name cannot be empty.", "Canceled", JOptionPane.WARNING_MESSAGE, new ImageIcon(appIcon));
                return;
            }
            int month = monthCodeToNumber((String) combo_month.getSelectedItem());
            String folderName = String.format("%d_%02d_%s", year, month, projectName.trim().replace(" ", "_"));
            destinationPath = Paths.get("/Volumes/RunMedia/Production/BROLL/" + year + "/Project_Stringouts/" + folderName + "/");
        }
        try {
            Files.createDirectories(destinationPath);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Could not create destination folder:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE, new ImageIcon(appIcon));
            return;
        }
        JDialog copyProgressDialog = new JDialog(frame, "Copying Files...", true);
        if (this.appIcon != null) {
            copyProgressDialog.setIconImage(this.appIcon);
        }
        JProgressBar copyProgressBar = new JProgressBar(0, 100);
        JLabel copyLabel = new JLabel("Starting copy...");
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(copyLabel, BorderLayout.NORTH);
        panel.add(copyProgressBar, BorderLayout.CENTER);
        copyProgressDialog.setContentPane(panel);
        copyProgressDialog.setSize(400, 200);
        copyProgressDialog.setLocationRelativeTo(frame);
        SwingWorker<Void, Long> copyWorker = new SwingWorker<>() {
            final long totalBytes = selectedFiles.stream().mapToLong(File::length).sum();
            long copiedBytes = 0;

            @Override
            protected Void doInBackground() throws Exception {
                byte[] buffer = new byte[8192];
                for (File sourceFile : selectedFiles) {
                    Path destFile = destinationPath.resolve(sourceFile.getName());
                    SwingUtilities.invokeLater(() -> copyLabel.setText("Copying: " + sourceFile.getName()));
                    try (InputStream in = new FileInputStream(sourceFile);
                         OutputStream out = new FileOutputStream(destFile.toFile())) {
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                            copiedBytes += bytesRead;
                            publish((copiedBytes * 100) / totalBytes);
                        }
                    }
                }
                return null;
            }

            @Override
            protected void process(List<Long> chunks) {
                copyProgressBar.setValue(chunks.get(chunks.size() - 1).intValue());
            }

            @Override
            protected void done() {
                copyProgressDialog.dispose();
                JOptionPane.showMessageDialog(frame, "Tagging complete!", "Success", JOptionPane.INFORMATION_MESSAGE, new ImageIcon(appIcon));
                btn_clear.doClick();
            }
        };
        copyWorker.execute();
        copyProgressDialog.setVisible(true);
    }

    private void addFilesFromDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    addFileOrDirectory(file);
                }
            }
        }
    }

    private boolean isVideoOrPhoto(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : VIDEO_PHOTO_EXTENSIONS)
            if (name.endsWith(ext)) return true;
        return false;
    }

    private void updateTagsLabel() {
        lbl_tags.setText("<html><div style='width:300px; height:`100px;'>Tags: " + String.join(", ", tags) + "</div></html>");
    }

    private void searchLocationAction(java.awt.event.ActionEvent e) {
        String city = txt_search_location.getText().trim();
        if (city.isEmpty()) return;
        try {
            String urlString = "https://nominatim.openstreetmap.org/search?q=" + URLEncoder.encode(city, "UTF-8") + "&format=json";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "MediaTagger/1.0");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String response = in.lines().collect(Collectors.joining());
                JSONArray results = new JSONArray(response);
                DefaultListModel<Location> model = new DefaultListModel<>();
                for (int i = 0; i < results.length(); i++) {
                    JSONObject obj = results.getJSONObject(i);
                    model.addElement(new Location(obj.getString("display_name"), obj.getString("lat"), obj.getString("lon")));
                }
                lst_search_location_results.setModel(model);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void initializeDatePicker() {
        Calendar cal = Calendar.getInstance();
        int todayDay = cal.get(Calendar.DAY_OF_MONTH);
        int todayMonth = cal.get(Calendar.MONTH);
        int todayYear = cal.get(Calendar.YEAR);
        String[] monthCodes = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        for (String monthCode : monthCodes) combo_month.addItem(monthCode);
        combo_month.setSelectedIndex(todayMonth);
        for (int y = todayYear - 10; y <= todayYear + 10; y++) combo_year.addItem(y);
        combo_year.setSelectedItem(todayYear);
        updateDayComboBox(todayDay);
        ActionListener listener = e -> {
            int previouslySelectedDay = (combo_day.getSelectedItem() != null) ? (int) combo_day.getSelectedItem() : 1;
            updateDayComboBox(previouslySelectedDay);
        };
        combo_month.addActionListener(listener);
        combo_year.addActionListener(listener);
    }

    private void updateDayComboBox(int dayToSelect) {
        int year = (int) combo_year.getSelectedItem();
        int month = monthCodeToNumber((String) combo_month.getSelectedItem());
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month - 1);
        int maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        Integer currentSelection = dayToSelect;
        combo_day.removeAllItems();
        for (int d = 1; d <= maxDays; d++) {
            combo_day.addItem(d);
        }
        combo_day.setSelectedItem(Math.min(currentSelection, maxDays));
    }

    private void embedJpegMetadata(File file, String description, Location location, String date) throws Exception {
        TiffOutputSet outputSet = null;
        try {
            JpegImageMetadata jpegMetadata = (JpegImageMetadata) Imaging.getMetadata(file);
            if (jpegMetadata != null && jpegMetadata.getExif() != null) {
                outputSet = jpegMetadata.getExif().getOutputSet();
            }
        } catch (Exception e) { /* Ignore */
        }
        if (outputSet == null) outputSet = new TiffOutputSet();
        TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
        rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
        rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, description);
        TiffOutputDirectory exifSubIFD = outputSet.getOrCreateExifDirectory();
        exifSubIFD.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
        exifSubIFD.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, date);
        if (location != null)
            outputSet.setGpsInDegrees(Double.parseDouble(location.lon), Double.parseDouble(location.lat));
        File tempFile = new File(file.getParent(), "temp_" + file.getName());
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            new ExifRewriter().updateExifMetadataLossless(file, os, outputSet);
        }
        if (!file.delete() || !tempFile.renameTo(file)) throw new IOException("Failed to replace file.");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
        LocalDateTime ldt = LocalDateTime.parse(date, formatter);
        Files.setAttribute(file.toPath(), "basic:creationTime", FileTime.from(ldt.toInstant(ZoneOffset.UTC)));
    }

    private void embedVideoMetadata(File file, String description, Location location, String date) throws Exception {
        String inputFilePath = file.getAbsolutePath();
        File tempFile = new File(file.getParent(), "temp_" + file.getName());
        String tempFilePath = tempFile.getAbsolutePath();
        ArrayList<String> command = new ArrayList<>();
        command.add(ffmpegExecutablePath);
        command.add("-i");
        command.add(inputFilePath);
        command.add("-c");
        command.add("copy");
        command.add("-metadata");
        command.add("description=" + description);
        DateTimeFormatter exifFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
        LocalDateTime ldt = LocalDateTime.parse(date, exifFormatter);
        command.add("-metadata");
        command.add("creation_time=" + ldt.atOffset(ZoneOffset.UTC).toString());
        if (location != null) {
            command.add("-metadata");
            command.add("location=" + String.format("%+f%+f/", Double.parseDouble(location.lat), Double.parseDouble(location.lon)));
        }
        command.add("-y");
        command.add(tempFilePath);
        Process process = new ProcessBuilder(command).start();
        if (process.waitFor() != 0) throw new IOException("FFmpeg process failed.");
        if (!file.delete() || !tempFile.renameTo(file)) throw new IOException("Failed to replace file.");
        Files.setAttribute(file.toPath(), "basic:creationTime", FileTime.from(ldt.toInstant(ZoneOffset.UTC)));
    }

    private int monthCodeToNumber(String monthCode) {
        return List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").indexOf(monthCode) + 1;
    }

    private static class Location {
        String displayName;
        String lat;
        String lon;

        public Location(String displayName, String lat, String lon) {
            this.displayName = displayName;
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private static class Detection {
        double time;
        String name;

        Detection(double time, String name) {
            this.time = time;
            this.name = name;
        }
    }

    private static class FaceData {
        List<String> names;
        List<Detection> detections;

        FaceData(List<String> names, List<Detection> detections) {
            this.names = names;
            this.detections = detections;
        }
    }
}
