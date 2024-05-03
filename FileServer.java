import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.security.NoSuchAlgorithmException;

public class FileServer {
    private static final boolean VERBOSE = true;
    private static final boolean EXTRA_VERBOSE = true;
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT_TCP = 9091;
    private static final int SERVER_PORT_UDP = 9999;
    private static final String SERVER_DIRECTORY = "remote_folder";

    private static final int BUFFER_SIZE = 1024;
    private static final int BLOCK_SIZE = 4 * 1024 * 1024;

    private static final String MESSAGE_SEPARATOR = "-x-xx-x-";

    public static void main(String[] args) throws IOException {

        Files.createDirectories(Paths.get(System.getProperty("user.dir") + File.separator + SERVER_DIRECTORY));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> startUdpServer(SERVER_IP, SERVER_PORT_UDP, BUFFER_SIZE,
                System.getProperty("user.dir") + File.separator + SERVER_DIRECTORY, true));
        executor.submit(() -> startTcpServer(SERVER_IP, SERVER_PORT_TCP, BUFFER_SIZE, VERBOSE));
    }

    private static void startUdpServer(String serverIp, int serverPort, int bufferSize, String outputDir,
            boolean verbose) {
        try (DatagramSocket udpSocket = new DatagramSocket(serverPort)) {
            if (verbose) {
                System.out.println(String.format("Server listening on %s:%d", serverIp, serverPort));
            }

            while (true) {
                byte[] buffer = new byte[bufferSize];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                String data = new String(packet.getData(), StandardCharsets.UTF_8).trim();

                String[] parts = data.split(MESSAGE_SEPARATOR);

                List<String> nonEmptyParts = new ArrayList<>();

                // Iterate through the parts array
                for (int i = 0; i < parts.length; i++) {
                    // Check if the length of the part is greater than 0
                    if (parts[i].length() > 0) {
                        // If it is, add it to the list of non-empty parts
                        nonEmptyParts.add(parts[i]);
                    }

                }

                // Convert the list of non-empty parts back to an array
                parts = nonEmptyParts.toArray(new String[0]);

                if (parts.length >= 6) {
                    String file = parts[0];
                    String partName = parts[1];
                    String fileHash = parts[2];
                    int partNum = Integer.parseInt(parts[3]);
                    int totalParts = Integer.parseInt(parts[4]);
                    int size = Integer.parseInt(parts[5]);

                    List<byte[]> chunks = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        packet = new DatagramPacket(buffer, buffer.length);
                        udpSocket.receive(packet);
                        chunks.add(Arrays.copyOfRange(packet.getData(), 0, packet.getLength()));
                    }

                    if (verbose) {
                        System.out.println(String.format("File '%s %d/%d' received and saved as '%s'", file,
                                partNum + 1, totalParts, partName));
                    }

                    File fileToWrite = new File(outputDir, partName);
                    try (FileOutputStream fos = new FileOutputStream(fileToWrite)) {
                        for (byte[] chunk : chunks) {
                            fos.write(chunk);
                        }
                    }

                    if (partNum + 1 == totalParts) {
                        File hashFile = new File(outputDir, "." + file);
                        try (PrintWriter pw = new PrintWriter(hashFile)) {
                            pw.println(fileHash);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error starting UDP server: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        } catch (IOException e) {
            System.err.println("Error receiving UDP packet: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        } catch (NumberFormatException e) {
            System.err.println("Error parsing integer: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        }
    }

    private static String calculateFileHash(String filePath) throws NoSuchAlgorithmException {
        try (InputStream input = new FileInputStream(filePath)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
            byte[] digest = md.digest();
            return bytesToHex(digest);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            System.err.println("Error converting bytes to hexadecimal: " + e.getMessage());
            return ""; // Return empty string if an error occurs
        }
    }

    private static void startTcpServer(String serverIp, int serverPort, int bufferSize, boolean verbose) {
        try (ServerSocket tcpSocket = new ServerSocket(serverPort)) {
            if (verbose) {
                System.out.println(
                        String.format("TCP Server started, waiting for connections on %s:%d", serverIp, serverPort));
            }

            while (true) {
                Socket clientSocket = tcpSocket.accept();
                try {
                    handleTcpCommunication(clientSocket, bufferSize, verbose);
                } catch (Exception e) {
                    System.err.println("Error handling TCP communication: " + e.getMessage());
                    e.printStackTrace(); // Print stack trace for debugging
                }
            }
        } catch (IOException e) {
            System.err.println("Error starting TCP server: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error in TCP server: " + e.getMessage());
        }
    }

    private static void handleTcpCommunication(Socket clientSocket, int bufferSize, boolean verbose) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String message;
            while ((message = reader.readLine()) != null) {
                System.out.println(" 175 --------------------------------------------");
                if (verbose) {
                    System.out.println(String.format("Received command from %s:%d",
                            clientSocket.getInetAddress().getHostAddress(), clientSocket.getPort()));
                    System.out.println(message);
                }

                List<String[]> messageCollector = new ArrayList<>();
                for (String line : message.split("\n")) {
                    String[] parts = line.split(MESSAGE_SEPARATOR);
                    messageCollector.add(parts);
                }

                List<String[]> serverFiles = new ArrayList<>();
                List<String[]> newOrChangedFiles = new ArrayList<>();
                List<String[]> tndFiles = new ArrayList<>();

                for (String[] fileInfo : messageCollector) {
                    if (fileInfo.length < 3) {
                        System.err.println("Invalid message format: " + Arrays.toString(fileInfo));
                        continue; // Skip this message and proceed with the next one
                    }

                    String file = fileInfo[1];
                    String fileHash = fileInfo[2];
                    File fileToCheck = new File(System.getProperty("user.dir") + File.separator + SERVER_DIRECTORY,
                            file);
                    try {
                        if (fileToCheck.exists()) {
                            String existingHash = calculateFileHash(fileToCheck.getAbsolutePath());
                            if (existingHash.equals(fileHash)) {
                                continue;
                            }
                        }
                    } catch (NoSuchAlgorithmException e) {
                        System.err.println("Error calculating file hash: " + e.getMessage());
                        continue; // Skip this file and proceed with the next one
                    }
                    serverFiles.add(fileInfo);
                    newOrChangedFiles.add(fileInfo);
                }

                for (File file : new File(System.getProperty("user.dir") + File.separator + SERVER_DIRECTORY)
                        .listFiles()) {
                    if (!file.getName().startsWith(".")) {
                        boolean found = false;
                        for (String[] fileInfo : messageCollector) {
                            if (fileInfo.length < 2) {
                                System.err.println("Invalid message format: " + Arrays.toString(fileInfo));
                                continue; // Skip this message and proceed with the next one
                            }
                            if (file.getName().equals(fileInfo[1])) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            try {
                                tndFiles.add(new String[] { null, file.getName(),
                                        calculateFileHash(file.getAbsolutePath()), null });
                            } catch (NoSuchAlgorithmException e) {
                                System.err.println("Error calculating file hash: " + e.getMessage());
                                continue; // Skip this file and proceed with the next one
                            }
                        }
                    }
                }

                if (verbose) {
                    System.out.println(newOrChangedFiles);
                }

                StringBuilder response = new StringBuilder();
                for (String[] fileInfo : newOrChangedFiles) {

                    for (String component : fileInfo) {
                        System.out.println(component);
                    }

                    response.append(String.format("%s-x-xx-x-%s-x-xx-x-%s-x-xx-x-%s\n", "None", fileInfo[1],
                            fileInfo[2], "None"));
                }
                if (newOrChangedFiles.isEmpty()) {
                    response.append("UPTODATE");
                }

                if (verbose) {
                    System.out.println(response.toString());
                }

                writer.println(response.toString() + "\r\n");
                writer.flush();
                Thread.sleep(800);

            }
        } catch (InterruptedException ie) {
            System.err.println("InterrupException error: " + ie.getMessage());
        } catch (IOException e) {
            System.err.println("Error handling TCP communication: " + e.getMessage());
        }

    }
}
