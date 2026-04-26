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
                System.Diagnostics.Debug.WriteLine($"[App] Unhandled exception: {args.Exception.Message}");
                args.Handled = true;
            };

            AppDomain.CurrentDomain.UnhandledException += (sender, args) =>
            {
                var ex = args.ExceptionObject as Exception;
                System.Diagnostics.Debug.WriteLine($"[App] Domain exception: {ex?.Message}");
            };

            System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (sender, args) =>
            {
                System.Diagnostics.Debug.WriteLine($"[App] Unobserved task exception: {args.Exception.Message}");
                args.SetObserved();
            };

            _settingsManager = new Core.SettingsManager();
            await _settingsManager.LoadAsync();

            _syncEngine = new Core.SyncEngine(_settingsManager);

            _trayIcon = new SystemTray.TrayIcon(_settingsManager, _syncEngine);
            _trayIcon.Initialize();

            var mainWindow = new MainWindow(_settingsManager, _syncEngine, _trayIcon);
            mainWindow.Show();

            if (_settingsManager.Settings.MinimizeToTray)
            {
                mainWindow.Hide();
            }

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
