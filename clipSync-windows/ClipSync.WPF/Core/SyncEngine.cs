using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using ClipSync.WPF.Network;

namespace ClipSync.WPF.Core
{
    public class SyncEngine : IDisposable
    {
        private readonly SettingsManager _settingsManager;
        private ClipboardMonitor? _clipboardMonitor;
        private WebSocketClient? _webSocketClient;
        private Network.HttpClient? _httpClient;
        private HeartbeatTimer? _heartbeatTimer;
        private ReconnectHandler? _reconnectHandler;
        private Storage.LocalDatabase? _database;
        private bool _isDisposed;
        private bool _isStarted;

        public event Action<string>? ConnectionStateChanged;
        public event Action<Network.ClipboardItem>? ClipboardItemReceived;
        public event Action<string>? ErrorOccurred;
        public event Action<List<Network.Device>>? DeviceListUpdated;

        public bool IsConnected => _webSocketClient?.IsConnected ?? false;

        public SyncEngine(SettingsManager settingsManager)
        {
            _settingsManager = settingsManager;
        }

        public async Task StartAsync()
        {
            if (_isStarted) return;
            _isStarted = true;

            _database = new Storage.LocalDatabase();
            await _database.InitializeAsync();

            _webSocketClient = new WebSocketClient();
            _webSocketClient.MessageReceived += OnWebSocketMessage;
            _webSocketClient.ConnectionStateChanged += OnConnectionStateChanged;

            _httpClient = new Network.HttpClient(_settingsManager);

            _heartbeatTimer = new HeartbeatTimer(_webSocketClient);
            _reconnectHandler = new ReconnectHandler(_webSocketClient, _settingsManager);

            _clipboardMonitor = new ClipboardMonitor(OnLocalClipboardChanged);

            if (!string.IsNullOrEmpty(_settingsManager.Settings.Token))
            {
                await ConnectAndAuthenticateAsync();
            }

            _clipboardMonitor.Start();
        }

        public async Task StopAsync()
        {
            _clipboardMonitor?.Stop();
            _heartbeatTimer?.Stop();
            _reconnectHandler?.Stop();

            if (_webSocketClient != null)
            {
                _webSocketClient.MessageReceived -= OnWebSocketMessage;
                _webSocketClient.ConnectionStateChanged -= OnConnectionStateChanged;
                await _webSocketClient.DisconnectAsync();
            }
        }

        private async Task ConnectAndAuthenticateAsync()
        {
            if (_webSocketClient == null) return;

            var wsUrl = _settingsManager.Settings.ServerUrl;
            if (!wsUrl.StartsWith("ws://") && !wsUrl.StartsWith("wss://"))
            {
                wsUrl = "ws://" + wsUrl;
            }

            await _webSocketClient.ConnectAsync(wsUrl);

            if (_webSocketClient.IsConnected)
            {
                var authMessage = Protocol.CreateAuthMessage(
                    _settingsManager.Settings.Token,
                    _settingsManager.Settings.DeviceName,
                    "windows");
                await _webSocketClient.SendAsync(authMessage);
            }
        }

        private async void OnLocalClipboardChanged(ClipboardChangedEventArgs args)
        {
            try
            {
                if (!_settingsManager.Settings.SyncEnabled) return;
                if (_webSocketClient == null || !_webSocketClient.IsConnected) return;

                var content = args.ContentType == ClipboardContentType.Text
                    ? args.TextContent
                    : Convert.ToBase64String(args.ImageContent ?? Array.Empty<byte>());

                if (string.IsNullOrEmpty(content)) return;

                var pushMessage = Protocol.CreateClipboardPushMessage(
                    args.ContentType == ClipboardContentType.Text ? "text" : "image",
                    content ?? "",
                    args.Format,
                    args.Size,
                    args.Checksum,
                    _settingsManager.Settings.EncryptionEnabled,
                    _settingsManager.Settings.EncryptionPassword);

                await _webSocketClient.SendAsync(pushMessage);

                await SaveClipboardItemAsync(args, "local", _settingsManager.Settings.DeviceName);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[SyncEngine] Clipboard push error: {ex.Message}");
            }
        }

