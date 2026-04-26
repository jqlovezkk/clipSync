using System;
using System.Windows;
using System.Windows.Controls;
using ClipSync.WPF.Network;

namespace ClipSync.WPF.UI.Views
{
    public partial class HistoryView : UserControl
    {
        public event Action? RefreshRequested;
        public event Action? ClearRequested;
        public event Action<Network.ClipboardItem>? CopyRequested;

        public HistoryView()
        {
            InitializeComponent();
            RefreshButton.Click += (s, e) => RefreshRequested?.Invoke();
            ClearButton.Click += (s, e) => ClearRequested?.Invoke();
            HistoryList.AddHandler(Button.ClickEvent, new RoutedEventHandler(OnCopyClicked));
        }

        public void SetItems(System.Collections.Generic.IList<Network.ClipboardItem> items)
        {
            HistoryList.ItemsSource = items;
            EmptyText.Visibility = items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private void OnCopyClicked(object sender, RoutedEventArgs e)
        {
            if (e.OriginalSource is Button button && button.Tag is Network.ClipboardItem item)
            {
                CopyRequested?.Invoke(item);
            }
        }
    }
}
