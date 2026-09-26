using System.Windows.Media;
using System.Windows.Media.Imaging;
using Glide.Core.Security;

namespace Glide.Windows.UI.Common;

/// <summary>
/// Converts QR Code bit matrices into WPF ImageSources with high-contrast quiet zones.
/// </summary>
public static class QrImageHelper
{
    /// <summary>
    /// Generates a frozen BitmapSource from the provided text for direct XAML Image binding.
    /// </summary>
    public static ImageSource? GenerateQrImageSource(string text, int pixelScale = 10, int quietZone = 4)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;

        try
        {
            var qr = QrCodeGenerator.EncodeText(text, QrErrorCorrectionLevel.Low);
            int qrSize = qr.Size;
            int totalModules = qrSize + quietZone * 2;
            int imageWidth = totalModules * pixelScale;
            int imageHeight = imageWidth;
            int stride = imageWidth * 4; // 32 bpp BGRA
            byte[] pixels = new byte[stride * imageHeight];

            // Fill background with pure white (0xFF, 0xFF, 0xFF, 0xFF) for maximum scanner readability
            Array.Fill(pixels, (byte)0xFF);

            // Draw pure pitch-black modules (0x00, 0x00, 0x00, 0xFF) for highest contrast
            for (int r = 0; r < qrSize; r++)
            {
                for (int c = 0; c < qrSize; c++)
                {
                    if (qr.GetModule(r, c))
                    {
                        int startX = (c + quietZone) * pixelScale;
                        int startY = (r + quietZone) * pixelScale;

                        for (int y = 0; y < pixelScale; y++)
                        {
                            int rowOffset = (startY + y) * stride;
                            for (int x = 0; x < pixelScale; x++)
                            {
                                int pixelOffset = rowOffset + (startX + x) * 4;
                                pixels[pixelOffset + 0] = 0x00; // B
                                pixels[pixelOffset + 1] = 0x00; // G
                                pixels[pixelOffset + 2] = 0x00; // R
                                pixels[pixelOffset + 3] = 0xFF; // A
                            }
                        }
                    }
                }
            }

            var bitmap = BitmapSource.Create(
                imageWidth, imageHeight,
                96, 96,
                PixelFormats.Bgra32,
                null,
                pixels,
                stride);

            bitmap.Freeze();
            return bitmap;
        }
        catch
        {
            return null;
        }
    }
}
