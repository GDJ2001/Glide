using Glide.Core.Storage;
using Xunit;

namespace Glide.Tests;

public class WindowsPathSanitizerTests
{
    [Theory]
    [InlineData("document.pdf", "document.pdf")]
    [InlineData("photos/vacation/summer.jpg", "photos/vacation/summer.jpg")]
    [InlineData(@"nested\folder\file.txt", "nested/folder/file.txt")]
    [InlineData("archive-2026.tar.gz", "archive-2026.tar.gz")]
    public void ValidPaths_SanitizeCorrectly(string input, string expected)
    {
        string result = WindowsPathSanitizer.SanitizeRelativePath(input);
        Assert.Equal(expected, result);
    }

    [Theory]
    [InlineData("../secrets.txt")]
    [InlineData("foo/../../etc/passwd")]
    [InlineData("./relative.txt")]
    [InlineData("foo/./bar.txt")]
    public void PathTraversal_ThrowsArgumentException(string malicious)
    {
        Assert.Throws<ArgumentException>(() => WindowsPathSanitizer.SanitizeRelativePath(malicious));
    }

    [Theory]
    [InlineData(@"\\192.168.1.1\share\payload.exe")]
    [InlineData(@"//remote/share/file.txt")]
    [InlineData("/root/file.txt")]
    [InlineData(@"\windows\system32")]
    public void AbsoluteAndUncPaths_ThrowsArgumentException(string uncPath)
    {
        Assert.Throws<ArgumentException>(() => WindowsPathSanitizer.SanitizeRelativePath(uncPath));
    }

    [Theory]
    [InlineData(@"C:\Windows\System32\cmd.exe")]
    [InlineData("D:payload.exe")]
    [InlineData("z:file.txt")]
    public void DriveLetterPaths_ThrowsArgumentException(string drivePath)
    {
        Assert.Throws<ArgumentException>(() => WindowsPathSanitizer.SanitizeRelativePath(drivePath));
    }

    [Theory]
    [InlineData("CON")]
    [InlineData("con.txt")]
    [InlineData("PRN")]
    [InlineData("prn.dat")]
    [InlineData("AUX")]
    [InlineData("aux.tar.gz")]
    [InlineData("NUL")]
    [InlineData("NUL.zip")]
    [InlineData("COM1")]
    [InlineData("com9.bin")]
    [InlineData("LPT1")]
    [InlineData("lpt5.pdf")]
    [InlineData("folder/aux.txt")]
    [InlineData("folder/com1/file.txt")]
    public void ReservedDosDeviceNames_ThrowsArgumentException(string dosPath)
    {
        Assert.Throws<ArgumentException>(() => WindowsPathSanitizer.SanitizeRelativePath(dosPath));
    }

    [Theory]
    [InlineData("foo<bar.txt")]
    [InlineData("foo>bar.txt")]
    [InlineData("foo:bar.txt")]
    [InlineData("foo\"bar.txt")]
    [InlineData("foo|bar.txt")]
    [InlineData("foo?bar.txt")]
    [InlineData("foo*bar.txt")]
    public void IllegalWindowsCharacters_ThrowsArgumentException(string illegal)
    {
        Assert.Throws<ArgumentException>(() => WindowsPathSanitizer.SanitizeRelativePath(illegal));
    }

    [Theory]
    [InlineData("trailingdot.")]
    [InlineData("trailingspace ")]
    [InlineData("folder /file.txt")]
    [InlineData("folder./file.txt")]
    public void TrailingDotsOrSpaces_ThrowsArgumentException(string trailing)
    {
        Assert.Throws<ArgumentException>(() => WindowsPathSanitizer.SanitizeRelativePath(trailing));
    }

    [Theory]
    [InlineData("vacation.jpg", "vacation.jpg")]
    [InlineData("../../etc/passwd", "etc_passwd")]
    [InlineData(@"C:\Windows\System32\cmd.exe", "Windows_System32_cmd.exe")]
    [InlineData("test<file>:*\"|?name.txt", "test_file______name.txt")]
    [InlineData("CON.txt", "_CON.txt")]
    [InlineData("prn.pdf", "_prn.pdf")]
    [InlineData("AUX.dat", "_AUX.dat")]
    [InlineData("nul", "_nul")]
    [InlineData("COM1.txt", "_COM1.txt")]
    [InlineData("lpt9.bin", "_lpt9.bin")]
    [InlineData(null, "Received file")]
    [InlineData("", "Received file")]
    [InlineData("...", "Received file")]
    [InlineData("___", "Received file")]
    public void SanitizeWebFilename_SanitizesSafely(string? input, string expected)
    {
        string result = WindowsPathSanitizer.SanitizeWebFilename(input);
        Assert.Equal(expected, result);
    }

    [Fact]
    public void SanitizeWebFilename_TruncatesOver255Chars()
    {
        string longName = new string('a', 300) + ".txt";
        string result = WindowsPathSanitizer.SanitizeWebFilename(longName);
        Assert.True(result.Length <= 255);
    }
}

