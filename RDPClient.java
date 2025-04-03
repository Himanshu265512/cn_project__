import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import javax.imageio.ImageIO;

public class RDPClient extends JFrame {
    private JLabel screenLabel;
    private String serverIP;
    private Socket socket;
    private DataOutputStream outputStream;

    public RDPClient(String serverIP) {
        this.serverIP = serverIP;
        
        // Setup UI
        setTitle("Remote Desktop Client - " + serverIP);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        screenLabel = new JLabel();
        add(screenLabel);
        
        // Set window size
        setSize(800, 600);
        setLocationRelativeTo(null);
        setVisible(true);
        
        // Connect to server
        connectToServer();
        
        // Start screen receiver thread
        new Thread(this::receiveScreen).start();
        
        // Setup mouse listeners
        setupMouseControl();
    }

    private void connectToServer() {
        try {
            socket = new Socket(serverIP, 1234);
            outputStream = new DataOutputStream(socket.getOutputStream());
            System.out.println("Connected to server at " + serverIP);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Connection failed: " + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private void receiveScreen() {
        try {
            InputStream inputStream = socket.getInputStream();
            DataInputStream dis = new DataInputStream(inputStream);
            
            while (true) {
                // Read image size
                int length = dis.readInt();
                if (length <= 0) continue;
                
                // Read image data
                byte[] imageData = new byte[length];
                dis.readFully(imageData);
                
                // Convert to image and display
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
                SwingUtilities.invokeLater(() -> {
                    screenLabel.setIcon(new ImageIcon(image));
                    pack();
                });
            }
        } catch (Exception e) {
            System.err.println("Screen receive error: " + e.getMessage());
        }
    }

    private void setupMouseControl() {
        screenLabel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                sendMouseEvent(e.getX(), e.getY(), getButtonMask(e.getButton()), 1);
            }
            
            public void mouseReleased(MouseEvent e) {
                sendMouseEvent(e.getX(), e.getY(), getButtonMask(e.getButton()), 2);
            }
        });
        
        screenLabel.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                sendMouseEvent(e.getX(), e.getY(), 0, 0);
            }
            
            public void mouseDragged(MouseEvent e) {
                sendMouseEvent(e.getX(), e.getY(), getButtonMask(e.getButton()), 0);
            }
        });
    }

    private int getButtonMask(int button) {
        switch (button) {
            case MouseEvent.BUTTON1: return InputEvent.BUTTON1_DOWN_MASK;
            case MouseEvent.BUTTON2: return InputEvent.BUTTON2_DOWN_MASK;
            case MouseEvent.BUTTON3: return InputEvent.BUTTON3_DOWN_MASK;
            default: return 0;
        }
    }

    private void sendMouseEvent(int x, int y, int buttonMask, int eventType) {
        try {
            outputStream.writeInt(x);
            outputStream.writeInt(y);
            outputStream.writeInt(buttonMask);
            outputStream.writeInt(eventType);
            outputStream.flush();
        } catch (Exception e) {
            System.err.println("Error sending mouse event: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String serverIP = JOptionPane.showInputDialog("Enter Server IP Address:");
            if (serverIP != null && !serverIP.trim().isEmpty()) {
                new RDPClient(serverIP);
            } else {
                System.exit(0);
            }
        });
    }
}