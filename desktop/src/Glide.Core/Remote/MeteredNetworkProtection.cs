using System;

namespace Glide.Core.Remote;

/// <summary>
/// Cost classification of the current network connection.
/// Conforms to OS network cost taxonomies (Android, Windows, Apple, Linux).
/// </summary>
public enum NetworkCostType
{
    /// <summary>
    /// Connection is unmetered (e.g., standard Wi-Fi, Ethernet).
    /// </summary>
    Unrestricted = 0,

    /// <summary>
    /// Connection is metered (e.g., cellular LTE/5G, capped mobile hotspot).
    /// </summary>
    Metered = 1,

    /// <summary>
    /// Cost status is unknown or indeterminate.
    /// </summary>
    Unknown = 2
}

/// <summary>
/// Snapshot of the active network interface's metered cost status.
/// </summary>
public sealed record MeteredNetworkState(
    bool IsMetered,
    NetworkCostType CostType,
    string? NetworkName = null
);

/// <summary>
/// Abstraction for platform-specific metered network state discovery.
/// Concrete implementations bridge to:
/// - Windows: NetworkCostType
/// - Android: NET_CAPABILITY_NOT_METERED
/// - Apple: nw_path_is_expensive / nw_path_is_constrained
/// - Linux: NetworkManager org.freedesktop.NetworkManager.Device.Metered
/// </summary>
public interface IMeteredNetworkDetector
{
    MeteredNetworkState GetCurrentNetworkState();
}

/// <summary>
/// Encapsulates the user confirmation dialog model when initiating or accepting
/// a transfer over a metered cellular connection.
/// Spec: REMOTE_INTERNET_TRANSFER_SPECIFICATION.md Section 6.2.
/// </summary>
public sealed record MeteredTransferPrompt(
    string ItemName,
    long TotalSizeBytes,
    string FormattedSize,
    string WarningMessage,
    string CancelActionText = "Cancel Transfer",
    string ProceedActionText = "Proceed on Cellular"
);

/// <summary>
/// Policy engine for evaluating metered network conditions and generating user disclosure prompts.
/// </summary>
public sealed class MeteredTransferPolicy
{
    private readonly IMeteredNetworkDetector _detector;

    public MeteredTransferPolicy(IMeteredNetworkDetector detector)
    {
        _detector = detector ?? throw new ArgumentNullException(nameof(detector));
    }

    /// <summary>
    /// Checks whether the current network state requires explicit user confirmation for the proposed transfer.
    /// </summary>
    public bool RequiresConfirmation(long totalSizeBytes)
    {
        if (totalSizeBytes <= 0)
        {
            return false;
        }

        MeteredNetworkState state = _detector.GetCurrentNetworkState();
        return state.IsMetered || state.CostType == NetworkCostType.Metered;
    }

    /// <summary>
    /// Formats a byte count into a human-readable decimal unit (B, KB, MB, GB).
    /// </summary>
    public static string FormatByteSize(long bytes)
    {
        if (bytes < 0)
        {
            return "0 B";
        }

        const long kb = 1024L;
        const long mb = kb * 1024L;
        const long gb = mb * 1024L;

        if (bytes >= gb)
        {
            double val = (double)bytes / gb;
            return $"{val:0.#} GB";
        }
        if (bytes >= mb)
        {
            double val = (double)bytes / mb;
            return $"{val:0.#} MB";
        }
        if (bytes >= kb)
        {
            double val = (double)bytes / kb;
            return $"{val:0.#} KB";
        }

        return $"{bytes} B";
    }

    /// <summary>
    /// Constructs a standardized MeteredTransferPrompt for display in the UI.
    /// </summary>
    public MeteredTransferPrompt CreatePrompt(string itemName, long totalSizeBytes)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(itemName);
        if (totalSizeBytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(totalSizeBytes), "Total size cannot be negative.");
        }

        string formattedSize = FormatByteSize(totalSizeBytes);
        string warningMessage = $"Transferring \"{itemName}\" ({formattedSize}) will use your mobile cellular data. Carrier charges may apply.";

        return new MeteredTransferPrompt(
            ItemName: itemName,
            TotalSizeBytes: totalSizeBytes,
            FormattedSize: formattedSize,
            WarningMessage: warningMessage
        );
    }
}
