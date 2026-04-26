using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using ClipSync.WPF.Core;
using ClipSync.WPF.UI.Views;

namespace ClipSync.WPF
{
    public partial class MainWindow : Window
    {
        private readonly SettingsManager _settingsManager;
        private readonly SyncEngine _syncEngine;
        private readonly SystemTray.TrayIcon _trayIcon;
        private LoginView? _loginView;
        private UserControl? _homeView;
        private HistoryView? _historyView;
        private UserControl? _devicesView;
        private SettingsView? _settingsView;
        private StackPanel? _deviceListPanel;
        private TextBlock? _deviceListStateText;
        private Border? _deviceEmptyState;
        private TextBlock? _homeAccountValueText;
        private TextBlock? _homeConnectionValueText;
        private TextBlock? _homeHistoryValueText;
        private TextBlock? _homeDeviceValueText;
        private TextBlock? _homeLastActivityText;
        private bool _isLoggedIn;
        private bool _isHistoryLoading;
        private bool _historyReloadPending;
        private bool _isDeviceListRequestInFlight;
        private string _activeTabHeader = "Home";
        private int _historyItemCount;
        private int _deviceCount;
        private DateTime? _lastClipboardSyncAt;

        public MainWindow(SettingsManager settingsManager, SyncEngine syncEngine, SystemTray.TrayIcon trayIcon)
        {
            _settingsManager = settingsManager;
            _syncEngine = syncEngine;
            _trayIcon = trayIcon;

            InitializeComponent();

            _trayIcon.SetMainWindow(this);

            MainTabs.SelectionChanged += OnTabChanged;
            // RefreshDevicesButton.Click += async (s, e) => await _syncEngine.RequestDeviceListAsync(); // Removed - button deleted from XAML

            SetupLoginView();
            SetupHomeView();
            SetupHistoryView();
            SetupDevicesView();
            SetupSettingsView();

            _syncEngine.ConnectionStateChanged += OnConnectionStateChanged;
            _syncEngine.ErrorOccurred += OnErrorOccurred;
            _syncEngine.DeviceListUpdated += OnDeviceListUpdated;
            _syncEngine.ClipboardItemReceived += OnClipboardItemReceived;

            ShowLoginContent();
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
            _historyView.ClearRequested += async () => await ClearHistoryAsync();
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

        private void SetupHomeView()
        {
            _homeView = new UserControl();

            var root = new Grid();
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            var headerPanel = new StackPanel
            {
                Margin = new Thickness(0, 0, 0, 20)
            };
            headerPanel.Children.Add(new TextBlock
            {
                Text = "Workspace Overview",
                FontSize = 26,
                FontWeight = FontWeights.SemiBold,
                Foreground = GetBrush("TextColor")
            });
            headerPanel.Children.Add(new TextBlock
            {
                Text = "查看当前连接状态、剪贴板历史和设备同步概览",
                Margin = new Thickness(0, 8, 0, 0),
                FontSize = 14,
                Foreground = GetBrush("TextSecondaryColor")
            });
            Grid.SetRow(headerPanel, 0);
            root.Children.Add(headerPanel);

            var cardsGrid = new Grid
            {
                Margin = new Thickness(0, 0, 0, 20)
            };
            cardsGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            cardsGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            cardsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            cardsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var accountCard = CreateSummaryCard("Account", "当前登录账号", out _homeAccountValueText);
            var connectionCard = CreateSummaryCard("Connection", "当前同步连接状态", out _homeConnectionValueText);
            var historyCard = CreateSummaryCard("History", "本地历史记录数量", out _homeHistoryValueText);
            var devicesCard = CreateSummaryCard("Devices", "当前已识别设备数", out _homeDeviceValueText);

            Grid.SetRow(accountCard, 0);
            Grid.SetColumn(accountCard, 0);
            Grid.SetRow(connectionCard, 0);
            Grid.SetColumn(connectionCard, 1);
            Grid.SetRow(historyCard, 1);
            Grid.SetColumn(historyCard, 0);
            Grid.SetRow(devicesCard, 1);
            Grid.SetColumn(devicesCard, 1);

            cardsGrid.Children.Add(accountCard);
            cardsGrid.Children.Add(connectionCard);
            cardsGrid.Children.Add(historyCard);
            cardsGrid.Children.Add(devicesCard);

            Grid.SetRow(cardsGrid, 1);
            root.Children.Add(cardsGrid);

            var tipsBorder = new Border
            {
                Background = GetBrush("SurfaceElevatedColor"),
                BorderBrush = GetBrush("BorderColor"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(20)
            };
            var tipsPanel = new StackPanel();
            tipsPanel.Children.Add(new TextBlock
            {
                Text = "Quick Tips",
                FontSize = 16,
                FontWeight = FontWeights.SemiBold,
                Foreground = GetBrush("TextColor")
            });

            _homeLastActivityText = new TextBlock
            {
                Margin = new Thickness(0, 12, 0, 0),
                FontSize = 13,
                Foreground = GetBrush("TextSecondaryColor"),
                TextWrapping = TextWrapping.Wrap
            };
            tipsPanel.Children.Add(_homeLastActivityText);
            tipsPanel.Children.Add(new TextBlock
            {
                Margin = new Thickness(0, 10, 0, 0),
                FontSize = 13,
                Foreground = GetBrush("TextMutedColor"),
                Text = "可从 History 快速复制最近内容，从 Devices 查看手机和电脑的在线情况。"
            });
            tipsBorder.Child = tipsPanel;
            Grid.SetRow(tipsBorder, 2);
            root.Children.Add(tipsBorder);

            _homeView.Content = root;
            UpdateHomeSummary();
        }

        private void SetupDevicesView()
        {
            _devicesView = new UserControl();

            var root = new Grid();
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            var headerBorder = new Border
            {
                Background = GetBrush("SurfaceElevatedColor"),
                BorderBrush = GetBrush("BorderColor"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(16),
                Margin = new Thickness(0, 0, 0, 16)
            };

            var headerGrid = new Grid();
            headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var headerTextPanel = new StackPanel();
            headerTextPanel.Children.Add(new TextBlock
            {
                Text = "Registered Devices",
                FontSize = 22,
                FontWeight = FontWeights.SemiBold,
                Foreground = GetBrush("TextColor")
            });
            headerTextPanel.Children.Add(new TextBlock
            {
                Text = "查看当前账号下已注册的设备与在线状态",
                Margin = new Thickness(0, 6, 0, 0),
                FontSize = 13,
                Foreground = GetBrush("TextSecondaryColor")
            });
            Grid.SetColumn(headerTextPanel, 0);
            headerGrid.Children.Add(headerTextPanel);

            var refreshButton = new Button
            {
                Content = "Refresh",
                Padding = new Thickness(14, 8, 14, 8),
                MinWidth = 96,
                Style = FindResource("SecondaryButtonStyle") as Style
            };
            refreshButton.Click += async (s, e) => await RefreshDeviceListAsync(force: true);
            Grid.SetColumn(refreshButton, 1);
            headerGrid.Children.Add(refreshButton);

            headerBorder.Child = headerGrid;
            Grid.SetRow(headerBorder, 0);
            root.Children.Add(headerBorder);

            _deviceListStateText = new TextBlock
            {
                Margin = new Thickness(4, 0, 0, 12),
                FontSize = 13,
                Foreground = GetBrush("TextSecondaryColor"),
                Text = "等待加载设备列表"
            };
            Grid.SetRow(_deviceListStateText, 1);
            root.Children.Add(_deviceListStateText);

            var listHost = new Grid();
            Grid.SetRow(listHost, 2);
            listHost.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            var listBorder = new Border
            {
                Background = GetBrush("SurfaceColor"),
                BorderBrush = GetBrush("BorderColor"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(16),
                HorizontalAlignment = HorizontalAlignment.Stretch,
                VerticalAlignment = VerticalAlignment.Stretch
            };
            Grid.SetRow(listBorder, 0);

            var listScrollViewer = new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                HorizontalContentAlignment = HorizontalAlignment.Stretch,
                VerticalContentAlignment = VerticalAlignment.Stretch,
                HorizontalAlignment = HorizontalAlignment.Stretch,
                VerticalAlignment = VerticalAlignment.Stretch
            };

            _deviceListPanel = new StackPanel
            {
                HorizontalAlignment = HorizontalAlignment.Stretch
            };
            _deviceEmptyState = new Border
            {
                Background = GetBrush("SurfaceElevatedColor"),
                BorderBrush = GetBrush("BorderColor"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(24),
                HorizontalAlignment = HorizontalAlignment.Stretch,
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = "当前没有可显示的设备，稍后刷新或确认其他设备是否已登录。",
                    FontSize = 14,
                    TextAlignment = TextAlignment.Center,
                    TextWrapping = TextWrapping.Wrap,
                    Foreground = GetBrush("TextSecondaryColor")
                }
            };
            _deviceEmptyState.Visibility = Visibility.Collapsed;

            listScrollViewer.Content = _deviceListPanel;
            listBorder.Child = listScrollViewer;
            listHost.Children.Add(listBorder);
            listHost.Children.Add(_deviceEmptyState);
            root.Children.Add(listHost);

            _devicesView.Content = root;
            AppLogger.Info("MainWindow", "设备页初始化完成，已创建空状态占位控件");
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
                        UpdateHomeSummary();
                        break;
                    case "disconnected":
                        StatusDot.Fill = FindResource("ErrorColor") as System.Windows.Media.Brush;
                        StatusText.Text = "Disconnected";
                        UpdateHomeSummary();
                        break;
                    case "connecting":
                        StatusDot.Fill = FindResource("WarningColor") as System.Windows.Media.Brush;
                        StatusText.Text = "Connecting...";
                        UpdateHomeSummary();
                        break;
                    case "auth_failed":
                        StatusDot.Fill = FindResource("ErrorColor") as System.Windows.Media.Brush;
                        StatusText.Text = "Auth Failed";
                        ShowLoginContent();
                        UpdateHomeSummary();
                        break;
                }
            });
        }

