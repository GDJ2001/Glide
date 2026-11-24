using Glide.Windows.UI.Services;

namespace Glide.Windows.UI.Tests;

public sealed class MsixReadinessTests
{
    [Fact]
    public void PackageIdentityHelper_MapsWin32ResultCodesCorrectly()
    {
        Assert.True(PackageIdentityHelper.IsPackagedResult(PackageIdentityHelper.ErrorSuccess));
        Assert.True(PackageIdentityHelper.IsPackagedResult(PackageIdentityHelper.ErrorInsufficientBuffer));
        Assert.False(PackageIdentityHelper.IsPackagedResult(PackageIdentityHelper.AppModelErrorNoPackage));
    }

    [Fact]
    public void PackageIdentityHelper_UnpackagedTestHost_ReturnsFalseWithoutThrowing()
    {
        Assert.False(PackageIdentityHelper.HasPackageIdentity);
    }

    [Fact]
    public async Task ForwardActivationAsync_WhenPipeUnavailable_HandlesTimeoutGracefully()
    {
        bool forwarded = await WindowsSingleInstance.ForwardActivationAsync(
            ["glide://pair/v1#test"],
            connectTimeoutMs: 50);

        Assert.False(forwarded);
    }

    [Fact]
    public async Task ForwardActivationAsync_WhenCancelled_ReturnsFalseGracefully()
    {
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        bool forwarded = await WindowsSingleInstance.ForwardActivationAsync(
            ["glide://pair/v1#test"],
            connectTimeoutMs: 50,
            cancellationToken: cts.Token);

        Assert.False(forwarded);
    }

    [Fact]
    public void GlideUpdateConfiguration_EmptyStoreBuildProperties_IsInvalid()
    {
        var config = new GlideUpdateConfiguration(string.Empty, string.Empty);

        Assert.False(config.IsValid);
        Assert.False(string.IsNullOrWhiteSpace(config.ValidationError));
    }
}
