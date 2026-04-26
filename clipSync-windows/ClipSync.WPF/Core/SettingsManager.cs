using System;
using System.IO;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace ClipSync.WPF.Core
{
    public class AppSettings
    {
        [JsonProperty("server_url")]
        public string ServerUrl { get; set; } = "ws://8.141.100.238:8080";

        [JsonProperty("http_url")]
        public string HttpUrl { get; set; } = "http://8.141.100.238:8081";

        [JsonProperty("username")]
        public string Username { get; set; } = "";

        [JsonProperty("token")]
        public string Token { get; set; } = "";

        [JsonProperty("device_id")]
        public string DeviceId { get; set; } = "";

        [JsonProperty("device_name")]
        public string DeviceName { get; set; } = Environment.MachineName;

        [JsonProperty("auto_start")]
        public bool AutoStart { get; set; } = true;

        [JsonProperty("sync_enabled")]
        public bool SyncEnabled { get; set; } = true;

        [JsonProperty("encryption_enabled")]
        public bool EncryptionEnabled { get; set; } = false;

        [JsonProperty("encryption_password")]
        public string EncryptionPassword { get; set; } = "";

        [JsonProperty("minimize_to_tray")]
        public bool MinimizeToTray { get; set; } = true;
    }

    public class SettingsManager
    {
        private readonly string _settingsPath;
        private readonly object _lock = new();

        public AppSettings Settings { get; private set; } = new();

        public SettingsManager()
        {
            var appDataPath = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            var clipSyncDir = Path.Combine(appDataPath, "ClipSync");
            if (!Directory.Exists(clipSyncDir))
            {
                Directory.CreateDirectory(clipSyncDir);
            }
            _settingsPath = Path.Combine(clipSyncDir, "settings.json");
        }

        public async Task LoadAsync()
        {
            await Task.Run(() =>
            {
                if (File.Exists(_settingsPath))
                {
                    var json = File.ReadAllText(_settingsPath);
                    var settings = JsonConvert.DeserializeObject<AppSettings>(json);
                    if (settings != null)
                    {
                        lock (_lock)
                        {
                            Settings = settings;
                        }
                    }
                }
            });
        }

        public async Task SaveAsync()
        {
            await Task.Run(() =>
            {
                lock (_lock)
                {
                    var json = JsonConvert.SerializeObject(Settings, Formatting.Indented);
                    File.WriteAllText(_settingsPath, json);
                }
            });
        }

        public void Update(Action<AppSettings> updater)
        {
            lock (_lock)
            {
                updater(Settings);
            }
        }
    }
}
