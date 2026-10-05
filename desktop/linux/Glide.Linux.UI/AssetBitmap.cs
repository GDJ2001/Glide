using System;
using Avalonia.Media.Imaging;
using Avalonia.Platform;

namespace Glide.Linux.UI;

public sealed class AssetBitmap : Bitmap
{
    public AssetBitmap(string uri) : base(AssetLoader.Open(new Uri(uri)))
    {
    }
}
