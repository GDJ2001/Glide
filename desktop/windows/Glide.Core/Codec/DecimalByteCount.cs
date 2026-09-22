using System.Text.RegularExpressions;

namespace Glide.Core.Codec;

public static class DecimalByteCount
{
    private static readonly Regex DecimalRegex = new(
        @"^(0|[1-9][0-9]*)$",
        RegexOptions.Compiled);

    public static long Parse(string text)
    {
        if (string.IsNullOrEmpty(text) || !DecimalRegex.IsMatch(text))
        {
            throw new ArgumentException($"Byte count must be a non-negative decimal string without leading zeros, got: '{text}'");
        }

        if (!long.TryParse(text, out var value) || value < 0)
        {
            throw new OverflowException($"Byte count decimal value overflows 64-bit signed integer: '{text}'");
        }

        return value;
    }

    public static string Serialize(long bytes)
    {
        if (bytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(bytes), $"Byte count must be non-negative, got: {bytes}");
        }

        return bytes.ToString();
    }
}
