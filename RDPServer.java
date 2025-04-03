import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import javax.imageio.ImageIO;

public class RDPServer {
    private static final int PORT = 1234;
    private static Robot robot;
    private static Rectangle screenSize;

    public static void main(String[] args) {
        try {
            // Initialize server
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("RDP Server started on port " + PORT);
            
            // Initialize screen capture
            robot = new Robot();
            screenSize = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            
            // Accept client connection
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected: " + clientSocket.getInetAddress());
            
            // Get streams
            OutputStream outputStream = clientSocket.getOutputStream();
            DataInputStream inputStream = new DataInputStream(clientSocket.getInputStream());
            
            // Start screen sharing thread
            new Thread(() -> handleScreenSharing(outputStream)).start();
            
            // Start input handling thread
            new Thread(() -> handleClientInput(inputStream)).start();
            
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleScreenSharing(OutputStream outputStream) {
        try {
            while (true) {
                // Capture screen
                BufferedImage image = robot.createScreenCapture(screenSize);
                
                // Compress to JPEG
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "jpg", baos);
                byte[] imageData = baos.toByteArray();
                
                // Send image size and data
                DataOutputStream dos = new DataOutputStream(outputStream);
                dos.writeInt(imageData.length);
                dos.write(imageData);
                dos.flush();
                
                // Control frame rate (adjust as needed)
                Thread.sleep(100);
            }
        } catch (Exception e) {
            System.err.println("Screen sharing error: " + e.getMessage());
        }
    }

    private static void handleClientInput(DataInputStream inputStream) {
        try {
            while (true) {
                // Read mouse coordinates and button state
                int x = inputStream.readInt();
                int y = inputStream.readInt();
                int buttonMask = inputStream.readInt();
                int eventType = inputStream.readInt();
                
                // Move mouse
                robot.mouseMove(x, y);
                
                // Handle mouse buttons
                if (eventType == 1) { // Press
                    robot.mousePress(buttonMask);
                } else if (eventType == 2) { // Release
                    robot.mouseRelease(buttonMask);
                }
            }
        } catch (Exception e) {
            System.err.println("Input handling error: " + e.getMessage());
        }
    }
}