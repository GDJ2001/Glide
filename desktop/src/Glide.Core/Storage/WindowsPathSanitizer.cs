using System.Text;
using System.Text.RegularExpressions;

namespace Glide.Core.Storage;

public static class WindowsPathSanitizer
{
    private static readonly HashSet<string> ReservedDosNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    private static readonly char[] IllegalChars = ['<', '>', ':', '"', '/', '\\', '|', '?', '*'];

    public static void ValidateRelativePath(string rawPath)
    {
        SanitizeRelativePath(rawPath);
    }

    public static string SanitizeRelativePath(string rawPath)
    {
        if (string.IsNullOrWhiteSpace(rawPath))
        {
            throw new ArgumentException("Relative path cannot be null or empty.");
        }

        if (rawPath.Length > 260)
        {
            throw new ArgumentException($"Total path length {rawPath.Length} exceeds Windows maximum 260 characters.");
        }

        // Check for UNC prefix or absolute root
        if (rawPath.StartsWith(@"\\") || rawPath.StartsWith("//") || rawPath.StartsWith('/') || rawPath.StartsWith('\\'))
        {
            throw new ArgumentException("Absolute or UNC paths are strictly prohibited.");
        }

        // Check for drive letters (e.g., C: or D:)
        if (rawPath.Length >= 2 && char.IsLetter(rawPath[0]) && rawPath[1] == ':')
        {
            throw new ArgumentException("Drive letter paths are strictly prohibited.");
        }

        // Normalize slashes
        string normalized = rawPath.Replace('\\', '/');

        var segments = normalized.Split('/', StringSplitOptions.None);
        var sanitizedSegments = new List<string>();

        foreach (var segment in segments)
        {
            if (string.IsNullOrEmpty(segment))
            {
                throw new ArgumentException("Path contains empty segments (e.g. double slashes or leading/trailing slashes).");
            }

            if (segment == "." || segment == "..")
            {
                throw new ArgumentException("Path traversal segments ('.' or '..') are strictly prohibited.");
            }

            if (segment.Length > 255)
            {
                throw new ArgumentException($"Path component '{segment}' length exceeds 255 characters.");
            }

            // Reject trailing dots or spaces
            if (segment.EndsWith('.') || segment.EndsWith(' '))
            {
                throw new ArgumentException($"Path component '{segment}' cannot end with a dot or space in Windows.");
            }

            // Check for control characters or illegal Windows characters
            foreach (char c in segment)
            {
                if (c < 0x20 || IllegalChars.Contains(c))
                {
                    throw new ArgumentException($"Path component contains illegal Windows character 0x{(int)c:X2}: '{segment}'");
                }
            }

            // Check reserved DOS device names (e.g. CON, NUL, AUX.txt)
            string baseName = segment;
            int dotIdx = segment.IndexOf('.');
            if (dotIdx >= 0)
            {
                baseName = segment[..dotIdx];
            }

            if (ReservedDosNames.Contains(baseName))
            {
                throw new ArgumentException($"Path component '{segment}' matches reserved Windows DOS device name '{baseName}'.");
            }

            sanitizedSegments.Add(segment);
        }

        string sanitized = string.Join('/', sanitizedSegments);
        if (sanitized.Length > 260)
        {
            throw new ArgumentException($"Sanitized path length {sanitized.Length} exceeds Windows maximum 260 characters.");
        }

        return sanitized;
    }

    public static string SanitizeWebFilename(string? rawName, string fallback = "Received file")
    {
        if (string.IsNullOrWhiteSpace(rawName))
        {
            return fallback;
        }

        // 1. Unicode NFC normalization
        string normalized = rawName.Normalize(NormalizationForm.FormC);

        // 2. Strip drive letter prefix
        normalized = Regex.Replace(normalized, @"^[a-zA-Z]:[\\/]?", string.Empty);

        // 3. Strip directory traversal sequences (..)
        while (normalized.Contains(".."))
        {
            normalized = normalized.Replace("..", string.Empty);
        }

        // 4. Strip leading slashes / absolute prefixes
        normalized = normalized.TrimStart('/', '\\');

        // 5. Replace illegal characters and controls
        var sb = new StringBuilder(normalized.Length);
        foreach (char c in normalized)
        {
            if (c < 0x20 || c == 0x7F || IllegalChars.Contains(c))
            {
                sb.Append('_');
            }
            else
            {
                sb.Append(c);
            }
        }
        string cleaned = sb.ToString();

        // 6. Strip leading/trailing dots, underscores, whitespace
        cleaned = cleaned.Trim().Trim('.', '_', ' ');

        // 7. Check if empty or only navigation dots/underscores
        if (string.IsNullOrWhiteSpace(cleaned) || cleaned == "." || cleaned == ".." || cleaned.All(c => c == '_' || c == '.'))
        {
            return fallback;
        }

        // 8. Reserved DOS device names
        string baseName = cleaned;
        int dotIdx = cleaned.IndexOf('.');
        if (dotIdx >= 0)
        {
            baseName = cleaned[..dotIdx];
        }

        if (ReservedDosNames.Contains(baseName))
        {
            cleaned = "_" + cleaned;
        }

        // 9. Length caps: maximum 255 characters
        if (cleaned.Length > 255)
        {
            cleaned = cleaned[..255];
        }

        return string.IsNullOrWhiteSpace(cleaned) ? fallback : cleaned;
    }
}