        private void OnErrorOccurred(string message)
        {
            Dispatcher.Invoke(() =>
            {
                // Log the error for debugging
                System.Diagnostics.Debug.WriteLine($"[MainWindow] Error occurred: {message}");
                
                if (_loginView != null && !_isLoggedIn)
                {
                    _loginView.ShowError(message);
                }
                else if (_isLoggedIn)
                {
                    // Show error in a message box for logged-in users since we removed ErrorBanner
                    MessageBox.Show(message, "Error", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            });
        }

        private void OnDeviceListUpdated(List<Network.Device> devices)
        {
            Dispatcher.Invoke(() =>
            {
                _isDeviceListRequestInFlight = false;
                _deviceCount = devices.Count;

                if (_deviceListPanel != null)
                {
                    var displayItems = devices
                        .OrderByDescending(device => device.IsOnline)
                        .ThenBy(device => device.DeviceName)
                        .ToList();

                    _deviceListPanel.Children.Clear();
                    foreach (var device in displayItems)
                    {
                        _deviceListPanel.Children.Add(CreateDeviceCard(device));
                    }

                    if (_deviceEmptyState != null)
                    {
                        _deviceEmptyState.Visibility = displayItems.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
                    }
                }

                if (_deviceListStateText != null)
                {
                    _deviceListStateText.Text = devices.Count == 0
                        ? "当前未返回任何设备，请确认手机与电脑是否登录同一账号"
                        : $"已加载 {devices.Count} 台设备，在线 {devices.Count(device => device.IsOnline)} 台";
                }

                AppLogger.Info("MainWindow", $"设备列表已更新: total={devices.Count}");
                UpdateHomeSummary();
            });
        }

        private async void OnClipboardItemReceived(Network.ClipboardItem item)
        {
            _lastClipboardSyncAt = DateTime.Now;
            AppLogger.Info("MainWindow", $"收到剪贴板项，准备刷新历史列表: type={item.ContentType}, source={item.SourceDeviceName}");
            await LoadHistoryAsync();
        }

        private void ShowMainContent()
        {
            _isLoggedIn = true;
            LoginViewContainer.Visibility = Visibility.Collapsed;
            MainContent.Visibility = Visibility.Visible;
            MainTabs.SelectedItem = HomeTab;
            ShowTabContent("Home");
            _ = LoadHistoryAsync();
            _ = RefreshDeviceListAsync();
        }

        private void ShowLoginContent()
        {
            _isLoggedIn = false;
            LoginViewContainer.Visibility = Visibility.Visible;
            MainContent.Visibility = Visibility.Collapsed;
            TabContent.Content = null;
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

        private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (IsInteractiveTitleBarElement(e.OriginalSource as DependencyObject))
            {
                return;
            }

            if (e.ClickCount == 2)
            {
                MaximizeButton_Click(sender, new RoutedEventArgs());
                e.Handled = true;
                return;
            }

            if (e.ButtonState == MouseButtonState.Pressed)
            {
                DragMove();
                e.Handled = true;
            }
        }

        private async Task LoadHistoryAsync()
        {
            if (_isHistoryLoading)
            {
                _historyReloadPending = true;
                AppLogger.Info("MainWindow", "历史记录正在加载中，合并本次刷新请求");
                return;
            }

            _isHistoryLoading = true;
            try
            {
                var items = await _syncEngine.GetLocalHistoryAsync(50);
                _historyItemCount = items.Count;

                if (_historyView != null)
                {
                    // 历史记录刷新可能由后台线程触发，这里统一切回 UI 线程更新控件。
                    await Dispatcher.InvokeAsync(() =>
                    {
                        _historyView.SetItems(items);
                        UpdateHomeSummary();
                    });
                }
            }
            finally
            {
                _isHistoryLoading = false;
                if (_historyReloadPending)
                {
                    _historyReloadPending = false;
                    _ = LoadHistoryAsync();
                }
            }
        }

        private async Task ClearHistoryAsync()
        {
            var result = MessageBox.Show(
                "确定要清空所有剪贴板历史记录吗？此操作不可撤销。",
                "清空历史记录",
                MessageBoxButton.YesNo,
                MessageBoxImage.Warning);

            if (result != MessageBoxResult.Yes)
            {
                return;
            }

            await _syncEngine.ClearHistoryAsync();
            _historyItemCount = 0;

            if (_historyView != null)
            {
                await Dispatcher.InvokeAsync(() =>
                {
                    _historyView.SetItems(new List<Network.ClipboardItem>());
                    UpdateHomeSummary();
                });
            }

            AppLogger.Info("MainWindow", "剪贴板历史记录已清空");
        }

        private void OnTabChanged(object sender, SelectionChangedEventArgs e)
        {
            if (e.Source != MainTabs)
            {
                return;
            }

            if (MainTabs.SelectedItem is TabItem tab)
            {
                var header = tab.Header?.ToString() ?? "Home";
                ShowTabContent(header);
            }
        }

        private void ShowTabContent(string header)
        {
            if (_activeTabHeader == header && TabContent.Content != null)
            {
                return;
            }

            _activeTabHeader = header;
            AppLogger.Info("MainWindow", $"切换标签页: tab={header}");

            switch (header)
            {
                case "Home":
                    TabContent.Content = _homeView;
                    UpdateHomeSummary();
                    break;
                case "History":
                    TabContent.Content = _historyView;
                    _ = LoadHistoryAsync();
                    break;
                case "Devices":
                    TabContent.Content = _devicesView;
                    _ = RefreshDeviceListAsync();
                    break;
                case "Settings":
                    TabContent.Content = _settingsView;
                    LoadSettingsView();
                    break;
            }
        }

        private async Task RefreshDeviceListAsync(bool force = false)
        {
            if (_isDeviceListRequestInFlight && !force)
            {
                AppLogger.Info("MainWindow", "设备列表请求仍在进行中，跳过重复请求");
                return;
            }

            _isDeviceListRequestInFlight = true;
            await Dispatcher.InvokeAsync(() =>
            {
                if (_deviceListStateText != null)
                {
                    _deviceListStateText.Text = "正在刷新设备列表...";
                }
            });

            AppLogger.Info("MainWindow", "开始请求设备列表");
            await _syncEngine.RequestDeviceListAsync();

            _ = Task.Run(async () =>
            {
                await Task.Delay(3000);
                await Dispatcher.InvokeAsync(() =>
                {
                    if (_isDeviceListRequestInFlight)
                    {
                        _isDeviceListRequestInFlight = false;
                        if (_deviceListStateText != null)
                        {
                            _deviceListStateText.Text = _deviceCount > 0
                                ? $"已显示 {_deviceCount} 台设备"
                                : "暂未收到设备列表响应，可稍后重试";
                        }
                    }
                });
            });
        }

        private Border CreateSummaryCard(string title, string description, out TextBlock valueText)
        {
            var border = new Border
            {
                Background = GetBrush("SurfaceElevatedColor"),
                BorderBrush = GetBrush("BorderColor"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(18),
                Margin = new Thickness(0, 0, 14, 14)
            };

            var panel = new StackPanel();
            panel.Children.Add(new TextBlock
            {
                Text = title,
                FontSize = 13,
                Foreground = GetBrush("TextSecondaryColor")
            });

            valueText = new TextBlock
            {
                Margin = new Thickness(0, 10, 0, 4),
                FontSize = 24,
                FontWeight = FontWeights.SemiBold,
                Foreground = GetBrush("TextColor")
            };
            panel.Children.Add(valueText);
            panel.Children.Add(new TextBlock
            {
                Text = description,
                FontSize = 12,
                Foreground = GetBrush("TextMutedColor")
            });

            border.Child = panel;
            return border;
        }

        private Border CreateDeviceCard(Network.Device device)
        {
            var card = new Border
            {
                Background = GetBrush("SurfaceElevatedColor"),
                BorderBrush = GetBrush(device.IsOnline ? "PrimaryColor" : "BorderColor"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(16),
                Margin = new Thickness(0, 0, 0, 12),
                HorizontalAlignment = HorizontalAlignment.Stretch
            };

            var layout = new Grid();
            layout.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            layout.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var infoPanel = new StackPanel();
            infoPanel.Children.Add(new TextBlock
            {
                Text = GetDeviceDisplayName(device),
                FontSize = 17,
                FontWeight = FontWeights.SemiBold,
                Foreground = GetBrush("TextColor")
            });
            infoPanel.Children.Add(new TextBlock
            {
                Text = $"平台: {GetPlatformDisplayName(device.Platform)}",
                Margin = new Thickness(0, 8, 0, 0),
                FontSize = 13,
                Foreground = GetBrush("TextSecondaryColor")
            });
            infoPanel.Children.Add(new TextBlock
            {
                Text = $"设备 ID: {GetDeviceIdDisplayText(device.DeviceId)}",
                Margin = new Thickness(0, 4, 0, 0),
                FontSize = 12,
                Foreground = GetBrush("TextMutedColor")
            });
            infoPanel.Children.Add(new TextBlock
            {
                Text = $"最近活动: {FormatLastSeen(device.LastSeen)}",
                Margin = new Thickness(0, 4, 0, 0),
                FontSize = 12,
                Foreground = GetBrush("TextMutedColor")
            });
            Grid.SetColumn(infoPanel, 0);
            layout.Children.Add(infoPanel);

            var statusBadge = new Border
            {
                Background = device.IsOnline ? GetBrush("PrimaryColor") : GetBrush("SurfaceHighColor"),
                CornerRadius = new CornerRadius(999),
                Padding = new Thickness(12, 6, 12, 6),
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Top,
                Child = new TextBlock
                {
                    Text = device.IsOnline ? "Online" : "Offline",
                    FontSize = 12,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = device.IsOnline ? Brushes.Black : GetBrush("TextSecondaryColor")
                }
            };
            Grid.SetColumn(statusBadge, 1);
            layout.Children.Add(statusBadge);

            card.Child = layout;
            return card;
        }

        private void UpdateHomeSummary()
        {
            if (_homeView == null)
            {
                return;
            }

            Dispatcher.Invoke(() =>
            {
                if (_homeAccountValueText != null)
                {
                    _homeAccountValueText.Text = string.IsNullOrWhiteSpace(_settingsManager.Settings.Username)
                        ? "Guest"
                        : _settingsManager.Settings.Username;
                }

                if (_homeConnectionValueText != null)
                {
                    _homeConnectionValueText.Text = StatusText.Text;
                }

                if (_homeHistoryValueText != null)
                {
                    _homeHistoryValueText.Text = _historyItemCount.ToString();
                }

                if (_homeDeviceValueText != null)
                {
                    _homeDeviceValueText.Text = _deviceCount.ToString();
                }

                if (_homeLastActivityText != null)
                {
                    _homeLastActivityText.Text = _lastClipboardSyncAt.HasValue
                        ? $"最近一次同步时间: {_lastClipboardSyncAt.Value:HH:mm:ss}"
                        : "最近一次同步时间: 暂无，等待新的剪贴板同步";
                }
            });
        }

        private Brush GetBrush(string resourceKey)
        {
            return (Brush)FindResource(resourceKey);
        }

        private string FormatLastSeen(long unixMilliseconds)
        {
            if (unixMilliseconds <= 0)
            {
                return "未知";
            }

            var lastSeen = DateTimeOffset.FromUnixTimeMilliseconds(unixMilliseconds).LocalDateTime;
            var delta = DateTime.Now - lastSeen;
            if (delta.TotalMinutes < 1)
            {
                return "刚刚在线";
            }

            if (delta.TotalHours < 1)
            {
                return $"{Math.Max(1, (int)delta.TotalMinutes)} 分钟前";
            }

            if (delta.TotalDays < 1)
            {
                return $"{Math.Max(1, (int)delta.TotalHours)} 小时前";
            }

            return lastSeen.ToString("yyyy-MM-dd HH:mm");
        }

        private string GetDeviceDisplayName(Network.Device device)
        {
            if (!string.IsNullOrWhiteSpace(device.DeviceName))
            {
                return device.DeviceName;
            }

            if (!string.IsNullOrWhiteSpace(device.DeviceId))
            {
                return $"未命名设备 {GetDeviceIdDisplayText(device.DeviceId)}";
            }

            return "未命名设备";
        }

        private static string GetPlatformDisplayName(string platform)
        {
            if (string.IsNullOrWhiteSpace(platform))
            {
                return "未知平台";
            }

            return platform.ToLowerInvariant() switch
            {
                "windows" => "Windows",
                "android" => "Android",
                "ios" => "iOS",
                "macos" => "macOS",
                "linux" => "Linux",
                _ => platform
            };
        }

        private static string GetDeviceIdDisplayText(string deviceId)
        {
            if (string.IsNullOrWhiteSpace(deviceId))
            {
                return "未提供";
            }

            return deviceId.Length <= 12 ? deviceId : deviceId[..12];
        }

        private static bool IsInteractiveTitleBarElement(DependencyObject? source)
        {
            while (source != null)
            {
                if (source is ButtonBase || source is TabControl || source is TabItem)
                {
                    return true;
                }

                source = VisualTreeHelper.GetParent(source);
            }

            return false;
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
            _deviceCount = 0;
            ShowLoginContent();
            StatusDot.Fill = FindResource("ErrorColor") as System.Windows.Media.Brush;
            StatusText.Text = "Disconnected";
            UpdateHomeSummary();
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
