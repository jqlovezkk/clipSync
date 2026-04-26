using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using ClipSync.WPF.Core;
using ClipSync.WPF.UI.Views;
using ClipSync.WPF.UI.ViewModels;

namespace ClipSync.WPF
{
    public partial class MainWindow : Window
    {
        private readonly SettingsManager _settingsManager;
        private readonly SyncEngine _syncEngine;
        private readonly SystemTray.TrayIcon _trayIcon;
        private LoginView? _loginView;
        private HistoryView? _historyView;
        private SettingsView? _settingsView;
        private bool _isLoggedIn;

        public MainWindow(SettingsManager settingsManager, SyncEngine syncEngine, SystemTray.TrayIcon trayIcon)
        {
            _settingsManager = settingsManager;
            _syncEngine = syncEngine;
            _trayIcon = trayIcon;

            InitializeComponent();

            _trayIcon.SetMainWindow(this);

            MainTabs.SelectionChanged += OnTabChanged;
            // RefreshDevicesButton.Click += async (s, e) => await _syncEngine.RequestDeviceListAsync(); // Removed - button deleted from XAML
            ErrorBanner.MouseLeftButtonUp += (s, e) => ErrorBanner.Visibility = Visibility.Collapsed;

            SetupLoginView();
            SetupHistoryView();
            SetupSettingsView();

            _syncEngine.ConnectionStateChanged += OnConnectionStateChanged;
            _syncEngine.ErrorOccurred += OnErrorOccurred;
            _syncEngine.DeviceListUpdated += OnDeviceListUpdated;
            _syncEngine.ClipboardItemReceived += OnClipboardItemReceived;

            if (!string.IsNullOrEmpty(_settingsManager.Settings.Token))
            {
                ShowMainContent();
            }
        }

        private void SetupLoginView()
        {
            _loginView = new LoginView();
            _loginView.SetServerUrls(_settingsManager.Settings.ServerUrl, _settingsManager.Settings.HttpUrl);
            _loginView.LoginRequested += OnLoginRequested;
            _loginView.RegisterRequested += OnRegisterRequested;
            LoginViewContainer.Content = _loginView;
        }

        private void SetupHistoryView()
        {
            _historyView = new HistoryView();
            _historyView.RefreshRequested += async () => await LoadHistoryAsync();
            _historyView.CopyRequested += (item) =>
            {
                try
                {
                    if (item.ContentType == "text")
                    {
                        Clipboard.SetText(item.Content);
                    }
                    else if (item.ContentType == "image")
                    {
                        var imageBytes = Convert.FromBase64String(item.Content);
                        var bitmap = new System.Windows.Media.Imaging.BitmapImage();
                        using (var ms = new System.IO.MemoryStream(imageBytes))
                        {
                            bitmap.BeginInit();
                            bitmap.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
                            bitmap.StreamSource = ms;
                            bitmap.EndInit();
                        }
                        bitmap.Freeze();
                        Clipboard.SetImage(bitmap);
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"[MainWindow] Copy error: {ex.Message}");
                }
            };
        }

        private void SetupSettingsView()
        {
            _settingsView = new SettingsView();
            _settingsView.SettingsSaved += OnSettingsSaved;
            _settingsView.LogoutRequested += async () => await OnLogoutAsync();
            LoadSettingsView();
        }

        private void LoadSettingsView()
        {
            var s = _settingsManager.Settings;
            _settingsView?.LoadSettings(
                s.ServerUrl, s.HttpUrl, s.AutoStart, s.SyncEnabled,
                s.EncryptionEnabled, s.MinimizeToTray, s.DeviceName, s.Username);
        }

        private async void OnLoginRequested(string serverUrl, string httpUrl, string username, string password)
        {
            _settingsManager.Update(s =>
            {
                s.ServerUrl = serverUrl;
                s.HttpUrl = httpUrl;
            });
            await _settingsManager.SaveAsync();

            await _syncEngine.LoginAsync(username, password);
        }

        private async void OnRegisterRequested(string serverUrl, string httpUrl, string username, string password)
        {
            _settingsManager.Update(s =>
            {
                s.ServerUrl = serverUrl;
                s.HttpUrl = httpUrl;
            });
            await _settingsManager.SaveAsync();

            await _syncEngine.RegisterAsync(username, password);
        }

        private void OnConnectionStateChanged(string status)
        {
            Dispatcher.Invoke(() =>
            {
                switch (status)
                {
                    case "connected":
                        StatusDot.Fill = FindResource("SuccessColor") as System.Windows.Media.Brush;
                        StatusText.Text = "Connected";
                        if (!_isLoggedIn)
                        {
                            ShowMainContent();
                        }
                        break;
                    case "disconnected":
                        StatusDot.Fill = FindResource("ErrorColor") as System.Windows.Media.Brush;
                        StatusText.Text = "Disconnected";
                        break;
                    case "connecting":
                        StatusDot.Fill = FindResource("WarningColor") as System.Windows.Media.Brush;
                        StatusText.Text = "Connecting...";
                        break;
                    case "auth_failed":
                        StatusDot.Fill = FindResource("ErrorColor") as System.Windows.Media.Brush;
                        StatusText.Text = "Auth Failed";
                        break;
                }
            });
        }

