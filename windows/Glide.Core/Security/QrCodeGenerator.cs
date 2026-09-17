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
/// Zero-dependency, pure C# standard QR Code Model 2 generator (ISO/IEC 18004).
/// Fully offline, air-gapped, and thread-safe.
/// </summary>
public static class QrCodeGenerator
{
    public const int MinVersion = 1;
    public const int MaxVersion = 40;

    // Galois Field GF(256) log and antilog tables
    private static readonly byte[] ExpTable = new byte[512];
    private static readonly byte[] LogTable = new byte[256];

    static QrCodeGenerator()
    {
        int val = 1;
        for (int i = 0; i < 255; i++)
        {
            ExpTable[i] = (byte)val;
            ExpTable[i + 255] = (byte)val;
            LogTable[val] = (byte)i;
            val <<= 1;
            if ((val & 0x100) != 0)
            {
                val ^= 0x11D; // Primitive polynomial x^8 + x^4 + x^3 + x^2 + 1
            }
        }
    }

    public static QrCode EncodeText(string text, QrErrorCorrectionLevel level = QrErrorCorrectionLevel.Medium)
    {
        ArgumentNullException.ThrowIfNull(text);
        byte[] data = Encoding.UTF8.GetBytes(text);
        return EncodeBinary(data, level);
    }

    public static QrCode EncodeBinary(byte[] data, QrErrorCorrectionLevel level = QrErrorCorrectionLevel.Medium)
    {
        ArgumentNullException.ThrowIfNull(data);

        // Find smallest version that fits data
        int version = SelectSmallestVersion(data.Length, level);
        var spec = GetBlockSpec(version, level);

        // 1. Build Bitstream (Byte Mode: 0100)
        var bits = new BitBuffer();
        bits.AppendBits(0b0100, 4); // Mode: Byte
        int charCountBits = version < 10 ? 8 : 16;
        bits.AppendBits(data.Length, charCountBits);
        foreach (byte b in data)
        {
            bits.AppendBits(b, 8);
        }

        // Terminator: up to 4 zero bits
        int totalDataBits = spec.TotalDataCodewords * 8;
        int termLength = Math.Min(4, totalDataBits - bits.Length);
        if (termLength > 0)
        {
            bits.AppendBits(0, termLength);
        }

        // Pad to byte boundary
        while (bits.Length % 8 != 0)
        {
            bits.AppendBits(0, 1);
        }

        // Pad with alternating 0xEC and 0x11
        byte[] padBytes = [0xEC, 0x11];
        int padIdx = 0;
        while (bits.Length < totalDataBits)
        {
            bits.AppendBits(padBytes[padIdx % 2], 8);
            padIdx++;
        }

        byte[] dataCodewords = bits.ToByteArray();

        // 2. Error Correction per Block
        byte[] genPoly = GetGeneratorPolynomial(spec.EccCodewordsPerBlock);

        int totalBlocks = spec.Group1Blocks + spec.Group2Blocks;
        var dataBlocks = new byte[totalBlocks][];
        var eccBlocks = new byte[totalBlocks][];

        int offset = 0;
        int blockIndex = 0;
        for (int i = 0; i < spec.Group1Blocks; i++)
        {
            dataBlocks[blockIndex] = new byte[spec.Group1DataCodewords];
            Array.Copy(dataCodewords, offset, dataBlocks[blockIndex], 0, spec.Group1DataCodewords);
            eccBlocks[blockIndex] = ComputeEcc(dataBlocks[blockIndex], genPoly);
            offset += spec.Group1DataCodewords;
            blockIndex++;
        }
        for (int i = 0; i < spec.Group2Blocks; i++)
        {
            dataBlocks[blockIndex] = new byte[spec.Group2DataCodewords];
            Array.Copy(dataCodewords, offset, dataBlocks[blockIndex], 0, spec.Group2DataCodewords);
            eccBlocks[blockIndex] = ComputeEcc(dataBlocks[blockIndex], genPoly);
            offset += spec.Group2DataCodewords;
            blockIndex++;
        }

        // 3. Interleave Codewords
        var interleaved = new List<byte>(spec.TotalDataCodewords + totalBlocks * spec.EccCodewordsPerBlock);
        int maxDataLen = Math.Max(spec.Group1DataCodewords, spec.Group2DataCodewords);
        for (int i = 0; i < maxDataLen; i++)
        {
            for (int b = 0; b < totalBlocks; b++)
            {
                if (i < dataBlocks[b].Length)
                {
                    interleaved.Add(dataBlocks[b][i]);
                }
            }
        }
        for (int i = 0; i < spec.EccCodewordsPerBlock; i++)
        {
            for (int b = 0; b < totalBlocks; b++)
            {
                interleaved.Add(eccBlocks[b][i]);
            }
        }

        // Convert to bits and append remainder bits
        var finalBits = new BitBuffer();
        foreach (byte b in interleaved)
        {
            finalBits.AppendBits(b, 8);
        }
        int remainderBits = GetRemainderBits(version);
        if (remainderBits > 0)
        {
            finalBits.AppendBits(0, remainderBits);
        }

        // 4. Populate Matrix with Function Patterns
        int size = 4 * version + 17;
        var functionModules = new bool[size, size];
        var baseMatrix = new bool[size, size];

        PlaceFunctionPatterns(version, baseMatrix, functionModules);

        // 5. Place Data Bits in Zig-Zag pattern
        PlaceDataBits(finalBits, baseMatrix, functionModules);

        // 6. Masking & Penalty Selection
        int bestMask = 0;
        int minPenalty = int.MaxValue;
        bool[,] bestMatrix = new bool[size, size];

        for (int mask = 0; mask < 8; mask++)
        {
            var maskedMatrix = ApplyMask(baseMatrix, functionModules, mask);
            PlaceFormatAndVersionInfo(version, level, mask, maskedMatrix);
            int penalty = CalculatePenalty(maskedMatrix);
            if (penalty < minPenalty)
            {
                minPenalty = penalty;
                bestMask = mask;
                bestMatrix = maskedMatrix;
            }
        }

        return new QrCode(version, level, bestMask, bestMatrix);
    }

