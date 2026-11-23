package com.yuzhuo.filehub.desktop;

import com.yuzhuo.filehub.FileHubApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Windows 桌面版入口。
 *
 * <p>只在 {@code -Dfilehub.desktop=true} 时启用（由 jpackage 生成的启动器传入）。
 * 直接 {@code java -jar} 时仍然走原来的服务器启动流程，Docker/MySQL 部署完全不变。</p>
 *
 * <p>职责：<br>
 * 1. 把数据目录固定到 {@code %LOCALAPPDATA%\FileHubData}，在 Spring 初始化前设置系统属性；<br>
 * 2. 单实例：用文件锁阻止同一数据目录启动第二个 JVM；<br>
 * 3. 启动内嵌 Tomcat（desktop profile，127.0.0.1:18080）；<br>
 * 4. 默认自动打开浏览器，并放一个托盘图标用于退出——jpackage 窗口没有控制台，
 *    没有托盘的话用户关不掉后台进程；<br>
 * 5. 启动失败时弹窗给出日志路径，而不是静默消失。</p>
 *
 * <p><b>外壳模式</b>（WebView2 外壳启动时设置 {@code FILE_HUB_DESKTOP_SHELL=1}）：
 * 不打开浏览器、不创建托盘，窗口和托盘都由外壳负责；外壳关闭 stdin 时本进程收到 EOF，
 * 优雅关闭 Spring 后退出。单独运行 FileHub.exe 时仍是原来的浏览器行为。</p>
 */
public final class DesktopLauncher {

    private static final int PORT = 18080;
    private static final String APP_URL = "http://127.0.0.1:" + PORT;
    private static final boolean SHELL_MODE =
            Boolean.getBoolean("filehub.desktop.shell")
                    || "1".equals(System.getenv("FILE_HUB_DESKTOP_SHELL"));

    private static ConfigurableApplicationContext context;
    private static FileChannel lockChannel;
    private static FileLock instanceLock;
    private static TrayIcon trayIcon;

    private DesktopLauncher() {
    }

    public static void run(String[] args) {
        Path dataDir = resolveDataDir();
        try {
            Files.createDirectories(dataDir.resolve("db"));
            Files.createDirectories(dataDir.resolve("storage"));
            Files.createDirectories(dataDir.resolve("logs"));
        } catch (IOException e) {
            showError("无法创建数据目录：" + dataDir + "\n" + e.getMessage());
            System.exit(1);
            return;
        }

        // Spring 的 ${filehub.data.dir} 与 logback 都从系统属性读；统一用 / 避免 JDBC URL 里的反斜杠。
        System.setProperty("filehub.data.dir", dataDir.toString().replace('\\', '/'));
        System.setProperty("filehub.desktop", "true");

        if (!acquireInstanceLock(dataDir)) {
            // 已有实例：外壳模式由外壳负责显示窗口，这里什么都不做直接退出；
            // 单独运行时等它把端口起好再开浏览器。
            if (!SHELL_MODE) {
                waitForServer(Duration.ofSeconds(15));
                openBrowser();
            }
            return;
        }

        try {
            context = new SpringApplicationBuilder(FileHubApplication.class)
                    .profiles("desktop")
                    .bannerMode(Banner.Mode.OFF)
                    .headless(false)
                    .run(args);
        } catch (Throwable t) {
            releaseInstanceLock();
            showError("FileHub 启动失败。\n" + rootMessage(t)
                    + "\n\n数据目录：" + dataDir
                    + "\n日志：" + dataDir.resolve("logs/filehub.log"));
            // AWT 的事件线程不是守护线程；不显式退出的话弹窗关掉后进程会挂在后台。
            System.exit(1);
            return;
        }

        if (SHELL_MODE) {
            watchStdinForExit();
        } else {
            openBrowser();
            installTrayOrWindow(dataDir);
        }
    }

