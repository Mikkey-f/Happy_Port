# Java 多线程端口扫描工具 - 技术文档

## 📋 目录

- [项目简介](#项目简介)
- [核心技术架构](#核心技术架构)
- [技术实现细节](#技术实现细节)
- [编译和运行](#编译和运行)
- [使用说明](#使用说明)
- [性能优化](#性能优化)
- [扩展开发](#扩展开发)

---

## 项目简介

这是一个基于 Java 开发的多线程端口扫描工具，具有以下特点：

- **高性能**: 基于线程池的并发扫描，支持自定义线程数
- **双模式**: 提供命令行和图形界面两种交互方式
- **实时反馈**: 动态显示扫描进度和发现的开放端口
- **服务识别**: 自动识别常见端口的服务类型
- **结果保存**: 支持将扫描结果保存为文本文件

**技术栈**:
- Java SE 8+
- Socket 网络编程
- java.util.concurrent 多线程框架
- Swing GUI 框架

---

## 核心技术架构

### 架构设计图

```
┌─────────────────────────────────────────────────┐
│                  用户界面层                      │
│  ┌──────────────┐        ┌──────────────┐      │
│  │ 命令行界面   │        │  Swing GUI   │      │
│  │  (CLI)       │        │  (PortScannerGUI)│  │
│  └──────┬───────┘        └──────┬───────┘      │
│         └────────────┬───────────┘              │
└──────────────────────┼──────────────────────────┘
                       │
┌──────────────────────┼──────────────────────────┐
│                  业务逻辑层                      │
│         ┌──────────┴──────────┐                │
│         │   PortScanner 类    │                │
│         │  ┌───────────────┐  │                │
│         │  │ scanPorts()   │  │  ← 主扫描方法 │
│         │  └───────────────┘  │                │
│         │  ┌───────────────┐  │                │
│         │  │ ScanCallback  │  │  ← 回调接口   │
│         │  └───────────────┘  │                │
│         └─────────────────────┘                │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────┼──────────────────────────┐
│                  数据访问层                      │
│         ┌──────────┴──────────┐                │
│         │  ┌───────────────┐  │                │
│         │  │ scanPort()    │  │  ← Socket连接 │
│         │  └───────────────┘  │                │
│         │  ┌───────────────┐  │                │
│         │  │ saveToFile()  │  │  ← 文件IO     │
│         │  └───────────────┘  │                │
│         └─────────────────────┘                │
└──────────────────────────────────────────────────┘
```

### 类关系图

```
┌──────────────────┐
│  PortScanner     │
├──────────────────┤
│ - host           │
│ - startPort      │
│ - endPort        │
│ - maxThreads     │
│ - callback       │
├──────────────────┤
│ + scanPorts()    │
│ - scanPort()     │
│ - getServiceName()│
│ + saveToFile()   │
└────────┬─────────┘
         │
         │ contains
         ▼
┌──────────────────┐         ┌──────────────────┐
│   PortResult     │         │  ScanCallback    │
├──────────────────┤         ├──────────────────┤
│ - port           │         │ + onProgress()   │
│ - service        │         │ + onPortFound()  │
├──────────────────┤         │ + onComplete()   │
│ + getPort()      │         │ + onError()      │
│ + getService()   │         └──────────────────┘
└──────────────────┘                  △
                                      │
                                      │ implements
                                      │
                          ┌──────────────────────┐
                          │  PortScannerGUI      │
                          ├──────────────────────┤
                          │ - hostField          │
                          │ - progressBar        │
                          │ - logArea            │
                          │ - resultTable        │
                          ├──────────────────────┤
                          │ - startScan()        │
                          │ - cancelScan()       │
                          │ - saveResults()      │
                          └──────────────────────┘
```

---

## 技术实现细节

### 1. 多线程扫描机制

#### 1.1 线程池设计

**实现代码**:
```java
ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
```

**技术要点**:
- 使用固定大小的线程池（`FixedThreadPool`）
- 线程数量可配置（默认 10，建议 50-100）
- 线程复用，避免频繁创建销毁线程的开销

**为什么选择 FixedThreadPool**:
1. 端口扫描任务数量已知（end - start + 1）
2. 任务特性相似（都是 Socket 连接测试）
3. 固定线程数易于控制资源占用

#### 1.2 任务提交机制

**实现代码**:
```java
Map<Future<PortResult>, Integer> futures = new ConcurrentHashMap<>();

for (int port = startPort; port <= endPort; port++) {
    final int currentPort = port;
    Future<PortResult> future = executor.submit(() -> scanPort(host, currentPort));
    futures.put(future, currentPort);
}
```

**技术要点**:
- 使用 `Future` 对象保存异步任务结果
- 使用 `ConcurrentHashMap` 保证线程安全
- Lambda 表达式简化 `Callable` 实现
- 使用 `final` 变量捕获循环变量

**为什么使用 Map<Future, Port>**:
- 需要在任务完成时知道对应的端口号
- `as_completed()` 按完成顺序返回，需要映射关系
- 方便进度计算和回调通知

#### 1.3 结果收集机制

**实现代码**:
```java
List<PortResult> openPorts = Collections.synchronizedList(new ArrayList<>());

for (Map.Entry<Future<PortResult>, Integer> entry : futures.entrySet()) {
    try {
        Future<PortResult> future = entry.getKey();
        Integer port = entry.getValue();
        
        PortResult result = future.get(); // 阻塞等待
        scannedPorts++;
        
        double progress = (scannedPorts * 100.0) / totalPorts;
        
        if (callback != null) {
            callback.onProgress(port, progress);
        }
        
        if (result != null) {
            openPorts.add(result);
            callback.onPortFound(result.getPort(), result.getService());
        }
    } catch (InterruptedException | ExecutionException e) {
        callback.onError("扫描端口时出错: " + e.getMessage());
    }
}
```

**技术要点**:
- `future.get()` 会阻塞等待任务完成
- 使用 `synchronizedList` 保证线程安全添加
- 实时计算进度百分比
- 通过回调接口通知上层

#### 1.4 线程池关闭

**实现代码**:
```java
finally {
    executor.shutdown();  // 不再接受新任务
    try {
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();  // 强制关闭
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

**技术要点**:
- `shutdown()`: 优雅关闭，等待已提交任务完成
- `awaitTermination()`: 等待最多 60 秒
- `shutdownNow()`: 强制中断所有任务
- 使用 `finally` 确保资源释放

---

### 2. 端口扫描核心算法

#### 2.1 TCP 连接测试

**实现代码**:
```java
private PortResult scanPort(String host, int port) {
    Socket socket = null;
    try {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 500); // 500ms 超时
        
        String service = getServiceName(port);
        return new PortResult(port, service);
        
    } catch (IOException e) {
        return null; // 端口关闭或超时
    } finally {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // 忽略关闭异常
            }
        }
    }
}
```

**技术原理**:
1. **TCP 三次握手**: 尝试与目标端口建立 TCP 连接
   ```
   Client                    Server
     │                          │
     ├───── SYN ─────────────>  │  (发起连接)
     │                          │
     │  <──── SYN-ACK ────────┤  (端口开放，响应)
     │                          │
     ├───── ACK ─────────────>  │  (确认连接)
     │                          │
   成功 = 端口开放
   
   Client                    Server
     │                          │
     ├───── SYN ─────────────>  │  (发起连接)
     │                          │
     │  <──── RST ────────────┤  (端口关闭，拒绝)
     │                          │
   失败 = 端口关闭
   ```

2. **超时设置**: 500ms 超时避免长时间等待
   - 太短：可能误判（网络延迟导致超时）
   - 太长：扫描速度慢
   - 500ms 是经验值，平衡准确性和速度

3. **资源管理**: `finally` 块确保 Socket 关闭
   - 避免文件描述符泄漏
   - 释放系统资源

#### 2.2 端口服务识别

**实现代码**:
```java
private String getServiceName(int port) {
    // 1. 首先从常见端口字典查找
    if (COMMON_PORTS.containsKey(port)) {
        return COMMON_PORTS.get(port);
    }
    
    // 2. 尝试从系统数据库获取
    try {
        String serviceName = getSystemServiceName(port);
        if (serviceName != null) {
            return serviceName;
        }
    } catch (Exception e) {
        // 忽略异常
    }
    
    // 3. 未知端口
    return "未知";
}
```

**识别策略**:
1. **内置字典**: 快速识别常见端口（80/HTTP、443/HTTPS 等）
2. **系统数据库**: 查询操作系统的服务数据库（`/etc/services`）
3. **降级处理**: 无法识别时返回"未知"

**常见端口字典设计**:
```java
private static final Map<Integer, String> COMMON_PORTS = new HashMap<Integer, String>() {{
    put(21, "FTP（文件传输协议）");
    put(22, "SSH（安全外壳协议）");
    put(23, "Telnet（远程登录协议）");
    put(25, "SMTP（简单邮件传输协议）");
    put(53, "DNS（域名系统）");
    put(80, "HTTP（超文本传输协议）");
    put(110, "POP3（邮局协议版本3）");
    put(143, "IMAP（互联网消息访问协议）");
    put(443, "HTTPS（安全的超文本传输协议）");
    put(3306, "MySQL 数据库");
    put(3389, "RDP（远程桌面协议）");
    put(8080, "HTTP-Alt（备用HTTP端口）");
}};
```

---

### 3. 回调机制设计

#### 3.1 回调接口定义

**接口设计**:
```java
public interface ScanCallback {
    void onProgress(int currentPort, double percentage);  // 进度更新
    void onPortFound(int port, String service);          // 发现开放端口
    void onComplete(List<PortResult> openPorts);         // 扫描完成
    void onError(String error);                          // 错误处理
}
```

**设计原则**:
1. **单一职责**: 每个方法只负责一种事件
2. **解耦合**: 扫描逻辑与界面更新分离
3. **灵活性**: 支持不同的实现（命令行、GUI）

#### 3.2 回调时机

```
扫描开始
    │
    ├─> 任务提交 (不触发回调)
    │
    ├─> 任务完成
    │   ├─> onProgress(port, percentage)  ← 每个端口扫描完成
    │   └─> onPortFound(port, service)    ← 发现开放端口时
    │
    ├─> 异常发生
    │   └─> onError(error)                ← 捕获到异常时
    │
    └─> 扫描结束
        └─> onComplete(openPorts)          ← 所有任务完成
```

#### 3.3 命令行模式实现

**实现示例**:
```java
scanner.setCallback(new ScanCallback() {
    @Override
    public void onProgress(int currentPort, double percentage) {
        System.out.printf("\r正在扫描端口 %d... 进度: %.2f%%", currentPort, percentage);
    }
    
    @Override
    public void onPortFound(int port, String service) {
        System.out.println("\n端口 " + port + " 已打开 - " + service);
    }
    
    @Override
    public void onComplete(List<PortResult> openPorts) {
        System.out.println("\n扫描完成。");
        displayResults(openPorts);
    }
    
    @Override
    public void onError(String error) {
        System.err.println("错误: " + error);
    }
});
```

**技术要点**:
- 使用 `\r` 实现进度条覆盖效果
- 发现端口时换行显示，避免被覆盖

#### 3.4 GUI 模式实现

**实现示例**:
```java
currentScanner.setCallback(new PortScanner.ScanCallback() {
    @Override
    public void onProgress(int currentPort, double percentage) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue((int) percentage);
            statusLabel.setText(String.format("正在扫描端口 %d... 进度: %.2f%%", 
                                              currentPort, percentage));
        });
    }
    
    @Override
    public void onPortFound(int port, String service) {
        SwingUtilities.invokeLater(() -> {
            appendLog("✓ 端口 " + port + " 已打开 - " + service);
            tableModel.addRow(new Object[]{port, service, "开放"});
        });
    }
    
    @Override
    public void onComplete(List<PortScanner.PortResult> openPorts) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("扫描完成！共发现 " + openPorts.size() + " 个开放端口");
            progressBar.setValue(100);
        });
    }
});
```

**关键技术点**:
- **`SwingUtilities.invokeLater()`**: 确保在 EDT（Event Dispatch Thread）中更新 GUI
- **为什么需要**: Swing 组件不是线程安全的，必须在 EDT 中操作
- **线程模型**:
  ```
  扫描线程 (Worker Thread)
      │
      ├─> 回调方法被调用
      │
      ├─> SwingUtilities.invokeLater()
      │   └─> 将任务提交到 EDT 队列
      │
  EDT (Event Dispatch Thread)
      │
      └─> 从队列取出任务
          └─> 更新 GUI 组件
  ```

---

### 4. Swing GUI 实现

#### 4.1 界面布局设计

**布局层次结构**:
```
JFrame (BorderLayout)
│
├─ NORTH: InputPanel (GridBagLayout)
│  ├─ 主机输入框
│  ├─ 端口范围输入框
│  ├─ 线程数输入框
│  └─ 按钮面板 (FlowLayout)
│
├─ CENTER: JSplitPane (垂直分割)
│  ├─ TOP: LogPanel (BorderLayout)
│  │  └─ JTextArea (在 JScrollPane 中)
│  │
│  └─ BOTTOM: ResultPanel (BorderLayout)
│     └─ JTable (在 JScrollPane 中)
│
└─ SOUTH: StatusPanel (BorderLayout)
   ├─ CENTER: JProgressBar
   └─ SOUTH: JLabel (状态信息)
