using System.Text.Json;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Xunit;

namespace Glide.Tests;

public sealed class ResumeBatchManifestTests
{
    private sealed record Fixture(ResumeBatchManifest Manifest, string Sha256);

    [Fact]
    public void MatchesSharedDigestAndBindsItemOrder()
    {
        var fixture = JsonSerializer.Deserialize<Fixture>(
            File.ReadAllText(FixturePath("resume_batch_manifest_v1.json")),
            new JsonSerializerOptions(JsonSerializerDefaults.Web))!;

        Assert.Equal(fixture.Sha256, fixture.Manifest.Digest());
        var reversed = fixture.Manifest with { Items = fixture.Manifest.Items.Reverse().ToArray() };
        Assert.NotEqual(fixture.Sha256, reversed.Digest());
    }

    [Fact]
    public void RoundTripsSharedTrustedResumeOpening()
    {
        byte[] wire = System.Text.Encoding.UTF8.GetBytes(
            File.ReadAllText(FixturePath("profile2_trusted_resume_open.json")).Trim());
        var parsed = Assert.IsType<Profile2Message.SessionOpen>(Profile2WireCodec.Decode(wire));
        Assert.Equal("receiver", parsed.InitiatorRole);
        Assert.Equal(wire, Profile2WireCodec.Encode(parsed));
    }

    private static string FixturePath(string name)
    {
        DirectoryInfo? directory = new(AppContext.BaseDirectory);
        string fixturePath = "";
        while (directory is not null)
        {
            fixturePath = Path.Combine(directory.FullName, "assets", "protocol", "fixtures",
                name);
            if (File.Exists(fixturePath))
                break;
            directory = directory.Parent;
        }
        Assert.NotNull(directory);
        return fixturePath;
    }
}
