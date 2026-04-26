using ClipSync.WPF.Core;
using Xunit;

namespace ClipSync.WPF.Tests
{
    public class SettingsManagerTests
    {
        private readonly SettingsManager _settingsManager;

        public SettingsManagerTests()
        {
            _settingsManager = new SettingsManager();
        }

        [Fact]
        public void DefaultSettings_HasExpectedValues()
        {
            // Assert
            Assert.NotNull(_settingsManager.Settings);
            Assert.False(string.IsNullOrEmpty(_settingsManager.Settings.ServerUrl));
            Assert.True(_settingsManager.Settings.SyncEnabled);  // Default is true
            Assert.False(_settingsManager.Settings.EncryptionEnabled);
            Assert.Equal(Environment.MachineName, _settingsManager.Settings.DeviceName);
        }

        [Fact]
        public void Update_ModifiesSettings()
        {
            // Act
            _settingsManager.Update(s =>
            {
                s.ServerUrl = "ws://localhost:8080";
                s.SyncEnabled = true;
                s.Token = "test-token-123";
            });

            // Assert
            Assert.Equal("ws://localhost:8080", _settingsManager.Settings.ServerUrl);
            Assert.True(_settingsManager.Settings.SyncEnabled);
            Assert.Equal("test-token-123", _settingsManager.Settings.Token);
        }

        [Fact]
        public async Task SaveAndLoad_PersistsSettings()
        {
            // Arrange
            _settingsManager.Update(s =>
            {
                s.ServerUrl = "ws://test-server:8080";
                s.DeviceName = "TestDevice";
            });

            // Act
            await _settingsManager.SaveAsync();
            var newManager = new SettingsManager();
            await newManager.LoadAsync();

            // Assert
            Assert.Equal("ws://test-server:8080", newManager.Settings.ServerUrl);
            Assert.Equal("TestDevice", newManager.Settings.DeviceName);
        }
    }
}