```

**布局管理器选择**:
- **BorderLayout**: 适合主面板，分为上中下三部分
- **GridBagLayout**: 适合表单输入，灵活控制位置和大小
- **FlowLayout**: 适合按钮排列，自动换行
- **JSplitPane**: 可调整大小的分割面板

#### 4.2 关键组件实现

**进度条设计**:
```java
progressBar = new JProgressBar(0, 100);
progressBar.setStringPainted(true);  // 显示百分比文字

// 更新进度
progressBar.setValue((int) percentage);
```

**结果表格设计**:
```java
String[] columnNames = {"端口号", "服务名称", "状态"};
tableModel = new DefaultTableModel(columnNames, 0) {
    @Override
    public boolean isCellEditable(int row, int column) {
        return false; // 禁止编辑
    }
};

resultTable = new JTable(tableModel);
resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

// 设置列宽
resultTable.getColumnModel().getColumn(0).setPreferredWidth(80);
resultTable.getColumnModel().getColumn(1).setPreferredWidth(300);
resultTable.getColumnModel().getColumn(2).setPreferredWidth(80);

// 添加数据
tableModel.addRow(new Object[]{port, service, "开放"});
```

**日志区域设计**:
```java
logArea = new JTextArea();
logArea.setEditable(false);  // 只读
logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));  // 等宽字体