    private static int SelectSmallestVersion(int dataByteCount, QrErrorCorrectionLevel level)
    {
        for (int v = MinVersion; v <= MaxVersion; v++)
        {
            var spec = GetBlockSpec(v, level);
            int charCountBits = v < 10 ? 8 : 16;
            // 4 bits mode + charCountBits + data*8
            int requiredBits = 4 + charCountBits + dataByteCount * 8;
            int capacityBits = spec.TotalDataCodewords * 8;
            if (requiredBits <= capacityBits)
            {
                return v;
            }
        }
        throw new ArgumentException($"Data payload of {dataByteCount} bytes exceeds maximum QR Code capacity at {level} level.", nameof(dataByteCount));
    }

    private static void PlaceFunctionPatterns(int version, bool[,] matrix, bool[,] function)
    {
        int size = matrix.GetLength(0);

        // 3 Finder patterns (7x7) + separators (8x8)
        PlaceFinder(0, 0, matrix, function);
        PlaceFinder(size - 7, 0, matrix, function);
        PlaceFinder(0, size - 7, matrix, function);

        // Timing patterns
        for (int i = 8; i < size - 8; i++)
        {
            bool val = i % 2 == 0;
            if (!function[6, i])
            {
                matrix[6, i] = val;
                function[6, i] = true;
            }
            if (!function[i, 6])
            {
                matrix[i, 6] = val;
                function[i, 6] = true;
            }
        }

        // Dark module at (4 * version + 9, 8) = (size - 8, 8)
        matrix[4 * version + 9, 8] = true;
        function[4 * version + 9, 8] = true;

        // Alignment patterns
        int[] alignCoords = GetAlignmentPatternPositions(version);
        for (int r = 0; r < alignCoords.Length; r++)
        {
            for (int c = 0; c < alignCoords.Length; c++)
            {
                int row = alignCoords[r];
                int col = alignCoords[c];
                // Skip if overlapping finder patterns
                if ((row <= 8 && col <= 8) || (row <= 8 && col >= size - 8) || (row >= size - 8 && col <= 8))
                {
                    continue;
                }
                PlaceAlignment(row, col, matrix, function);
            }
        }

        // Reserve Format Info areas
        for (int i = 0; i <= 8; i++)
        {
            if (i != 6) { function[8, i] = true; function[i, 8] = true; }
        }
        for (int i = 0; i < 7; i++)
        {
            function[size - 1 - i, 8] = true;
        }
        for (int i = 0; i < 8; i++)
        {
            function[8, size - 1 - i] = true;
        }

        // Reserve Version Info areas (Versions >= 7)
        if (version >= 7)
        {
            for (int r = 0; r < 6; r++)
            {
                for (int c = 0; c < 3; c++)
                {
                    function[r, size - 11 + c] = true;
                    function[size - 11 + c, r] = true;
                }
            }
        }
    }

