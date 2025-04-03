import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import javax.imageio.*;

public class RDPClient extends JFrame {
    private volatile BufferedImage currentImage;
    private volatile boolean running = false;
    private Socket socket;
    private DataOutputStream outputStream;
    private JPanel screenPanel;
    private JLabel statusLabel;
    private int realFPS = 0;
    private long lastStatTime = System.currentTimeMillis();
    private int framesReceived = 0;

    public RDPClient() {
        setTitle("RDP Client - Optimized");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        
        // Screen panel with proper focus handling
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
                    
                    // Display real FPS and connection status
                    g2d.setColor(Color.RED);
                    g2d.drawString("FPS: " + realFPS + " | " + statusLabel.getText(), 10, 20);
                }
            }
        };
        screenPanel.setPreferredSize(new Dimension(800, 600));
        screenPanel.setFocusable(true);
        screenPanel.setRequestFocusEnabled(true);
        add(new JScrollPane(screenPanel), BorderLayout.CENTER);
        
        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton connectBtn = new JButton("Connect");
        JButton disconnectBtn = new JButton("Disconnect");
        JTextField ipField = new JTextField(15);
        ipField.setText("192.168.");
        statusLabel = new JLabel("Disconnected");
        
        controlPanel.add(new JLabel("Server IP:"));
        controlPanel.add(ipField);
        controlPanel.add(connectBtn);
        controlPanel.add(disconnectBtn);
        controlPanel.add(statusLabel);
        add(controlPanel, BorderLayout.NORTH);
        
        // Event listeners
        connectBtn.addActionListener(e -> connect(ipField.getText().trim()));
        disconnectBtn.addActionListener(e -> disconnect());
        
        // Input handling with proper focus management
        setupInputHandling();
        
        // Repaint timer (30 FPS)
        new Timer(33, e -> screenPanel.repaint()).start();
        
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void connect(String ip) {
        if (ip.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter server IP address");
            return;
        }
        
        new Thread(() -> {
            try {
                statusLabel.setText("Connecting...");
                socket = new Socket();
                socket.setTcpNoDelay(true); // Disable Nagle's algorithm
                socket.connect(new InetSocketAddress(ip, 1234), 3000);
                
                outputStream = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), 65536));
                DataInputStream input = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), 65536));
                
                running = true;
                framesReceived = 0;
                lastStatTime = System.currentTimeMillis();
                statusLabel.setText("Connected to " + ip);
                
                // Main receive loop
                while (running) {
                    int length = input.readInt();
                    byte[] imageData = new byte[length];
                    input.readFully(imageData);
                    
                    BufferedImage newImage = ImageIO.read(new ByteArrayInputStream(imageData));
                    if (newImage != null) {
                        currentImage = newImage;
                        framesReceived++;
                    }
                    
                    // Update real FPS every second
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastStatTime > 1000) {
                        realFPS = framesReceived;
                        framesReceived = 0;
                        lastStatTime = currentTime;
                    }
                }
            } catch (Exception e) {
                statusLabel.setText("Disconnected: " + e.getMessage());
                disconnect();
            }
        }).start();
    }

    private void disconnect() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        statusLabel.setText("Disconnected");
        currentImage = null;
    }

    private void setupInputHandling() {
        // Mouse click focuses the panel
        screenPanel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                screenPanel.requestFocusInWindow();
                sendMouseEvent(e, 1);
            }
            public void mouseReleased(MouseEvent e) {
                sendMouseEvent(e, 2);
            }
        });
        
        // Mouse movement
        screenPanel.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                sendMouseEvent(e, 0);
            }
            public void mouseDragged(MouseEvent e) {
                sendMouseEvent(e, 0);
            }
        });
        
        // Keyboard - fixed implementation
        screenPanel.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_UNDEFINED) {
                    sendKeyEvent(e.getKeyCode(), 1);
                }
            }
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_UNDEFINED) {
                    sendKeyEvent(e.getKeyCode(), 2);
                }
            }
        });
    }

    private void sendMouseEvent(MouseEvent e, int action) {
        if (outputStream != null && running) {
            try {
                BufferedImage img = currentImage;
                if (img == null) return;
                
                double scaleX = (double)img.getWidth()/screenPanel.getWidth();
                double scaleY = (double)img.getHeight()/screenPanel.getHeight();
                
                synchronized(outputStream) {
                    outputStream.writeInt(0); // Mouse event
                    outputStream.writeInt((int)(e.getX() * scaleX));
                    outputStream.writeInt((int)(e.getY() * scaleY));
                    outputStream.writeInt(getButtonMask(e.getButton()));
                    outputStream.writeInt(action);
                    outputStream.flush();
                }
            } catch (IOException ex) {
                disconnect();
            }
        }
    }

    private void sendKeyEvent(int keyCode, int action) {
        if (outputStream != null && running) {
            try {
                synchronized(outputStream) {
                    outputStream.writeInt(1); // Keyboard event
                    outputStream.writeInt(keyCode);
                    outputStream.writeInt(action);
                    outputStream.flush();
                }
            } catch (IOException ex) {
                disconnect();
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
