using System;
using Glide.Core.Remote;
using Xunit;

namespace Glide.Tests;

public class MeteredNetworkProtectionTests
{
    private sealed class MockMeteredNetworkDetector : IMeteredNetworkDetector
    {
        public MeteredNetworkState State { get; set; }

        public MockMeteredNetworkDetector(bool isMetered, NetworkCostType costType = NetworkCostType.Metered)
        {
            State = new MeteredNetworkState(isMetered, costType, "TestConnection");
        }

        public MeteredNetworkState GetCurrentNetworkState() => State;
    }

    [Theory]
    [InlineData(0, "0 B")]
    [InlineData(512, "512 B")]
    [InlineData(1024, "1 KB")]
    [InlineData(1536, "1.5 KB")]
    [InlineData(1048576, "1 MB")]
    [InlineData(356515840, "340 MB")]
    [InlineData(1073741824, "1 GB")]
    [InlineData(2684354560, "2.5 GB")]
    public void FormatByteSize_FormatsCorrectly(long bytes, string expected)
    {
        string actual = MeteredTransferPolicy.FormatByteSize(bytes);
        Assert.Equal(expected, actual);
    }

    [Fact]
    public void RequiresConfirmation_UnmeteredConnection_ReturnsFalse()
    {
        var detector = new MockMeteredNetworkDetector(isMetered: false, costType: NetworkCostType.Unrestricted);
        var policy = new MeteredTransferPolicy(detector);

        bool required = policy.RequiresConfirmation(356515840L);
        Assert.False(required);
    }

    [Fact]
    public void RequiresConfirmation_MeteredConnection_ReturnsTrue()
    {
        var detector = new MockMeteredNetworkDetector(isMetered: true, costType: NetworkCostType.Metered);
        var policy = new MeteredTransferPolicy(detector);

        bool required = policy.RequiresConfirmation(356515840L);
        Assert.True(required);
    }

    [Fact]
    public void RequiresConfirmation_ZeroOrNegativeBytes_ReturnsFalse()
    {
        var detector = new MockMeteredNetworkDetector(isMetered: true, costType: NetworkCostType.Metered);
        var policy = new MeteredTransferPolicy(detector);

        Assert.False(policy.RequiresConfirmation(0));
        Assert.False(policy.RequiresConfirmation(-100));
    }

    [Fact]
    public void CreatePrompt_ConstructsStandardizedUserWarningDialog()
    {
        var detector = new MockMeteredNetworkDetector(isMetered: true);
        var policy = new MeteredTransferPolicy(detector);

        long sizeBytes = 356515840L; // 340 MB
        string itemName = "project_archive.zip";

        MeteredTransferPrompt prompt = policy.CreatePrompt(itemName, sizeBytes);

        Assert.Equal(itemName, prompt.ItemName);
        Assert.Equal(sizeBytes, prompt.TotalSizeBytes);
        Assert.Equal("340 MB", prompt.FormattedSize);
        Assert.Contains("project_archive.zip", prompt.WarningMessage);
        Assert.Contains("340 MB", prompt.WarningMessage);
        Assert.Contains("mobile cellular data", prompt.WarningMessage);
        Assert.Contains("Carrier charges may apply", prompt.WarningMessage);
        Assert.Equal("Cancel Transfer", prompt.CancelActionText);
        Assert.Equal("Proceed on Cellular", prompt.ProceedActionText);
    }

    [Fact]
    public void CreatePrompt_InvalidArguments_ThrowsExceptions()
    {
        var detector = new MockMeteredNetworkDetector(isMetered: true);
        var policy = new MeteredTransferPolicy(detector);

        Assert.Throws<ArgumentException>(() => policy.CreatePrompt("", 1024));
        Assert.Throws<ArgumentOutOfRangeException>(() => policy.CreatePrompt("file.txt", -5));
    }
}