        private async void OnWebSocketMessage(string message)
        {
            try
            {
                var wsMessage = Protocol.Deserialize(message);
                if (wsMessage == null) return;

                switch (wsMessage.Type)
                {
                    case "auth_response":
                        await HandleAuthResponse(wsMessage);
                        break;
                    case "clipboard_sync":
                        await HandleClipboardSync(wsMessage);
                        break;
                    case "heartbeat_ack":
                        _heartbeatTimer?.OnHeartbeatAck();
                        break;
                    case "clipboard_history":
                        HandleClipboardHistory(wsMessage);
                        break;
                    case "device_list_response":
                        HandleDeviceListResponse(wsMessage);
                        break;
                    case "error":
                        HandleErrorMessage(wsMessage);
                        break;
                    case "ping":
                        await _webSocketClient?.SendAsync(Protocol.CreatePongMessage())!;
                        break;
                }
            }
            catch
            {
                // Ignore malformed messages
            }
        }

        private async Task HandleAuthResponse(WebSocketMessage message)
        {
            var success = message.Payload?.Value<bool>("success") ?? false;
            var deviceId = message.Payload?.Value<string>("device_id") ?? "";
            var msg = message.Payload?.Value<string>("message") ?? "";

            if (success && !string.IsNullOrEmpty(deviceId))
            {
                _settingsManager.Update(s => s.DeviceId = deviceId);
                await _settingsManager.SaveAsync();
                _heartbeatTimer?.Start();
                _reconnectHandler?.OnAuthenticated();
                ConnectionStateChanged?.Invoke("connected");

                await RequestClipboardPullAsync();
            }
            else
            {
                ErrorOccurred?.Invoke($"Authentication failed: {msg}");
                ConnectionStateChanged?.Invoke("auth_failed");
            }
        }

        private async Task HandleClipboardSync(WebSocketMessage message)
        {
            if (!_settingsManager.Settings.SyncEnabled) return;

            var sourceDeviceId = message.Payload?.Value<string>("source_device_id") ?? "";
            var sourceDeviceName = message.Payload?.Value<string>("source_device_name") ?? "";
            var contentType = message.Payload?.Value<string>("content_type") ?? "text";
            var content = message.Payload?.Value<string>("content") ?? "";
            var format = message.Payload?.Value<string>("format") ?? "";
            var checksum = message.Payload?.Value<string>("checksum") ?? "";
            var encrypted = message.Payload?.Value<bool>("encrypted");

            if (string.IsNullOrEmpty(content) || string.IsNullOrEmpty(checksum)) return;

            if (_clipboardMonitor != null)
            {
                _clipboardMonitor.SetLastChecksum(checksum);
            }

            var decryptedContent = content;
            if (encrypted == true && _settingsManager.Settings.EncryptionEnabled && !string.IsNullOrEmpty(_settingsManager.Settings.EncryptionPassword))
            {
                try
                {
                    decryptedContent = EncryptionHelper.Decrypt(content, _settingsManager.Settings.EncryptionPassword);
                }
                catch
                {
                    ErrorOccurred?.Invoke("Failed to decrypt clipboard content");
                    return;
                }
            }

            try
            {
                // Clipboard operations must run on STA thread
                System.Windows.Application.Current.Dispatcher.Invoke(() =>
                {
                    if (contentType == "text")
                    {
                        System.Windows.Clipboard.SetText(decryptedContent);
                    }
                    else if (contentType == "image")
                    {
                        var imageBytes = Convert.FromBase64String(decryptedContent);
                        var bitmap = new System.Windows.Media.Imaging.BitmapImage();
                        bitmap.BeginInit();
                        bitmap.StreamSource = new System.IO.MemoryStream(imageBytes);
                        bitmap.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
                        bitmap.EndInit();
                        bitmap.Freeze();
                        System.Windows.Clipboard.SetImage(bitmap);
                    }
                });
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[SyncEngine] Set clipboard error: {ex.Message}");
                ErrorOccurred?.Invoke("Failed to set clipboard content");
                return;
            }

            var item = new Network.ClipboardItem
            {
                ContentType = contentType,
                Content = decryptedContent,
                Format = format,
                Checksum = checksum,
                SourceDeviceId = sourceDeviceId,
                SourceDeviceName = sourceDeviceName,
                CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };

            ClipboardItemReceived?.Invoke(item);

            if (_database != null)
            {
                await _database.InsertClipboardItemAsync(item);
            }
        }

        private void HandleClipboardHistory(WebSocketMessage message)
        {
            // Handle clipboard history response if needed
        }

