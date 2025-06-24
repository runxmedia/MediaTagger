package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainInterface {
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
    private final JFrame frame;

    private JFileChooser file_chooser;
    private ArrayList<File> selectedFiles;
    private ArrayList<String> tags;
    private Location selectedLocation;
    private final String ffmpegCommand = "ffmpeg";
    private static final String[] VIDEO_PHOTO_EXTENSIONS = {".jpg", ".jpeg", ".mp4"};
    private Path resourceDir;

    public static void main(String[] args) {
        new MainInterface();
    }

    public MainInterface() {
        frame = new JFrame();
        frame.setContentPane(pnl_main_interface);
        frame.setTitle("Media Tagger");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setSize(800, 800);
        frame.setVisible(true);

        file_chooser = new JFileChooser();
        selectedFiles = new ArrayList<>();
        tags = new ArrayList<>();

        try {
            setupResources();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Failed to initialize resources: " + e.getMessage(), "Initialization Error", JOptionPane.ERROR_MESSAGE);
        }
        setupGUI();
    }

    private void setupResources() throws IOException {
        String userHome = System.getProperty("user.home");
        resourceDir = Paths.get(userHome, ".mediatagger");
        if (!Files.exists(resourceDir)) {
            Files.createDirectories(resourceDir);
        }
        String[] resourceFiles = {"video_tagger_CLI.py", "known_faces.index", "names.json", "install_dependencies.sh", "mount_server.sh"};
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
            if (!tag.isEmpty()) {
                tags.add(tag);
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
                JOptionPane.showMessageDialog(frame, "Could not open the help link.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            JOptionPane.showMessageDialog(frame, "Could not open link, browser not supported.", "Error", JOptionPane.ERROR_MESSAGE);
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
            JOptionPane.showMessageDialog(frame, "Please add at least one tag and select a location.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (chk_lega_approved.isSelected()) {
            tags.add("Reviewed by Legal");
        }

        List<File> videosToProcess = selectedFiles.stream()
                .filter(f -> f.getName().toLowerCase().endsWith(".mp4"))
                .collect(Collectors.toList());

        Map<File, List<String>> recognizedTags = runAllFaceRecognition(videosToProcess);
        if (recognizedTags == null) {
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
        }

        runFinalEmbeddingProcess(confirmedTags);
    }

    private Map<File, List<String>> runAllFaceRecognition(List<File> videos) {
        final JDialog progressDialog = new JDialog(frame, "Recognizing Faces...", true);
        final JProgressBar overallProgressBar = new JProgressBar(0, videos.size() * 100);
        final JLabel overallLabel = new JLabel("Overall Progress");
        final JLabel statusLabel = new JLabel("Starting...");
        final JProgressBar frameProgressBar = new JProgressBar(0, 100);
        final JLabel frameLabel = new JLabel("Video Progress: 0%");
        final JButton cancelButton = new JButton("Cancel");
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        frameProgressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        frameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        overallProgressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        overallLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel progressPanel = new JPanel();
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
        progressPanel.add(statusLabel);
        progressPanel.add(Box.createVerticalStrut(5));
        progressPanel.add(frameProgressBar);
        progressPanel.add(frameLabel);
        progressPanel.add(Box.createVerticalStrut(10));
        progressPanel.add(overallProgressBar);
        progressPanel.add(overallLabel);
        panel.add(progressPanel, BorderLayout.CENTER);
        panel.add(cancelButton, BorderLayout.SOUTH);
        progressDialog.setContentPane(panel);
        progressDialog.setSize(400, 200);
        progressDialog.setLocationRelativeTo(frame);
        final Process[] processHolder = new Process[1];
        final int[] currentVideoIndex = {0};
        SwingWorker<Map<File, List<String>>, Void> worker = new SwingWorker<>() {
            @Override
            protected Map<File, List<String>> doInBackground() throws Exception {
                Map<File, List<String>> results = new HashMap<>();
                Path scriptPath = resourceDir.resolve("video_tagger_CLI.py");
                Path indexPath = resourceDir.resolve("known_faces.index");
                Path namesPath = resourceDir.resolve("names.json");
                for (int i = 0; i < videos.size(); i++) {
                    if (isCancelled()) break;
                    currentVideoIndex[0] = i;
                    File video = videos.get(i);
                    final int currentVideoNum = i + 1;
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Processing: " + video.getName()));
                    ArrayList<String> command = new ArrayList<>();
                    command.add("python3");
                    command.add(scriptPath.toString());
                    command.add(video.getAbsolutePath());
                    command.add(indexPath.toString());
                    command.add(namesPath.toString());
                    if (chk_show_preview.isSelected()) {
                        command.add("--preview");
                    }
                    ProcessBuilder pb = new ProcessBuilder(command);
                    processHolder[0] = pb.start();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(processHolder[0].getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("PROGRESS:")) {
                                setProgress(Integer.parseInt(line.substring(9)));
                            } else {
                                System.out.println("Python: " + line);
                            }
                        }
                    }
                    if (processHolder[0].waitFor() == 0) {
                        Path resultsPath = Paths.get(video.getAbsolutePath().substring(0, video.getAbsolutePath().lastIndexOf('.')) + ".txt");
                        if (Files.exists(resultsPath)) {
                            results.put(video, Files.readAllLines(resultsPath));
                            Files.delete(resultsPath);
                        } else {
                            results.put(video, new ArrayList<>());
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
            return null;
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            return null;
        }
    }

    private List<String> showTagReviewDialog(File video, List<String> initialNames) {
        JDialog dialog = new JDialog(frame, "Review Tags for " + video.getName(), true);
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

    private void runFinalEmbeddingProcess(Map<File, List<String>> allFileTags) {
        JDialog progressDialog = new JDialog(frame, "Embedding Metadata...", true);
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
                    int choice = JOptionPane.showOptionDialog(frame, "The files have been tagged. You may now copy them into the appropriate X Grid Folder", "Success", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[1]);
                    if (choice == 0) {
                        openHelpLink();
                    }
                }
            }
        };
        worker.execute();
        progressDialog.setVisible(true);
    }

    private void copyFilesToServer() {
        Path runMediaPath = Paths.get("/Volumes/RunMedia/");
        if (!Files.exists(runMediaPath)) {
                try {
                    Process p = new ProcessBuilder("bash", resourceDir.resolve("mount_server.sh").toString()).start();
                    p.waitFor();
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
        }
        if (!Files.exists(runMediaPath)) {
            JOptionPane.showMessageDialog(frame, "Could not copy files. The server could not be connected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Path destinationPath;
        int year = (int) combo_year.getSelectedItem();
        if (rdo_finished.isSelected()) {
            destinationPath = Paths.get("/Volumes/RunMedia/XGridLibrary/" + year + "/");
        } else {
            String projectName = JOptionPane.showInputDialog(frame, "Enter the Project Name for the B-Roll folder:", "B-Roll Project Name", JOptionPane.PLAIN_MESSAGE);
            if (projectName == null || projectName.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Copy canceled: Project name cannot be empty.", "Canceled", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int month = monthCodeToNumber((String) combo_month.getSelectedItem());
            String folderName = String.format("%d_%02d_%s", year, month, projectName.trim().replace(" ", "_"));
            destinationPath = Paths.get("/Volumes/RunMedia/Production/BROLL/" + year + "/Project_Stringouts/" + folderName + "/");
        }
        try {
            Files.createDirectories(destinationPath);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Could not create destination directory:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JDialog copyProgressDialog = new JDialog(frame, "Copying Files...", true);
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
                JOptionPane.showMessageDialog(frame, "File copy complete!", "Success", JOptionPane.INFORMATION_MESSAGE);
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
        lbl_tags.setText("<html><div style='width:350px;'>Tags: " + String.join(", ", tags) + "</div></html>");
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
            outputSet.setGPSInDegrees(Double.parseDouble(location.lon), Double.parseDouble(location.lat));
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
        command.add(ffmpegCommand);
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
}