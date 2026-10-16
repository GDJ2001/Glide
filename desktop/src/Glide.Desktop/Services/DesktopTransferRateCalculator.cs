using System.Diagnostics;

namespace Glide.Desktop.Services;

public sealed class DesktopTransferRateCalculator
{
    private long _lastBytes;
    private long _lastTimestamp = Stopwatch.GetTimestamp();
    private double _smoothedRate;

    public void Reset(long initialBytes = 0)
    {
        _lastBytes = initialBytes;
        _lastTimestamp = Stopwatch.GetTimestamp();
        _smoothedRate = 0;
    }

    public (double BytesPerSecond, string RateText, string TransferredBytesText) Update(long currentBytes, long totalBytes)
    {
        long now = Stopwatch.GetTimestamp();
        double elapsed = Stopwatch.GetElapsedTime(_lastTimestamp, now).TotalSeconds;

        if (elapsed >= 0.15 || (_smoothedRate <= 0 && elapsed >= 0.001 && currentBytes > _lastBytes))
        {
            long deltaBytes = Math.Max(0, currentBytes - _lastBytes);
            double currentRate = deltaBytes / elapsed;

            _smoothedRate = _smoothedRate <= 0
                ? currentRate
                : (_smoothedRate * 0.7) + (currentRate * 0.3);

            _lastBytes = currentBytes;
            _lastTimestamp = now;
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
        while (len >= 1000.0 && order < suffixes.Length - 1)
        {
            order++;
            len /= 1000.0;
        }
        return order == 0 ? $"{len:0} {suffixes[order]}" : $"{len:0.0} {suffixes[order]}";
    }

    public static string FormatRate(double bytesPerSecond)
    {
        if (bytesPerSecond <= 0) return "0 B/s";
        if (bytesPerSecond >= 100_000.0)
        {
            return $"{bytesPerSecond / 1_000_000.0:0.0} MB/s";
        }
        return $"{FormatBytes((long)bytesPerSecond)}/s";
    }
}