    private static void PlaceFinder(int startRow, int startCol, bool[,] matrix, bool[,] function)
    {
        int size = matrix.GetLength(0);
        for (int r = -1; r <= 7; r++)
        {
            for (int c = -1; c <= 7; c++)
            {
                int row = startRow + r;
                int col = startCol + c;
                if (row >= 0 && row < size && col >= 0 && col < size)
                {
                    function[row, col] = true;
                    if (r >= 0 && r <= 6 && c >= 0 && c <= 6)
                    {
                        bool dark = r == 0 || r == 6 || c == 0 || c == 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
                        matrix[row, col] = dark;
                    }
                    else
                    {
                        matrix[row, col] = false; // Separator
                    }
                }
            }
        }
    }

    private static void PlaceAlignment(int centerRow, int centerCol, bool[,] matrix, bool[,] function)
    {
        for (int r = -2; r <= 2; r++)
        {
            for (int c = -2; c <= 2; c++)
            {
                int row = centerRow + r;
                int col = centerCol + c;
                function[row, col] = true;
                bool dark = Math.Abs(r) == 2 || Math.Abs(c) == 2 || (r == 0 && c == 0);
                matrix[row, col] = dark;
            }
        }
    }

    private static void PlaceDataBits(BitBuffer bits, bool[,] matrix, bool[,] function)
    {
        int size = matrix.GetLength(0);
        int bitIndex = 0;
        int totalBits = bits.Length;

        // Right to left in 2-column steps
        for (int col = size - 1; col > 0; col -= 2)
        {
            if (col == 6) col--; // Skip vertical timing column

            bool upward = ((size - 1 - col) / 2) % 2 == 0;
            for (int i = 0; i < size; i++)
            {
                int row = upward ? (size - 1 - i) : i;
                for (int c = 0; c < 2; c++)
                {
                    int currCol = col - c;
                    if (!function[row, currCol])
                    {
                        bool bit = bitIndex < totalBits && bits.GetBit(bitIndex);
                        matrix[row, currCol] = bit;
                        bitIndex++;
                    }
                }
            }
        }
    }