    /**
     * 外壳模式：外壳持有子进程的 stdin 管道，关闭窗口时关掉管道。
     * 这里读到 EOF 就优雅关闭 Spring；外壳被强杀时管道同样会断开，不会留下孤儿 Java 进程。
     */
    private static void watchStdinForExit() {
        Thread watcher = new Thread(() -> {
            try {
                while (System.in.read() != -1) {
                    // 忽略输入，只等 EOF
                }
            } catch (IOException ignored) {
                // 管道异常也按外壳已退出处理
            }
            shutdown();
        }, "desktop-shell-stdin");
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * 数据目录：允许 -Dfilehub.data.dir 覆盖（测试/绿色版用），默认 %LOCALAPPDATA%\FileHubData。
     *
     * <p>刻意不放在安装目录 %LOCALAPPDATA%\FileHub 下：MSI 卸载会连同安装目录一起删，
     * 实测过一次，用户上传的文件会跟着没。数据放在同级的独立目录，卸载只删程序。</p>
     */
    private static Path resolveDataDir() {
        String override = System.getProperty("filehub.data.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            localAppData = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        }
        return Path.of(localAppData, "FileHubData").toAbsolutePath().normalize();
    }

    /** 对数据目录里的锁文件加独占锁；返回 false 表示已有实例在跑。 */
    private static boolean acquireInstanceLock(Path dataDir) {
        try {
            lockChannel = FileChannel.open(dataDir.resolve("filehub.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock();
            if (instanceLock == null) {
                lockChannel.close();
                lockChannel = null;
                return false;
            }
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            // 锁文件不可写时宁可照常启动，也不要让用户完全用不了。
            System.err.println("创建单实例锁失败，继续启动：" + e.getMessage());
            return true;
        }
    }

    private static void releaseInstanceLock() {
        try {
            if (instanceLock != null) {
                instanceLock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
            // 进程退出时操作系统也会释放
        }
    }

    private static boolean waitForServer(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(APP_URL + "/actuator/health").openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                // 200/401 都说明端口已经有服务，可以打开浏览器了
                conn.getResponseCode();
                conn.disconnect();
                return true;
            } catch (IOException e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static void openBrowser() {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            System.err.println("当前环境不支持打开浏览器，请手动访问 " + APP_URL);
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(APP_URL));
        } catch (IOException | RuntimeException e) {
            System.err.println("打开浏览器失败：" + e.getMessage());
        }
    }

    private static void openDataDir(Path dataDir) {
        try {
            Desktop.getDesktop().open(dataDir.toFile());
        } catch (IOException | RuntimeException e) {
            showError("无法打开数据目录：" + dataDir + "\n" + e.getMessage());
        }
    }

    /** 优先用系统托盘；托盘不可用（极少数远程桌面环境）时退化为一个小控制窗口。 */
    private static void installTrayOrWindow(Path dataDir) {
        try {
            if (!SystemTray.isSupported()) {
                showControlWindow(dataDir);
                return;
            }
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("打开 FileHub");
            open.addActionListener(e -> openBrowser());
            MenuItem data = new MenuItem("打开数据目录");
            data.addActionListener(e -> openDataDir(dataDir));
            MenuItem exit = new MenuItem("退出");
            exit.addActionListener(e -> shutdown());
            menu.add(open);
            menu.add(data);
            menu.addSeparator();
            menu.add(exit);

            trayIcon = new TrayIcon(createTrayImage(), "FileHub - " + APP_URL, menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> openBrowser());
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException | RuntimeException e) {
            System.err.println("创建系统托盘失败，改用控制窗口：" + e.getMessage());
            showControlWindow(dataDir);
        }
    }

    private static void showControlWindow(Path dataDir) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("FileHub");
            frame.setIconImage(createTrayImage());
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    shutdown();
                }
            });
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            JLabel label = new JLabel("FileHub 正在运行：" + APP_URL);
            JPanel buttons = new JPanel();
            JButton open = new JButton("打开界面");
            open.addActionListener(e -> openBrowser());
            JButton data = new JButton("数据目录");
            data.addActionListener(e -> openDataDir(dataDir));
            JButton exit = new JButton("退出");
            exit.addActionListener(e -> shutdown());
            buttons.add(open);
            buttons.add(data);
            buttons.add(exit);
            panel.add(label, BorderLayout.CENTER);
            panel.add(buttons, BorderLayout.SOUTH);
            frame.setContentPane(panel);
            frame.setSize(420, 140);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static Image createTrayImage() {
        try (InputStream resource = DesktopLauncher.class.getResourceAsStream("/static/app-icon.png")) {
            if (resource != null) {
                BufferedImage appIcon = ImageIO.read(resource);
                if (appIcon != null) {
                    return appIcon;
                }
            }
        } catch (IOException e) {
            System.err.println("读取应用图标失败，使用备用图标：" + e.getMessage());
        }

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new java.awt.Color(0x2F, 0x6F, 0xED));
            g.fillRoundRect(0, 0, 16, 16, 5, 5);
            g.setColor(java.awt.Color.WHITE);
            // 资源缺失或损坏时保留可辨识的备用图标。
            g.fillRect(3, 6, 10, 7);
            g.fillRect(3, 4, 5, 3);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void shutdown() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
        if (context != null) {
            try {
                context.close();
            } catch (RuntimeException e) {
                System.err.println("关闭 Spring 上下文失败：" + e.getMessage());
            }
            context = null;
        }
        releaseInstanceLock();
        System.exit(0);
    }

    private static void showError(String message) {
        System.err.println(message);
        // 外壳模式由外壳统一弹窗/报错，避免后台再冒一个没人管的对话框。
        if (SHELL_MODE || GraphicsEnvironment.isHeadless()) {
            return;
        }
        Runnable dialog = () -> JOptionPane.showMessageDialog(null, message,
                "FileHub", JOptionPane.ERROR_MESSAGE);
        try {
            // 同步等用户点掉弹窗：调用方随后要 System.exit，异步弹窗会被一起杀掉。
            if (SwingUtilities.isEventDispatchThread()) {
                dialog.run();
            } else {
                SwingUtilities.invokeAndWait(dialog);
            }
        } catch (Exception e) {
            System.err.println("显示错误弹窗失败：" + e.getMessage());
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
