using System.Globalization;
using System.Windows.Data;

namespace Glide.Windows.UI.Common;

/// <summary>Maps a transfer value to an exact bar width without compositor timing.</summary>
public sealed class ProgressWidthConverter : IMultiValueConverter
{
    public object Convert(object[] values, Type targetType, object parameter, CultureInfo culture)
    {
        if (values.Length != 3 || values[0] is not double value ||
            values[1] is not double maximum || values[2] is not double trackWidth ||
            !double.IsFinite(value) || !double.IsFinite(maximum) ||
            !double.IsFinite(trackWidth) || maximum <= 0 || trackWidth <= 0)
            return 0d;

        return Math.Clamp(value / maximum, 0d, 1d) * trackWidth;
    }

    public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
