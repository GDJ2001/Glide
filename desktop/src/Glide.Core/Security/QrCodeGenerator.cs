using System.Text;

namespace Glide.Core.Security;

/// <summary>
/// Error correction level for QR Code generation (ISO/IEC 18004).
/// </summary>
public enum QrErrorCorrectionLevel
{
    Low = 0,       // L: 7% recovery
    Medium = 1,    // M: 15% recovery
    Quartile = 2,  // Q: 25% recovery
    High = 3       // H: 30% recovery
}

/// <summary>
/// Encapsulates a generated QR Code bit matrix (ISO/IEC 18004).
/// </summary>
public sealed class QrCode
{
    public int Version { get; }
    public int Size { get; }
    public QrErrorCorrectionLevel ErrorCorrectionLevel { get; }
    public int MaskPattern { get; }
    private readonly bool[,] _modules;

    public QrCode(int version, QrErrorCorrectionLevel level, int maskPattern, bool[,] modules)
    {
        Version = version;
        ErrorCorrectionLevel = level;
        MaskPattern = maskPattern;
        Size = modules.GetLength(0);
        _modules = modules;
    }

    public bool GetModule(int row, int col)
    {
        if (row < 0 || row >= Size || col < 0 || col >= Size) return false;
        return _modules[row, col];
    }

    /// <summary>
    /// Returns a copy of the raw boolean module matrix (true = dark, false = light).
    /// </summary>
    public bool[,] ToMatrix() => (bool[,])_modules.Clone();

    /// <summary>
    /// Renders the QR code as a compact ANSI/UTF-8 half-block string suitable for terminal display.
    /// Uses upper half block '▀' (\u2580), lower half block '▄' (\u2584), full block '█' (\u2588), and space ' '.
    /// Each character cell represents 2 vertical QR modules, allowing high-contrast scanning on dark terminal backgrounds.
    /// </summary>
    public string ToTerminalString(int border = 2)
    {
        if (border < 0) border = 0;
        int totalWidth = Size + 2 * border;
        int totalHeight = Size + 2 * border;
        var sb = new StringBuilder();

        for (int r = 0; r < totalHeight; r += 2)
        {
            for (int c = 0; c < totalWidth; c++)
            {
                int x = c - border;
                int y1 = r - border;
                int y2 = r + 1 - border;

                // On standard dark terminal backgrounds, white/light quiet zone and modules are drawn with foreground (white).
                // true = dark module (black), false = light module (white).
                bool topWhite = y1 < 0 || y1 >= Size || x < 0 || x >= Size || !GetModule(y1, x);
                bool botWhite = (r + 1 >= totalHeight) || y2 < 0 || y2 >= Size || x < 0 || x >= Size || !GetModule(y2, x);

                if (topWhite && botWhite)
                {
                    sb.Append('█'); // \u2588 Full block (both top & bottom are white)
                }
                else if (topWhite && !botWhite)
                {
                    sb.Append('▀'); // \u2580 Upper half block (top is white, bottom is black)
                }
                else if (!topWhite && botWhite)
                {
                    sb.Append('▄'); // \u2584 Lower half block (top is black, bottom is white)
                }
                else
                {
                    sb.Append(' '); // Space (both top & bottom are black)
                }
            }
            sb.AppendLine();
        }

        return sb.ToString();
    }
}

/// <summary>
/// Offline QR Model 2 encoding, isolated behind Glide's existing matrix contract.
/// Uses QRCoder's standards-compliant block tables and placement rather than a
/// custom encoder. No networking, image rendering or platform dependency is involved.
/// </summary>
public static class QrCodeGenerator
{
    public const int MinVersion = 1;
    public const int MaxVersion = 40;

    public static QrCode EncodeText(string text, QrErrorCorrectionLevel level = QrErrorCorrectionLevel.Medium)
    {
        ArgumentNullException.ThrowIfNull(text);
        return EncodeBinary(Encoding.UTF8.GetBytes(text), level);
    }

    public static QrCode EncodeBinary(byte[] data, QrErrorCorrectionLevel level = QrErrorCorrectionLevel.Medium)
    {
        ArgumentNullException.ThrowIfNull(data);
        var ecc = level switch
        {
            QrErrorCorrectionLevel.Low => QRCoder.QRCodeGenerator.ECCLevel.L,
            QrErrorCorrectionLevel.Medium => QRCoder.QRCodeGenerator.ECCLevel.M,
            QrErrorCorrectionLevel.Quartile => QRCoder.QRCodeGenerator.ECCLevel.Q,
            QrErrorCorrectionLevel.High => QRCoder.QRCodeGenerator.ECCLevel.H,
            _ => throw new ArgumentOutOfRangeException(nameof(level))
        };
        using var generator = new QRCoder.QRCodeGenerator();
        using var encoded = generator.CreateQrCode(data, ecc);
        // QRCoder includes four quiet-zone modules on every side. Glide renderers
        // already add their own quiet zone; expose only the symbol here.
        int size = encoded.ModuleMatrix.Count - 8;
        var modules = new bool[size, size];
        for (int row = 0; row < size; row++)
            for (int col = 0; col < size; col++)
                modules[row, col] = encoded.ModuleMatrix[row + 4][col + 4];

        // Read the top-left format copy in MSB order to preserve MaskPattern.
        int[] rows = [8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0];
        int[] cols = [0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8];
        int format = 0;
        for (int i = 0; i < 15; i++)
            format = (format << 1) | (modules[rows[i], cols[i]] ? 1 : 0);
        int mask = ((format ^ 0x5412) >> 10) & 7;
        return new QrCode((size - 17) / 4, level, mask, modules);
    }
}
