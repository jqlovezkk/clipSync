using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;

namespace ClipSync.WPF.UI.ViewModels
{
    public class HistoryViewModel : INotifyPropertyChanged
    {
        private readonly Core.SyncEngine _syncEngine;
        private ObservableCollection<Network.ClipboardItem> _historyItems = new();
        private bool _isLoading;
        private string _selectedContent = "";

        public ObservableCollection<Network.ClipboardItem> HistoryItems
        {
            get => _historyItems;
            set { _historyItems = value; OnPropertyChanged(); }
        }

        public bool IsLoading
        {
            get => _isLoading;
            set { _isLoading = value; OnPropertyChanged(); }
        }

        public string SelectedContent
        {
            get => _selectedContent;
            set { _selectedContent = value; OnPropertyChanged(); }
        }

        public ICommand RefreshCommand { get; }
        public ICommand CopyCommand { get; }
        public ICommand ClearCommand { get; }

        public HistoryViewModel(Core.SyncEngine syncEngine)
        {
            _syncEngine = syncEngine;
            RefreshCommand = new RelayCommand(async () => await LoadHistoryAsync());
            CopyCommand = new RelayCommand<Network.ClipboardItem>(CopyToClipboard);
            ClearCommand = new RelayCommand(async () => await ClearHistoryAsync());
        }

        public async System.Threading.Tasks.Task LoadHistoryAsync()
        {
            IsLoading = true;
            try
            {
                var items = await _syncEngine.GetLocalHistoryAsync(50);
                HistoryItems.Clear();
                foreach (var item in items)
                {
                    HistoryItems.Add(item);
                }
            }
            finally
            {
                IsLoading = false;
            }
        }

        private void CopyToClipboard(Network.ClipboardItem? item)
        {
            if (item == null) return;

            try
            {
                if (item.ContentType == "text")
                {
                    Clipboard.SetText(item.Content);
                }
                else if (item.ContentType == "image")
                {
                    var imageBytes = Convert.FromBase64String(item.Content);
                    var bitmap = new System.Windows.Media.Imaging.BitmapImage();
                    using (var ms = new System.IO.MemoryStream(imageBytes))
                    {
                        bitmap.BeginInit();
                        bitmap.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
                        bitmap.StreamSource = ms;
                        bitmap.EndInit();
                    }
                    bitmap.Freeze();
                    Clipboard.SetImage(bitmap);
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[HistoryViewModel] Copy error: {ex.Message}");
            }
        }

        private async System.Threading.Tasks.Task ClearHistoryAsync()
        {
            if (_syncEngine != null)
            {
                HistoryItems.Clear();
                await System.Threading.Tasks.Task.CompletedTask;
            }
        }

        public event PropertyChangedEventHandler? PropertyChanged;

        protected virtual void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}
