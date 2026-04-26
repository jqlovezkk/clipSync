using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;
using ClipSync.WPF.Core;

namespace ClipSync.WPF.UI.ViewModels
{
    public class SettingsViewModel : INotifyPropertyChanged
    {
        private readonly SettingsManager _settingsManager;
        private string _serverUrl = "";
        private string _httpUrl = "";
        private bool _autoStart;
        private bool _syncEnabled;
        private bool _encryptionEnabled;
        private bool _minimizeToTray;
        private string _username = "";
        private string _deviceName = "";

        public string ServerUrl
        {
            get => _serverUrl;
            set { _serverUrl = value; OnPropertyChanged(); }
        }

        public string HttpUrl
        {
            get => _httpUrl;
            set { _httpUrl = value; OnPropertyChanged(); }
        }

        public bool AutoStart
        {
            get => _autoStart;
            set { _autoStart = value; OnPropertyChanged(); }
        }

        public bool SyncEnabled
        {
            get => _syncEnabled;
            set { _syncEnabled = value; OnPropertyChanged(); }
        }

        public bool EncryptionEnabled
        {
            get => _encryptionEnabled;
            set { _encryptionEnabled = value; OnPropertyChanged(); }
        }

        public bool MinimizeToTray
        {
            get => _minimizeToTray;
            set { _minimizeToTray = value; OnPropertyChanged(); }
        }

        public string Username
        {
            get => _username;
            set { _username = value; OnPropertyChanged(); }
        }

        public string DeviceName
        {
            get => _deviceName;
            set { _deviceName = value; OnPropertyChanged(); }
        }

        public ICommand SaveCommand { get; }

        public SettingsViewModel(SettingsManager settingsManager)
        {
            _settingsManager = settingsManager;
            SaveCommand = new RelayCommand(SaveSettings);
            LoadSettings();
        }

        private void LoadSettings()
        {
            var settings = _settingsManager.Settings;
            ServerUrl = settings.ServerUrl;
            HttpUrl = settings.HttpUrl;
            AutoStart = settings.AutoStart;
            SyncEnabled = settings.SyncEnabled;
            EncryptionEnabled = settings.EncryptionEnabled;
            MinimizeToTray = settings.MinimizeToTray;
            Username = settings.Username;
            DeviceName = settings.DeviceName;
        }

        private async void SaveSettings()
        {
            _settingsManager.Update(s =>
            {
                s.ServerUrl = ServerUrl;
                s.HttpUrl = HttpUrl;
                s.AutoStart = AutoStart;
                s.SyncEnabled = SyncEnabled;
                s.EncryptionEnabled = EncryptionEnabled;
                s.MinimizeToTray = MinimizeToTray;
                s.DeviceName = DeviceName;
            });

            if (AutoStart)
            {
                SystemTray.AutoStart.Enable();
            }
            else
            {
                SystemTray.AutoStart.Disable();
            }

            await _settingsManager.SaveAsync();
        }

        public event PropertyChangedEventHandler? PropertyChanged;

        protected virtual void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}