    private static bool[,] ApplyMask(bool[,] baseMatrix, bool[,] function, int mask)
    {
        int size = baseMatrix.GetLength(0);
        var result = new bool[size, size];
        for (int r = 0; r < size; r++)
        {
            for (int c = 0; c < size; c++)
            {
                if (function[r, c])
                {
                    result[r, c] = baseMatrix[r, c];
                }
                else
                {
                    bool invert = mask switch
                    {
                        0 => (r + c) % 2 == 0,
                        1 => r % 2 == 0,
                        2 => c % 3 == 0,
                        3 => (r + c) % 3 == 0,
                        4 => (r / 2 + c / 3) % 2 == 0,
                        5 => ((r * c) % 2) + ((r * c) % 3) == 0,
                        6 => (((r * c) % 2) + ((r * c) % 3)) % 2 == 0,
                        7 => (((r + c) % 2) + ((r * c) % 3)) % 2 == 0,
                        _ => false
                    };
                    result[r, c] = baseMatrix[r, c] ^ invert;
                }
            }
        }
        return result;
    }

    private static void PlaceFormatAndVersionInfo(int version, QrErrorCorrectionLevel level, int mask, bool[,] matrix)
    {
        int size = matrix.GetLength(0);

        // 1. Format Information: 5 bits -> 15 bits BCH (15, 5) XOR 0x5412
        int eccBits = level switch
        {
            QrErrorCorrectionLevel.Low => 0b01,
            QrErrorCorrectionLevel.Medium => 0b00,
            QrErrorCorrectionLevel.Quartile => 0b11,
            QrErrorCorrectionLevel.High => 0b10,
            _ => 0b00
        };
        int formatData = (eccBits << 3) | mask;
        int rem = formatData << 10;
        int gen = 0x537; // x^10 + x^8 + x^5 + x^4 + x^2 + x + 1
        for (int i = 14; i >= 10; i--)
        {
            if ((rem & (1 << i)) != 0)
            {
                rem ^= gen << (i - 10);
            }
        }
        int formatCode = ((formatData << 10) | rem) ^ 0x5412;

        // Place format bits: Top-left and Split
        int[] r1 = [8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0];
        int[] c1 = [0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8];

        int[] r2 = [size - 1, size - 2, size - 3, size - 4, size - 5, size - 6, size - 7,
                    8, 8, 8, 8, 8, 8, 8, 8];
        int[] c2 = [8, 8, 8, 8, 8, 8, 8,
                    size - 8, size - 7, size - 6, size - 5, size - 4, size - 3, size - 2, size - 1];

        for (int i = 0; i < 15; i++)
        {
            bool bit = ((formatCode >> i) & 1) == 1;
            matrix[r1[i], c1[i]] = bit;
            matrix[r2[i], c2[i]] = bit;
        }

        // 2. Version Information (Versions >= 7): 6 bits -> 18 bits BCH (18, 6)
        if (version >= 7)
        {
            int vRem = version << 12;
            int vGen = 0x1F25;
            for (int i = 17; i >= 12; i--)
            {
                if ((vRem & (1 << i)) != 0)
                {
                    vRem ^= vGen << (i - 12);
                }
            }
            int versionCode = (version << 12) | vRem;

            for (int i = 0; i < 18; i++)
            {
                bool bit = ((versionCode >> i) & 1) == 1;
                int row = size - 11 + (i % 3);
                int col = i / 3;
                matrix[row, col] = bit;
                matrix[col, row] = bit;
            }
        }
    }

