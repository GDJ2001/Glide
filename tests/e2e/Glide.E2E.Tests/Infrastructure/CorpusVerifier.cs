using System.Security.Cryptography;
using System.Text;

namespace Glide.E2E.Tests.Infrastructure;

public sealed record CorpusFileEntry(string RelativePath, long SizeBytes, string Sha256);

public sealed record CorpusManifest(IReadOnlyList<CorpusFileEntry> Items, long TotalBytes);

public static class CorpusVerifier
{
    public static string WriteIncompressibleFile(string filePath, long sizeBytes, int seed)
    {
        string? dir = Path.GetDirectoryName(filePath);
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }

        var rng = new Random(seed);
        byte[] buffer = new byte[64 * 1024];
        using (var fs = File.Open(filePath, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            long remaining = sizeBytes;
            while (remaining > 0)
            {
                int chunk = (int)Math.Min(buffer.Length, remaining);
                rng.NextBytes(buffer.AsSpan(0, chunk));
                fs.Write(buffer, 0, chunk);
                remaining -= chunk;
            }
            fs.Flush();
        }

        return ComputeFileSha256(filePath);
    }

    public static string WriteUtf8TextFile(string filePath, string content)
    {
        string? dir = Path.GetDirectoryName(filePath);
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }

        File.WriteAllText(filePath, content, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
        return ComputeFileSha256(filePath);
    }

    public static string ComputeFileSha256(string filePath)
    {
        using var fs = File.OpenRead(filePath);
        return Convert.ToHexStringLower(SHA256.HashData(fs));
    }

    public static CorpusManifest CreateSmallBatch(string sourceDir, int count, int baseSize = 1024, int seedOffset = 1)
    {
        Directory.CreateDirectory(sourceDir);
        for (int i = 1; i <= count; i++)
        {
            string path = Path.Combine(sourceDir, $"{i:D4}-item.bin");
            WriteIncompressibleFile(path, baseSize + (i * 19), seedOffset + i);
        }
        return BuildManifest(sourceDir);
    }

    public static CorpusManifest CreateExactItemCountBatch(string sourceDir, int count, int bytesPerItem = 64)
    {
        Directory.CreateDirectory(sourceDir);
        for (int i = 0; i < count; i++)
        {
            string sub = Path.Combine(sourceDir, $"bucket_{i / 100:D2}");
            Directory.CreateDirectory(sub);
            string path = Path.Combine(sub, $"item_{i:D4}.bin");
            WriteIncompressibleFile(path, bytesPerItem, 10_000 + i);
        }
        return BuildManifest(sourceDir);
    }

    public static CorpusManifest CreateDeveloperProjectBundle(string sourceDir, int largeMiB = 50)
    {
        Directory.CreateDirectory(sourceDir);

        // 0-byte file
        WriteIncompressibleFile(Path.Combine(sourceDir, "00-empty.bin"), 0, 1);

        // 36 small files (>32 items to force multi-page OFFER_PAGE)
        for (int i = 1; i <= 36; i++)
        {
            WriteIncompressibleFile(Path.Combine(sourceDir, $"small-{i:D2}.bin"), 512 + i * 29, 100 + i);
        }

        // Unicode and spaces
        WriteUtf8TextFile(Path.Combine(sourceDir, "Unicode_🚀_测试_файл.txt"), "Glide E2E Unicode 🌟✨");
        WriteUtf8TextFile(Path.Combine(sourceDir, "Project Notes With Spaces.md"), "# Glide E2E Notes\nVerified!");

        // Nested hierarchy + duplicate basename in separate folder
        WriteIncompressibleFile(Path.Combine(sourceDir, "docs", "specs", "readme.bin"), 4096, 501);
        WriteIncompressibleFile(Path.Combine(sourceDir, "archive", "readme.bin"), 8192, 502);

        // Medium 10 MiB + Large incompressible payload
        WriteIncompressibleFile(Path.Combine(sourceDir, "90-medium-10mib.bin"), 10L * 1024 * 1024, 900);
        if (largeMiB > 0)
        {
            WriteIncompressibleFile(Path.Combine(sourceDir, $"99-large-{largeMiB}mib.bin"), (long)largeMiB * 1024 * 1024, 999);
        }

        return BuildManifest(sourceDir);
    }

    public static CorpusManifest BuildManifest(string rootDir)
    {
        if (!Directory.Exists(rootDir))
        {
            return new CorpusManifest([], 0);
        }

        var entries = new List<CorpusFileEntry>();
        long total = 0;
        foreach (string file in Directory.GetFiles(rootDir, "*", SearchOption.AllDirectories)
                     .OrderBy(static f => f, StringComparer.Ordinal))
        {
            if (Path.GetFileName(file) == "corpus-manifest.json")
            {
                continue;
            }
            var fi = new FileInfo(file);
            string rel = Path.GetRelativePath(rootDir, file).Replace('\\', '/');
            string sha = ComputeFileSha256(file);
            entries.Add(new CorpusFileEntry(rel, fi.Length, sha));
            total += fi.Length;
        }

        return new CorpusManifest(entries, total);
    }

    public static void AssertDestinationMatchesManifest(CorpusManifest manifest, string destDir)
    {
        if (!Directory.Exists(destDir))
        {
            throw new DirectoryNotFoundException($"Destination directory does not exist: {destDir}");
        }

        var destFiles = Directory.GetFiles(destDir, "*", SearchOption.AllDirectories)
            .Select(f => Path.GetRelativePath(destDir, f).Replace('\\', '/'))
            .OrderBy(static p => p, StringComparer.Ordinal)
            .ToList();

        var expectedPaths = manifest.Items.Select(i => i.RelativePath).OrderBy(static p => p, StringComparer.Ordinal).ToList();
        if (destFiles.Count != expectedPaths.Count)
        {
            throw new InvalidOperationException(
                $"Destination file count {destFiles.Count} does not match expected {expectedPaths.Count}.");
        }

        foreach (CorpusFileEntry item in manifest.Items)
        {
            string destPath = Path.Combine(destDir, item.RelativePath.Replace('/', Path.DirectorySeparatorChar));
            if (!File.Exists(destPath))
            {
                throw new FileNotFoundException($"Missing expected destination file: {item.RelativePath}", destPath);
            }

            var fi = new FileInfo(destPath);
            if (fi.Length != item.SizeBytes)
            {
                throw new InvalidOperationException(
                    $"Byte length mismatch for '{item.RelativePath}': expected {item.SizeBytes}, actual {fi.Length}.");
            }

            string actualSha = ComputeFileSha256(destPath);
            if (!string.Equals(actualSha, item.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException(
                    $"SHA-256 mismatch for '{item.RelativePath}': expected {item.Sha256}, actual {actualSha}.");
            }
        }
    }

    public static void AssertDestinationEmpty(string destDir)
    {
        if (!Directory.Exists(destDir))
        {
            return;
        }

        string[] files = Directory.GetFiles(destDir, "*", SearchOption.AllDirectories);
        if (files.Length > 0)
        {
            throw new InvalidOperationException(
                $"Expected empty destination '{destDir}', found {files.Length} file(s): {string.Join(", ", files)}");
        }
    }
}
