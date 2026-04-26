using System;
using System.Globalization;
using System.Windows.Data;

namespace ClipSync.WPF.Converters
{
    public class UnixTimestampConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is long timestamp)
            {
                // Assuming timestamp is in milliseconds
                var dt = DateTimeOffset.FromUnixTimeMilliseconds(timestamp).LocalDateTime;
                
                // Format nicely, e.g. "Today, 14:30" or "Apr 25, 14:30"
                if (dt.Date == DateTime.Today)
                {
                    return $"Today, {dt:HH:mm}";
                }
                else if (dt.Date == DateTime.Today.AddDays(-1))
                {
                    return $"Yesterday, {dt:HH:mm}";
                }
                else
                {
                    return dt.ToString("MMM dd, yyyy HH:mm");
                }
            }
            return value;
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }
}