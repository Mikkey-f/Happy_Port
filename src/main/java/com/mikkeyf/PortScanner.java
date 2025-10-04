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
    private volatile boolean isCancelled = false;

    // 回调接口，用于更新进度和结果
    private ScanCallback callback;

    public interface ScanCallback {
        void onProgress(int currentPort, double percentage);
        void onPortFound(int port, String service);
        void onComplete(List<PortResult> openPorts);
        void onError(String error);
    }

    /**
     * 端口扫描结果类
     */
    public static class PortResult {
        private final int port;
        private final String service;

        public PortResult(int port, String service) {
            this.port = port;
            this.service = service;
        }

        public int getPort() {
            return port;
        }

        public String getService() {
            return service;
        }

        @Override
        public String toString() {
            return "端口 " + port + ": " + service;
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

    public void cancel() {
        this.isCancelled = true;
    }

    /**
     * 扫描单个端口
     */
    private PortResult scanPort(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500); // 500ms 超时

            // 获取端口服务名称
            String service = getServiceName(port);
            return new PortResult(port, service);

        } catch (IOException e) {
            return null; // 端口关闭
        }
        // 忽略关闭异常
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
        int totalPorts = endPort - startPort + 1;

        ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
        Map<Future<PortResult>, Integer> futures = new ConcurrentHashMap<>();

        try {
            // 提交所有扫描任务
            for (int port = startPort; port <= endPort; port++) {
                if (isCancelled) {
                    break;
                }
                final int currentPort = port;
                Future<PortResult> future = executor.submit(() -> scanPort(host, currentPort));
                futures.put(future, currentPort);
            }

            // 处理完成的任务
            int scannedPorts = 0;
            for (Map.Entry<Future<PortResult>, Integer> entry : futures.entrySet()) {
                if (isCancelled) {
                    break;
                }

                try {
                    Future<PortResult> future = entry.getKey();
                    Integer port = entry.getValue();

                    PortResult result = future.get(); // 阻塞等待结果
                    scannedPorts++;

                    double progress = (scannedPorts * 100.0) / totalPorts;

                    // 回调更新进度
                    if (callback != null) {
                        callback.onProgress(port, progress);
                    }

                    // 如果端口开放
                    if (result != null) {
                        openPorts.add(result);
                        if (callback != null) {
                            callback.onPortFound(result.getPort(), result.getService());
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

