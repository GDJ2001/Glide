namespace Glide.Desktop.Services;

public sealed class DesktopTransferRateCalculator
{
    private long _lastBytes;
    private DateTimeOffset _lastTime = DateTimeOffset.UtcNow;
    private double _smoothedRate;

    public void Reset(long initialBytes = 0)
    {
        _lastBytes = initialBytes;
        _lastTime = DateTimeOffset.UtcNow;
        _smoothedRate = 0;
    }

    public (double BytesPerSecond, string RateText, string TransferredBytesText) Update(long currentBytes, long totalBytes)
    {
        var now = DateTimeOffset.UtcNow;
        double elapsed = (now - _lastTime).TotalSeconds;

        if (elapsed >= 0.25)
        {
            long deltaBytes = Math.Max(0, currentBytes - _lastBytes);
            double currentRate = deltaBytes / elapsed;

            _smoothedRate = _smoothedRate <= 0
                ? currentRate
                : (_smoothedRate * 0.7) + (currentRate * 0.3);

            _lastBytes = currentBytes;
            _lastTime = now;
        }

        string rateText = FormatRate(_smoothedRate);
        string transferredBytesText = $"{FormatBytes(currentBytes)} / {FormatBytes(totalBytes)}";

        return (_smoothedRate, rateText, transferredBytesText);
    }

    public static string FormatBytes(long bytes)
    {
        if (bytes < 0) return "0 B";
        string[] suffixes = ["B", "KB", "MB", "GB", "TB"];
        int order = 0;
        double len = bytes;
        while (len >= 1024 && order < suffixes.Length - 1)
        {
            order++;
            len /= 1024;
        }
        return order == 0 ? $"{len:0} {suffixes[order]}" : $"{len:0.0} {suffixes[order]}";
    }

    public static string FormatRate(double bytesPerSecond)
    {
        if (bytesPerSecond <= 0) return "0 B/s";
        return $"{FormatBytes((long)bytesPerSecond)}/s";
    }
}
