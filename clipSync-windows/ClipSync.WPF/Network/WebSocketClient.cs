using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using ClipSync.WPF.Core;

namespace ClipSync.WPF.Network
{
    public class WebSocketClient : IDisposable
    {
        private ClientWebSocket? _webSocket;
        private CancellationTokenSource? _receiveCts;
        private bool _isDisposed;
        private int _connectionVersion;
        private const int MaxMessageSize = 10 * 1024 * 1024; // 10MB max message

        public bool IsConnected => _webSocket?.State == WebSocketState.Open;

        public event Action<string>? MessageReceived;
        public event Action<bool>? ConnectionStateChanged;

        public async Task ConnectAsync(string url)
        {
            var connectionVersion = Interlocked.Increment(ref _connectionVersion);
            AppLogger.Info("WebSocketClient", $"开始连接 WebSocket: {url}");
            await DisconnectAsync(notifyStateChange: false);

            _webSocket = new ClientWebSocket();
            _receiveCts = new CancellationTokenSource();

            try
            {
                await _webSocket.ConnectAsync(new Uri(url), _receiveCts.Token);
                AppLogger.Info("WebSocketClient", $"WebSocket 连接成功: {url}");
                ConnectionStateChanged?.Invoke(true);
                _ = Task.Run(() => ReceiveLoop(_receiveCts.Token, connectionVersion), _receiveCts.Token);
            }
            catch (Exception ex)
            {
                AppLogger.Error("WebSocketClient", $"WebSocket 连接失败: {url}", ex);
                if (connectionVersion == Volatile.Read(ref _connectionVersion))
                {
                    ConnectionStateChanged?.Invoke(false);
                }
            }
        }

        public async Task DisconnectAsync(bool notifyStateChange = true)
        {
            var wasConnected = _webSocket?.State == WebSocketState.Open;
            if (wasConnected)
            {
                AppLogger.Info("WebSocketClient", "开始断开 WebSocket 连接");
            }
            _receiveCts?.Cancel();

            if (_webSocket != null && _webSocket.State == WebSocketState.Open)
            {
                try
                {
                    await _webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", CancellationToken.None);
                }
                catch
                {
                    // Ignore close errors
                }
            }

            _webSocket?.Dispose();
            _webSocket = null;
            _receiveCts?.Dispose();
            _receiveCts = null;
            if (notifyStateChange && wasConnected)
            {
                AppLogger.Info("WebSocketClient", "WebSocket 已断开");
                ConnectionStateChanged?.Invoke(false);
            }
        }

        public async Task SendAsync(string message)
        {
            if (_webSocket == null || _webSocket.State != WebSocketState.Open) return;

            try
            {
                var bytes = Encoding.UTF8.GetBytes(message);
                await _webSocket.SendAsync(
                    new ArraySegment<byte>(bytes),
                    WebSocketMessageType.Text,
                    true,
                    CancellationToken.None);
            }
            catch (Exception ex)
            {
                // Send failed, connection likely dropped
                AppLogger.Error("WebSocketClient", "发送 WebSocket 消息失败", ex);
            }
        }

        private async Task ReceiveLoop(CancellationToken cancellationToken, int connectionVersion)
        {
            if (_webSocket == null) return;

            var buffer = new byte[8192];
            var messageBuilder = new MemoryStream();

            try
            {
                while (!cancellationToken.IsCancellationRequested && _webSocket.State == WebSocketState.Open)
                {
                    messageBuilder.SetLength(0);
                    WebSocketReceiveResult result;

                    do
                    {
                        result = await _webSocket.ReceiveAsync(
                            new ArraySegment<byte>(buffer),
                            cancellationToken);

                        if (result.MessageType == WebSocketMessageType.Close)
                        {
                            return;
                        }

                        messageBuilder.Write(buffer, 0, result.Count);

                        // Prevent excessively large messages
                        if (messageBuilder.Length > MaxMessageSize)
                        {
                            System.Diagnostics.Debug.WriteLine("[WebSocketClient] Message too large, discarding");
                            messageBuilder.SetLength(0);
                            return;
                        }
                    }
                    while (!result.EndOfMessage);

                    var message = Encoding.UTF8.GetString(messageBuilder.ToArray());
                    MessageReceived?.Invoke(message);
                }
            }
            catch (OperationCanceledException)
            {
                // Expected on disconnect
            }
            catch (System.Net.WebSockets.WebSocketException ex) when (
                ex.Message.Contains("closed") || ex.Message.Contains("handshake"))
            {
                AppLogger.Warn("WebSocketClient", "WebSocket 连接被远端关闭（未完成握手）");
            }
            catch (Exception ex)
            {
                AppLogger.Warn("WebSocketClient", $"WebSocket 接收消息异常: {ex.GetType().Name}: {ex.Message}");
            }
            finally
            {
                if (connectionVersion == Volatile.Read(ref _connectionVersion) &&
                    (_webSocket == null || _webSocket.State != WebSocketState.Open))
                {
                    AppLogger.Warn("WebSocketClient", "WebSocket 接收循环结束，连接状态变为已断开");
                    ConnectionStateChanged?.Invoke(false);
                }
            }
        }

        public void Dispose()
        {
            if (_isDisposed) return;
            _isDisposed = true;
            _ = DisconnectAsync();
        }
    }
}
