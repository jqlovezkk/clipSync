using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;

namespace ClipSync.WPF.UI.ViewModels
{
    public class MainViewModel : INotifyPropertyChanged
    {
        private string _connectionStatus = "Disconnected";
        private string _statusColor = "#FF4444";
        private int _selectedTabIndex;
        private ObservableCollection<Network.Device> _devices = new();
        private string _errorMessage = "";
        private bool _showError;

        public string ConnectionStatus
        {
            get => _connectionStatus;
            set { _connectionStatus = value; OnPropertyChanged(); }
        }

        public string StatusColor
        {
            get => _statusColor;
            set { _statusColor = value; OnPropertyChanged(); }
        }

        public int SelectedTabIndex
        {
            get => _selectedTabIndex;
            set { _selectedTabIndex = value; OnPropertyChanged(); }
        }

        public ObservableCollection<Network.Device> Devices
        {
            get => _devices;
            set { _devices = value; OnPropertyChanged(); }
        }

        public string ErrorMessage
        {
            get => _errorMessage;
            set { _errorMessage = value; OnPropertyChanged(); OnPropertyChanged(nameof(IsErrorVisible)); }
        }

        public bool IsErrorVisible
        {
            get => _showError;
            set { _showError = value; OnPropertyChanged(); }
        }

        public ICommand ClearErrorCommand { get; }

        public MainViewModel()
        {
            ClearErrorCommand = new RelayCommand(() => { ErrorMessage = ""; IsErrorVisible = false; });
        }

        public void UpdateConnectionStatus(string status)
        {
            switch (status)
            {
                case "connected":
                    ConnectionStatus = "Connected";
                    StatusColor = "#44BB44";
                    break;
                case "disconnected":
                    ConnectionStatus = "Disconnected";
                    StatusColor = "#FF4444";
                    break;
                case "connecting":
                    ConnectionStatus = "Connecting...";
                    StatusColor = "#FFAA00";
                    break;
                case "auth_failed":
                    ConnectionStatus = "Auth Failed";
                    StatusColor = "#FF4444";
                    break;
                default:
                    ConnectionStatus = status;
                    StatusColor = "#FFAA00";
                    break;
            }
        }

        public void UpdateDevices(System.Collections.Generic.List<Network.Device> devices)
        {
            Devices.Clear();
            foreach (var device in devices)
            {
                Devices.Add(device);
            }
        }

        public void ShowError(string message)
        {
            ErrorMessage = message;
            IsErrorVisible = true;
        }

        public event PropertyChangedEventHandler? PropertyChanged;

        protected virtual void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}
