package org.example;

import javax.swing.*;
import javax.swing.SwingWorker;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.util.List;

public class MainInterface {
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


    // FFmpeg command depending on OS
    private final String ffmpegCommand;

    private static final String[] VIDEO_PHOTO_EXTENSIONS = {".jpg", ".jpeg", ".mp4"};

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

        ai.djl.engine.Engine.debugEnvironment();


        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        ImageIcon icon_file = null;
        try { //setICON
            icon_file = new ImageIcon(classLoader.getResource("icon.png"));
            frame.setIconImage(icon_file.getImage());
        } catch (Exception e) {
            e.printStackTrace();
        }

        frame.setVisible(true);
        file_chooser = new JFileChooser();
        selectedFiles = new ArrayList<>();
        tags = new ArrayList<>();
        people = new ArrayList<>();

        setupGUI();
        // Determine ffmpeg command based on OS
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            ffmpegCommand = "ffmpeg";
        } else {
            ffmpegCommand = "/opt/homebrew/bin/ffmpeg";
        }
        checkFFmpegInstallation();

    }

    private void setupGUI(){

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

// Recursive method to add files from a directory
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

    // Method to check if a file is a video or photo
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

        // Define month codes array
        String[] monthCodes = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

        // Populate month combo box with 3-letter month codes
        combo_month.removeAllItems();
        for (String monthCode : monthCodes) {
            combo_month.addItem(monthCode);
        }
        combo_month.setSelectedItem(monthCodes[todayMonth - 1]);

        // Populate year combo box (range: current year - 10 to current year + 10)
        combo_year.removeAllItems();
        for (int y = todayYear - 10; y <= todayYear + 10; y++) {
            combo_year.addItem(y);
        }
        combo_year.setSelectedItem(todayYear);

        // Populate day combo box based on current month and year
        updateDayComboBox(todayYear, todayMonth, todayDay);

        // Add listeners to update day combo box when month or year changes
        combo_month.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String monthStr = (String) combo_month.getSelectedItem();
                int selectedMonth = monthCodeToNumber(monthStr);
                int selectedYear = (int) combo_year.getSelectedItem();
                int currentDay = (int) combo_day.getSelectedItem();
                updateDayComboBox(selectedYear, selectedMonth, currentDay);
            }
        });

        combo_year.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String monthStr = (String) combo_month.getSelectedItem();
                int selectedMonth = monthCodeToNumber(monthStr);
                int selectedYear = (int) combo_year.getSelectedItem();
                int currentDay = (int) combo_day.getSelectedItem();
                updateDayComboBox(selectedYear, selectedMonth, currentDay);
            }
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
            // If reading metadata fails, create a new output set
        }
        if (outputSet == null) {
            outputSet = new TiffOutputSet();
        }

        // Add description to the root directory
        TiffOutputDirectory rootDirectory = outputSet.getOrCreateRootDirectory();
        rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION);
        rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, description);

        // Inject the date into the EXIF subdirectory
        TiffOutputDirectory exifSubIFD = outputSet.getOrCreateExifDirectory();
        exifSubIFD.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
        exifSubIFD.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, date);
        exifSubIFD.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED);
        exifSubIFD.add(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, date);

        if (location != null) {
            double lat = Double.parseDouble(location.lat);
            double lon = Double.parseDouble(location.lon);

            int latDegrees = (int) Math.floor(Math.abs(lat));
            int latMinutes = (int) Math.floor((Math.abs(lat) - latDegrees) * 60);
            double latSeconds = (Math.abs(lat) - latDegrees - latMinutes / 60.0) * 3600;

            int lonDegrees = (int) Math.floor(Math.abs(lon));
            int lonMinutes = (int) Math.floor((Math.abs(lon) - lonDegrees) * 60);
            double lonSeconds = (Math.abs(lon) - lonDegrees - lonMinutes / 60.0) * 3600;

            RationalNumber latDeg = RationalNumber.valueOf(latDegrees);
            RationalNumber latMin = RationalNumber.valueOf(latMinutes);
            RationalNumber latSec = RationalNumber.valueOf(latSeconds);

            RationalNumber lonDeg = RationalNumber.valueOf(lonDegrees);
            RationalNumber lonMin = RationalNumber.valueOf(lonMinutes);
            RationalNumber lonSec = RationalNumber.valueOf(lonSeconds);

            TiffOutputDirectory gpsDirectory = outputSet.getOrCreateGpsDirectory();
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF);
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LATITUDE);
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF);
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_LONGITUDE);

            String latRef = (lat >= 0) ? "N" : "S";
            String lonRef = (lon >= 0) ? "E" : "W";
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF, latRef);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE, new RationalNumber[]{latDeg, latMin, latSec});
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF, lonRef);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE, new RationalNumber[]{lonDeg, lonMin, lonSec});
        }

        File tempFile = new File(file.getParent(), "temp_" + file.getName());
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            new ExifRewriter().updateExifMetadataLossless(file, os, outputSet);
        }
        if (!file.delete()) {
            throw new Exception("Failed to delete original file: " + file.getAbsolutePath());
        }
        if (!tempFile.renameTo(file)) {
            throw new Exception("Failed to rename temporary file to original file: " + file.getAbsolutePath());
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
        LocalDateTime ldt = LocalDateTime.parse(date, formatter);
        FileTime fileTime = FileTime.from(ldt.toInstant(ZoneOffset.UTC));
        Files.setAttribute(file.toPath(), "basic:creationTime", fileTime);
    }

    private void embedVideoMetadata(File file, String description, Location location, String date) throws Exception {
        String inputFilePath = file.getAbsolutePath();
        File tempFile = new File(file.getParent(), "temp_" + file.getName());
        String tempFilePath = tempFile.getAbsolutePath();

        // Build ffmpeg command to add metadata without transcoding
        ArrayList<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-i");
        command.add(inputFilePath);
        command.add("-c");
        command.add("copy");
        command.add("-metadata");
        command.add("description=" + description);
        command.add("-metadata");
        command.add("date=" + date);

        // Convert the date from EXIF format (yyyy:MM:dd HH:mm:ss) to ISO 8601 format for MP4 creation_time
        DateTimeFormatter exifFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
        LocalDateTime ldt = LocalDateTime.parse(date, exifFormatter);
        DateTimeFormatter isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        String isoDate = ldt.format(isoFormatter);

        command.add("-metadata");
        command.add("creation_time=" + isoDate);
        if (location != null) {
            double latVal = Double.parseDouble(location.lat);
            double lonVal = Double.parseDouble(location.lon);
            String locationString = String.format("%+f%+f/", latVal, lonVal);
            command.add("-metadata");
            command.add("location=" + locationString);
        }
        command.add(tempFilePath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new Exception("FFmpeg process failed with exit code " + exitCode);
        }
        // Replace original file with the new file containing metadata
        if (!file.delete()) {
            throw new Exception("Failed to delete original file: " + file.getAbsolutePath());
        }
        if (!tempFile.renameTo(file)) {
            throw new Exception("Failed to rename temporary file to original file: " + file.getAbsolutePath());
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
        ldt = LocalDateTime.parse(date, formatter);
        FileTime fileTime = FileTime.from(ldt.toInstant(ZoneOffset.UTC));
        Files.setAttribute(file.toPath(), "basic:creationTime", fileTime);
    }



    private int monthCodeToNumber(String monthCode) {
        switch (monthCode) {
            case "Jan": return 1;
            case "Feb": return 2;
            case "Mar": return 3;
            case "Apr": return 4;
            case "May": return 5;
            case "Jun": return 6;
            case "Jul": return 7;
            case "Aug": return 8;
            case "Sep": return 9;
            case "Oct": return 10;
            case "Nov": return 11;
            case "Dec": return 12;
            default: return 1;
        }
    }

    private void checkFFmpegInstallation() {
        if (!isFFmpegInstalled()) {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) {
                JOptionPane.showMessageDialog(frame,
                    "FFmpeg is not installed. Please download and install FFmpeg for Windows from https://ffmpeg.org/download.html and ensure it's added to your PATH.",
                    "FFmpeg Not Found", JOptionPane.ERROR_MESSAGE);
                System.exit(0);
            } else {
                int response = JOptionPane.showConfirmDialog(
                    frame,
                    "The video tagger is not installed. Would you like to install it?",
                    "FFmpeg Not Found",
                    JOptionPane.YES_NO_OPTION
                );
                if (response == JOptionPane.YES_OPTION) {
                    try {
                        Process process = new ProcessBuilder("bash", "/Applications/MediaTagger/ffmpeginstall.sh").start();
                        int exitCode = process.waitFor();
                        if (exitCode != 0) {
                            JOptionPane.showMessageDialog(frame,
                                "Video tagger install failed with exit code " + exitCode + "\n Media Tagger will now exit");
                            System.exit(0);
                        } else {
                            JOptionPane.showMessageDialog(frame, "Video tagger installation completed successfully.");
                        }
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(frame,
                            "An error occurred while running the installation script: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                } else {
                    System.exit(0);
                }
            }
        }
    }

    private boolean isFFmpegInstalled() {
        try {
            Process process = new ProcessBuilder(ffmpegCommand, "-version").start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
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