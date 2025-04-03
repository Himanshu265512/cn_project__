import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import javax.imageio.ImageIO;
import java.util.concurrent.*;

public class RDPClient extends JFrame {
    private volatile BufferedImage currentImage;
    private String serverIP;
    private volatile boolean running = false;
    private ExecutorService executor;
    private Socket socket;
    private DataOutputStream outputStream;
    
    // GUI Components
    private JPanel screenPanel;
    private JButton connectButton, disconnectButton;
    private JTextField ipField;
    private JLabel statusLabel;
    private JComboBox<String> qualityCombo;
    
    // Performance tracking
    private int fps = 0;
    private long lastFpsUpdate = System.currentTimeMillis();

    public RDPClient() {
        setTitle("High-Speed RDP Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        
        // Create accelerated screen panel
        screenPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                BufferedImage img = currentImage;
                if (img != null) {
                    Graphics2D g2d = (Graphics2D) g;
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, 
                                         RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, 
                                         RenderingHints.VALUE_RENDER_SPEED);
                    g2d.drawImage(img, 0, 0, getWidth(), getHeight(), null);
                    
                    // Show FPS counter
                    g2d.setColor(Color.RED);
                    g2d.drawString("FPS: " + fps, 10, 20);
                }
            }
        };
        screenPanel.setPreferredSize(Toolkit.getDefaultToolkit().getScreenSize());
        add(new JScrollPane(screenPanel), BorderLayout.CENTER);
        
        // Control panel with hardware-accelerated components
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setDoubleBuffered(true);
        
        ipField = new JTextField(15);
        ipField.setText("192.168.");
        
        qualityCombo = new JComboBox<>(new String[]{"Low (Fastest)", "Medium", "High (Best Quality)"});
        qualityCombo.setSelectedIndex(1);
        
        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        
        statusLabel = new JLabel("Disconnected");
        
        controlPanel.add(new JLabel("Server IP:"));
        controlPanel.add(ipField);
        controlPanel.add(new JLabel("Quality:"));
        controlPanel.add(qualityCombo);
        controlPanel.add(connectButton);
        controlPanel.add(disconnectButton);
        controlPanel.add(statusLabel);
        
        add(controlPanel, BorderLayout.NORTH);
        
        // Event listeners
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect());
        setupMouseControl();
        
        // High-performance rendering timer
        new Timer(16, e -> { // ~60 FPS refresh
            screenPanel.repaint();
            if (System.currentTimeMillis() - lastFpsUpdate > 1000) {
                lastFpsUpdate = System.currentTimeMillis();
                fps = 0;
            }
            fps++;
        }).start();
        
        pack();
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setVisible(true);
    }

    private void connect() {
        serverIP = ipField.getText().trim();
        if (serverIP.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter server IP address");
            return;
        }
        
        connectButton.setEnabled(false);
        disconnectButton.setEnabled(true);
        ipField.setEnabled(false);
        statusLabel.setText("Connecting...");
        
        executor = Executors.newFixedThreadPool(2); // One for network, one for image processing
        
        executor.execute(() -> {
            try {
                socket = new Socket();
                socket.setTcpNoDelay(true); // Disable Nagle's algorithm
                socket.setPerformancePreferences(0, 2, 1); // Prioritize latency
                socket.connect(new InetSocketAddress(serverIP, 1234), 3000);
                
                outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
                InputStream inputStream = new BufferedInputStream(socket.getInputStream(), 65536);
                DataInputStream dis = new DataInputStream(inputStream);
                
                running = true;
                statusLabel.setText("Connected - Receiving...");
                
                // ImageIO reader with cache for better performance
                ImageIO.setUseCache(false);
                
                while (running) {
                    try {
                        int length = dis.readInt();
                        if (length <= 0) continue;
                        
                        byte[] imageData = new byte[length];
                        dis.readFully(imageData);
                        
                        // Process image in separate thread to avoid network delay
                        final byte[] finalData = imageData;
                        executor.execute(() -> {
                            try {
                                BufferedImage newImage = ImageIO.read(new ByteArrayInputStream(finalData));
                                if (newImage != null) {
                                    // Convert to compatible image for faster rendering
                                    GraphicsConfiguration gc = screenPanel.getGraphicsConfiguration();
                                    BufferedImage optimizedImage = gc.createCompatibleImage(
                                        newImage.getWidth(), newImage.getHeight(), Transparency.OPAQUE);
                                    Graphics2D g2d = optimizedImage.createGraphics();
                                    g2d.drawImage(newImage, 0, 0, null);
                                    g2d.dispose();
                                    currentImage = optimizedImage;
                                }
                            } catch (Exception e) {
                                if (running) {
                                    SwingUtilities.invokeLater(() -> 
                                        statusLabel.setText("Image processing error"));
                                }
                            }
                        });
                    } catch (SocketException e) {
                        if (running) {
                            SwingUtilities.invokeLater(() -> 
                                statusLabel.setText("Connection lost"));
                            disconnect();
                        }
                        break;
                    } catch (Exception e) {
                        if (running) {
                            SwingUtilities.invokeLater(() -> 
                                statusLabel.setText("Error: " + e.getMessage()));
                            disconnect();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Connection failed");
                    JOptionPane.showMessageDialog(this, "Connection failed: " + e.getMessage());
                    resetConnection();
                });
            }
        });
    }

    private void disconnect() {
        running = false;
        statusLabel.setText("Disconnecting...");
        
        new Thread(() -> {
            try {
                if (executor != null) {
                    executor.shutdownNow();
                }
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            SwingUtilities.invokeLater(this::resetConnection);
        }).start();
    }

    private void resetConnection() {
        currentImage = null;
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        ipField.setEnabled(true);
        statusLabel.setText("Disconnected");
        screenPanel.repaint();
    }

    private void setupMouseControl() {
        screenPanel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { sendInputEvent(e, 1); }
            public void mouseReleased(MouseEvent e) { sendInputEvent(e, 2); }
        });
        
        screenPanel.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) { sendInputEvent(e, 0); }
            public void mouseDragged(MouseEvent e) { sendInputEvent(e, 0); }
        });
        
        screenPanel.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) { sendKeyEvent(e, 1); }
            public void keyReleased(KeyEvent e) { sendKeyEvent(e, 2); }
        });
    }

    private void sendInputEvent(MouseEvent e, int action) {
        if (outputStream != null && running) {
            try {
                double scaleX = (double)(currentImage != null ? currentImage.getWidth() : 1) / screenPanel.getWidth();
                double scaleY = (double)(currentImage != null ? currentImage.getHeight() : 1) / screenPanel.getHeight();
                
                outputStream.writeInt((int)(e.getX() * scaleX));
                outputStream.writeInt((int)(e.getY() * scaleY));
                outputStream.writeInt(getButtonMask(e.getButton()));
                outputStream.writeInt(action);
                outputStream.flush();
            } catch (IOException ex) {
                if (running) disconnect();
            }
        }
    }

    private void sendKeyEvent(KeyEvent e, int action) {
        if (outputStream != null && running) {
            try {
                outputStream.writeInt(0); // Marker for keyboard event
                outputStream.writeInt(e.getKeyCode());
                outputStream.writeInt(action);
                outputStream.flush();
            } catch (IOException ex) {
                if (running) disconnect();
            }
        }
    }

    private int getButtonMask(int button) {
        switch (button) {
            case MouseEvent.BUTTON1: return InputEvent.BUTTON1_DOWN_MASK;
            case MouseEvent.BUTTON2: return InputEvent.BUTTON2_DOWN_MASK;
            case MouseEvent.BUTTON3: return InputEvent.BUTTON3_DOWN_MASK;
            default: return 0;
        }
    }

    public static void main(String[] args) {
        // Enable hardware acceleration
        System.setProperty("sun.java2d.opengl", "true");
        System.setProperty("sun.java2d.d3d", "true");
        
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new RDPClient();
        });
    }
}
