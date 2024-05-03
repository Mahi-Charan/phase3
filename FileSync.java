import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

public class FileSync {
    private static final boolean VERBOSE = true;
    private static final boolean EXTRA_VERBOSE = true;
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT_TCP = 9091;
    private static final int SERVER_PORT_UDP = 9999;
    private static final String CLIENT_DIRECTORY = "local_directory";

    private static final int BUFFER_SIZE = 1024;
    private static final int BLOCK_SIZE = 4 * 1024 * 1024;
    private static final int CHUNK_SIZE = 4 * 1024;

    private static final String MESSAGE_SEPARATOR = "-x-xx-x-";
    private static final String UDP_MESSAGE_SEPARATOR = MESSAGE_SEPARATOR + MESSAGE_SEPARATOR + MESSAGE_SEPARATOR
            + MESSAGE_SEPARATOR;

    public static void main(String[] args) throws IOException {

        String cwdir = System.getProperty("user.dir");
        System.out.println(cwdir + File.separator + CLIENT_DIRECTORY);

        File directory = new File(System.getProperty("user.dir") + File.separator + CLIENT_DIRECTORY);
        if (!directory.exists()) {
            directory.mkdir();
        }
        try {
            sync(SERVER_IP, SERVER_PORT_TCP, SERVER_PORT_UDP,
                    System.getProperty("user.dir") + File.separator + CLIENT_DIRECTORY, VERBOSE);
        } catch (IOException e) {
            e.printStackTrace(); // or handle the exception as per your application's requirements
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace(); // or handle the exception as per your application's requirements
        }
    }

    private static void sync(String serverIp, int serverPortTcp, int serverPortUdp, String inputDir, boolean verbose)
            throws IOException, NoSuchAlgorithmException {
        File[] files = new File(inputDir).listFiles();

        List<String> messageCollector = new ArrayList<>();
        for (File file : files) {
            String filePath = file.getAbsolutePath();
            String message = createMessage(file.getName(), "INIT", calculateFileHash(filePath), 0);
            messageCollector.add(message);
        }

        String msg = String.join("\n", messageCollector) + "\r\n";

        try (Socket tcpSocket = new Socket(serverIp, serverPortTcp)) {
            OutputStream output = tcpSocket.getOutputStream();
            output.write(msg.getBytes());
            output.flush();
            Thread.sleep(800);
            InputStream input = tcpSocket.getInputStream();
            byte[] responseBytes = new byte[BUFFER_SIZE];

            // Read at least one byte
            int bytesRead = input.read(responseBytes);

            String response = "";
            if (bytesRead > 0) {
                // Process the received bytes
                response = new String(responseBytes, 0, bytesRead);
                // Your processing logic here
                if (verbose) {
                    System.out.println("Received response from server: " + response);
                }
            } else {
                // Handle case where no bytes are received
                System.out.println("No response received from server.");
            }

            // byte[] responseBytes = input.readNBytes(BUFFER_SIZE);

            if (response.equals("UPTODATE")) {
                return;
            }

            List<String[]> messageCollectorList = parseMessages(response);
            if (verbose) {
                System.out.println("Message collector list:");
                for (String[] msgData : messageCollectorList) {
                    System.out.println(Arrays.toString(msgData));
                }
            }

            for (String[] msgData : messageCollectorList) {
                if (msgData.length < 4) { // 6
                    System.err.println("Invalid message format: " + Arrays.toString(msgData));
                    continue; // Skip this message and proceed with the next one
                }

                String file = msgData[1];
                String partName = msgData[1];
                String fileHash = msgData[2];
                int partNum = 0;
                int totalParts = 1;
                int size = 1;

                List<byte[]> fileBlocks = getFileBlocks(inputDir, file);

                String calculatedFileHash = calculateFileHash(inputDir + File.separator + file);

                int totalChunks = fileBlocks.size() / CHUNK_SIZE + (fileBlocks.size() % CHUNK_SIZE > 0 ? 1 : 0);

                if (verbose) {
                    System.out.println("Uploading file: " + file);
                }
                for (int idx = 0; idx < fileBlocks.size(); idx += CHUNK_SIZE) {
                    List<byte[]> chunks = fileBlocks.subList(idx, Math.min(idx + CHUNK_SIZE, fileBlocks.size()));
                    String udpMessage = createUdpMessage(file, partName, calculatedFileHash, partNum, totalChunks,
                            chunks);

                    udpSend(serverIp, serverPortUdp, udpMessage, chunks, verbose);
                    partNum++;
                }
            }
        } catch (InterruptedException ie) {
            System.err.println("InterrupException error: " + ie.getMessage());
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("Array index out of bounds error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static List<byte[]> getFileBlocks(String folder, String file) throws IOException {
        List<byte[]> blocks = new ArrayList<>();
        try (InputStream input = new FileInputStream(new File(folder, file))) {
            byte[] buffer = new byte[BLOCK_SIZE];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                blocks.add(Arrays.copyOfRange(buffer, 0, bytesRead));
            }
        }
        return blocks;
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
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static List<String[]> parseMessages(String response) {
        List<String[]> messageList = new ArrayList<>();
        String[] lines = response.split("\n");
        for (String line : lines) {
            String[] parts = line.split(Pattern.quote(MESSAGE_SEPARATOR));
            messageList.add(parts);
        }
        return messageList;
    }

    private static String createMessage(String file, String status, String fileHash, int partNum) {
        return String.format("%s%s%s%s%s", status, MESSAGE_SEPARATOR, file, MESSAGE_SEPARATOR, fileHash,
                MESSAGE_SEPARATOR, partNum);
    }

    private static String createUdpMessage(String file, String partName, String fileHash, int partNum, int totalParts,
            List<byte[]> chunks) throws UnsupportedEncodingException {
        StringBuilder sb = new StringBuilder();
        sb.append(file).append(UDP_MESSAGE_SEPARATOR)
                .append(partName).append(UDP_MESSAGE_SEPARATOR)
                .append(fileHash).append(UDP_MESSAGE_SEPARATOR)
                .append(partNum).append(UDP_MESSAGE_SEPARATOR)
                .append(totalParts).append(UDP_MESSAGE_SEPARATOR)
                .append(chunks.size()).append(UDP_MESSAGE_SEPARATOR);

        for (byte[] chunk : chunks) {
            sb.append(new String(chunk, "UTF-8")).append(UDP_MESSAGE_SEPARATOR);
        }

        return sb.toString();
    }

    private static void udpSend(String serverIp, int serverPortUdp, String message, List<byte[]> chunks,
            boolean verbose) throws IOException {
        InetAddress serverAddress = InetAddress.getByName(serverIp);
        DatagramSocket udpSocket = new DatagramSocket();

        byte[] fileBytes = message.getBytes();
        DatagramPacket packet = new DatagramPacket(fileBytes, fileBytes.length, serverAddress, serverPortUdp);
        udpSocket.send(packet);

        if (verbose) {
            System.out.println("    ... sending UDP message");
        }

        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunkBytes = chunks.get(i);
            packet = new DatagramPacket(chunkBytes, chunkBytes.length, serverAddress, serverPortUdp);
            udpSocket.send(packet);

            if (verbose) {
                System.out.println("    ... sending chunk " + (i + 1) + " of " + chunks.size());
            }
        }

        udpSocket.close();
    }
}
