using System.Reflection;
using System.Text;

namespace Glide.Transport.WebRtc;

/// <summary>
/// Embedded WebRTC Static Client SPA assets for .NET (HTML/CSS/JS).
/// Loaded from EmbeddedResource.
/// </summary>
public static class GlideWebRtcAssets
{
    private const string ResourcePrefix = "Glide.Transport.WebRtc.Assets";

    public static byte[] GetIndexHtml() => LoadResource("index.html");
    public static byte[] GetStyleCss() => LoadResource("style.css");
    public static byte[] GetAppJs() => LoadResource("app.js");

    private static byte[] LoadResource(string name)
    {
        var assembly = Assembly.GetExecutingAssembly();
        var resourceName = $"{ResourcePrefix}.{name}";
        using var stream = assembly.GetManifestResourceStream(resourceName);
        if (stream == null)
            throw new InvalidOperationException($"Embedded resource '{resourceName}' not found.");
        using var ms = new MemoryStream();
        stream.CopyTo(ms);
        return ms.ToArray();
    }
}
