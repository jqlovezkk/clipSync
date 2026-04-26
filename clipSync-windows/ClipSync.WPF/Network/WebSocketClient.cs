using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace ClipSync.WPF.Network
{
    public class WebSocketClient : IDisposable
    {
        private ClientWebSocket? _webSocket;
        private CancellationTokenSource? _receiveCts;
        private bool _isDisposed;
        private const int MaxMessageSize = 10 * 1024 * 1024; // 10MB max message

        public bool IsConnected => _webSocket?.State == WebSocketState.Open;

        public event Action<string>? MessageReceived;
        public event Action<bool>? ConnectionStateChanged;

        public async Task ConnectAsync(string url)
        {
            await DisconnectAsync();

            _webSocket = new ClientWebSocket();
            _receiveCts = new CancellationTokenSource();

            try
            {
                await _webSocket.ConnectAsync(new Uri(url), _receiveCts.Token);
                ConnectionStateChanged?.Invoke(true);
                _ = Task.Run(() => ReceiveLoop(_receiveCts.Token), _receiveCts.Token);
            }
            catch
            {
                ConnectionStateChanged?.Invoke(false);
            }
        }

        public async Task DisconnectAsync()
        {
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
            ConnectionStateChanged?.Invoke(false);
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
            catch
            {
                // Send failed, connection likely dropped
            }
        }

        private async Task ReceiveLoop(CancellationToken cancellationToken)
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
            catch
            {
                // Connection error
            }
            finally
            {
                ConnectionStateChanged?.Invoke(false);
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