// 自动滚动到底部
private void appendLog(String message) {
    logArea.append(message + "\n");
    logArea.setCaretPosition(logArea.getDocument().getLength());
}
```

#### 4.3 输入验证机制

**实现代码**:
```java
private void startScan() {
    // 1. 验证主机地址
    String host = hostField.getText().trim();
    if (host.isEmpty()) {
        JOptionPane.showMessageDialog(this, "请输入目标主机地址！", 
                                      "输入错误", JOptionPane.ERROR_MESSAGE);
        return;
    }
    
    // 2. 验证端口范围
    try {
        startPort = Integer.parseInt(startPortField.getText().trim());
        endPort = Integer.parseInt(endPortField.getText().trim());
        
        if (startPort < 1 || startPort > 65535 || endPort < 1 || endPort > 65535) {
            throw new NumberFormatException("端口范围必须在 1-65535 之间");
        }
        if (startPort > endPort) {
            throw new NumberFormatException("起始端口不能大于结束端口");
        }
    } catch (NumberFormatException e) {
        JOptionPane.showMessageDialog(this, "输入参数错误: " + e.getMessage(), 
                                      "输入错误", JOptionPane.ERROR_MESSAGE);
        return;
    }
    
    // 3. 验证线程数
    threads = Integer.parseInt(threadsField.getText().trim());
    if (threads < 1 || threads > 1000) {
        throw new NumberFormatException("线程数必须在 1-1000 之间");
    }
}
```

**验证规则**:
| 参数 | 规则 | 错误提示 |
|------|------|---------|
| 主机地址 | 不能为空 | "请输入目标主机地址！" |
| 起始端口 | 1-65535 | "端口范围必须在 1-65535 之间" |
| 结束端口 | 1-65535 且 ≥ 起始端口 | "起始端口不能大于结束端口" |
| 线程数 | 1-1000 | "线程数必须在 1-1000 之间" |

#### 4.4 取消扫描实现

**实现代码**:
```java
// 1. 扫描器类中添加取消标志
private volatile boolean isCancelled = false;

