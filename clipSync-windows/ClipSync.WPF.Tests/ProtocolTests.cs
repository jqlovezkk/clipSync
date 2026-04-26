using ClipSync.WPF.Network;
using Newtonsoft.Json.Linq;
using Xunit;

namespace ClipSync.WPF.Tests
{
    public class ProtocolTests
    {
        [Fact]
        public void CreateAuthMessage_ValidInput()
        {
            // Act
            string message = Protocol.CreateAuthMessage("test-token", "TestDevice", "windows");

            // Assert
            Assert.NotNull(message);
            var obj = JObject.Parse(message);
            Assert.Equal("auth", obj["type"]?.Value<string>());
            Assert.Equal("test-token", obj["payload"]?["token"]?.Value<string>());
        }

        [Fact]
        public void CreateHeartbeatMessage_ValidSequence()
        {
            // Act
            string message = Protocol.CreateHeartbeatMessage(5);

            // Assert
            var obj = JObject.Parse(message);
            Assert.Equal("heartbeat", obj["type"]?.Value<string>());
            Assert.Equal(5, obj["payload"]?["seq"]?.Value<int>());
        }

        [Fact]
        public void CreateClipboardPushMessage_TextContent()
        {
            // Act
            string message = Protocol.CreateClipboardPushMessage(
                "text",
                "Hello World",
                "text/plain",
                11,
                "checksum123",
                false,
                "");

            // Assert
            var obj = JObject.Parse(message);
            Assert.Equal("clipboard_push", obj["type"]?.Value<string>());
            Assert.Equal("text", obj["payload"]?["content_type"]?.Value<string>());
            Assert.Equal("Hello World", obj["payload"]?["content"]?.Value<string>());
        }

        [Fact]
        public void CreateClipboardPullMessage_WithLimit()
        {
            // Act
            string message = Protocol.CreateClipboardPullMessage(20, 0);

            // Assert
            var obj = JObject.Parse(message);
            Assert.Equal("clipboard_pull", obj["type"]?.Value<string>());
            Assert.Equal(20, obj["payload"]?["limit"]?.Value<int>());
        }

        [Fact]
        public void CreateDeviceListMessage()
        {
            // Act
            string message = Protocol.CreateDeviceListMessage();

            // Assert
            var obj = JObject.Parse(message);
            Assert.Equal("device_list", obj["type"]?.Value<string>());
        }

        [Fact]
        public void Deserialize_ValidMessage()
        {
            // Arrange
            string json = """
            {
                "type": "auth_response",
                "version": 1,
                "timestamp": 1234567890,
                "payload": {
                    "success": true,
                    "device_id": "dev-123",
                    "message": "OK"
                }
            }
            """;

            // Act
            var message = Protocol.Deserialize(json);

            // Assert
            Assert.NotNull(message);
            Assert.Equal("auth_response", message.Type);
            Assert.True(message.Payload?["success"]?.Value<bool>());
        }

        [Fact]
        public void Deserialize_InvalidJson_ReturnsNull()
        {
            // Act
            var message = Protocol.Deserialize("not valid json");

            // Assert
            Assert.Null(message);
        }

        [Fact]
        public void CreatePongMessage()
        {
            // Act
            string message = Protocol.CreatePongMessage();

            // Assert
            var obj = JObject.Parse(message);
            Assert.Equal("pong", obj["type"]?.Value<string>());
        }
    }
}