    private static int CalculatePenalty(bool[,] matrix)
    {
        int size = matrix.GetLength(0);
        int penalty = 0;

        // Rule 1: 5 or more same color in a row / column
        for (int r = 0; r < size; r++)
        {
            int run = 1;
            for (int c = 1; c < size; c++)
            {
                if (matrix[r, c] == matrix[r, c - 1])
                {
                    run++;
                }
                else
                {
                    if (run >= 5) penalty += 3 + (run - 5);
                    run = 1;
                }
            }
            if (run >= 5) penalty += 3 + (run - 5);
        }
        for (int c = 0; c < size; c++)
        {
            int run = 1;
            for (int r = 1; r < size; r++)
            {
                if (matrix[r, c] == matrix[r - 1, c])
                {
                    run++;
                }
                else
                {
                    if (run >= 5) penalty += 3 + (run - 5);
                    run = 1;
                }
            }
            if (run >= 5) penalty += 3 + (run - 5);
        }

        // Rule 2: 2x2 blocks of same color
        for (int r = 0; r < size - 1; r++)
        {
            for (int c = 0; c < size - 1; c++)
            {
                bool color = matrix[r, c];
                if (matrix[r + 1, c] == color && matrix[r, c + 1] == color && matrix[r + 1, c + 1] == color)
                {
                    penalty += 3;
                }
            }
        }

        // Rule 3: 1:1:3:1:1 pattern with 4 light modules before or after
        for (int r = 0; r < size; r++)
        {
            for (int c = 0; c <= size - 11; c++)
            {
                if (IsFinderLike(matrix[r, c], matrix[r, c + 1], matrix[r, c + 2], matrix[r, c + 3], matrix[r, c + 4],
                                 matrix[r, c + 5], matrix[r, c + 6], matrix[r, c + 7], matrix[r, c + 8], matrix[r, c + 9], matrix[r, c + 10]))
                {
                    penalty += 40;
                }
            }
        }
        for (int c = 0; c < size; c++)
        {
            for (int r = 0; r <= size - 11; r++)
            {
                if (IsFinderLike(matrix[r, c], matrix[r + 1, c], matrix[r + 2, c], matrix[r + 3, c], matrix[r + 4, c],
                                 matrix[r + 5, c], matrix[r + 6, c], matrix[r + 7, c], matrix[r + 8, c], matrix[r + 9, c], matrix[r + 10, c]))
                {
                    penalty += 40;
                }
            }
        }

        // Rule 4: Balance of dark and light
        int darkCount = 0;
        for (int r = 0; r < size; r++)
        {
            for (int c = 0; c < size; c++)
            {
                if (matrix[r, c]) darkCount++;
            }
        }
        int total = size * size;
        int percent = (darkCount * 100) / total;
        int prev5 = (percent / 5) * 5;
        int next5 = prev5 + 5;
        int diff1 = Math.Abs(prev5 - 50) / 5;
        int diff2 = Math.Abs(next5 - 50) / 5;
        penalty += Math.Min(diff1, diff2) * 10;

        return penalty;
    }

    private static bool IsFinderLike(bool m0, bool m1, bool m2, bool m3, bool m4, bool m5, bool m6, bool m7, bool m8, bool m9, bool m10)
    {
        // 0000 1 0 111 0 1 OR 1 0 111 0 1 0000
        bool p1 = !m0 && !m1 && !m2 && !m3 && m4 && !m5 && m6 && m7 && m8 && !m9 && m10;
        bool p2 = m0 && !m1 && m2 && m3 && m4 && !m5 && m6 && !m7 && !m8 && !m9 && !m10;
        return p1 || p2;
    }

    // GF(256) Multiplication
    private static byte GfMultiply(byte a, byte b)
    {
        if (a == 0 || b == 0) return 0;
        return ExpTable[LogTable[a] + LogTable[b]];
    }

    private static byte[] GetGeneratorPolynomial(int numEccCodewords)
    {
        var poly = new byte[numEccCodewords + 1];
        poly[0] = 1;
        for (int i = 0; i < numEccCodewords; i++)
        {
            byte factor = ExpTable[i];
            for (int j = i + 1; j >= 1; j--)
            {
                poly[j] = (byte)(poly[j] ^ GfMultiply(poly[j - 1], factor));
            }
            poly[0] = GfMultiply(poly[0], factor);
        }
        Array.Reverse(poly);
        return poly;
    }

    private static byte[] ComputeEcc(ReadOnlySpan<byte> data, byte[] genPoly)
    {
        int numEcc = genPoly.Length - 1;
        byte[] result = new byte[numEcc];
        foreach (byte b in data)
        {
            byte factor = (byte)(b ^ result[0]);
            for (int j = 0; j < numEcc - 1; j++)
            {
                result[j] = (byte)(result[j + 1] ^ GfMultiply(genPoly[j + 1], factor));
            }
            result[numEcc - 1] = GfMultiply(genPoly[numEcc], factor);
        }
        return result;
    }