public void cancel() {
    this.isCancelled = true;
}

// 2. 在扫描循环中检查
for (int port = startPort; port <= endPort; port++) {
    if (isCancelled) {
        break;  // 提前退出
    }
    // 提交任务...
}

// 3. GUI 中实现取消按钮
private void cancelScan() {
    if (currentScanner != null) {
        currentScanner.cancel();
        appendLog("\n用户取消了扫描操作");
        statusLabel.setText("扫描已取消");
    }
}
```

**技术要点**:
- 使用 `volatile` 保证可见性
- 提交任务前检查取消标志
- 取消后恢复界面状态

---

### 5. 文件 I/O 实现

#### 5.1 结果保存

**实现代码**:
```java
public static void saveToFile(List<PortResult> openPorts, String filename) {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
        for (PortResult result : openPorts) {
            writer.write("端口 " + result.getPort() + " 已打开 - " + result.getService());
            writer.newLine();
        }
    } catch (IOException e) {
        System.err.println("保存文件失败: " + e.getMessage());
    }
}
```

**技术要点**:
- **Try-with-resources**: 自动关闭资源
- **BufferedWriter**: 缓冲写入，提高效率
- **append 模式**: `new FileWriter(filename, true)` 追加内容

#### 5.2 GUI 文件选择器

**实现代码**:
```java
private void saveResults() {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setSelectedFile(new File("port_scan_results.txt"));
    
    int result = fileChooser.showSaveDialog(this);
    if (result == JFileChooser.APPROVE_OPTION) {
        File file = fileChooser.getSelectedFile();
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write("端口扫描结果\n");
            writer.write("保存时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
            writer.write("========================================\n\n");
            
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                int port = (int) tableModel.getValueAt(i, 0);
                String service = (String) tableModel.getValueAt(i, 1);
                writer.write("端口 " + port + " 已打开 - " + service + "\n");
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "保存失败: " + e.getMessage());
        }
    }
}
```

---

### 6. 异常处理机制

#### 6.1 网络异常

```java
try {
    socket.connect(new InetSocketAddress(host, port), 500);
} catch (SocketTimeoutException e) {
    // 超时：端口可能被防火墙过滤
    return null;
} catch (ConnectException e) {
    // 连接被拒绝：端口关闭
    return null;
} catch (UnknownHostException e) {
    // 域名解析失败
    callback.onError("无法解析主机: " + host);
} catch (IOException e) {
    // 其他 I/O 异常
    return null;
}
```

#### 6.2 线程异常

```java
try {
    PortResult result = future.get();
} catch (InterruptedException e) {
    // 线程被中断
    Thread.currentThread().interrupt();
    callback.onError("扫描被中断");
} catch (ExecutionException e) {
    // 任务执行中抛出异常
    callback.onError("扫描出错: " + e.getCause().getMessage());
}
```

#### 6.3 GUI 异常

```java
try {
    currentScanner.scanPorts();
} catch (Exception e) {
    SwingUtilities.invokeLater(() -> {
        JOptionPane.showMessageDialog(this, 
            "扫描过程中发生异常: " + e.getMessage(), 
            "异常", 
            JOptionPane.ERROR_MESSAGE);
        
        // 恢复界面状态
        setInputEnabled(true);
        scanButton.setEnabled(true);
        cancelButton.setEnabled(false);
    });
}
```

---

## 编译和运行

### 编译

```bash
# 编译核心类
javac PortScanner.java

