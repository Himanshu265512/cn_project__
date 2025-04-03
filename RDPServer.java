import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import javax.imageio.*;
import javax.imageio.stream.*;

public class RDPServer {
    private static final int PORT = 1234;
    private static volatile boolean running = false;
    private static ServerSocket serverSocket;
    private static float compressionQuality = 0.7f;
    private static int realFPS = 0;
    private static long lastStatTime = System.currentTimeMillis();

    public static void main(String[] args) {
        System.out.println("Starting Optimized RDP Server...");
        
        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            serverSocket = new ServerSocket(PORT);
            serverSocket.setPerformancePreferences(0, 2, 1); // Prioritize latency
            
            System.out.println("Server IP Addresses:");
            NetworkInterface.getNetworkInterfaces().asIterator()
                .forEachRemaining(ni -> ni.getInetAddresses().asIterator()
                    .forEachRemaining(ia -> {
                        if (!ia.isLoopbackAddress() && ia instanceof Inet4Address) {
                            System.out.println("  " + ni.getDisplayName() + ": " + ia.getHostAddress());
                        }
                    }));

            while (true) {
                Socket clientSocket = serverSocket.accept();
                if (running) {
                    clientSocket.close();
                    continue;
                }

                System.out.println("Client connected: " + clientSocket.getInetAddress());
                running = true;
                
                // Configure high-performance socket
                clientSocket.setTcpNoDelay(true);
                clientSocket.setSendBufferSize(65536);
                clientSocket.setReceiveBufferSize(65536);

                // Start services
                new Thread(() -> handleClientInput(robot, clientSocket)).start();
                new Thread(() -> streamScreen(robot, screenRect, clientSocket)).start();
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    private static void streamScreen(Robot robot, Rectangle screenRect, Socket clientSocket) {
        try (OutputStream outputStream = new BufferedOutputStream(clientSocket.getOutputStream(), 65536)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(compressionQuality);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
            BufferedImage prevImage = null;
            int framesSent = 0;

            while (running) {
                long startTime = System.nanoTime();
                
                // Capture screen
                BufferedImage image = robot.createScreenCapture(screenRect);
                
                // Only send if image changed (frame differencing)
                if (prevImage == null || !imagesEqual(prevImage, image)) {
                    baos.reset();
                    writer.setOutput(new MemoryCacheImageOutputStream(baos));
                    writer.write(null, new IIOImage(image, null, null), param);
                    
                    synchronized (outputStream) {
                        DataOutputStream dos = new DataOutputStream(outputStream);
                        dos.writeInt(baos.size());
                        dos.write(baos.toByteArray());
                        outputStream.flush();
                    }
                    framesSent++;
                    prevImage = image;
                }

                // Calculate real FPS every second
                if (System.currentTimeMillis() - lastStatTime > 1000) {
                    realFPS = framesSent;
                    System.out.println("Actual FPS: " + realFPS + " | Quality: " + 
                                      (int)(compressionQuality * 100) + "%");
                    framesSent = 0;
                    lastStatTime = System.currentTimeMillis();
                }

                // Dynamic delay to maintain ~30FPS max
                long processTime = (System.nanoTime() - startTime) / 1_000_000;
                long sleepTime = Math.max(10, 33 - processTime); // Target ~30FPS
                Thread.sleep(sleepTime);
            }
            writer.dispose();
        } catch (Exception e) {
            if (running) {
                System.err.println("Streaming error: " + e.getMessage());
                shutdown();
            }
        }
    }

    private static void handleClientInput(Robot robot, Socket clientSocket) {
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(clientSocket.getInputStream()))) {
            
            while (running) {
                int inputType = input.readInt();
                
                switch (inputType) {
                    case 0: // Mouse event
                        handleMouseEvent(robot, input);
                        break;
                        
                    case 1: // Keyboard event
                        handleKeyboardEvent(robot, input);
                        break;
                        
                    case 2: // Settings change
                        compressionQuality = input.readFloat();
                        System.out.println("Client changed quality to: " + 
                                         (int)(compressionQuality * 100) + "%");
                        break;
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("Input error: " + e.getMessage());
                shutdown();
            }
        }
    }

    private static void handleMouseEvent(Robot robot, DataInputStream input) throws IOException {
        int x = input.readInt();
        int y = input.readInt();
        int buttonMask = input.readInt();
        int action = input.readInt();
        
        robot.mouseMove(x, y);
        if (action == 1) robot.mousePress(buttonMask);
        else if (action == 2) robot.mouseRelease(buttonMask);
    }

    private static void handleKeyboardEvent(Robot robot, DataInputStream input) throws IOException {
        int keyCode = input.readInt();
        int action = input.readInt();
        
        if (action == 1) robot.keyPress(keyCode);
        else if (action == 2) robot.keyRelease(keyCode);
    }

    private static boolean imagesEqual(BufferedImage img1, BufferedImage img2) {
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            return false;
        }
        
        // Compare small random samples for performance
        for (int i = 0; i < 3; i++) {
            int x = (int)(Math.random() * img1.getWidth());
            int y = (int)(Math.random() * img1.getHeight());
            if (img1.getRGB(x, y) != img2.getRGB(x, y)) {
                return false;
            }
        }
        return true;
    }

    private static void shutdown() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            System.err.println("Shutdown error: " + e.getMessage());
        }
        System.out.println("Server stopped");
    }
}
