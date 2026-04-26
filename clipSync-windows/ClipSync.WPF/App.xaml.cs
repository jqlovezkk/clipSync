using System;
using System.Windows;

namespace ClipSync.WPF
{
    public partial class App : Application
    {
        private Core.SettingsManager? _settingsManager;
        private Core.SyncEngine? _syncEngine;
        private SystemTray.TrayIcon? _trayIcon;

        protected override async void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            // Global exception handlers to prevent crashes
            DispatcherUnhandledException += (sender, args) =>
            {
                Core.AppLogger.Error("App", "发生未处理的 UI 线程异常", args.Exception);
                args.Handled = true;
            };

            AppDomain.CurrentDomain.UnhandledException += (sender, args) =>
            {
                var ex = args.ExceptionObject as Exception;
                Core.AppLogger.Error("App", "发生未处理的应用程序域异常", ex);
            };

            System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (sender, args) =>
            {
                Core.AppLogger.Error("App", "发生未观察到的任务异常", args.Exception);
                args.SetObserved();
            };

            _settingsManager = new Core.SettingsManager();
            await _settingsManager.LoadAsync();

            _syncEngine = new Core.SyncEngine(_settingsManager);

            _trayIcon = new SystemTray.TrayIcon(_settingsManager, _syncEngine);
            _trayIcon.Initialize();

            var mainWindow = new MainWindow(_settingsManager, _syncEngine, _trayIcon);
            mainWindow.Show();

            await _syncEngine.StartAsync();
        }

        protected override async void OnExit(ExitEventArgs e)
        {
            if (_syncEngine != null)
            {
                await _syncEngine.StopAsync();
                _syncEngine.Dispose();
            }
            _trayIcon?.Dispose();
            base.OnExit(e);
        }
    }
}
