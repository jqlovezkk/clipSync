using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using ClipSync.WPF.Network;
using Newtonsoft.Json.Linq;

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
            AppLogger.Info("SyncEngine", "同步引擎启动");

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
                AppLogger.Info("SyncEngine", "检测到本地已保存 Token，尝试自动连接并鉴权");
                await ConnectAndAuthenticateAsync();
            }

            _clipboardMonitor.Start();
        }

        public async Task StopAsync()
        {
            AppLogger.Info("SyncEngine", "同步引擎停止");
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

        private async Task DisconnectSessionAsync()
        {
            AppLogger.Info("SyncEngine", "开始注销当前登录会话");
            _heartbeatTimer?.Stop();
            _reconnectHandler?.ResetAuthentication();

            if (_webSocketClient != null)
            {
                await _webSocketClient.DisconnectAsync();
            }

            ConnectionStateChanged?.Invoke("disconnected");
        }

        private async Task ConnectAndAuthenticateAsync()
        {
            if (_webSocketClient == null) return;
            var wsUrl = _settingsManager.Settings.ServerUrl;
            if (!wsUrl.StartsWith("ws://") && !wsUrl.StartsWith("wss://"))
            {
                wsUrl = "ws://" + wsUrl;
            }

            AppLogger.Info("SyncEngine", $"准备连接并鉴权: ws_url={wsUrl}, device_name={_settingsManager.Settings.DeviceName}");
            ConnectionStateChanged?.Invoke("connecting");
            await _webSocketClient.ConnectAsync(wsUrl);

            if (!_webSocketClient.IsConnected)
            {
                AppLogger.Warn("SyncEngine", $"WebSocket 连接失败，未能发送鉴权消息: ws_url={wsUrl}");
                ErrorOccurred?.Invoke("WebSocket 连接失败，请检查服务器地址或网络");
                ConnectionStateChanged?.Invoke("disconnected");
                return;
            }

            var authMessage = Protocol.CreateAuthMessage(
                _settingsManager.Settings.Token,
                _settingsManager.Settings.DeviceName,
                "windows");
            AppLogger.Info("SyncEngine", "WebSocket 已连接，开始发送鉴权消息");
            await _webSocketClient.SendAsync(authMessage);
        }

        private async void OnLocalClipboardChanged(ClipboardChangedEventArgs args)
        {
            try
            {
                if (!_settingsManager.Settings.SyncEnabled) return;
                if (_webSocketClient == null || !_webSocketClient.IsConnected) return;
                await PublishClipboardChangedEventAsync(args);
            }
            catch (InvalidOperationException ex)
            {
                System.Diagnostics.Debug.WriteLine($"[SyncEngine] Clipboard push blocked: {ex.Message}");
                ErrorOccurred?.Invoke(ex.Message);
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
                AppLogger.Info("SyncEngine", $"收到 WebSocket 消息: type={wsMessage.Type}");

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
            catch (Exception ex)
            {
                AppLogger.Error("SyncEngine", "处理 WebSocket 消息失败", ex);
            }
        }

        private async Task HandleAuthResponse(WebSocketMessage message)
        {
            var success = GetBool(message.Payload, "success");
            var deviceId = GetString(message.Payload, "device_id");
            var msg = GetString(message.Payload, "message");

            if (success && !string.IsNullOrEmpty(deviceId))
            {
                _settingsManager.Update(s => s.DeviceId = deviceId);
                await _settingsManager.SaveAsync();
                _heartbeatTimer?.Start();
                _reconnectHandler?.OnAuthenticated();
                AppLogger.Info("SyncEngine", $"WebSocket 鉴权成功: device_id={deviceId}");
                ConnectionStateChanged?.Invoke("connected");

                await RequestClipboardPullAsync();
            }
            else
            {
                AppLogger.Warn("SyncEngine", $"WebSocket 鉴权失败: message={msg}");
                ErrorOccurred?.Invoke($"Authentication failed: {msg}");
                ConnectionStateChanged?.Invoke("auth_failed");
            }
        }

        private async Task HandleClipboardSync(WebSocketMessage message)
        {
            if (!_settingsManager.Settings.SyncEnabled) return;

            var sourceDeviceId = GetString(message.Payload, "source_device_id");
            var sourceDeviceName = GetString(message.Payload, "source_device_name", "Unknown device");
            var contentType = GetString(message.Payload, "content_type", "text");
            var content = GetString(message.Payload, "content");
            var format = GetString(message.Payload, "format");
            var checksum = GetString(message.Payload, "checksum");
            var encrypted = GetNullableBool(message.Payload, "encrypted");

            if (string.IsNullOrEmpty(content) || string.IsNullOrEmpty(checksum)) return;
            if (!string.IsNullOrEmpty(sourceDeviceId) && sourceDeviceId == _settingsManager.Settings.DeviceId) return;

            if (_clipboardMonitor != null)
            {
                _clipboardMonitor.SuppressNextChange(checksum);
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
                        DeviceId = GetString(deviceToken, "device_id"),
                        DeviceName = GetString(deviceToken, "device_name"),
                        Platform = GetString(deviceToken, "platform"),
                        LastSeen = GetLong(deviceToken, "last_seen"),
                        IsOnline = GetBool(deviceToken, "is_online")
                    };
                    devices.Add(device);
                }
            }
            AppLogger.Info("SyncEngine", $"收到设备列表响应: count={devices.Count}");
            DeviceListUpdated?.Invoke(devices);
        }

        private void HandleErrorMessage(WebSocketMessage message)
        {
            var code = GetString(message.Payload, "code", "UNKNOWN");
            var msg = GetString(message.Payload, "message");
            AppLogger.Warn("SyncEngine", $"收到服务端错误: code={code}, message={msg}");
            if (code == "DUPLICATE_CONTENT")
            {
                AppLogger.Info("SyncEngine", "检测到重复剪贴板内容，已静默忽略");
                return;
            }
            if (code == "AUTH_FAILED")
            {
                ConnectionStateChanged?.Invoke("auth_failed");
            }
            ErrorOccurred?.Invoke($"[{code}] {msg}");
        }

        private void OnConnectionStateChanged(bool isConnected)
        {
            if (isConnected)
            {
                AppLogger.Info("SyncEngine", "WebSocket 底层连接已建立，等待鉴权结果");
                ConnectionStateChanged?.Invoke("connecting");
                return;
            }

            AppLogger.Warn("SyncEngine", "WebSocket 底层连接已断开");
            ConnectionStateChanged?.Invoke("disconnected");
            if (!isConnected)
            {
                _reconnectHandler?.ScheduleReconnect();
            }
        }

        public async Task LoginAsync(string username, string password)
        {
            if (_httpClient == null) return;
            AppLogger.Info("SyncEngine", $"开始执行登录: username={username}");

            var deviceName = _settingsManager.Settings.DeviceName;
            var result = await _httpClient.LoginAsync(username, password, deviceName, "windows");

            if (result.Success && !string.IsNullOrEmpty(result.Token))
            {
                AppLogger.Info("SyncEngine", $"HTTP 登录成功，开始建立 WebSocket 鉴权: username={username}, device_id={result.DeviceId}");
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
                AppLogger.Warn("SyncEngine", $"HTTP 登录失败: username={username}, error={result.Error}");
                ErrorOccurred?.Invoke(result.Error ?? "Login failed");
            }
        }

        public async Task RegisterAsync(string username, string password)
        {
            if (_httpClient == null) return;
            AppLogger.Info("SyncEngine", $"开始执行注册: username={username}");

            var deviceName = _settingsManager.Settings.DeviceName;
            var result = await _httpClient.RegisterAsync(username, password, deviceName, "windows");

            if (result.Success && !string.IsNullOrEmpty(result.Token))
            {
                AppLogger.Info("SyncEngine", $"HTTP 注册成功，开始建立 WebSocket 鉴权: username={username}, device_id={result.DeviceId}");
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
                AppLogger.Warn("SyncEngine", $"HTTP 注册失败: username={username}, error={result.Error}");
                ErrorOccurred?.Invoke(result.Error ?? "Registration failed");
            }
        }

        public async Task LogoutAsync()
        {
            await DisconnectSessionAsync();
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

        public async Task ClearHistoryAsync()
        {
            if (_database == null) return;
            await _database.ClearHistoryAsync();
        }

        public async Task PushClipboardItemAsync(Network.ClipboardItem item)
        {
            if (item == null)
            {
                return;
            }

            var args = new ClipboardChangedEventArgs
            {
                ContentType = item.ContentType == "image" ? ClipboardContentType.Image : ClipboardContentType.Text,
                TextContent = item.ContentType == "text" ? item.Content : null,
                ImageContent = item.ContentType == "image" ? Convert.FromBase64String(item.Content) : null,
                Format = item.Format,
                Checksum = string.IsNullOrEmpty(item.Checksum) ? ComputeChecksum(item) : item.Checksum,
                Size = item.Size > 0 ? item.Size : ComputeSize(item)
            };

            if (_clipboardMonitor != null && !string.IsNullOrEmpty(args.Checksum))
            {
                _clipboardMonitor.SuppressNextChange(args.Checksum);
            }

            await PublishClipboardChangedEventAsync(args);
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

        private async Task PublishClipboardChangedEventAsync(ClipboardChangedEventArgs args)
        {
            var content = args.ContentType == ClipboardContentType.Text
                ? args.TextContent
                : Convert.ToBase64String(args.ImageContent ?? Array.Empty<byte>());

            if (string.IsNullOrEmpty(content))
            {
                return;
            }

            var pushMessage = Protocol.CreateClipboardPushMessage(
                args.ContentType == ClipboardContentType.Text ? "text" : "image",
                content,
                args.Format,
                args.Size,
                args.Checksum,
                _settingsManager.Settings.EncryptionEnabled,
                _settingsManager.Settings.EncryptionPassword);

            await _webSocketClient!.SendAsync(pushMessage);
            await SaveClipboardItemAsync(args, "local", _settingsManager.Settings.DeviceName);
        }

        private static string ComputeChecksum(Network.ClipboardItem item)
        {
            if (item.ContentType == "image")
            {
                return EncryptionHelper.ComputeChecksum(Convert.FromBase64String(item.Content));
            }

            return EncryptionHelper.ComputeChecksum(item.Content);
        }

        private static long ComputeSize(Network.ClipboardItem item)
        {
            if (item.ContentType == "image")
            {
                return Convert.FromBase64String(item.Content).LongLength;
            }

            return System.Text.Encoding.UTF8.GetByteCount(item.Content);
        }

        private static string GetString(JToken? token, string key, string defaultValue = "")
        {
            try
            {
                return token?[key]?.Value<string>() ?? defaultValue;
            }
            catch
            {
                return defaultValue;
            }
        }

        private static bool GetBool(JToken? token, string key, bool defaultValue = false)
        {
            try
            {
                return token?[key]?.Value<bool>() ?? defaultValue;
            }
            catch
            {
                return defaultValue;
            }
        }

        private static bool? GetNullableBool(JToken? token, string key)
        {
            try
            {
                return token?[key]?.Value<bool?>();
            }
            catch
            {
                return null;
            }
        }

        private static long GetLong(JToken? token, string key, long defaultValue = 0)
        {
            try
            {
                return token?[key]?.Value<long>() ?? defaultValue;
            }
            catch
            {
                return defaultValue;
            }
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