    private record struct BlockSpec(
        int EccCodewordsPerBlock,
        int Group1Blocks,
        int Group1DataCodewords,
        int Group2Blocks,
        int Group2DataCodewords)
    {
        public int TotalDataCodewords => (Group1Blocks * Group1DataCodewords) + (Group2Blocks * Group2DataCodewords);
    }

    private static BlockSpec GetBlockSpec(int version, QrErrorCorrectionLevel level)
    {
        // ISO/IEC 18004 Table 9 - Error correction characteristics
        return (version, level) switch
        {
            (1, QrErrorCorrectionLevel.Low) => new(7, 1, 19, 0, 0),
            (1, QrErrorCorrectionLevel.Medium) => new(10, 1, 16, 0, 0),
            (1, QrErrorCorrectionLevel.Quartile) => new(13, 1, 13, 0, 0),
            (1, QrErrorCorrectionLevel.High) => new(17, 1, 9, 0, 0),

            (2, QrErrorCorrectionLevel.Low) => new(10, 1, 34, 0, 0),
            (2, QrErrorCorrectionLevel.Medium) => new(16, 1, 28, 0, 0),
            (2, QrErrorCorrectionLevel.Quartile) => new(22, 1, 22, 0, 0),
            (2, QrErrorCorrectionLevel.High) => new(28, 1, 16, 0, 0),

            (3, QrErrorCorrectionLevel.Low) => new(15, 1, 55, 0, 0),
            (3, QrErrorCorrectionLevel.Medium) => new(26, 1, 44, 0, 0),
            (3, QrErrorCorrectionLevel.Quartile) => new(18, 2, 17, 0, 0),
            (3, QrErrorCorrectionLevel.High) => new(22, 2, 13, 0, 0),

            (4, QrErrorCorrectionLevel.Low) => new(20, 1, 80, 0, 0),
            (4, QrErrorCorrectionLevel.Medium) => new(18, 2, 32, 0, 0),
            (4, QrErrorCorrectionLevel.Quartile) => new(26, 2, 24, 0, 0),
            (4, QrErrorCorrectionLevel.High) => new(16, 4, 9, 0, 0),

            (5, QrErrorCorrectionLevel.Low) => new(26, 1, 108, 0, 0),
            (5, QrErrorCorrectionLevel.Medium) => new(24, 2, 43, 0, 0),
            (5, QrErrorCorrectionLevel.Quartile) => new(18, 2, 15, 2, 16),
            (5, QrErrorCorrectionLevel.High) => new(22, 2, 11, 2, 12),

            (6, QrErrorCorrectionLevel.Low) => new(18, 2, 68, 0, 0),
            (6, QrErrorCorrectionLevel.Medium) => new(16, 4, 27, 0, 0),
            (6, QrErrorCorrectionLevel.Quartile) => new(24, 4, 19, 0, 0),
            (6, QrErrorCorrectionLevel.High) => new(28, 4, 15, 0, 0),

            (7, QrErrorCorrectionLevel.Low) => new(20, 2, 78, 0, 0),
            (7, QrErrorCorrectionLevel.Medium) => new(18, 4, 31, 0, 0),
            (7, QrErrorCorrectionLevel.Quartile) => new(18, 2, 14, 4, 15),
            (7, QrErrorCorrectionLevel.High) => new(26, 4, 13, 1, 14),

            (8, QrErrorCorrectionLevel.Low) => new(24, 2, 97, 0, 0),
            (8, QrErrorCorrectionLevel.Medium) => new(22, 2, 38, 2, 39),
            (8, QrErrorCorrectionLevel.Quartile) => new(22, 4, 18, 2, 19),
            (8, QrErrorCorrectionLevel.High) => new(26, 4, 14, 2, 15),

            (9, QrErrorCorrectionLevel.Low) => new(30, 2, 116, 0, 0),
            (9, QrErrorCorrectionLevel.Medium) => new(22, 3, 36, 2, 37),
            (9, QrErrorCorrectionLevel.Quartile) => new(20, 4, 16, 4, 17),
            (9, QrErrorCorrectionLevel.High) => new(24, 4, 12, 4, 13),

            (10, QrErrorCorrectionLevel.Low) => new(18, 2, 68, 2, 69),
            (10, QrErrorCorrectionLevel.Medium) => new(26, 4, 43, 1, 44),
            (10, QrErrorCorrectionLevel.Quartile) => new(24, 6, 19, 2, 20),
            (10, QrErrorCorrectionLevel.High) => new(28, 6, 15, 2, 16),

            (11, QrErrorCorrectionLevel.Low) => new(20, 4, 81, 0, 0),
            (11, QrErrorCorrectionLevel.Medium) => new(30, 1, 50, 4, 51),
            (11, QrErrorCorrectionLevel.Quartile) => new(28, 4, 22, 4, 23),
            (11, QrErrorCorrectionLevel.High) => new(24, 3, 12, 8, 13),

            (12, QrErrorCorrectionLevel.Low) => new(24, 2, 92, 2, 93),
            (12, QrErrorCorrectionLevel.Medium) => new(22, 6, 36, 2, 37),
            (12, QrErrorCorrectionLevel.Quartile) => new(26, 4, 20, 6, 21),
            (12, QrErrorCorrectionLevel.High) => new(28, 7, 14, 4, 15),

            (13, QrErrorCorrectionLevel.Low) => new(26, 4, 107, 0, 0),
            (13, QrErrorCorrectionLevel.Medium) => new(22, 8, 37, 1, 38),
            (13, QrErrorCorrectionLevel.Quartile) => new(24, 8, 20, 4, 21),
            (13, QrErrorCorrectionLevel.High) => new(22, 12, 11, 4, 12),

            (14, QrErrorCorrectionLevel.Low) => new(30, 3, 115, 1, 116),
            (14, QrErrorCorrectionLevel.Medium) => new(24, 4, 40, 5, 41),
            (14, QrErrorCorrectionLevel.Quartile) => new(20, 11, 16, 5, 17),
            (14, QrErrorCorrectionLevel.High) => new(24, 11, 12, 5, 13),

            (15, QrErrorCorrectionLevel.Low) => new(22, 5, 87, 1, 88),
            (15, QrErrorCorrectionLevel.Medium) => new(24, 5, 41, 5, 42),
            (15, QrErrorCorrectionLevel.Quartile) => new(30, 5, 24, 7, 25),
            (15, QrErrorCorrectionLevel.High) => new(24, 11, 12, 7, 13),

            (16, QrErrorCorrectionLevel.Low) => new(24, 5, 98, 1, 99),
            (16, QrErrorCorrectionLevel.Medium) => new(28, 7, 45, 3, 46),
            (16, QrErrorCorrectionLevel.Quartile) => new(24, 15, 19, 2, 20),
            (16, QrErrorCorrectionLevel.High) => new(30, 3, 15, 13, 16),

            (17, QrErrorCorrectionLevel.Low) => new(28, 1, 107, 5, 108),
            (17, QrErrorCorrectionLevel.Medium) => new(28, 10, 46, 1, 47),
            (17, QrErrorCorrectionLevel.Quartile) => new(28, 1, 22, 15, 23),
            (17, QrErrorCorrectionLevel.High) => new(28, 2, 14, 17, 15),

            (18, QrErrorCorrectionLevel.Low) => new(30, 5, 120, 1, 121),
            (18, QrErrorCorrectionLevel.Medium) => new(26, 9, 43, 4, 44),
            (18, QrErrorCorrectionLevel.Quartile) => new(28, 17, 22, 1, 23),
            (18, QrErrorCorrectionLevel.High) => new(28, 2, 14, 19, 15),

            (19, QrErrorCorrectionLevel.Low) => new(28, 3, 113, 4, 114),
            (19, QrErrorCorrectionLevel.Medium) => new(26, 3, 44, 11, 45),
            (19, QrErrorCorrectionLevel.Quartile) => new(26, 17, 21, 4, 22),
            (19, QrErrorCorrectionLevel.High) => new(26, 9, 13, 16, 14),

            (20, QrErrorCorrectionLevel.Low) => new(28, 3, 107, 5, 108),
            (20, QrErrorCorrectionLevel.Medium) => new(26, 3, 41, 13, 42),
            (20, QrErrorCorrectionLevel.Quartile) => new(30, 15, 24, 5, 25),
            (20, QrErrorCorrectionLevel.High) => new(28, 15, 15, 10, 16),

            _ => GetHigherVersionBlockSpec(version, level)
        };
    }

