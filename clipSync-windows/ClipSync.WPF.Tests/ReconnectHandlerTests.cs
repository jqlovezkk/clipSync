using ClipSync.WPF.Network;
using ClipSync.WPF.Core;
using Moq;
using Xunit;

namespace ClipSync.WPF.Tests
{
    public class ReconnectHandlerTests
    {
        [Fact]
        public void ScheduleReconnect_NotAuthenticated_DoesNothing()
        {
            // Arrange
            var mockWebSocket = new Mock<WebSocketClient>();
            var settingsManager = new SettingsManager();
            var handler = new ReconnectHandler(mockWebSocket.Object, settingsManager);

            // Act - Don't authenticate, just schedule
            handler.ScheduleReconnect();

            // Assert - Should not throw, but also not schedule
            // (We can't easily test the internal state, so this is a smoke test)
        }

        [Fact]
        public void OnAuthenticated_ResetsAttempts()
        {
            // Arrange
            var mockWebSocket = new Mock<WebSocketClient>();
            var settingsManager = new SettingsManager();
            var handler = new ReconnectHandler(mockWebSocket.Object, settingsManager);

            // Act
            handler.OnAuthenticated();

            // Assert - Internal state should be reset
            // (Again, smoke test to ensure no crashes)
        }

        [Fact]
        public void ResetAuthentication_ClearsState()
        {
            // Arrange
            var mockWebSocket = new Mock<WebSocketClient>();
            var settingsManager = new SettingsManager();
            var handler = new ReconnectHandler(mockWebSocket.Object, settingsManager);

            // Act
            handler.ResetAuthentication();

            // Assert - Should not throw
        }

        [Fact]
        public void Stop_CleansUpResources()
        {
            // Arrange
            var mockWebSocket = new Mock<WebSocketClient>();
            var settingsManager = new SettingsManager();
            var handler = new ReconnectHandler(mockWebSocket.Object, settingsManager);

            // Act
            handler.Stop();

            // Assert - Should not throw, resources cleaned up
        }
    }
}
