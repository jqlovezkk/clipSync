using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows;
using System.Windows.Interop;

namespace ClipSync.WPF.Core
{
    public enum ClipboardContentType
    {
        None,
        Text,
        Image
    }

    public class ClipboardChangedEventArgs : EventArgs
    {
        public ClipboardContentType ContentType { get; set; }
        public string? TextContent { get; set; }
        public byte[]? ImageContent { get; set; }
        public string Format { get; set; } = "";
        public string Checksum { get; set; } = "";
        public long Size { get; set; }
    }

    public class ClipboardMonitor : IDisposable
    {
        private readonly Action<ClipboardChangedEventArgs> _onClipboardChanged;
        private string _lastObservedChecksum = "";
        private string _suppressedChecksum = "";
        private bool _isDisposed;
        private Thread? _monitorThread;
        private volatile bool _isRunning;

        public ClipboardMonitor(Action<ClipboardChangedEventArgs> onClipboardChanged)
        {
            _onClipboardChanged = onClipboardChanged;
        }

        public void Start()
        {
            if (_isRunning) return;
            _isRunning = true;

            _monitorThread = new Thread(MonitorLoop)
            {
                IsBackground = true,
                Name = "ClipboardMonitor"
            };
            _monitorThread.SetApartmentState(ApartmentState.STA);
            _monitorThread.Start();
        }

        public void Stop()
        {
            _isRunning = false;
        }

        private void MonitorLoop()
        {
            var lastAccessTime = DateTime.MinValue;

            while (_isRunning && !_isDisposed)
            {
                try
                {
                    Thread.Sleep(500);

                    if (!IsClipboardFormatAvailable(CF_UNICODETEXT) &&
                        !IsClipboardFormatAvailable(CF_DIB) &&
                        !IsClipboardFormatAvailable(CF_BITMAP))
                    {
                        continue;
                    }

                    var args = ReadClipboardContent();
                    if (args != null && !string.IsNullOrEmpty(args.Checksum))
                    {
                        if (args.Checksum == _lastObservedChecksum)
                        {
                            continue;
                        }

                        _lastObservedChecksum = args.Checksum;

                        if (args.Checksum == _suppressedChecksum)
                        {
                            _suppressedChecksum = "";
                            continue;
                        }

                        _onClipboardChanged(args);
                    }
                }
                catch
                {
                    Thread.Sleep(1000);
                }
            }
        }

        private ClipboardChangedEventArgs? ReadClipboardContent()
        {
            var args = new ClipboardChangedEventArgs();

            try
            {
                // Retry loop for clipboard access (other apps may lock it briefly)
                for (int retry = 0; retry < 3; retry++)
                {
                    try
                    {
                        if (Clipboard.ContainsText())
                        {
                            var text = Clipboard.GetText();
                            if (string.IsNullOrEmpty(text))
                                return null;

                            args.ContentType = ClipboardContentType.Text;
                            args.TextContent = text;
                            args.Format = "text/plain";
                            args.Size = System.Text.Encoding.UTF8.GetByteCount(text);
                            args.Checksum = EncryptionHelper.ComputeChecksum(text);
                            return args;
                        }
                        else if (Clipboard.ContainsImage())
                        {
                            var image = Clipboard.GetImage();
                            if (image == null)
                                return null;

                            using var ms = new System.IO.MemoryStream();
                            var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder();
                            encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(image));
                            encoder.Save(ms);

                            var bytes = ms.ToArray();
                            if (bytes.Length == 0)
                                return null;

                            args.ContentType = ClipboardContentType.Image;
                            args.ImageContent = bytes;
                            args.Format = "image/png";
                            args.Size = bytes.Length;
                            args.Checksum = EncryptionHelper.ComputeChecksum(bytes);
                            return args;
                        }
                        else
                        {
                            return null;
                        }
                    }
                    catch (System.Runtime.InteropServices.COMException)
                    {
                        // Clipboard locked by another app, retry
                        Thread.Sleep(100);
                    }
                }
                return null;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[ClipboardMonitor] Read error: {ex.Message}");
                return null;
            }
        }

        public void SuppressNextChange(string checksum)
        {
            _suppressedChecksum = checksum;
            _lastObservedChecksum = checksum;
        }

        public void Dispose()
        {
            _isDisposed = true;
            _isRunning = false;
        }

        private const int CF_UNICODETEXT = 13;
        private const int CF_DIB = 8;
        private const int CF_BITMAP = 2;

        [DllImport("user32.dll")]
        private static extern bool IsClipboardFormatAvailable(int format);
    }
}
