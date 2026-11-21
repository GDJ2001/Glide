using Glide.Desktop.Services;

namespace Glide.Desktop;

/// <summary>
/// Compatibility facade over <see cref="DesktopBatchPreparer"/> for expanding files and folders
/// chosen by a desktop user into protocol-safe transfer items.
/// </summary>
public static class DesktopTransferSelection
{
    public static DesktopTransferSelectionResult Expand(
        IEnumerable<string> sourcePaths) =>
        DesktopBatchPreparer.Expand(sourcePaths);
}

public sealed record DesktopTransferSelectionIssue(string Path, string Reason);

public sealed record DesktopTransferSelectionResult(
    IReadOnlyList<DesktopTransferFile> Files,
    IReadOnlyList<DesktopTransferSelectionIssue> Skipped);
