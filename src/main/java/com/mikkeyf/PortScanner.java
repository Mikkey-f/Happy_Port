package com.mikkeyf;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
/**
 * @Author: Mikkeyf
 * @CreateTime: 2025/10/4 15:51
 */
public class PortScanner {

    /**
     * 扫描协议类型
     */
    public enum Protocol {
        TCP("TCP"),
        UDP("UDP"),
        BOTH("TCP+UDP");

        private final String displayName;

        Protocol(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * UDP端口状态
     */
    public enum UdpPortState {
        OPEN("开放"),
        CLOSED("关闭"),
        OPEN_OR_FILTERED("开放|过滤");

        private final String displayName;

        UdpPortState(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // 常见的端口及其作用（中文说明）
    private static final Map<Integer, String> COMMON_PORTS = new HashMap<>() {{
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

    private final String host;
    private final int startPort;
    private final int endPort;
    private final int maxThreads;
    private Protocol protocol = Protocol.TCP; // 默认TCP扫描
    private volatile boolean isCancelled = false;

    // 回调接口，用于更新进度和结果
    private ScanCallback callback;

    public interface ScanCallback {
        void onProgress(int currentPort, double percentage);
        void onPortFound(int port, String service); // 已弃用，保留兼容性
        void onPortFoundDetailed(PortResult result); // 新方法，传递完整结果
        void onComplete(List<PortResult> openPorts);
        void onError(String error);
    }

    /**
     * 端口扫描结果类
     */
    public static class PortResult {
        private final int port;
        private final String service;
        private final Protocol protocol;
        private final String state; // 状态描述（TCP: "开放", UDP: "开放", "关闭", "开放|过滤"）

        public PortResult(int port, String service, Protocol protocol, String state) {
            this.port = port;
            this.service = service;
            this.protocol = protocol;
            this.state = state;
        }

        // 兼容旧代码的构造函数
        public PortResult(int port, String service) {
            this(port, service, Protocol.TCP, "开放");
        }

        public int getPort() {
            return port;
        }

        public String getService() {
            return service;
        }

        public Protocol getProtocol() {
            return protocol;
        }

        public String getState() {
            return state;
        }

        @Override
        public String toString() {
            return String.format("端口 %d (%s): %s - %s", port, protocol.getDisplayName(), service, state);
        }
    }

    public PortScanner(String host, int startPort, int endPort, int maxThreads) {
        this.host = host;
        this.startPort = startPort;
        this.endPort = endPort;
        this.maxThreads = maxThreads;
    }

    public void setCallback(ScanCallback callback) {
        this.callback = callback;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void cancel() {
        this.isCancelled = true;
    }

    /**
     * 扫描单个TCP端口
     */
    private PortResult scanTcpPort(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500); // 500ms 超时

            // 获取端口服务名称
            String service = getServiceName(port);
            return new PortResult(port, service, Protocol.TCP, "开放");

        } catch (IOException e) {
            return null; // 端口关闭
        }
    }

    /**
     * 扫描单个UDP端口
     * 注意：UDP扫描本质上不可靠，结果仅供参考
     */
    private PortResult scanUdpPort(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1000); // 1秒超时

            InetAddress address = InetAddress.getByName(host);
            
            // 准备UDP数据包
            byte[] sendData = getUdpProbeData(port);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            
            // 发送数据包
            socket.send(sendPacket);
            
            // 尝试接收响应
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            
            try {
                socket.receive(receivePacket);
                // 收到响应，端口可能开放
                String service = getServiceName(port);
                return new PortResult(port, service, Protocol.UDP, "开放");
                
            } catch (SocketTimeoutException e) {
                // 超时无响应，可能开放或被过滤
                String service = getServiceName(port);
                return new PortResult(port, service, Protocol.UDP, "开放|过滤");
            }
            
        } catch (PortUnreachableException e) {
            // 收到ICMP端口不可达，端口关闭
            return null;
            
        } catch (IOException e) {
            // 其他IO异常，可能是网络问题
            return null;
        }
    }

    /**
     * 根据端口生成特定的UDP探测数据
     * 针对常见服务发送协议特定的探测包
     */
    private byte[] getUdpProbeData(int port) {
        switch (port) {
            case 53: // DNS
                // DNS查询包（查询 example.com 的 A 记录）
                return new byte[]{
                    0x00, 0x1e, // Transaction ID
                    0x01, 0x00, // Flags: standard query
                    0x00, 0x01, // Questions: 1
                    0x00, 0x00, // Answer RRs: 0
                    0x00, 0x00, // Authority RRs: 0
                    0x00, 0x00, // Additional RRs: 0
                    0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
                    0x03, 'c', 'o', 'm',
                    0x00, // End of name
                    0x00, 0x01, // Type: A
                    0x00, 0x01  // Class: IN
                };
                
            case 123: // NTP
                // NTP请求包
                return new byte[]{
                    0x1b, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                };
                
            case 161: // SNMP
                // SNMP GetRequest包（简化版）
                return new byte[]{
                    0x30, 0x26, 0x02, 0x01, 0x00, 0x04, 0x06, 'p', 'u', 'b', 'l', 'i', 'c',
                    (byte) 0xa0, 0x19, 0x02, 0x01, 0x01, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00,
                    0x30, 0x0e, 0x30, 0x0c, 0x06, 0x08, 0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00,
                    0x05, 0x00
                };
                
            default:
                // 默认发送空数据包
                return new byte[0];
        }
    }

    /**
     * 获取端口服务名称
     */
    private String getServiceName(int port) {
        // 首先从常见端口字典查找
        if (COMMON_PORTS.containsKey(port)) {
            return COMMON_PORTS.get(port);
        }

        // 尝试从系统数据库获取
        try {
            String serviceName = getSystemServiceName(port);
            if (serviceName != null) {
                return serviceName;
            }
        } catch (Exception e) {
            // 忽略异常
        }

        return "未知";
    }

    /**
     * 尝试从系统数据库获取端口的服务名称
     */
    private String getSystemServiceName(int port) {
        // Java 中没有直接对应 socket.getservbyport() 的方法
        // 这里可以读取系统的 services 文件或使用其他方法
        // 简化实现，直接返回 null
        return null;
    }

    /**
     * 多线程扫描端口
     */
    public void scanPorts() {
        List<PortResult> openPorts = Collections.synchronizedList(new ArrayList<>());
        
        // 根据协议类型计算总端口数
        int portsPerProtocol = endPort - startPort + 1;
        int totalPorts = protocol == Protocol.BOTH ? portsPerProtocol * 2 : portsPerProtocol;

        ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
        List<Future<PortResult>> futures = new ArrayList<>();

        try {
            // 根据协议类型提交扫描任务
            if (protocol == Protocol.TCP || protocol == Protocol.BOTH) {
                // TCP扫描
                for (int port = startPort; port <= endPort; port++) {
                    if (isCancelled) break;
                    final int currentPort = port;
                    futures.add(executor.submit(() -> scanTcpPort(host, currentPort)));
                }
            }
            
            if (protocol == Protocol.UDP || protocol == Protocol.BOTH) {
                // UDP扫描
                for (int port = startPort; port <= endPort; port++) {
                    if (isCancelled) break;
                    final int currentPort = port;
                    futures.add(executor.submit(() -> scanUdpPort(host, currentPort)));
                }
            }

            // 处理完成的任务
            int scannedPorts = 0;
            for (Future<PortResult> future : futures) {
                if (isCancelled) {
                    break;
                }

                try {
                    PortResult result = future.get(); // 阻塞等待结果
                    scannedPorts++;

                    double progress = (scannedPorts * 100.0) / totalPorts;

                    // 如果端口开放或有结果
                    if (result != null) {
                        openPorts.add(result);
                        
                        // 回调更新进度
                        if (callback != null) {
                            callback.onProgress(result.getPort(), progress);
                            callback.onPortFound(result.getPort(), result.getService()); // 兼容旧版本
                            callback.onPortFoundDetailed(result); // 新版本，传递完整信息
                        }
                    } else {
                        // 端口关闭，只更新进度
                        if (callback != null) {
                            callback.onProgress(0, progress);
                        }
                    }

                } catch (InterruptedException | ExecutionException e) {
                    if (callback != null) {
                        callback.onError("扫描端口时出错: " + e.getMessage());
                    }
                }
            }

        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }

        // 排序结果
        openPorts.sort(Comparator.comparingInt(PortResult::getPort));

        if (callback != null && !isCancelled) {
            callback.onComplete(openPorts);
        }

    }

    /**
     * 保存扫描结果到文件
     */
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

    /**
     * 显示扫描结果
     */
    public static void displayResults(List<PortResult> openPorts) {
        if (openPorts.isEmpty()) {
            System.out.println("未找到打开的端口。");
            return;
        }

        System.out.println("\n扫描结果：");
        System.out.println("-".repeat(50));
        for (PortResult result : openPorts) {
            if ("未知".equals(result.getService())) {
                System.out.println("端口 " + result.getPort() + ": 未知（此端口可能用于自定义或不常见的服务，建议进一步调查。）");
            } else {
                System.out.println("端口 " + result.getPort() + ": " + result.getService());
            }
        }
        System.out.println("-".repeat(50));
    }

    /**
     * 命令行版本的主函数
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java PortScanner <主机地址> [-s 起始端口] [-e 结束端口] [-t 线程数]");
            System.out.println("示例: java PortScanner www.163.com -s 1 -e 1024 -t 10");
            return;
        }

        String host = args[0];
        int startPort = 1;
        int endPort = 1024;
        int maxThreads = 10;

        // 简单的参数解析
        for (int i = 1; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                switch (args[i]) {
                    case "-s":
                    case "--start-port":
                        startPort = Integer.parseInt(args[i + 1]);
                        break;
                    case "-e":
                    case "--end-port":
                        endPort = Integer.parseInt(args[i + 1]);
                        break;
                    case "-t":
                    case "--threads":
                        maxThreads = Integer.parseInt(args[i + 1]);
                        break;
                }
            }
        }

        System.out.println("正在扫描 " + host + " 的端口范围 " + startPort + " 到 " + endPort + "，使用 " + maxThreads + " 个线程...");

        PortScanner scanner = new PortScanner(host, startPort, endPort, maxThreads);
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
            public void onPortFoundDetailed(PortResult result) {
                System.out.printf("\n端口 %d (%s) %s - %s\n", 
                    result.getPort(), 
                    result.getProtocol().getDisplayName(),
                    result.getState(),
                    result.getService());
            }

            @Override
            public void onComplete(List<PortResult> openPorts) {
                System.out.println("\n扫描完成。");
                displayResults(openPorts);
                saveToFile(openPorts, "port_scan_results.txt");
                System.out.println("结果已保存到 port_scan_results.txt");
            }

            @Override
            public void onError(String error) {
                System.err.println("错误: " + error);
            }
        });

        scanner.scanPorts();
    }
}

