using System;
using System.Threading;
using System.Threading.Tasks;

namespace ClipSync.WPF.Network
{
    public class HeartbeatTimer
    {
        private readonly WebSocketClient _webSocketClient;
        private Timer? _timer;
        private int _sequenceNumber;
        private bool _isRunning;

        private const int IntervalMs = 30000; // 30 seconds

        public HeartbeatTimer(WebSocketClient webSocketClient)
        {
            _webSocketClient = webSocketClient;
        }

        public void Start()
        {
            if (_isRunning) return;
            _isRunning = true;
            _sequenceNumber = 0;

            _timer = new Timer(SendHeartbeat, null, IntervalMs, IntervalMs);
        }

        public void Stop()
        {
            _isRunning = false;
            _timer?.Dispose();
            _timer = null;
        }

        public void OnHeartbeatAck()
        {
            // Server acknowledged heartbeat
        }

        private async void SendHeartbeat(object? state)
        {
            if (!_webSocketClient.IsConnected) return;

            _sequenceNumber++;
            var message = Protocol.CreateHeartbeatMessage(_sequenceNumber);
            await _webSocketClient.SendAsync(message);
        }
    }
}