# 编译 GUI 类
javac PortScannerGUI.java
```

### 运行

**命令行模式**:
```bash
java PortScanner <主机地址> [-s 起始端口] [-e 结束端口] [-t 线程数]

# 示例
java PortScanner 127.0.0.1 -s 1 -e 1024 -t 50
```

**图形界面模式**:
```bash
java PortScannerGUI
```

---

## 使用说明

### 命令行参数

| 参数 | 简写 | 默认值 | 说明 |
|------|------|--------|------|
| 主机地址 | - | 必填 | 目标主机的域名或 IP 地址 |
| --start-port | -s | 1 | 起始端口号（1-65535） |
| --end-port | -e | 1024 | 结束端口号（1-65535） |
| --threads | -t | 10 | 最大线程数（1-1000） |

### GUI 操作

1. **配置参数**: 在输入框中填写目标主机、端口范围、线程数
2. **开始扫描**: 点击"开始扫描"按钮
3. **查看进度**: 观察进度条和日志区域
4. **查看结果**: 在结果表格中查看所有开放端口
5. **保存结果**: 点击"保存结果"按钮，选择保存位置
6. **取消扫描**: 如需中止，点击"取消扫描"按钮

---

## 性能优化

### 1. 线程数调优

**推荐配置**:
- **局域网**: 50-100 个线程
- **互联网**: 10-30 个线程
- **慢速网络**: 5-10 个线程

**原因**:
- 线程过多：上下文切换开销增加，可能被防火墙限制
- 线程过少：无法充分利用并发优势

### 2. 超时时间调整

当前设置为 **500ms**，可根据需要调整：

```java
socket.connect(new InetSocketAddress(host, port), timeout);
```

- **100-300ms**: 局域网快速扫描
- **500-1000ms**: 互联网标准扫描
- **>1000ms**: 慢速网络或高准确性要求

### 3. 内存优化

**当前实现**:
```java
List<PortResult> openPorts = Collections.synchronizedList(new ArrayList<>());
```

**改进建议**:
- 大范围扫描时（如全端口），使用流式处理
- 定期清理已完成的 Future 对象
- 限制同时提交的任务数量

### 4. GUI 响应优化

**避免 EDT 阻塞**:
- 所有耗时操作在后台线程执行
- 使用 `SwingUtilities.invokeLater()` 更新 GUI
- 批量更新表格数据

---

## 扩展开发

### 1. 支持 UDP 扫描

```java
private boolean scanUdpPort(String host, int port) {
    try (DatagramSocket socket = new DatagramSocket()) {
        socket.setSoTimeout(500);
        
        byte[] data = new byte[1024];
        DatagramPacket packet = new DatagramPacket(data, data.length, 
                                                    InetAddress.getByName(host), port);
        socket.send(packet);
        
        DatagramPacket response = new DatagramPacket(data, data.length);
        socket.receive(response);
        return true;
    } catch (Exception e) {
        return false;
    }
}
```

### 2. 端口指纹识别

```java
private String identifyService(String host, int port) {
    try (Socket socket = new Socket(host, port)) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        
        // 发送探测数据
        out.write("GET / HTTP/1.0\r\n\r\n".getBytes());
        
        // 读取响应
        byte[] buffer = new byte[1024];
        int len = in.read(buffer);
        String response = new String(buffer, 0, len);
        
        // 分析响应识别服务
        if (response.contains("HTTP")) return "HTTP Server";
        if (response.contains("SSH")) return "SSH Server";
        // ...
    }
    return "未知";
}
```

### 3. 批量主机扫描

```java
public class BatchScanner {
    public void scanHosts(List<String> hosts, int startPort, int endPort) {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        
        for (String host : hosts) {
            executor.submit(() -> {
                PortScanner scanner = new PortScanner(host, startPort, endPort, 50);
                scanner.scanPorts();
            });
        }
        
        executor.shutdown();
    }
}
```

### 4. 扫描历史记录

```java
public class ScanHistory {
    private List<ScanRecord> records = new ArrayList<>();
    
