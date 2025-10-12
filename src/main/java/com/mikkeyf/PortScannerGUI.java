package com.mikkeyf;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
/**
 * @Author: Mikkeyf
 * @CreateTime: 2025/10/4 15:50
 */
public class PortScannerGUI extends JFrame {

    // GUI 组件
    private JTextField hostField;
    private JTextField startPortField;
    private JTextField endPortField;
    private JTextField threadsField;
    private JComboBox<PortScanner.Protocol> protocolComboBox;
    private JButton scanButton;
    private JButton cancelButton;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JTextArea logArea;
    private DefaultTableModel tableModel;

    private PortScanner currentScanner;

    public PortScannerGUI() {
        initializeUI();
    }

    /**
     * 初始化用户界面
     */
    private void initializeUI() {
        setTitle("端口扫描工具 v1.0");
        setSize(900, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // 创建主面板
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 顶部输入面板
        mainPanel.add(createInputPanel(), BorderLayout.NORTH);

        // 中间分割面板（日志和结果表格）
        mainPanel.add(createCenterPanel(), BorderLayout.CENTER);

        // 底部状态面板
        mainPanel.add(createStatusPanel(), BorderLayout.SOUTH);

        add(mainPanel);

        // 设置窗口图标和外观
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            SwingUtilities.updateComponentTreeUI(this);
        } catch (Exception e) {
            // 使用默认外观
        }
    }

