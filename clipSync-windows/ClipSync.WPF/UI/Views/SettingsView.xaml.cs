using System;
using System.Windows;
using System.Windows.Controls;

namespace ClipSync.WPF.UI.Views
{
    public partial class SettingsView : UserControl
    {
        public event Action<string, string, bool, bool, bool, bool, string>? SettingsSaved;
        public event Action? LogoutRequested;

        public SettingsView()
        {
            InitializeComponent();
            SaveButton.Click += OnSaveClicked;
            LogoutButton.Click += (s, e) => LogoutRequested?.Invoke();
        }

        public void LoadSettings(string serverUrl, string httpUrl, bool autoStart, bool syncEnabled,
            bool encryptionEnabled, bool minimizeToTray, string deviceName, string username)
        {
            ServerUrlBox.Text = serverUrl;
            HttpUrlBox.Text = httpUrl;
            AutoStartCheck.IsChecked = autoStart;
            SyncEnabledCheck.IsChecked = syncEnabled;
            EncryptionEnabledCheck.IsChecked = encryptionEnabled;
            MinimizeToTrayCheck.IsChecked = minimizeToTray;
            DeviceNameBox.Text = deviceName;
            UsernameText.Text = string.IsNullOrEmpty(username) ? "Not logged in" : $"Logged in as: {username}";
        }

        private void OnSaveClicked(object sender, RoutedEventArgs e)
        {
            SettingsSaved?.Invoke(
                ServerUrlBox.Text.Trim(),
                HttpUrlBox.Text.Trim(),
                AutoStartCheck.IsChecked ?? false,
                SyncEnabledCheck.IsChecked ?? true,
                EncryptionEnabledCheck.IsChecked ?? false,
                MinimizeToTrayCheck.IsChecked ?? true,
                DeviceNameBox.Text.Trim());
        }
    }
}
