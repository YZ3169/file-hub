using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace FileHubShell
{
    internal static class Program
    {
        internal const string WindowTitle = "FileHub";
        internal const int AppPort = 18080;
        internal static readonly string AppUrl = "http://127.0.0.1:" + AppPort;

        private static Mutex instanceMutex;

        [STAThread]
        private static void Main()
        {
            bool createdNew;
            instanceMutex = new Mutex(true, "Local\\FileHubShell_SingleInstance", out createdNew);
            if (!createdNew)
            {
                ActivateExistingWindow();
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            Application.ThreadException += delegate(object sender, ThreadExceptionEventArgs e)
            {
                MainForm.Log("ui exception: " + e.Exception);
            };
            AppDomain.CurrentDomain.UnhandledException += delegate(object sender, UnhandledExceptionEventArgs e)
            {
                MainForm.Log("unhandled exception: " + e.ExceptionObject);
            };
            Application.Run(new MainForm());
            GC.KeepAlive(instanceMutex);
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        private static void ActivateExistingWindow()
        {
            IntPtr handle = FindWindow(null, WindowTitle);
            if (handle != IntPtr.Zero)
            {
                ShowWindow(handle, 9); // SW_RESTORE
                SetForegroundWindow(handle);
            }
        }
    }

    internal sealed class MainForm : Form
    {
        private readonly WebView2 webView;
        private readonly Label loadingLabel;
        private NotifyIcon trayIcon;
        private Process backend;
        private bool ownBackend;
        private bool exiting;
        private bool webReady;
        private string dataDir;

        public MainForm()
        {
            Text = Program.WindowTitle;
            StartPosition = FormStartPosition.CenterScreen;

            Rectangle workArea = Screen.PrimaryScreen.WorkingArea;
            Width = Math.Min(1280, workArea.Width - 80);
            Height = Math.Min(860, workArea.Height - 80);
            MinimumSize = new Size(900, 600);
            BackColor = Color.FromArgb(23, 23, 31);
            Icon = LoadAppIcon();

            loadingLabel = new Label();
            loadingLabel.Text = "正在启动 FileHub...";
            loadingLabel.ForeColor = Color.White;
            loadingLabel.BackColor = Color.Transparent;
            loadingLabel.Dock = DockStyle.Fill;
            loadingLabel.TextAlign = ContentAlignment.MiddleCenter;
            loadingLabel.Font = new Font("Microsoft YaHei UI", 12F);
            Controls.Add(loadingLabel);

            webView = new WebView2();
            webView.Dock = DockStyle.Fill;
            webView.Visible = false;
            Controls.Add(webView);

            CreateTrayIcon();

            Shown += OnShown;
            FormClosing += OnFormClosing;
            FormClosed += OnFormClosed;
        }

        private Icon LoadAppIcon()
        {
            try
            {
                string backendExe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "FileHubBackend.exe");
                Icon icon = File.Exists(backendExe) ? Icon.ExtractAssociatedIcon(backendExe) : null;
                if (icon == null)
                {
                    icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
                }
                return icon != null ? icon : SystemIcons.Application;
            }
            catch (Exception)
            {
                return SystemIcons.Application;
            }
        }

        private void CreateTrayIcon()
        {
            trayIcon = new NotifyIcon();
            trayIcon.Icon = Icon;
            trayIcon.Text = "FileHub";
            trayIcon.Visible = true;

            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("打开 FileHub", null, delegate { RestoreFromTray(); });
            menu.Items.Add("打开数据目录", null, delegate { OpenDataDir(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出", null, delegate { ExitApplication(); });
            trayIcon.ContextMenuStrip = menu;
            trayIcon.DoubleClick += delegate { RestoreFromTray(); };
        }

        private async void OnShown(object sender, EventArgs e)
        {
            try
            {
                Log("shell starting, url=" + Program.AppUrl);
                dataDir = GetDataDir();
                Directory.CreateDirectory(dataDir);
                string webviewDir = Path.Combine(dataDir, "webview2");
                Directory.CreateDirectory(webviewDir);

                if (!IsServerUp())
                {
                    StartBackend();
                }

                string waitError = await WaitForServerAsync(TimeSpan.FromSeconds(90));
                if (waitError != null)
                {
                    ShowStartupError(waitError);
                    return;
                }

                try
                {
                    await InitWebViewAsync(webviewDir);
                }
                catch (WebView2RuntimeNotFoundException)
                {
                    ShowStartupError("未找到 Microsoft Edge WebView2 运行时。"
                            + "Windows 11 一般自带；精简版系统或部分 Windows 10 需要先安装：\n"
                            + "https://developer.microsoft.com/microsoft-edge/webview2/");
                    return;
                }
                webReady = true;
                webView.Visible = true;
                webView.BringToFront();
                loadingLabel.Visible = false;
                Log("shell ready");
            }
            catch (Exception ex)
            {
                Log("startup failed: " + ex);
                ShowStartupError(ex.Message);
            }
        }

        private async Task InitWebViewAsync(string userDataFolder)
        {
            CoreWebView2Environment environment =
                    await CoreWebView2Environment.CreateAsync(null, userDataFolder, null);
            await webView.EnsureCoreWebView2Async(environment);

            CoreWebView2 core = webView.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = true;
            core.Settings.IsZoomControlEnabled = true;

            core.NavigationStarting += OnNavigationStarting;
            core.NewWindowRequested += OnNewWindowRequested;
            core.Navigate(Program.AppUrl);
        }

        private void OnNavigationStarting(object sender, CoreWebView2NavigationStartingEventArgs e)
        {
            if (IsAppUri(e.Uri))
            {
                return;
            }
            e.Cancel = true;
            OpenExternal(e.Uri);
        }

        private void OnNewWindowRequested(object sender, CoreWebView2NewWindowRequestedEventArgs e)
        {
            e.Handled = true;
            if (IsAppUri(e.Uri))
            {
                webView.CoreWebView2.Navigate(e.Uri);
            }
            else
            {
                OpenExternal(e.Uri);
            }
        }

        private static bool IsAppUri(string uri)
        {
            if (uri == null)
            {
                return false;
            }
            if (uri.StartsWith("about:blank", StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
            // 只放行本机固定端口，避免 http://127.0.0.1:18080@evil.com 这类前缀绕过
            try
            {
                Uri parsed = new Uri(uri);
                return parsed.Scheme == Uri.UriSchemeHttp
                        && parsed.Host == "127.0.0.1"
                        && parsed.Port == Program.AppPort
                        && string.IsNullOrEmpty(parsed.UserInfo);
            }
            catch (UriFormatException)
            {
                return false;
            }
        }

        private void StartBackend()
        {
            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            string backendExe = Path.Combine(baseDir, "FileHubBackend.exe");
            if (!File.Exists(backendExe))
            {
                backendExe = Path.Combine(baseDir, "FileHub.exe");
            }
            if (string.Equals(Path.GetFullPath(backendExe),
                    Path.GetFullPath(Application.ExecutablePath),
                    StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException("找不到后台程序 FileHubBackend.exe。");
            }

            ProcessStartInfo info = new ProcessStartInfo(backendExe);
            info.WorkingDirectory = baseDir;
            info.UseShellExecute = false;
            info.CreateNoWindow = true;
            info.RedirectStandardInput = true;
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            info.EnvironmentVariables["FILE_HUB_DESKTOP_SHELL"] = "1";

            backend = new Process();
            backend.StartInfo = info;
            backend.EnableRaisingEvents = true;
            backend.Exited += OnBackendExited;
            backend.OutputDataReceived += delegate { };
            backend.ErrorDataReceived += delegate { };
            backend.Start();
            backend.BeginOutputReadLine();
            backend.BeginErrorReadLine();
            ownBackend = true;
            Log("backend started, pid=" + backend.Id);
        }

        private void OnBackendExited(object sender, EventArgs e)
        {
            if (exiting || !webReady || IsDisposed)
            {
                return;
            }
            try
            {
                BeginInvoke(new Action(delegate
                {
                    if (!exiting)
                    {
                        ShowStartupError("后台服务意外退出。");
                    }
                }));
            }
            catch (Exception)
            {
                // 窗口已经开始销毁，忽略
            }
        }

        // 服务就绪返回 null；失败返回给用户看的错误说明。
        private async Task<string> WaitForServerAsync(TimeSpan timeout)
        {
            DateTime deadline = DateTime.UtcNow.Add(timeout);
            while (DateTime.UtcNow < deadline)
            {
                if (exiting)
                {
                    return "窗口已关闭。";
                }
                if (IsServerUp())
                {
                    return null;
                }
                if (ownBackend && backend != null && backend.HasExited)
                {
                    int code = backend.ExitCode;
                    Log("backend exited during startup, code=" + code);
                    return "后台服务启动失败（进程已退出，退出码 " + code + "），请查看应用日志。";
                }
                await Task.Delay(300);
            }
            return "后台服务没有在 90 秒内就绪，请查看应用日志。";
        }

        private static bool IsServerUp()
        {
            try
            {
                HttpWebRequest request = (HttpWebRequest)WebRequest.Create(Program.AppUrl + "/actuator/health");
                request.Proxy = null;
                request.Timeout = 1200;
                request.ReadWriteTimeout = 1200;
                request.KeepAlive = false;
                using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
                {
                    return (int)response.StatusCode == 200;
                }
            }
            catch (Exception)
            {
                return false;
            }
        }

        private void OnFormClosing(object sender, FormClosingEventArgs e)
        {
            Log("form closing, reason=" + e.CloseReason + ", exiting=" + exiting);
            if (exiting)
            {
                return;
            }
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                CloseChoice choice = ClosePrompt.Show(this);
                Log("close choice=" + choice);
                if (choice == CloseChoice.MinimizeToTray)
                {
                    MinimizeToTray();
                }
                else if (choice == CloseChoice.Exit)
                {
                    ExitApplication();
                }
                return;
            }

            // 注销/关机等系统关闭：不弹窗，直接清理
            exiting = true;
            StopBackend();
        }

        private void MinimizeToTray()
        {
            Hide();
            if (trayIcon != null)
            {
                trayIcon.ShowBalloonTip(2500, "FileHub", "已最小化到系统托盘，双击图标可重新打开。",
                        ToolTipIcon.Info);
            }
        }

        private void RestoreFromTray()
        {
            Show();
            if (WindowState == FormWindowState.Minimized)
            {
                WindowState = FormWindowState.Normal;
            }
            Activate();
            BringToFront();
        }

        private void OpenDataDir()
        {
            try
            {
                string dir = dataDir != null ? dataDir : GetDataDir();
                Directory.CreateDirectory(dir);
                Process.Start("explorer.exe", "\"" + dir + "\"");
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "无法打开数据目录：" + ex.Message, "FileHub",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private void ExitApplication()
        {
            if (exiting)
            {
                return;
            }
            exiting = true;
            if (trayIcon != null)
            {
                trayIcon.Visible = false;
                trayIcon.Dispose();
                trayIcon = null;
            }
            StopBackend();
            Close();
            Application.Exit();
        }

        private void StopBackend()
        {
            if (!ownBackend || backend == null)
            {
                return;
            }
            try
            {
                if (!backend.HasExited && backend.StandardInput != null)
                {
                    // Java 侧读到 EOF 会优雅关闭 Spring，再退出进程
                    backend.StandardInput.Close();
                    Log("backend stdin closed, waiting for graceful exit");
                }
                if (!backend.WaitForExit(12000))
                {
                    Log("backend did not exit in 12s, killing");
                    backend.Kill();
                    backend.WaitForExit(3000);
                }
                Log("backend exited, code=" + backend.ExitCode);
            }
            catch (Exception ex)
            {
                Log("stop backend failed: " + ex);
            }
        }

        private void OnFormClosed(object sender, FormClosedEventArgs e)
        {
            Log("form closed, reason=" + e.CloseReason + ", exiting=" + exiting);
            if (!exiting)
            {
                exiting = true;
                StopBackend();
            }
            if (trayIcon != null)
            {
                trayIcon.Visible = false;
                trayIcon.Dispose();
                trayIcon = null;
            }
            try
            {
                webView.Dispose();
            }
            catch (Exception)
            {
                // 忽略销毁异常
            }
        }

        private void ShowStartupError(string detail)
        {
            if (exiting)
            {
                return;
            }
            string logPath = Path.Combine(dataDir != null ? dataDir : GetDataDir(), "logs", "filehub.log");
            Log("startup error: " + detail);
            MessageBox.Show(this,
                    detail + "\n\n应用日志：" + logPath
                            + "\n外壳日志：" + Path.Combine(GetDataDir(), "logs", "shell.log"),
                    "FileHub 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            ExitApplication();
        }

        private static string GetDataDir()
        {
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            return Path.Combine(local, "FileHubData");
        }

        private static void OpenExternal(string uri)
        {
            if (string.IsNullOrEmpty(uri))
            {
                return;
            }
            // 只把网页/邮件链接交给默认浏览器，不触发 file:、shell: 等协议处理器
            if (!uri.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                    && !uri.StartsWith("https://", StringComparison.OrdinalIgnoreCase)
                    && !uri.StartsWith("mailto:", StringComparison.OrdinalIgnoreCase))
            {
                Log("blocked external uri: " + uri);
                return;
            }
            try
            {
                Process.Start(uri);
            }
            catch (Exception)
            {
                // 没有可用的外部程序时忽略
            }
        }

        internal static void Log(string message)
        {
            try
            {
                string dir = Path.Combine(GetDataDir(), "logs");
                Directory.CreateDirectory(dir);
                File.AppendAllText(Path.Combine(dir, "shell.log"),
                        DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + Environment.NewLine);
            }
            catch (Exception)
            {
                // 日志失败不影响主流程
            }
        }
    }

    internal enum CloseChoice
    {
        Cancel,
        MinimizeToTray,
        Exit
    }

    internal sealed class ClosePrompt : Form
    {
        private CloseChoice choice = CloseChoice.Cancel;

        private ClosePrompt()
        {
            Text = "关闭 FileHub";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            ClientSize = new Size(500, 160);

            Label label = new Label();
            label.Text = "要最小化到系统托盘继续运行，还是直接退出 FileHub？";
            label.SetBounds(20, 22, 460, 44);
            Controls.Add(label);

            Button trayButton = new Button();
            trayButton.Text = "最小化到托盘";
            trayButton.SetBounds(30, 92, 160, 34);
            trayButton.Click += delegate
            {
                choice = CloseChoice.MinimizeToTray;
                Close();
            };
            Controls.Add(trayButton);

            Button exitButton = new Button();
            exitButton.Text = "直接退出";
            exitButton.SetBounds(210, 92, 110, 34);
            exitButton.Click += delegate
            {
                choice = CloseChoice.Exit;
                Close();
            };
            Controls.Add(exitButton);

            Button cancelButton = new Button();
            cancelButton.Text = "取消";
            cancelButton.SetBounds(340, 92, 110, 34);
            cancelButton.Click += delegate
            {
                choice = CloseChoice.Cancel;
                Close();
            };
            Controls.Add(cancelButton);

            AcceptButton = trayButton;
            CancelButton = cancelButton;
        }

        internal static CloseChoice Show(Form owner)
        {
            using (ClosePrompt prompt = new ClosePrompt())
            {
                prompt.ShowDialog(owner);
                return prompt.choice;
            }
        }
    }
}
