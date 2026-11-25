using System.Text.Json;
using Glide.Core.Codec;
using Glide.Core.Models;
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

    [Fact]
    public void DecodesSharedBatchApprovalWithVerifiedPrefix()
    {
        var message = ProtocolCodec.Decode(File.ReadAllBytes(FixturePath("resume_batch_approval_v1.json")));
        Assert.True(message.Body["accepted"]?.GetValue<bool>());
        Assert.Equal(new string('a', 64), message.Extensions?["resumeManifestDigest"]?.ToString());
        Assert.Equal("1", message.Extensions?["resumeVerifiedCount"]?.ToString());
    }

    [Fact]
    public async Task OnlyVerifiedPrefixSurvivesRestartAndRevocationRemovesIt()
    {
        var fixture = JsonSerializer.Deserialize<Fixture>(
            File.ReadAllText(FixturePath("resume_batch_manifest_v1.json")),
            new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        var manifest = fixture.Manifest;
        var sessionId = new SessionId(manifest.SessionId);
        string root = Path.Combine(Path.GetTempPath(), "glide-resume-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            var writer = new DesktopResumeStore(root);
            await writer.SaveAcceptedBatchAsync(manifest, DesktopResumeJournalRoles.Receiver);
            var first = manifest.Items[0];
            var second = manifest.Items[1];
            await Assert.ThrowsAsync<InvalidDataException>(() => writer.RecordVerifiedBatchCommitAsync(
                sessionId, new ItemId(second.Id), second.SizeBytes, second.Digest));
            await Assert.ThrowsAsync<InvalidDataException>(() => writer.RecordVerifiedBatchCommitAsync(
                sessionId, new ItemId(first.Id), first.SizeBytes + 1, first.Digest));
            Assert.True(await writer.RecordVerifiedBatchCommitAsync(
                sessionId, new ItemId(first.Id), first.SizeBytes, first.Digest));

            var reopened = new DesktopResumeStore(root);
            Assert.Null(await reopened.FindAcceptedBatchAsync(
                sessionId, DesktopResumeJournalRoles.Receiver, manifest.ReceiverFingerprint, fixture.Sha256));
            var batch = await reopened.FindAcceptedBatchAsync(
                sessionId, DesktopResumeJournalRoles.Receiver, manifest.SenderFingerprint, fixture.Sha256);
            Assert.NotNull(batch);
            Assert.Equal(1, batch.VerifiedCount);
            Assert.True(await reopened.RecordVerifiedBatchCommitAsync(
                sessionId, new ItemId(first.Id), first.SizeBytes, first.Digest));
            Assert.Equal(1, await reopened.PurgePeerAsync(manifest.SenderFingerprint));
            Assert.Null(await reopened.FindAcceptedBatchAsync(
                sessionId, DesktopResumeJournalRoles.Receiver, manifest.SenderFingerprint, fixture.Sha256));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task RecoversPublishedFileBeforeReceiptAfterRestart()
    {
        string root = Path.Combine(Path.GetTempPath(), "glide-publish-" + Guid.NewGuid().ToString("N"));
        string destination = Path.Combine(root, "received");
        Directory.CreateDirectory(destination);
        try
        {
            byte[] payload = "complete"u8.ToArray();
            var session = SessionId.NewRandom();
            var item = ItemId.NewRandom();
            string digest = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(payload));
            var manifest = new ResumeBatchManifest(session.Value, Guid.NewGuid().ToString(), new string('a', 64),
                new string('b', 64), [new(item.Value, "file.txt", null, payload.Length, 1024, digest, digest)]);
            string storeRoot = Path.Combine(root, "resume");
            var store = new DesktopResumeStore(storeRoot);
            await store.SaveAcceptedBatchAsync(manifest, DesktopResumeJournalRoles.Receiver);
            string target = Path.Combine(destination, "file.txt");
            await store.RecordBatchPublishCandidateAsync(session, item, target);
            File.WriteAllText(target, "mismatch");
            var reopened = new DesktopResumeStore(storeRoot);
            Assert.Equal(0, (await reopened.RecoverBatchPublishAsync(session, destination))?.VerifiedCount);

            File.WriteAllBytes(target, payload);
            var recovered = await reopened.RecoverBatchPublishAsync(session, destination);
            Assert.Equal(1, recovered?.VerifiedCount);
            Assert.Null(recovered?.PendingCommitPath);
            Assert.Equal(payload, File.ReadAllBytes(target));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
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
