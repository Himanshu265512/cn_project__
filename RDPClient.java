import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.*;
import javax.imageio.*;

public class RDPClient extends JFrame {
    private BufferedImage currentImage;
    private String serverIP;
    private volatile boolean running = false;
    private Socket socket;
    private DataOutputStream outputStream;
    
    // GUI Components
    private JPanel screenPanel;
    private JButton connectButton, disconnectButton;
    private JTextField ipField;
    private JSlider qualitySlider, scrollSlider;
    private JLabel statusLabel;
    
    // Performance stats
    private AtomicInteger fps = new AtomicInteger(0);
    private long lastFpsTime = System.currentTimeMillis();

    public RDPClient() {
        setTitle("Ultimate RDP Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        
        // Screen panel with hardware acceleration
        screenPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                BufferedImage img = currentImage;
                if (img != null) {
                    Graphics2D g2d = (Graphics2D)g;
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, 
                                       RenderingHints.VALUE_RENDER_SPEED);
                    g2d.drawImage(img, 0, 0, getWidth(), getHeight(), this);
                    
                    // Display stats
                    g2d.setColor(Color.RED);
                    g2d.drawString(String.format("FPS: %d Quality: %d%% Scroll: %d", 
                        fps.get(), qualitySlider.getValue(), scrollSlider.getValue()), 10, 20);
                }
            }
        };
        screenPanel.setPreferredSize(Toolkit.getDefaultToolkit().getScreenSize());
        add(new JScrollPane(screenPanel), BorderLayout.CENTER);
        
        // Control panel
        JPanel controlPanel = new JPanel(new GridLayout(2, 1));
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        // Connection controls
        ipField = new JTextField(15);
        ipField.setText("192.168.");
        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        statusLabel = new JLabel("Disconnected");
        
        // Quality and scroll controls
        qualitySlider = new JSlider(30, 90, 70);
        qualitySlider.setMajorTickSpacing(20);
        qualitySlider.setPaintTicks(true);
        qualitySlider.setPaintLabels(true);
        
        scrollSlider = new JSlider(1, 10, 5);
        scrollSlider.setMajorTickSpacing(3);
        scrollSlider.setPaintTicks(true);
        scrollSlider.setPaintLabels(true);
        
        // Add components
        topPanel.add(new JLabel("Server IP:"));
        topPanel.add(ipField);
        topPanel.add(connectButton);
        topPanel.add(disconnectButton);
        topPanel.add(statusLabel);
        
        bottomPanel.add(new JLabel("Quality:"));
        bottomPanel.add(qualitySlider);
        bottomPanel.add(new JLabel("Scroll Sens:"));
        bottomPanel.add(scrollSlider);
        
        controlPanel.add(topPanel);
        controlPanel.add(bottomPanel);
        add(controlPanel, BorderLayout.NORTH);
        
        // Event listeners
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect());
        qualitySlider.addChangeListener(e -> updateSettings());
        scrollSlider.addChangeListener(e -> updateSettings());
        
        // Input handling
        setupInputHandling();
        
        // Start rendering timer
        new Timer(16, e -> {
            screenPanel.repaint();
            if (System.currentTimeMillis() - lastFpsTime > 1000) {
                fps.set(0);
                lastFpsTime = System.currentTimeMillis();
            }
            fps.incrementAndGet();
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
        
        new Thread(() -> {
            try {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setPerformancePreferences(0, 2, 1);
                socket.connect(new InetSocketAddress(serverIP, 1234), 3000);
                
                outputStream = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), 65536));
                DataInputStream input = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), 65536));
                
                running = true;
                statusLabel.setText("Connected");
                
                // Send initial settings
                updateSettings();
                
                // Start screen receiver
                while (running) {
                    try {
                        int length = input.readInt();
                        if (length <= 0) continue;
                        
                        byte[] imageData = new byte[length];
                        input.readFully(imageData);
                        
                        BufferedImage newImage = ImageIO.read(new ByteArrayInputStream(imageData));
                        if (newImage != null) {
                            currentImage = newImage;
                        }
                    } catch (SocketException e) {
                        if (running) disconnect();
                    } catch (Exception e) {
                        if (running) {
                            SwingUtilities.invokeLater(() -> 
                                statusLabel.setText("Error: " + e.getMessage()));
                            disconnect();
                        }
                    }
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Connection failed");
                    JOptionPane.showMessageDialog(this, "Connection failed: " + e.getMessage());
                    resetConnection();
                });
            }
        }).start();
    }

    private void disconnect() {
        running = false;
        statusLabel.setText("Disconnecting...");
        
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(this::resetConnection);
    }

    private void resetConnection() {
        currentImage = null;
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        ipField.setEnabled(true);
        statusLabel.setText("Disconnected");
        screenPanel.repaint();
    }

    private void setupInputHandling() {
        // Mouse events
        screenPanel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { sendMouseEvent(e, 1); }
            public void mouseReleased(MouseEvent e) { sendMouseEvent(e, 2); }
        });
        
        screenPanel.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) { sendMouseEvent(e, 0); }
            public void mouseDragged(MouseEvent e) { sendMouseEvent(e, 0); }
        });
        
        // Mouse wheel events
        screenPanel.addMouseWheelListener(e -> {
            if (outputStream != null && running) {
                try {
                    outputStream.writeInt(2); // Scroll event
                    outputStream.writeInt(e.getWheelRotation() * scrollSlider.getValue());
                    outputStream.flush();
                } catch (IOException ex) {
                    if (running) disconnect();
                }
            }
        });
        
        // Keyboard events
        screenPanel.setFocusable(true);
        screenPanel.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) { sendKeyEvent(e, 1); }
            public void keyReleased(KeyEvent e) { sendKeyEvent(e, 2); }
        });
    }

    private void sendMouseEvent(MouseEvent e, int action) {
        if (outputStream != null && running) {
            try {
                double scaleX = currentImage != null ? 
                    (double)currentImage.getWidth() / screenPanel.getWidth() : 1;
                double scaleY = currentImage != null ? 
                    (double)currentImage.getHeight() / screenPanel.getHeight() : 1;
                
                outputStream.writeInt(0); // Mouse event
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
                outputStream.writeInt(1); // Keyboard event
                outputStream.writeInt(e.getKeyCode());
                outputStream.writeInt(action);
                outputStream.flush();
            } catch (IOException ex) {
                if (running) disconnect();
            }
        }
    }

    private void updateSettings() {
        if (outputStream != null && running) {
            try {
                outputStream.writeInt(3); // Settings event
                outputStream.writeFloat(qualitySlider.getValue() / 100f);
                outputStream.writeInt(scrollSlider.getValue());
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
        
        SwingUtilities.invokeLater(() -> new RDPClient());
    }
}
