using System;
using ClipSync.WPF.Core;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace ClipSync.WPF.Network
{
    public class WebSocketMessage
    {
        [JsonProperty("type")]
        public string Type { get; set; } = "";

        [JsonProperty("version")]
        public int Version { get; set; } = 1;

        [JsonProperty("timestamp")]
        public long Timestamp { get; set; }

        [JsonProperty("device_id")]
        public string? DeviceId { get; set; }

        [JsonProperty("payload")]
        public JObject? Payload { get; set; }

        public static WebSocketMessage Create(string type, JObject payload, string? deviceId = null)
        {
            return new WebSocketMessage
            {
                Type = type,
                Version = 1,
                Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                DeviceId = deviceId,
                Payload = payload
            };
        }
    }

    public class ClipboardItem
    {
        public long Id { get; set; }
        public string ContentType { get; set; } = "";
        public string Content { get; set; } = "";
        public string Format { get; set; } = "";
        public long Size { get; set; }
        public string Checksum { get; set; } = "";
        public string SourceDeviceId { get; set; } = "";
        public string SourceDeviceName { get; set; } = "";
        public long CreatedAt { get; set; }
    }

    public class Device
    {
        public string DeviceId { get; set; } = "";
        public string DeviceName { get; set; } = "";
        public string Platform { get; set; } = "";
        public long LastSeen { get; set; }
        public bool IsOnline { get; set; }
    }

    public static class Protocol
    {
        public static string Serialize(WebSocketMessage message)
        {
            return JsonConvert.SerializeObject(message);
        }

        public static WebSocketMessage? Deserialize(string json)
        {
            try
            {
                return JsonConvert.DeserializeObject<WebSocketMessage>(json);
            }
            catch
            {
                return null;
            }
        }

        public static string CreateAuthMessage(string token, string deviceName, string platform)
        {
            var payload = new JObject
            {
                ["token"] = token,
                ["device_name"] = deviceName,
                ["platform"] = platform
            };
            return Serialize(WebSocketMessage.Create("auth", payload));
        }

        public static string CreateHeartbeatMessage(int seq)
        {
            var payload = new JObject
            {
                ["seq"] = seq
            };
            return Serialize(WebSocketMessage.Create("heartbeat", payload));
        }

        public static string CreateClipboardPushMessage(
            string contentType,
            string content,
            string format,
            long size,
            string checksum,
            bool encryptionEnabled,
            string encryptionPassword)
        {
            var finalContent = content;
            var encrypted = false;

            if (encryptionEnabled && !string.IsNullOrEmpty(encryptionPassword))
            {
                try
                {
                    finalContent = EncryptionHelper.Encrypt(content, encryptionPassword);
                    encrypted = true;
                }
                catch (Exception ex)
                {
                    // Encryption failed - do NOT fall back to unencrypted content
                    System.Diagnostics.Debug.WriteLine($"[Protocol] Encryption failed: {ex.Message}");
                    throw new InvalidOperationException("Encryption failed. Content will not be sent unencrypted.", ex);
                }
            }

            var payload = new JObject
            {
                ["content_type"] = contentType,
                ["content"] = finalContent,
                ["format"] = format,
                ["size"] = size,
                ["checksum"] = checksum
            };

            if (encrypted)
            {
                payload["encrypted"] = true;
            }

            return Serialize(WebSocketMessage.Create("clipboard_push", payload));
        }

        public static string CreateClipboardPullMessage(int limit, long afterId)
        {
            var payload = new JObject
            {
                ["limit"] = limit
            };
            if (afterId > 0)
            {
                payload["after_id"] = afterId;
            }
            return Serialize(WebSocketMessage.Create("clipboard_pull", payload));
        }

        public static string CreateDeviceListMessage()
        {
            return Serialize(WebSocketMessage.Create("device_list", new JObject()));
        }

        public static string CreatePongMessage()
        {
            return Serialize(WebSocketMessage.Create("pong", new JObject()));
        }
    }
}
