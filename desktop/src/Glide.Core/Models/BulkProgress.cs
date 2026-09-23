namespace Glide.Core.Models;

/// <summary>
/// Progress update for a bulk data transfer operation.
/// </summary>
/// <param name="BulkRef">Unique reference for this bulk operation</param>
/// <param name="BytesTransferred">Number of bytes transferred so far</param>
/// <param name="IsComplete">Whether the transfer is complete</param>
public readonly record struct BulkProgress(
    string BulkRef,
    long BytesTransferred,
    bool IsComplete
);
