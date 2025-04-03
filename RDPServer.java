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
    private static ExecutorService executor;
    private static ServerSocket serverSocket;
    private static float compressionQuality = 0.7f;
    private static volatile int scrollSensitivity = 5;

    public static void main(String[] args) {
        System.out.println("Starting Ultimate RDP Server...");
        
        // Enable hardware acceleration
        System.setProperty("sun.java2d.opengl", "true");
        System.setProperty("sun.java2d.d3d", "true");

        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            serverSocket = new ServerSocket(PORT);
            serverSocket.setPerformancePreferences(0, 2, 1);
            
            // Print network information
            printNetworkInfo();

            executor = Executors.newFixedThreadPool(4); // Network, capture, compression, input

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

                // Create dedicated mouse wheel listener
                MouseWheelListener wheelListener = e -> {
                    try {
                        DataOutputStream dos = new DataOutputStream(
                            new BufferedOutputStream(clientSocket.getOutputStream()));
                        dos.writeInt(2); // Scroll event type
                        dos.writeInt(e.getWheelRotation() * scrollSensitivity);
                        dos.flush();
                    } catch (IOException ex) {
                        if (running) shutdown();
                    }
                };
                
                Toolkit.getDefaultToolkit().addAWTEventListener(
                    event -> {
                        if (event instanceof MouseWheelEvent) {
                            wheelListener.mouseWheelMoved((MouseWheelEvent)event);
                        }
                    }, AWTEvent.MOUSE_WHEEL_EVENT_MASK);

                // Start services
                executor.execute(() -> handleClientInput(robot, clientSocket));
                executor.execute(() -> streamScreen(robot, screenRect, clientSocket));
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            shutdown();
        }
    }

    private static void streamScreen(Robot robot, Rectangle screenRect, Socket clientSocket) {
        try {
            OutputStream outputStream = new BufferedOutputStream(
                clientSocket.getOutputStream(), 65536);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
            BufferedImage prevImage = null;
            long lastFrameTime = System.currentTimeMillis();
            int fps = 0;

            while (running) {
                long captureStart = System.nanoTime();
                BufferedImage image = robot.createScreenCapture(screenRect);
                
                if (prevImage != null && imagesEqual(prevImage, image)) {
                    Thread.sleep(10);
                    continue;
                }
                prevImage = image;

                // Dynamic quality adjustment based on network latency
                param.setCompressionQuality(compressionQuality);
                
                baos.reset();
                writer.setOutput(new MemoryCacheImageOutputStream(baos));
                writer.write(null, new IIOImage(image, null, null), param);
                
                synchronized (outputStream) {
                    byte[] imageData = baos.toByteArray();
                    DataOutputStream dos = new DataOutputStream(outputStream);
                    dos.writeInt(imageData.length);
                    dos.write(imageData);
                    outputStream.flush();
                }

                // Performance monitoring
                fps++;
                if (System.currentTimeMillis() - lastFrameTime > 1000) {
                    System.out.printf("FPS: %d, Quality: %.0f%%, Scroll Sens: %d%n",
                        fps, compressionQuality * 100, scrollSensitivity);
                    fps = 0;
                    lastFrameTime = System.currentTimeMillis();
                }

                long processTime = (System.nanoTime() - captureStart) / 1_000_000;
                Thread.sleep(Math.max(0, 16 - processTime));
            }
            writer.dispose();
        } catch (Exception e) {
            if (running) shutdown();
        }
    }

    private static void handleClientInput(Robot robot, Socket clientSocket) {
        try {
            DataInputStream input = new DataInputStream(
                new BufferedInputStream(clientSocket.getInputStream()));
            
            while (running) {
                int inputType = input.readInt();
                
                switch (inputType) {
                    case 0: // Mouse event
                        handleMouseEvent(robot, input);
                        break;
                        
                    case 1: // Keyboard event
                        handleKeyboardEvent(robot, input);
                        break;
                        
                    case 2: // Scroll event
                        int scrollAmount = input.readInt();
                        robot.mouseWheel(scrollAmount);
                        break;
                        
                    case 3: // Settings change
                        compressionQuality = input.readFloat();
                        scrollSensitivity = input.readInt();
                        System.out.println("Client updated settings: Quality=" + 
                            compressionQuality + " ScrollSens=" + scrollSensitivity);
                        break;
                }
            }
        } catch (Exception e) {
            if (running) shutdown();
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
        // Fast comparison using hash codes
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            return false;
        }
        
        // Compare small random samples for performance
        for (int i = 0; i < 5; i++) {
            int x = (int)(Math.random() * img1.getWidth());
            int y = (int)(Math.random() * img1.getHeight());
            if (img1.getRGB(x, y) != img2.getRGB(x, y)) {
                return false;
            }
        }
        return true;
    }

    private static void printNetworkInfo() throws Exception {
        System.out.println("Available IP Addresses:");
        NetworkInterface.getNetworkInterfaces().asIterator()
            .forEachRemaining(ni -> ni.getInetAddresses().asIterator()
                .forEachRemaining(ia -> {
                    if (!ia.isLoopbackAddress() && ia instanceof Inet4Address) {
                        System.out.println("  " + ni.getDisplayName() + ": " + ia.getHostAddress());
                    }
                }));
    }

    private static void shutdown() {
        running = false;
        try {
            if (executor != null) executor.shutdownNow();
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            System.err.println("Shutdown error: " + e.getMessage());
        }
        System.out.println("Server stopped");
    }
}
