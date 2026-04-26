using System;
using System.Drawing;
using System.Windows;
using System.Windows.Controls;
using Hardcodet.Wpf.TaskbarNotification;

namespace ClipSync.WPF.SystemTray
{
    public class TrayIcon : IDisposable
    {
        private readonly Core.SettingsManager _settingsManager;
        private readonly Core.SyncEngine _syncEngine;
        private TaskbarIcon? _taskbarIcon;
        private Window? _mainWindow;
        private bool _isDisposed;

        public TrayIcon(Core.SettingsManager settingsManager, Core.SyncEngine syncEngine)
        {
            _settingsManager = settingsManager;
            _syncEngine = syncEngine;
        }

        public void SetMainWindow(Window mainWindow)
        {
            _mainWindow = mainWindow;
        }

        public void Initialize()
        {
            _taskbarIcon = new TaskbarIcon
            {
                Icon = CreateDefaultIcon(),
                ToolTipText = "ClipSync - Clipboard Sync",
                Visibility = Visibility.Visible,
                MenuActivation = PopupActivationMode.RightClick
            };

            var menu = new ContextMenu();

            var showItem = new MenuItem { Header = "Show" };
            showItem.Click += (s, e) => ShowWindow();
            menu.Items.Add(showItem);

            var hideItem = new MenuItem { Header = "Hide" };
            hideItem.Click += (s, e) => HideWindow();
            menu.Items.Add(hideItem);

            menu.Items.Add(new Separator());

            var exitItem = new MenuItem { Header = "Exit" };
            exitItem.Click += async (s, e) => await ExitApplication();
            menu.Items.Add(exitItem);

            _taskbarIcon.ContextMenu = menu;

            _taskbarIcon.DoubleClickCommand = new RelayCommand(ShowWindow);
        }

        private void ShowWindow()
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                if (_mainWindow != null)
                {
                    _mainWindow.Show();
                    _mainWindow.WindowState = WindowState.Normal;
                    _mainWindow.Activate();
                }
            });
        }

        private void HideWindow()
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                _mainWindow?.Hide();
            });
        }

        private async System.Threading.Tasks.Task ExitApplication()
        {
            await _syncEngine.StopAsync();
            _syncEngine.Dispose();
            Application.Current.Shutdown();
        }

        private Icon CreateDefaultIcon()
        {
            using var bitmap = new Bitmap(16, 16);
            using var graphics = System.Drawing.Graphics.FromImage(bitmap);
            graphics.Clear(Color.FromArgb(0, 120, 212));

            using var brush = new SolidBrush(Color.White);
            var font = new System.Drawing.Font("Arial", 10, System.Drawing.FontStyle.Bold);
            graphics.DrawString("C", font, brush, 2, 1);

            var iconHandle = bitmap.GetHicon();
            return Icon.FromHandle(iconHandle);
        }

        public void Dispose()
        {
            if (_isDisposed) return;
            _isDisposed = true;
            _taskbarIcon?.Dispose();
        }
    }
}
