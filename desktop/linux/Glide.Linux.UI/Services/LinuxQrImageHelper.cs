using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Glide.Core.Security;

namespace Glide.Linux.UI.Services;

/// <summary>Creates a local, high-contrast QR bitmap without a web dependency.</summary>
public static class LinuxQrImageHelper
{
    public static Bitmap Create(string invitationUri, int pixelScale = 5, int quietZone = 4)
    {
        var qr = QrCodeGenerator.EncodeText(invitationUri, QrErrorCorrectionLevel.Low);
        int width = checked((qr.Size + 2 * quietZone) * pixelScale);
        int stride = checked(width * 4);
        var pixels = new byte[checked(stride * width)];
        Array.Fill(pixels, (byte)255);

        for (int row = 0; row < qr.Size; row++)
        for (int column = 0; column < qr.Size; column++)
        {
            if (!qr.GetModule(row, column)) continue;
            int firstX = (column + quietZone) * pixelScale;
            int firstY = (row + quietZone) * pixelScale;
            for (int y = 0; y < pixelScale; y++)
            for (int x = 0; x < pixelScale; x++)
            {
                int offset = (firstY + y) * stride + (firstX + x) * 4;
                pixels[offset] = pixels[offset + 1] = pixels[offset + 2] = 0;
            }
        }

        var bitmap = new WriteableBitmap(
            new PixelSize(width, width), new Vector(96, 96),
            PixelFormat.Bgra8888, AlphaFormat.Opaque);
        using (var frame = bitmap.Lock())
        {
            for (int row = 0; row < width; row++)
            {
                Marshal.Copy(pixels, row * stride,
                    IntPtr.Add(frame.Address, row * frame.RowBytes), stride);
            }
        }
        return bitmap;
    }
}