        private void HandleDeviceListResponse(WebSocketMessage message)
        {
            var devices = new List<Network.Device>();
            var devicesArray = message.Payload?.Property("devices")?.Value;
            if (devicesArray != null)
            {
                foreach (var deviceToken in devicesArray)
                {
                    var device = new Network.Device
                    {
                        DeviceId = deviceToken.Value<string>("device_id") ?? "",
                        DeviceName = deviceToken.Value<string>("device_name") ?? "",
                        Platform = deviceToken.Value<string>("platform") ?? "",
                        LastSeen = deviceToken.Value<long>("last_seen"),
                        IsOnline = deviceToken.Value<bool>("is_online")
                    };
                    devices.Add(device);
                }
            }
            DeviceListUpdated?.Invoke(devices);
        }

        private void HandleErrorMessage(WebSocketMessage message)
        {
            var code = message.Payload?.Value<string>("code") ?? "UNKNOWN";
            var msg = message.Payload?.Value<string>("message") ?? "";
            ErrorOccurred?.Invoke($"[{code}] {msg}");
        }

        private void OnConnectionStateChanged(bool isConnected)
        {
            ConnectionStateChanged?.Invoke(isConnected ? "connected" : "disconnected");
            if (!isConnected)
            {
                _reconnectHandler?.ScheduleReconnect();
            }
        }

        public async Task LoginAsync(string username, string password)
        {
            if (_httpClient == null) return;

            var deviceName = _settingsManager.Settings.DeviceName;
            var result = await _httpClient.LoginAsync(username, password, deviceName, "windows");

            if (result.Success && !string.IsNullOrEmpty(result.Token))
            {
                _settingsManager.Update(s =>
                {
                    s.Username = username;
                    s.Token = result.Token!;
                    s.DeviceId = result.DeviceId ?? string.Empty;
                });
                await _settingsManager.SaveAsync();

                await ConnectAndAuthenticateAsync();
            }
            else
            {
                ErrorOccurred?.Invoke(result.Error ?? "Login failed");
            }
        }

        public async Task RegisterAsync(string username, string password)
        {
            if (_httpClient == null) return;

            var deviceName = _settingsManager.Settings.DeviceName;
            var result = await _httpClient.RegisterAsync(username, password, deviceName, "windows");

            if (result.Success && !string.IsNullOrEmpty(result.Token))
            {
                _settingsManager.Update(s =>
                {
                    s.Username = username;
                    s.Token = result.Token!;
                    s.DeviceId = result.DeviceId ?? string.Empty;
                });
                await _settingsManager.SaveAsync();

                await ConnectAndAuthenticateAsync();
            }
            else
            {
                ErrorOccurred?.Invoke(result.Error ?? "Registration failed");
            }
        }

        public async Task LogoutAsync()
        {
            await StopAsync();
            _settingsManager.Update(s =>
            {
                s.Token = "";
                s.DeviceId = "";
                s.Username = "";
            });
            await _settingsManager.SaveAsync();
        }

        public async Task RequestClipboardPullAsync()
        {
            if (_webSocketClient == null || !_webSocketClient.IsConnected) return;
            var message = Protocol.CreateClipboardPullMessage(20, 0);
            await _webSocketClient.SendAsync(message);
        }

        public async Task RequestDeviceListAsync()
        {
            if (_webSocketClient == null || !_webSocketClient.IsConnected) return;
            var message = Protocol.CreateDeviceListMessage();
            await _webSocketClient.SendAsync(message);
        }

        public async Task<List<Network.ClipboardItem>> GetLocalHistoryAsync(int limit = 50)
        {
            if (_database == null) return new List<Network.ClipboardItem>();
            return await _database.GetClipboardHistoryAsync(limit);
        }

        private async Task SaveClipboardItemAsync(ClipboardChangedEventArgs args, string sourceDeviceId, string sourceDeviceName)
        {
            if (_database == null) return;

            var item = new Network.ClipboardItem
            {
                ContentType = args.ContentType == ClipboardContentType.Text ? "text" : "image",
                Content = args.TextContent ?? Convert.ToBase64String(args.ImageContent ?? Array.Empty<byte>()),
                Format = args.Format,
                Checksum = args.Checksum,
                Size = args.Size,
                SourceDeviceId = sourceDeviceId,
                SourceDeviceName = sourceDeviceName,
                CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };

            await _database.InsertClipboardItemAsync(item);
        }

        public void Dispose()
        {
            if (_isDisposed) return;
            _isDisposed = true;
            _clipboardMonitor?.Dispose();
            _database?.Dispose();
        }
    }
}
