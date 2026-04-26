using System;
using System.Threading;
using System.Threading.Tasks;
using ClipSync.WPF.Core;

namespace ClipSync.WPF.Network
{
    public class ReconnectHandler
    {
        private readonly WebSocketClient _webSocketClient;
        private readonly SettingsManager _settingsManager;
        private CancellationTokenSource? _reconnectCts;
        private bool _isAuthenticated;
        private int _reconnectAttempts;

        private const int MinDelayMs = 1000;
        private const int MaxDelayMs = 60000;
        private const double BackoffMultiplier = 2.0;

        public ReconnectHandler(WebSocketClient webSocketClient, SettingsManager settingsManager)
        {
            _webSocketClient = webSocketClient;
            _settingsManager = settingsManager;
            _webSocketClient.ConnectionStateChanged += OnConnectionStateChanged;
        }

        public void OnAuthenticated()
        {
            _isAuthenticated = true;
            _reconnectAttempts = 0;
        }

        public void ResetAuthentication()
        {
            _isAuthenticated = false;
            _reconnectAttempts = 0;
            _reconnectCts?.Cancel();
        }

        public void ScheduleReconnect()
        {
            if (!_isAuthenticated) return;

            _reconnectCts?.Cancel();
            _reconnectCts = new CancellationTokenSource();

            var delay = CalculateDelay();
            _ = Task.Run(async () =>
            {
                try
                {
                    await Task.Delay(delay, _reconnectCts.Token);

                    if (_reconnectCts.Token.IsCancellationRequested) return;

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
                catch
                {
                    // Reconnect failed, will be retried on next disconnect
                }
            }, _reconnectCts.Token);
        }

        public void Stop()
        {
            _reconnectCts?.Cancel();
            _reconnectCts?.Dispose();
            _reconnectCts = null;
            _webSocketClient.ConnectionStateChanged -= OnConnectionStateChanged;
        }

        private void OnConnectionStateChanged(bool isConnected)
        {
            if (isConnected)
            {
                _reconnectAttempts = 0;
            }
        }

        private int CalculateDelay()
        {
            var delay = (int)(MinDelayMs * Math.Pow(BackoffMultiplier, _reconnectAttempts));
            _reconnectAttempts++;
            return Math.Min(delay, MaxDelayMs);
        }
    }
}
