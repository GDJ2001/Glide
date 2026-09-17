using Glide.Core.Security;
using Xunit;

namespace Glide.Tests;

public class QrCodeGeneratorTests
{
    [Fact]
    public void EncodeText_ShortString_GeneratesValidVersion1Matrix()
    {
        var qr = QrCodeGenerator.EncodeText("HELLO", QrErrorCorrectionLevel.Medium);

        Assert.Equal(1, qr.Version);
        Assert.Equal(21, qr.Size);
        Assert.Equal(QrErrorCorrectionLevel.Medium, qr.ErrorCorrectionLevel);

        // Verify top-left finder pattern center (row 3, col 3) is dark
        Assert.True(qr.GetModule(3, 3));

        // Verify finder pattern corners (row 0, col 0) are dark
        Assert.True(qr.GetModule(0, 0));
        Assert.True(qr.GetModule(0, 6));
        Assert.True(qr.GetModule(6, 0));
        Assert.True(qr.GetModule(6, 6));

        // Verify finder pattern outer separator (row 0, col 7) is light
        Assert.False(qr.GetModule(0, 7));
        Assert.False(qr.GetModule(7, 0));

        // Verify dark module at (4*1 + 9, 8) = (13, 8)
        Assert.True(qr.GetModule(13, 8));
    }

    [Fact]
    public void EncodeText_WebShareUrl_SelectsCorrectVersionAndSize()
    {
        string url = "http://192.168.1.100:52341/?token=3f8a4b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a";
        var qr = QrCodeGenerator.EncodeText(url, QrErrorCorrectionLevel.Medium);

        Assert.True(qr.Version >= 4);
        int expectedSize = 4 * qr.Version + 17;
        Assert.Equal(expectedSize, qr.Size);

        // Verify timing patterns on row 6 and col 6 between finders
        for (int i = 8; i < qr.Size - 8; i++)
        {
            bool expected = i % 2 == 0;
            Assert.Equal(expected, qr.GetModule(6, i));
            Assert.Equal(expected, qr.GetModule(i, 6));
        }

        // Verify dark module at (4*V + 9, 8)
        Assert.True(qr.GetModule(4 * qr.Version + 9, 8));
    }

    [Fact]
    public void EncodeText_PairingInvitationUri_EncodesSuccessfully()
    {
        string pairingUri = "glide://pair/v1#eyJpbnZpdGF0aW9uSWQiOiJjOGYwZGYwZC0wMDExLTIyMzMtNDQ1NS02Njc3ODg5OWFhYmIiLCJuYW1lIjoiUGl4ZWwgOSIsImVuZHBvaW50cyI6W3siaG9zdCI6IjE5Mi4xNjguMS4xMDUiLCJwb3J0Ijo1NDMxMn1dfQ";
        var qr = QrCodeGenerator.EncodeText(pairingUri, QrErrorCorrectionLevel.Medium);

        Assert.True(qr.Version >= 7);
        Assert.Equal(4 * qr.Version + 17, qr.Size);

        // Check top-right finder
        Assert.True(qr.GetModule(0, qr.Size - 7));
        Assert.True(qr.GetModule(3, qr.Size - 4));

        // Check bottom-left finder
        Assert.True(qr.GetModule(qr.Size - 7, 0));
        Assert.True(qr.GetModule(qr.Size - 4, 3));
    }

    [Fact]
    public void QrCode_ToMatrix_ReturnsIndependentCopy()
    {
        var qr = QrCodeGenerator.EncodeText("TEST", QrErrorCorrectionLevel.Low);
        var matrix = qr.ToMatrix();

        Assert.Equal(qr.Size, matrix.GetLength(0));
        Assert.Equal(qr.Size, matrix.GetLength(1));

        // Mutating returned matrix does not change qr
        bool original = qr.GetModule(0, 0);
        matrix[0, 0] = !original;
        Assert.Equal(original, qr.GetModule(0, 0));
    }

    [Fact]
    public void EncodeText_Null_ThrowsArgumentNullException()
    {
        Assert.Throws<ArgumentNullException>(() => QrCodeGenerator.EncodeText(null!));
    }
}