    public void addRecord(String host, List<PortResult> results) {
        ScanRecord record = new ScanRecord();
        record.setTimestamp(new Date());
        record.setHost(host);
        record.setResults(results);
        records.add(record);
    }
    
    public void saveToDatabase() {
        // 保存到 SQLite 或其他数据库
    }
}
```

### 5. 导出多种格式

```java
public interface ResultExporter {
    void export(List<PortResult> results, String filename);
}

public class CsvExporter implements ResultExporter {
    public void export(List<PortResult> results, String filename) {
        // 导出为 CSV 格式
    }
}

public class JsonExporter implements ResultExporter {
    public void export(List<PortResult> results, String filename) {
        // 导出为 JSON 格式
    }
}
```

---

## 常见问题

### Q1: 为什么有些端口扫描不到？

**可能原因**:
1. 目标主机开启了防火墙
2. 端口被 IDS/IPS 系统过滤
3. 超时时间设置过短
4. 网络不稳定

### Q2: 如何提高扫描准确性？

**建议**:
1. 增加超时时间（如 1000ms）
2. 减少线程数避免被限制
3. 多次扫描取交集
4. 使用更专业的工具（如 nmap）

### Q3: 大范围扫描（全端口）很慢怎么办？

**优化方案**:
1. 增加线程数到 200-500
2. 减少超时时间到 100-200ms
3. 先扫描常用端口，再扫描其他端口
4. 使用异步 I/O（NIO）

### Q4: 如何避免被目标主机检测？

**建议**:
1. 降低扫描速度（减少线程数）
2. 随机化扫描顺序
3. 使用代理或 VPN
4. 遵守网络使用规范

---

## 参考资料

1. **Java 官方文档**
   - https://docs.oracle.com/javase/8/docs/

2. **Java 并发编程**
   - 《Java 并发编程实战》
   - https://docs.oracle.com/javase/tutorial/essential/concurrency/

3. **网络协议**
   - 《TCP/IP 详解 卷1：协议》
   - RFC 793 (TCP)

4. **Swing 编程**
   - https://docs.oracle.com/javase/tutorial/uiswing/

5. **端口扫描技术**
   - https://nmap.org/book/man-port-scanning-techniques.html

---

## 开发者信息

**项目名称**: Java 多线程端口扫描工具  
**版本**: 1.0  
**开发时间**: 2025年10月  
**开发语言**: Java SE 8+  
**许可证**: MIT License  

**联系方式**: [1647228132@qq.com]  
**项目地址**: [GitHub 地址]

---

## 更新日志

### v1.0 (2025-10-04)
- ✅ 实现多线程端口扫描核心功能
- ✅ 支持命令行和图形界面两种模式
- ✅ 添加常见端口服务识别
- ✅ 实现扫描结果保存功能
- ✅ 添加取消扫描功能
- ✅ 完善异常处理和输入验证

---

**祝你使用愉快！如有问题欢迎反馈。** 🎉