        private void OnErrorOccurred(string message)
        {
            Dispatcher.Invoke(() =>
            {
                ErrorText.Text = message;
                ErrorBanner.Visibility = Visibility.Visible;

                if (_loginView != null && !_isLoggedIn)
                {
                    _loginView.ShowError(message);
                }
            });
        }

        private void OnDeviceListUpdated(System.Collections.Generic.List<Network.Device> devices)
        {
            // Device list updated
        }

        private async void OnClipboardItemReceived(Network.ClipboardItem item)
        {
            await LoadHistoryAsync();
        }

        private void ShowMainContent()
        {
            _isLoggedIn = true;
            LoginViewContainer.Visibility = Visibility.Collapsed;
            MainContent.Visibility = Visibility.Visible;
            _ = LoadHistoryAsync();
        }

        private void ShowLoginContent()
        {
            _isLoggedIn = false;
            LoginViewContainer.Visibility = Visibility.Visible;
            MainContent.Visibility = Visibility.Collapsed;
        }

        private void MinimizeButton_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState.Minimized;
        }

        private void MaximizeButton_Click(object sender, RoutedEventArgs e)
        {
            if (WindowState == WindowState.Maximized)
                WindowState = WindowState.Normal;
            else
                WindowState = WindowState.Maximized;
        }

        private void CloseButton_Click(object sender, RoutedEventArgs e)
        {
            if (_settingsManager.Settings.MinimizeToTray)
            {
                Hide();
            }
            else
            {
                Application.Current.Shutdown();
            }
        }

        private async System.Threading.Tasks.Task LoadHistoryAsync()
        {
            var items = await _syncEngine.GetLocalHistoryAsync(50);
            _historyView?.SetItems(items);
        }

        private void OnTabChanged(object sender, SelectionChangedEventArgs e)
        {
            if (MainTabs.SelectedItem is TabItem tab)
            {
                switch (tab.Header)
                {
                    case "Home":
                        TabContent.Content = null;
                        // RefreshDevicesButton.Visibility = Visibility.Collapsed; // Removed
                        break;
                    case "History":
                        TabContent.Content = _historyView;
                        // RefreshDevicesButton.Visibility = Visibility.Collapsed; // Removed
                        _ = LoadHistoryAsync();
                        break;
                    case "Devices":
                        TabContent.Content = CreateDevicesView();
                        // RefreshDevicesButton.Visibility = Visibility.Visible; // Removed
                        _ = _syncEngine.RequestDeviceListAsync();
                        break;
                    case "Settings":
                        TabContent.Content = _settingsView;
                        // RefreshDevicesButton.Visibility = Visibility.Collapsed; // Removed
                        LoadSettingsView();
                        break;
                }
            }
        }

        private UserControl CreateDevicesView()
        {
            var view = new UserControl();
            var grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            var header = new TextBlock
            {
                Text = "Registered Devices",
                FontSize = 18,
                FontWeight = System.Windows.FontWeights.SemiBold,
                Margin = new Thickness(0, 0, 0, 12)
            };
            Grid.SetRow(header, 0);
            grid.Children.Add(header);

            var listView = new ListView { BorderThickness = new Thickness(0), Name = "DeviceListView" };
            var gridView = new GridView();
            gridView.Columns.Add(new GridViewColumn { Header = "Device Name", DisplayMemberBinding = new System.Windows.Data.Binding("DeviceName"), Width = 180 });
            gridView.Columns.Add(new GridViewColumn { Header = "Platform", DisplayMemberBinding = new System.Windows.Data.Binding("Platform"), Width = 100 });
            gridView.Columns.Add(new GridViewColumn { Header = "Status", Width = 100 });
            listView.View = gridView;
            Grid.SetRow(listView, 1);
            grid.Children.Add(listView);

            view.Content = grid;
            return view;
        }

        private async void OnSettingsSaved(string serverUrl, string httpUrl, bool autoStart, bool syncEnabled,
            bool encryptionEnabled, bool minimizeToTray, string deviceName)
        {
            _settingsManager.Update(s =>
            {
                s.ServerUrl = serverUrl;
                s.HttpUrl = httpUrl;
                s.AutoStart = autoStart;
                s.SyncEnabled = syncEnabled;
                s.EncryptionEnabled = encryptionEnabled;
                s.MinimizeToTray = minimizeToTray;
                s.DeviceName = deviceName;
            });

            if (autoStart)
            {
                SystemTray.AutoStart.Enable();
            }
            else
            {
                SystemTray.AutoStart.Disable();
            }

            await _settingsManager.SaveAsync();
        }

        private async System.Threading.Tasks.Task OnLogoutAsync()
        {
            await _syncEngine.LogoutAsync();
            ShowLoginContent();
            StatusDot.Fill = FindResource("ErrorColor") as System.Windows.Media.Brush;
            StatusText.Text = "Disconnected";
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            if (_settingsManager.Settings.MinimizeToTray)
            {
                e.Cancel = true;
                Hide();
            }
            base.OnClosing(e);
        }
    }
}
