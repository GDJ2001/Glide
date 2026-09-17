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
}
