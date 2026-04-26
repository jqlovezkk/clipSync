using System;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media.Imaging;

namespace ClipSync.WPF.Converters
{
    public class ImagePreviewConverter : IMultiValueConverter
    {
        public object? Convert(object[] values, Type targetType, object parameter, CultureInfo culture)
        {
            try
            {
                if (values.Length < 2 || values[0] == null || values[1] == null)
                    return DependencyProperty.UnsetValue;

                var content = values[0].ToString();
                var format = values[1].ToString();

                if (string.IsNullOrEmpty(content) || string.IsNullOrEmpty(format))
                    return DependencyProperty.UnsetValue;

                // Convert Base64 string to byte array
                var imageBytes = System.Convert.FromBase64String(content);
                
                // Create BitmapImage from byte array
                var bitmap = new BitmapImage();
                using (var ms = new MemoryStream(imageBytes))
                {
                    bitmap.BeginInit();
                    bitmap.CacheOption = BitmapCacheOption.OnLoad;
                    bitmap.StreamSource = ms;
                    bitmap.EndInit();
                }
                bitmap.Freeze();
                
                return bitmap;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[ImagePreviewConverter] Convert error: {ex.Message}");
                return DependencyProperty.UnsetValue;
            }
        }

        public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }
}
