using Glide.App;
using Xunit;

namespace Glide.Tests;

public sealed class ResumeCliArgsTests
{
    [Fact]
    public void TryParseResumeRunArgs_AcceptsDestinationAndConnectInAnyOrder()
    {
        const string sid = "11111111-1111-4111-8111-111111111111";
        string customDest = Path.Combine(Path.GetTempPath(), "glide-cli-dest");

        Assert.True(Program.TryParseResumeRunArgs(
            ["run", sid, "--destination", customDest, "--connect", "192.168.1.50:42424"],
            out var parsed));
        Assert.NotNull(parsed);
        Assert.Equal(sid, parsed!.SessionId);
        Assert.Equal(Path.GetFullPath(customDest), parsed.DestinationRoot);
        Assert.Equal("192.168.1.50:42424", parsed.ConnectEndpoint);

        Assert.True(Program.TryParseResumeRunArgs(["run", sid], out var minimal));
        Assert.NotNull(minimal);
        Assert.Null(minimal!.DestinationRoot);
        Assert.Null(minimal.ConnectEndpoint);

        Assert.False(Program.TryParseResumeRunArgs(["run", "not-a-uuid", "--destination", customDest], out _));
        Assert.False(Program.TryParseResumeRunArgs(["run", sid, "--unknown"], out _));
    }
}