    /**
     * 创建输入面板
     */
    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("扫描配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 主机地址
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("目标主机:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        hostField = new JTextField("127.0.0.1", 20);
        panel.add(hostField, gbc);

        // 起始端口
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(new JLabel("起始端口:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.3;
        startPortField = new JTextField("1", 8);
        panel.add(startPortField, gbc);

        // 结束端口
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("结束端口:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.3;
        endPortField = new JTextField("1024", 8);
        panel.add(endPortField, gbc);

        // 线程数
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(new JLabel("线程数:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.3;
        threadsField = new JTextField("10", 8);
        panel.add(threadsField, gbc);

        // 协议类型
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        panel.add(new JLabel("扫描协议:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.3;
        protocolComboBox = new JComboBox<>(PortScanner.Protocol.values());
        protocolComboBox.setSelectedItem(PortScanner.Protocol.TCP);
        protocolComboBox.setToolTipText("UDP扫描结果不可靠，仅供参考");
        panel.add(protocolComboBox, gbc);

        // 按钮面板
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 4;
        panel.add(createButtonPanel(), gbc);

        return panel;
    }

    /**
     * 创建按钮面板
     */
    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));

        // 开始扫描按钮
        scanButton = new JButton("开始扫描");
        scanButton.setIcon(UIManager.getIcon("FileView.computerIcon"));
        scanButton.addActionListener(e -> startScan());
        panel.add(scanButton);

        // 取消按钮
        cancelButton = new JButton("取消扫描");
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> cancelScan());
        panel.add(cancelButton);

        // 清空结果按钮
        JButton clearButton = new JButton("清空结果");
        clearButton.addActionListener(e -> clearResults());
        panel.add(clearButton);

        // 保存结果按钮
        JButton saveButton = new JButton("保存结果");
        saveButton.addActionListener(e -> saveResults());
        panel.add(saveButton);

        return panel;
    }

    /**
     * 创建中间面板（分割日志和结果表格）
     */
    private JPanel createCenterPanel() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.5);

        // 日志面板
        splitPane.setTopComponent(createLogPanel());

        // 结果表格面板
        splitPane.setBottomComponent(createResultPanel());

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(splitPane, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 创建日志面板
     */
    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("扫描日志"));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(logArea);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * 创建结果表格面板
     */
    private JPanel createResultPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("扫描结果"));

        // 创建表格
        String[] columnNames = {"端口号", "协议", "服务名称", "状态"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // 禁止编辑
            }
        };

        JTable resultTable = new JTable(tableModel);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(250);
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(100);

        JScrollPane scrollPane = new JScrollPane(resultTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * 创建状态面板
     */
    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        // 进度条
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        panel.add(progressBar, BorderLayout.CENTER);

        // 状态标签
        statusLabel = new JLabel("就绪");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        panel.add(statusLabel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * 开始扫描
     */
    private void startScan() {
        // 验证输入
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入目标主机地址！", "输入错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int startPort, endPort, threads;
        try {
            startPort = Integer.parseInt(startPortField.getText().trim());
            endPort = Integer.parseInt(endPortField.getText().trim());
            threads = Integer.parseInt(threadsField.getText().trim());

            if (startPort < 1 || startPort > 65535 || endPort < 1 || endPort > 65535) {
                throw new NumberFormatException("端口范围必须在 1-65535 之间");
            }
            if (startPort > endPort) {
                throw new NumberFormatException("起始端口不能大于结束端口");
            }
            if (threads < 1 || threads > 1000) {
                throw new NumberFormatException("线程数必须在 1-1000 之间");
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "输入参数错误: " + e.getMessage(), "输入错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 清空之前的结果
        logArea.setText("");
        tableModel.setRowCount(0);
        progressBar.setValue(0);

        // 禁用输入和扫描按钮
        setInputEnabled(false);
        scanButton.setEnabled(false);
        cancelButton.setEnabled(true);

        // 获取协议选择
        PortScanner.Protocol protocol = (PortScanner.Protocol) protocolComboBox.getSelectedItem();

        // 记录开始时间
        String startTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        appendLog("========================================");
        appendLog("扫描开始时间: " + startTime);
        appendLog("目标主机: " + host);
        appendLog("端口范围: " + startPort + " - " + endPort);
        appendLog("扫描协议: " + protocol.getDisplayName());
        appendLog("线程数: " + threads);
        if (protocol == PortScanner.Protocol.UDP || protocol == PortScanner.Protocol.BOTH) {
            appendLog("注意：UDP扫描结果不可靠，仅供参考");
        }
        appendLog("========================================\n");

        // 创建扫描器
        currentScanner = new PortScanner(host, startPort, endPort, threads);
        currentScanner.setProtocol(protocol);
        currentScanner.setCallback(new PortScanner.ScanCallback() {
            @Override
            public void onProgress(int currentPort, double percentage) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue((int) percentage);
                    statusLabel.setText(String.format("正在扫描端口 %d... 进度: %.2f%%", currentPort, percentage));
                });
            }

            @Override
            public void onPortFound(int port, String service) {
                // 保留兼容性，实际使用onPortFoundDetailed
            }

            @Override
            public void onPortFoundDetailed(PortScanner.PortResult result) {
                SwingUtilities.invokeLater(() -> {
                    String logMessage = String.format("✓ 端口 %d (%s) %s - %s", 
                        result.getPort(), 
                        result.getProtocol().getDisplayName(),
                        result.getState(),
                        result.getService());
                    appendLog(logMessage);
                    
                    tableModel.addRow(new Object[]{
                        result.getPort(), 
                        result.getProtocol().getDisplayName(),
                        result.getService(), 
                        result.getState()
                    });
                });
            }

            @Override
            public void onComplete(List<PortScanner.PortResult> openPorts) {
                SwingUtilities.invokeLater(() -> {
                    String endTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    appendLog("\n========================================");
                    appendLog("扫描完成时间: " + endTime);
                    appendLog("共发现 " + openPorts.size() + " 个开放端口");
                    appendLog("========================================");

                    statusLabel.setText("扫描完成！共发现 " + openPorts.size() + " 个开放端口");
                    progressBar.setValue(100);

                    // 恢复按钮状态
                    setInputEnabled(true);
                    scanButton.setEnabled(true);
                    cancelButton.setEnabled(false);

                    if (!openPorts.isEmpty()) {
                        int result = JOptionPane.showConfirmDialog(
                                PortScannerGUI.this,
                                "扫描完成！是否保存结果到文件？",
                                "扫描完成",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.INFORMATION_MESSAGE
                        );
                        if (result == JOptionPane.YES_OPTION) {
                            saveResults();
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("错误: " + error);
                    JOptionPane.showMessageDialog(PortScannerGUI.this, error, "扫描错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        });

        // 在新线程中执行扫描
        Thread scanThread = new Thread(() -> {
            try {
                currentScanner.scanPorts();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("扫描异常: " + e.getMessage());
                    JOptionPane.showMessageDialog(PortScannerGUI.this,
                            "扫描过程中发生异常: " + e.getMessage(),
                            "异常",
                            JOptionPane.ERROR_MESSAGE);

                    setInputEnabled(true);
                    scanButton.setEnabled(true);
                    cancelButton.setEnabled(false);
                });
            }
        });
        scanThread.start();
    }

    /**
     * 取消扫描
     */
    private void cancelScan() {
        if (currentScanner != null) {
            currentScanner.cancel();
            appendLog("\n用户取消了扫描操作");
            statusLabel.setText("扫描已取消");

            setInputEnabled(true);
            scanButton.setEnabled(true);
            cancelButton.setEnabled(false);
        }
    }

    /**
     * 清空结果
     */
    private void clearResults() {
        int result = JOptionPane.showConfirmDialog(
                this,
                "确定要清空所有结果吗？",
                "确认清空",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION) {
            logArea.setText("");
            tableModel.setRowCount(0);
            progressBar.setValue(0);
            statusLabel.setText("就绪");
        }
    }

    /**
     * 保存结果
     */
    private void saveResults() {
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "没有可保存的结果！", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new File("port_scan_results.txt"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();

            try {
                java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(file));
                writer.write("端口扫描结果\n");
                writer.write("保存时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
                writer.write("目标主机: " + hostField.getText() + "\n");
                writer.write("========================================\n\n");

                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    int port = (int) tableModel.getValueAt(i, 0);
                    String protocolStr = (String) tableModel.getValueAt(i, 1);
                    String service = (String) tableModel.getValueAt(i, 2);
                    String state = (String) tableModel.getValueAt(i, 3);
                    writer.write(String.format("端口 %d (%s) %s - %s\n", port, protocolStr, state, service));
                }

                writer.close();

                JOptionPane.showMessageDialog(this,
                        "结果已保存到: " + file.getAbsolutePath(),
                        "保存成功",
                        JOptionPane.INFORMATION_MESSAGE);
                appendLog("结果已保存到: " + file.getAbsolutePath());

            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                        "保存失败: " + e.getMessage(),
                        "错误",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 设置输入控件是否可用
     */
    private void setInputEnabled(boolean enabled) {
        hostField.setEnabled(enabled);
        startPortField.setEnabled(enabled);
        endPortField.setEnabled(enabled);
        threadsField.setEnabled(enabled);
        protocolComboBox.setEnabled(enabled);
    }

    /**
     * 追加日志
     */
    private void appendLog(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /**
     * 主函数
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PortScannerGUI gui = new PortScannerGUI();
            gui.setVisible(true);
        });
    }
}

