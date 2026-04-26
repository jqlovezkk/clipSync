using System;
using System.Windows;
using System.Windows.Controls;

namespace ClipSync.WPF.UI.Views
{
    public partial class LoginView : UserControl
    {
        public event Action<string, string, string, string>? LoginRequested;
        public event Action<string, string, string, string>? RegisterRequested;

        public LoginView()
        {
            InitializeComponent();
            LoginButton.Click += OnLoginClicked;
            RegisterButton.Click += OnRegisterClicked;
        }

        public void SetServerUrls(string wsUrl, string httpUrl)
        {
            ServerUrlBox.Text = wsUrl;
            HttpUrlBox.Text = httpUrl;
        }

        public void ShowError(string message)
        {
            ErrorText.Text = message;
            ErrorText.Visibility = Visibility.Visible;
        }

        public void ClearError()
        {
            ErrorText.Visibility = Visibility.Collapsed;
        }

        private void OnLoginClicked(object sender, RoutedEventArgs e)
        {
            ClearError();
            var serverUrl = ServerUrlBox.Text.Trim();
            var httpUrl = HttpUrlBox.Text.Trim();
            var username = UsernameBox.Text.Trim();
            var password = PasswordBox.Password;

            if (string.IsNullOrEmpty(username) || string.IsNullOrEmpty(password))
            {
                ShowError("Username and password are required");
                return;
            }

            LoginRequested?.Invoke(serverUrl, httpUrl, username, password);
        }

        private void OnRegisterClicked(object sender, RoutedEventArgs e)
        {
            ClearError();
            var serverUrl = ServerUrlBox.Text.Trim();
            var httpUrl = HttpUrlBox.Text.Trim();
            var username = UsernameBox.Text.Trim();
            var password = PasswordBox.Password;

            if (string.IsNullOrEmpty(username) || string.IsNullOrEmpty(password))
            {
                ShowError("Username and password are required");
                return;
            }

            RegisterRequested?.Invoke(serverUrl, httpUrl, username, password);
        }
    }
}
