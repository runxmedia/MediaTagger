package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
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
import java.util.stream.Collectors;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.RationalNumber;
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
    // ... (keep all your existing component declarations)
    private JScrollPane lst_files;
    private JButton btn_clear;
    private JButton btn_add;
    private JButton btn_remove_item;
    private JTextField txt_tags;
    private JButton btn_tag_remove;
    private JLabel lbl_tags;
    private JButton btn_tag_add;
    private JLabel lbl_people;
    private JTextField txt_people;
    private JButton btn_people_remove;
    private JButton btn_people_add;
    private JLabel lbl_location;
    private JButton btn_search_location;
    private JButton btn_go;
    private JPanel pnl_main_interface;
    private JList lst_file_contents;
    private JTextField txt_search_location;
    private JList lst_search_location_results;
    private JLabel lbl_date;
    private JComboBox combo_month;
    private JComboBox combo_day;
    private JComboBox combo_year;
    private JButton btn_recognize_faces;
    private final JFrame frame;

    private JFileChooser file_chooser;
    private ArrayList<File> selectedFiles;
    private ArrayList<String> tags;
    private ArrayList<String> people;
    private Location selectedLocation;
    private final String ffmpegCommand = "ffmpeg"; // Set for Homebrew install
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

        // ... (icon loading code)

        frame.setVisible(true);
        file_chooser = new JFileChooser();
        selectedFiles = new ArrayList<>();
        tags = new ArrayList<>();
        people = new ArrayList<>();

        try {
            setupResources();
            checkDependencies();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Failed to initialize: " + e.getMessage(), "Initialization Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
            System.exit(1);
        }

        setupGUI();
    }

    private void setupResources() throws IOException {
        String userHome = System.getProperty("user.home");
        resourceDir = Paths.get(userHome, ".mediatagger");
        if (!Files.exists(resourceDir)) {
            Files.createDirectories(resourceDir);
        }

        String[] resourceFiles = {"video_tagger_CLI.py", "known_faces.index", "names.json", "install_dependencies.sh"};

        for (String fileName : resourceFiles) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(fileName)) {
                if (in == null) throw new IOException("Resource not found in JAR: " + fileName);
                Path destination = resourceDir.resolve(fileName);
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void checkDependencies() {
        Path flagFile = resourceDir.resolve(".dependencies_installed");
        if (Files.exists(flagFile)) {
            System.out.println("Dependencies are already installed.");
            return;
        }

        int response = JOptionPane.showConfirmDialog(frame,
                "Setup required. Media Tagger will use Homebrew to install FFmpeg, Python, \n" +
                        "and necessary face recognition libraries. This is a one-time setup.\n\n" +
                        "Do you want to proceed?",
                "Initial Environment Setup", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (response == JOptionPane.YES_OPTION) {
            JDialog progressDialog = new JDialog(frame, "Setting Up Environment...", true);
            JTextArea textArea = new JTextArea(15, 50);
            textArea.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(textArea);
            progressDialog.getContentPane().add(scrollPane);
            progressDialog.pack();
            progressDialog.setLocationRelativeTo(frame);

            SwingWorker<Boolean, String> worker = new SwingWorker<Boolean, String>() {
                @Override
                protected Boolean doInBackground() throws Exception {
                    Path scriptPath = resourceDir.resolve("install_dependencies.sh");

                    // Make script executable
                    new ProcessBuilder("chmod", "+x", scriptPath.toString()).start().waitFor();

                    ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString());
                    pb.directory(resourceDir.toFile());
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            publish(line);
                        }
                    }
                    return process.waitFor() == 0;
                }

                @Override
                protected void process(List<String> chunks) {
                    for (String line : chunks) {
                        textArea.append(line + "\n");
                    }
                }

                @Override
                protected void done() {
                    progressDialog.dispose();
                    try {
                        if (get()) {
                            JOptionPane.showMessageDialog(frame, "Setup successful! The application is ready to use.", "Success", JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            JOptionPane.showMessageDialog(frame, "Setup failed. Please check the log for details.", "Error", JOptionPane.ERROR_MESSAGE);
                            System.exit(1);
                        }
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(frame, "An error occurred during setup: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        e.printStackTrace();
                        System.exit(1);
                    }
                }
            };

            worker.execute();
            progressDialog.setVisible(true);
        } else {
            JOptionPane.showMessageDialog(frame, "Setup was canceled. The application will now exit.", "Canceled", JOptionPane.WARNING_MESSAGE);
            System.exit(0);
        }
    }

    // ... (The rest of your setupGUI() and other methods remain the same as the previous response)
    // MAKE SURE TO COPY THE FULL setupGUI() method from the previous response here.
    // It includes the listeners for all your buttons, including the new `btn_recognize_faces`.
    // The `runFaceRecognition` method also remains unchanged.
    private void setupGUI(){
        // ... (all your existing btn_add, btn_clear, etc. listeners remain the same)
        btn_add.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                file_chooser.setDialogTitle("Select Photos and Videos to add");
                file_chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                file_chooser.setMultiSelectionEnabled(true);

                // Set file filter to accept only supported video/photo extensions and directories
                file_chooser.setAcceptAllFileFilterUsed(false);
                file_chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
                    @Override
                    public boolean accept(File f) {
                        if (f.isDirectory()) {
                            return true;
                        }
                        String name = f.getName().toLowerCase();
                        for (String ext : VIDEO_PHOTO_EXTENSIONS) {
                            if (name.endsWith(ext)) {
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public String getDescription() {
                        return "Supported formats (" + String.join(", ", VIDEO_PHOTO_EXTENSIONS) + ")";
                    }
                });

                if (file_chooser.showOpenDialog(btn_add) == JFileChooser.APPROVE_OPTION) {
                    for (File file : file_chooser.getSelectedFiles()) {
                        if(file.getAbsolutePath().contains("RunMedia")){
                            JOptionPane.showMessageDialog(frame, "Tagging files directly on the server is prohibited.\nPlease tag files on your local machine before copying them to the server.", "File Tag location", JOptionPane.WARNING_MESSAGE);
                            selectedFiles.clear();
                            lst_file_contents.setListData(selectedFiles.toArray());
                            return;
                        }
                        if (file.isFile() && isVideoOrPhoto(file)) {
                            selectedFiles.add(file);
                        } else if (file.isDirectory()) {
                            addFilesFromDirectory(file);
                        }
                    }
                    lst_file_contents.setListData(selectedFiles.toArray(new File[0]));
                }
            }
        });

        btn_clear.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectedFiles.clear();
                lst_file_contents.setListData(selectedFiles.toArray());
            }
        });

        btn_remove_item.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                List<File> itemsToRemove = lst_file_contents.getSelectedValuesList();
                selectedFiles.removeAll(itemsToRemove);
                lst_file_contents.setListData(selectedFiles.toArray(new File[0]));
            }
        });

        btn_tag_add.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String tag = txt_tags.getText().trim();
                if (!tag.isEmpty()) {
                    tags.add(tag);
                    updateTagsLabel();
                    txt_tags.setText("");
                }
            }
        });

        btn_tag_remove.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!tags.isEmpty()) {
                    tags.remove(tags.size() - 1);
                    updateTagsLabel();
                }
            }
        });

        btn_people_add.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String person = txt_people.getText().trim();
                if (!person.isEmpty()) {
                    people.add(person);
                    updatePeopleLabel();
                    txt_people.setText("");
                }
            }
        });

        btn_people_remove.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!people.isEmpty()) {
                    people.remove(people.size() - 1);
                    updatePeopleLabel();
                }
            }
        });

        btn_search_location.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String city = txt_search_location.getText().trim();
                if (!city.isEmpty()) {
                    try {
                        String urlString = "https://nominatim.openstreetmap.org/search?q=" + URLEncoder.encode(city, "UTF-8") + "&format=json";
                        URL url = new URL(urlString);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        String inputLine;
                        StringBuilder response = new StringBuilder();
                        while ((inputLine = in.readLine()) != null) {
                            response.append(inputLine);
                        }
                        in.close();

                        JSONArray results = new JSONArray(response.toString());
                        DefaultListModel<Location> model = new DefaultListModel<>();
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject obj = results.getJSONObject(i);
                            String displayName = obj.getString("display_name");
                            String lat = obj.getString("lat");
                            String lon = obj.getString("lon");
                            model.addElement(new Location(displayName, lat, lon));
                        }
                        lst_search_location_results.setModel(model);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });

        btn_recognize_faces.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                List<File> videos = selectedFiles.stream()
                        .filter(f -> f.getName().toLowerCase().endsWith(".mp4"))
                        .collect(Collectors.toList());

                if (videos.isEmpty()) {
                    JOptionPane.showMessageDialog(frame, "Please select at least one MP4 video to process.", "No Videos Selected", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }

                runFaceRecognition(videos);
            }
        });

        lst_search_location_results.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) {
                    Location loc = (Location) lst_search_location_results.getSelectedValue();
                    if (loc != null) {
                        selectedLocation = loc;
                        int containerWidth = lbl_tags.getParent().getWidth() / 2;
                        lbl_location.setText("<html><div style='width:" + containerWidth + "px;'>Location: " + loc.displayName + "</div></html>");
                    }
                }
            }
        });

        btn_go.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (tags.isEmpty() || selectedLocation == null) {
                    JOptionPane.showMessageDialog(frame, "Please add at least one tag and select a location.", "Warning", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                boolean hasVideo = false;
                for (File file : selectedFiles) {
                    if (file.getName().toLowerCase().endsWith(".mp4")) {
                        hasVideo = true;
                        break;
                    }
                }
                if (hasVideo && people.isEmpty()) {
                    int response = JOptionPane.showConfirmDialog(frame,
                            "Warning: Videos with people should have at least one person tag.\nIf the video contains no people (or people who don't regularly appear in Sunrun content) you may proceed without tags.\nDo you want to continue?",
                            "Warning", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (response != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                // Build description text from tags and people
                String description = "Tags: " + String.join(", ", tags) + " - People: " + String.join(", ", people);

                // Retrieve selected date from date picker
                int day = (int) combo_day.getSelectedItem();
                String monthStr = (String) combo_month.getSelectedItem();
                int month = monthCodeToNumber(monthStr);
                int year = (int) combo_year.getSelectedItem();
                // Format date as 'YYYY:MM:DD 00:00:00' for EXIF standard
                String selectedDate = String.format("%04d:%02d:%02d 00:00:00", year, month, day);

                // Show progress dialog with cancel option
                final JDialog progressDialog = new JDialog(frame, "Processing", true);
                final JProgressBar progressBar = new JProgressBar(0, selectedFiles.size());
                progressBar.setValue(0);
                progressBar.setStringPainted(false);
                final JLabel lblProgress = new JLabel("0%", SwingConstants.CENTER);

                // Perform tagging in background
                SwingWorker<Void, Integer> worker = new SwingWorker<Void, Integer>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        int count = 0;
                        for (File file : selectedFiles) {
                            if (isCancelled()) {
                                break;
                            }
                            String lowerName = file.getName().toLowerCase();
                            try {
                                if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                                    embedJpegMetadata(file, description, selectedLocation, selectedDate);
                                } else if (lowerName.endsWith(".mp4")) {
                                    embedVideoMetadata(file, description, selectedLocation, selectedDate);
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                            count++;
                            publish(count);
                        }
                        return null;
                    }

                    @Override
                    protected void process(List<Integer> chunks) {
                        for (int value : chunks) {
                            progressBar.setValue(value);
                            int percent = (int)((value * 100.0) / selectedFiles.size());
                            lblProgress.setText(percent + "%");
                        }
                    }

                    @Override
                    protected void done() {
                        progressDialog.dispose();
                        if (!isCancelled()) {
                            JOptionPane.showMessageDialog(frame, "Metadata tagging completed.");
                        }
                    }
                };

                JButton btnCancelProgress = new JButton("Cancel");
                btnCancelProgress.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        worker.cancel(true);
                    }
                });

                JPanel progressPanel = new JPanel(new BorderLayout(10, 10));
                progressPanel.add(progressBar, BorderLayout.NORTH);
                progressPanel.add(lblProgress, BorderLayout.CENTER);
                progressPanel.add(btnCancelProgress, BorderLayout.SOUTH);
                progressDialog.getContentPane().add(progressPanel);
                progressDialog.pack();
                progressDialog.setLocationRelativeTo(frame);

                worker.execute();
                progressDialog.setVisible(true);
            }
        });

        //Enter button listeners
        txt_tags.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                btn_tag_add.doClick();
            }
        });

        txt_people.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                btn_people_add.doClick();
            }
        });

        txt_search_location.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                btn_search_location.doClick();
            }
        });


        // Add drag-and-drop support to the file list
        lst_file_contents.setDropTarget(new DropTarget(lst_file_contents, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> droppedFiles = (List<File>) evt.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : droppedFiles) {
                        if(file.getAbsolutePath().contains("RunMedia")){
                            JOptionPane.showMessageDialog(frame, "Media Tagger doesn't work with files on the server.\nPlease tag files on your local machine before copying them to the server.", "File Tag location", JOptionPane.WARNING_MESSAGE);
                            selectedFiles.clear();
                            lst_file_contents.setListData(selectedFiles.toArray());
                            return;
                        }
                        if (file.isFile() && isVideoOrPhoto(file)) {
                            selectedFiles.add(file);
                        } else if (file.isDirectory()) {
                            addFilesFromDirectory(file);
                        }
                    }
                    lst_file_contents.setListData(selectedFiles.toArray(new File[0]));
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }));
        initializeDatePicker();

    }

    private void runFaceRecognition(List<File> videos) {
        // --- UI Setup for the new Progress Dialog ---
        final JDialog progressDialog = new JDialog(frame, "Recognizing Faces...", false);

        // Overall Progress Bar
        final JProgressBar overallProgressBar = new JProgressBar(0, videos.size());
        final JLabel overallLabel = new JLabel("Overall Progress: 0 / " + videos.size());

        // Current Video Status Label
        final JLabel statusLabel = new JLabel("Starting...");

        // Frame Progress Bar
        final JProgressBar frameProgressBar = new JProgressBar(0, 100);
        final JLabel frameLabel = new JLabel("Video Progress: 0%");

        // Set horizontal alignment for all components to center them in the layout
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        frameProgressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        frameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        overallProgressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        overallLabel.setAlignmentX(Component.CENTER_ALIGNMENT);


        // Layout the components in the dialog
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel progressPanel = new JPanel();
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));

        progressPanel.add(statusLabel);
        progressPanel.add(Box.createVerticalStrut(5));
        progressPanel.add(frameProgressBar);
        progressPanel.add(frameLabel); // Text is now below the bar
        progressPanel.add(Box.createVerticalStrut(10));
        progressPanel.add(overallProgressBar);
        progressPanel.add(overallLabel); // Text is now below the bar

        panel.add(progressPanel, BorderLayout.CENTER);
        progressDialog.setContentPane(panel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(frame);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                Path scriptPath = resourceDir.resolve("video_tagger_CLI.py");
                Path indexPath = resourceDir.resolve("known_faces.index");
                Path namesPath = resourceDir.resolve("names.json");

                int videoCount = 0;
                for (File video : videos) {
                    videoCount++;
                    final int currentVideoNum = videoCount;

                    // Update overall progress and status label on the EDT
                    SwingUtilities.invokeLater(() -> {
                        overallProgressBar.setValue(currentVideoNum);
                        overallLabel.setText("Overall Progress: " + currentVideoNum + " / " + videos.size());
                        statusLabel.setText("Processing: " + video.getName());
                        frameProgressBar.setValue(0);
                        frameLabel.setText("Video Progress: 0%");
                    });

                    setProgress(0);

                    ProcessBuilder pb = new ProcessBuilder(
                            "python3",
                            scriptPath.toString(),
                            video.getAbsolutePath(),
                            indexPath.toString(),
                            namesPath.toString()
                    );
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("PROGRESS:")) {
                                try {
                                    String progressValue = line.substring("PROGRESS:".length());
                                    int percent = Integer.parseInt(progressValue);
                                    setProgress(percent);
                                } catch (NumberFormatException e) {
                                    System.err.println("Could not parse progress line: " + line);
                                }
                            } else {
                                System.out.println("Python: " + line);
                            }
                        }
                    }

                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        String resultsPathStr = video.getAbsolutePath().substring(0, video.getAbsolutePath().lastIndexOf('.')) + ".txt";
                        Path resultsPath = Paths.get(resultsPathStr);
                        if (Files.exists(resultsPath)) {
                            List<String> names = Files.readAllLines(resultsPath, StandardCharsets.UTF_8);
                            SwingUtilities.invokeLater(() -> {
                                for (String name : names) {
                                    if (name != null && !name.trim().isEmpty() && !people.contains(name)) {
                                        people.add(name);
                                    }
                                }
                            });
                            Files.delete(resultsPath);
                        }
                    } else {
                        System.err.println("Python script failed for " + video.getName() + " with exit code " + exitCode);
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                updatePeopleLabel();
                JOptionPane.showMessageDialog(frame, "Face recognition complete.", "Finished", JOptionPane.INFORMATION_MESSAGE);
            }
        };

        // This listener links the worker's setProgress(n) calls to the frame progress bar
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int progress = (Integer) evt.getNewValue();
                frameProgressBar.setValue(progress);
                frameLabel.setText("Video Progress: " + progress + "%");
            }
        });

        worker.execute();
        progressDialog.setVisible(true);
    }

    private void addFilesFromDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {  // Ensures it's not null to avoid NullPointerException
            for (File file : files) {
                if (file.isFile() && isVideoOrPhoto(file)) {
                    selectedFiles.add(file);
                } else if (file.isDirectory()) {
                    addFilesFromDirectory(file);  // Recursive call for nested directories
                }
            }
        }
    }

    private boolean isVideoOrPhoto(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : VIDEO_PHOTO_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void updateTagsLabel() {
        int containerWidth = lbl_tags.getParent().getWidth()/2;
        String tagsText = String.join(", ", tags);
        lbl_tags.setText("<html><div style='width:" + containerWidth + "px;'>Tags: "+ tagsText + "</div></html>");    }

    private void updatePeopleLabel() {
        int containerWidth = lbl_people.getParent().getWidth() / 2;
        if (people.isEmpty()) {
            lbl_people.setText("<html><div style='width:" + containerWidth + "px;'>People (Mandatory for Videos, Optional for Photos)</div></html>");
        } else {
            String peopleText = String.join(", ", people);
            lbl_people.setText("<html><div style='width:" + containerWidth + "px;'>People: " + peopleText + "</div></html>");
        }
    }

    private void initializeDatePicker() {
        Calendar cal = Calendar.getInstance();
        int todayDay = cal.get(Calendar.DAY_OF_MONTH);
        int todayMonth = cal.get(Calendar.MONTH) + 1; // Calendar.MONTH is 0-based
        int todayYear = cal.get(Calendar.YEAR);

        String[] monthCodes = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

        combo_month.removeAllItems();
        for (String monthCode : monthCodes) {
            combo_month.addItem(monthCode);
        }
        combo_month.setSelectedItem(monthCodes[todayMonth - 1]);

        combo_year.removeAllItems();
        for (int y = todayYear - 10; y <= todayYear + 10; y++) {
            combo_year.addItem(y);
        }
        combo_year.setSelectedItem(todayYear);

        updateDayComboBox(todayYear, todayMonth, todayDay);

        combo_month.addActionListener(e -> {
            String monthStr = (String) combo_month.getSelectedItem();
            int selectedMonth = monthCodeToNumber(monthStr);
            int selectedYear = (int) combo_year.getSelectedItem();
            int currentDay = (int) combo_day.getSelectedItem();
            updateDayComboBox(selectedYear, selectedMonth, currentDay);
        });

        combo_year.addActionListener(e -> {
            String monthStr = (String) combo_month.getSelectedItem();
            int selectedMonth = monthCodeToNumber(monthStr);
            int selectedYear = (int) combo_year.getSelectedItem();
            int currentDay = (int) combo_day.getSelectedItem();
            updateDayComboBox(selectedYear, selectedMonth, currentDay);
        });
    }

    private void updateDayComboBox(int year, int month, int currentDay) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month - 1);
        int maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        combo_day.removeAllItems();
        for (int d = 1; d <= maxDays; d++) {
            combo_day.addItem(d);
        }
        if (currentDay > maxDays) {
            combo_day.setSelectedItem(maxDays);
        } else {
            combo_day.setSelectedItem(currentDay);
        }
    }

    private void embedJpegMetadata(File file, String description, Location location, String date) throws Exception {
        TiffOutputSet outputSet = null;
        try {
            JpegImageMetadata jpegMetadata = (JpegImageMetadata) Imaging.getMetadata(file);
            if (jpegMetadata != null && jpegMetadata.getExif() != null) {
                outputSet = jpegMetadata.getExif().getOutputSet();
            }
        } catch (Exception e) {
            // Ignore
        }
        if (outputSet == null) {
            outputSet = new TiffOutputSet();
        }

        TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
        rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
        rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, description);

        TiffOutputDirectory exifSubIFD = outputSet.getOrCreateExifDirectory();
        exifSubIFD.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
        exifSubIFD.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, date);
        exifSubIFD.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED);
        exifSubIFD.add(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, date);

        if (location != null) {
            outputSet.setGPSInDegrees(Double.parseDouble(location.lon), Double.parseDouble(location.lat));
        }

        File tempFile = new File(file.getParent(), "temp_" + file.getName());
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            new ExifRewriter().updateExifMetadataLossless(file, os, outputSet);
        }
        if (!file.delete() || !tempFile.renameTo(file)) {
            throw new IOException("Failed to replace original file with tagged file.");
        }

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
        DateTimeFormatter isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        String isoDate = ldt.format(isoFormatter);

        command.add("-metadata");
        command.add("creation_time=" + isoDate);
        if (location != null) {
            String locationString = String.format("%+f%+f/", Double.parseDouble(location.lat), Double.parseDouble(location.lon));
            command.add("-metadata");
            command.add("location=" + locationString);
        }
        command.add(tempFilePath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("ffmpeg: " + line);
            }
        }

        if (process.waitFor() != 0) throw new IOException("FFmpeg process failed.");
        if (!file.delete() || !tempFile.renameTo(file)) {
            throw new IOException("Failed to replace original file with tagged file.");
        }

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