    private static BlockSpec GetHigherVersionBlockSpec(int version, QrErrorCorrectionLevel level)
    {
        // High versions up to 40 (fallback formula based on total raw modules)
        int totalModules = (4 * version + 17) * (4 * version + 17);
        int functionModules = 3 * 64 + (4 * version + 17 - 16) * 2;
        int availableModules = totalModules - functionModules;
        int totalCodewords = availableModules / 8;
        int approxEcc = level switch
        {
            QrErrorCorrectionLevel.Low => (int)(totalCodewords * 0.20),
            QrErrorCorrectionLevel.Medium => (int)(totalCodewords * 0.35),
            QrErrorCorrectionLevel.Quartile => (int)(totalCodewords * 0.50),
            QrErrorCorrectionLevel.High => (int)(totalCodewords * 0.60),
            _ => (int)(totalCodewords * 0.35)
        };
        int totalData = totalCodewords - approxEcc;
        int blocks = Math.Max(1, totalCodewords / 50);
        int eccPerBlock = approxEcc / blocks;
        int dataPerBlock = totalData / blocks;
        int rem = totalData % blocks;
        return new BlockSpec(eccPerBlock, blocks - rem, dataPerBlock, rem, dataPerBlock + 1);
    }

    private static int[] GetAlignmentPatternPositions(int version)
    {
        if (version == 1) return [];
        int count = (version / 7) + 2;
        int[] result = new int[count];
        result[0] = 6;
        if (count == 2)
        {
            result[1] = 4 * version + 10;
            return result;
        }

        int step = (int)Math.Ceiling((double)(4 * version + 4) / (count - 1));
        if (step % 2 != 0) step++; // Step must be even
        for (int i = count - 1; i >= 1; i--)
        {
            result[i] = (4 * version + 10) - (count - 1 - i) * step;
        }
        return result;
    }

    private static int GetRemainderBits(int version)
    {
        return version switch
        {
            >= 2 and <= 6 => 7,
            >= 14 and <= 20 => 3,
            >= 21 and <= 27 => 4,
            >= 28 and <= 34 => 3,
            _ => 0
        };
    }

    private sealed class BitBuffer
    {
        private readonly List<bool> _bits = [];

        public int Length => _bits.Count;

        public void AppendBits(int value, int count)
        {
            for (int i = count - 1; i >= 0; i--)
            {
                _bits.Add(((value >> i) & 1) == 1);
            }
        }

        public bool GetBit(int index) => _bits[index];

        public byte[] ToByteArray()
        {
            int numBytes = (_bits.Count + 7) / 8;
            byte[] bytes = new byte[numBytes];
            for (int i = 0; i < _bits.Count; i++)
            {
                if (_bits[i])
                {
                    bytes[i / 8] |= (byte)(1 << (7 - (i % 8)));
                }
            }
            return bytes;
        }
    }
}
