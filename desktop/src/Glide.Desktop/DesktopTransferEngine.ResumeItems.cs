using System.Buffers;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Transport.Framing;

namespace Glide.Desktop;

public sealed partial class DesktopTransferEngine
{
    private async Task SendResumedItemAsync(LanRecordChannel channel, DesktopControlSequence sequence,
        SessionId session, SessionId original, ResumeBatchManifest.Item item, DesktopResumeStore store,
        DesktopPersistentTrustManager trust, DesktopTrustedPeer peer, string binding, HashSet<string> caps,
        Action<long> progress, CancellationToken token)
    {
        var request = await sequence.ReadExpectedAsync(channel, MessageTypes.ResumeRequest, session, token,
            timeout: _timeoutPolicy.CalculateFinalizationDeadline(item.SizeBytes)).ConfigureAwait(false);
        RequireExactString(request, "originalSessionId", original.Value);
        RequireExactString(request, "itemId", item.Id);
        RequireExactString(request, "requesterFingerprintHex", peer.FingerprintHex);
        RequireExactString(request, "responderFingerprintHex", trust.LocalFingerprintHex());
        RequireExactString(request, "channelBindingHex", binding);
        string capabilityHash = IdentityResumeTranscript.CapabilityHashHex(caps);
        RequireExactString(request, "capabilityHashHex", capabilityHash);
        int chunkSize = item.SizeBytes == 0 ? 1 : item.ChunkSize;
        string root = item.RootHash ?? new string('0', 64);
        RequireExactString(request, "chunkRootHashHex", root);
        RequireExactLong(request, "totalSizeBytes", item.SizeBytes);
        RequireExactLong(request, "chunkSize", chunkSize);
        long offset = long.Parse(request.Body["contiguousByteOffset"]!.ToString());
        long chunks = long.Parse(request.Body["verifiedChunkCount"]!.ToString());
        if (item.SizeBytes > 0 && offset == item.SizeBytes)
            throw new ProtocolViolationException(WireErrorCodes.ProtocolOrder, "A committed item needs a batch receipt.");
        byte[] transcript = IdentityResumeTranscript.ResumeRequestBytes(session.Value, original.Value, item.Id,
            "receiver", "sender", peer.FingerprintHex, trust.LocalFingerprintHex(),
            request.Body["sessionNonceHex"]!.ToString(), binding, capabilityHash, chunkSize, item.SizeBytes, chunks, offset, root);
        RequireResumePeer(trust, peer);
        using (var key = ECDsa.Create())
        {
            byte[] spki = Convert.FromHexString(peer.PublicKeyDerHex);
            key.ImportSubjectPublicKeyInfo(spki, out int read);
            if (read != spki.Length || !key.VerifyData(transcript,
                    Convert.FromHexString(request.Body["signatureHex"]!.ToString()), HashAlgorithmName.SHA256,
                    DSASignatureFormat.Rfc3279DerSequence))
                throw new ProtocolViolationException(WireErrorCodes.AuthenticationFailed, "Invalid signed resume request.");
        }
        var requestId = request.MessageId.Value;
        DesktopResumeSourceSnapshot? snapshot = null;
        if (item.SizeBytes > 0)
        {
            var journal = await store.LoadAsync(original, new ItemId(item.Id), token).ConfigureAwait(false);
            try
            {
                if (journal?.SourcePath == null || journal.JournalRole != DesktopResumeJournalRoles.Sender
                    || journal.PeerFingerprintHex != peer.FingerprintHex || journal.WholeFileSha256Hex != item.Digest)
                    throw new InvalidDataException("Missing approved source.");
                snapshot = await new DesktopResumeSourceVerifier().SnapshotAsync(journal.SourcePath, chunkSize, token)
                    .ConfigureAwait(false);
                if (snapshot.SourceIdentity != journal.SourceIdentity || snapshot.TotalBytes != item.SizeBytes ||
                    snapshot.WholeFileSha256Hex != item.Digest || snapshot.ChunkRootHashHex != root)
                    throw new InvalidDataException("Source contents changed.");
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidDataException)
            {
                var reject = ResumeReply(requestId, original, item);
                reject["code"] = ResumeRejectCodes.FileChanged;
                await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.ResumeReject, session, reject), token)
                    .ConfigureAwait(false);
                throw new ProtocolViolationException(WireErrorCodes.SourceUnavailable,
                    "The original source file changed or is unavailable.");
            }
        }
        RequireResumePeer(trust, peer);
        var accept = ResumeReply(requestId, original, item);
        accept["startChunkIndex"] = chunks;
        accept["startByteOffset"] = offset.ToString();
        await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.ResumeAccept, session, accept), token).ConfigureAwait(false);
        if (snapshot != null)
        {
            var bulk = new BulkRef(requestId);
            var start = (JsonObject)accept.DeepClone();
            start["bulkRef"] = bulk.Value;
            start["remainingBytes"] = (item.SizeBytes - offset).ToString();
            start["totalSizeBytes"] = item.SizeBytes.ToString();
            start["chunkSize"] = chunkSize;
            start["chunkRootHashHex"] = root;
            start["wholeFileSha256Hex"] = item.Digest;
            await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.ResumeStreamStart, session, start), token).ConfigureAwait(false);
            var ready = await sequence.ReadExpectedAsync(channel, MessageTypes.ResumeStreamReady, session, token).ConfigureAwait(false);
            RequireResumeReply(ready, requestId, original, item);
            RequireExactString(ready, "bulkRef", bulk.Value);
            await using var source = new FileStream(snapshot.SourcePath, FileMode.Open, FileAccess.Read, FileShare.Read,
                StreamBufferBytes, FileOptions.Asynchronous | FileOptions.SequentialScan);
            source.Position = offset;
            byte[] chunk = ArrayPool<byte>.Shared.Rent(chunkSize);
            try
            {
                long position = offset;
                progress(position);
                for (long index = chunks; position < item.SizeBytes; index++)
                {
                    RequireResumePeer(trust, peer);
                    int length = (int)Math.Min(chunkSize, item.SizeBytes - position);
                    await source.ReadExactlyAsync(chunk.AsMemory(0, length), token).ConfigureAwait(false);
                    if (Convert.ToHexStringLower(SHA256.HashData(chunk.AsSpan(0, length))) !=
                        snapshot.ChunkHashesHex[checked((int)index)])
                        throw new ProtocolViolationException(WireErrorCodes.SourceUnavailable,
                            "The source changed during resume.");
                    for (int sent = 0; sent < length;)
                    {
                        int frame = Math.Min(StreamBufferBytes, length - sent);
                        await channel.WriteDataChunkAsync(bulk, chunk.AsMemory(sent, frame), token).ConfigureAwait(false);
                        sent += frame;
                        position += frame;
                    }
                    progress(position);
                }
                await channel.WriteStreamEndAsync(bulk, item.SizeBytes - offset, token).ConfigureAwait(false);
            }
            finally
            {
                ArrayPool<byte>.Shared.Return(chunk, clearArray: true);
            }
        }
        var result = await sequence.ReadExpectedAsync(channel, MessageTypes.ResumeResult, session, token,
            timeout: _timeoutPolicy.CalculateFinalizationDeadline(item.SizeBytes)).ConfigureAwait(false);
        RequireResumeReply(result, requestId, original, item);
        RequireExactString(result, "status", "verified");
        RequireExactString(result, "sha256", item.Digest);
        RequireExactLong(result, "sizeBytes", item.SizeBytes);
    }

    private async Task ReceiveResumedItemAsync(LanRecordChannel channel, DesktopControlSequence sequence,
        SessionId session, SessionId original, ResumeBatchManifest.Item item, DesktopReceiveRequest local,
        DesktopPersistentTrustManager trust, DesktopTrustedPeer peer, string binding, HashSet<string> caps,
        IDesktopTransferInteraction interaction, Action<long> progress, CancellationToken token)
    {
        var store = local.ResumeStore!;
        var itemId = new ItemId(item.Id);
        long offset = 0;
        long chunks = 0;
        int chunkSize = item.SizeBytes == 0 ? 1 : item.ChunkSize;
        string root = item.RootHash ?? new string('0', 64);
        if (item.SizeBytes > 0)
        {
            var authority = await store.ReconcileReceiverAsync(original, itemId, peer.FingerprintHex,
                trust.GetTrustedPeer(peer.FingerprintHex) != null, token, requirePositiveRemainder: true).ConfigureAwait(false);
            if (authority.Code != DesktopResumeValidationCode.Ready || authority.Journal == null ||
                authority.Journal.TotalBytes != item.SizeBytes || authority.Journal.ChunkSize != chunkSize ||
                authority.Journal.ChunkRootHashHex != root)
                throw new ProtocolViolationException(WireErrorCodes.AuthenticationFailed, "Retained receive authority is unavailable.");
            offset = authority.Journal.ContiguousByteOffset;
            chunks = authority.Journal.VerifiedChunks;
        }
        RequireResumePeer(trust, peer);
        string fp = trust.LocalFingerprintHex();
        string capHash = IdentityResumeTranscript.CapabilityHashHex(caps);
        string nonce = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
        byte[] transcript = IdentityResumeTranscript.ResumeRequestBytes(session.Value, original.Value, item.Id,
            "receiver", "sender", fp, peer.FingerprintHex, nonce, binding, capHash, chunkSize, item.SizeBytes, chunks, offset, root);
        var request = sequence.Create(MessageTypes.ResumeRequest, session, new JsonObject
        {
            ["profile"] = IdentityResumeTranscript.ResumeProfile, ["originalSessionId"] = original.Value, ["itemId"] = item.Id,
            ["requesterFingerprintHex"] = fp, ["responderFingerprintHex"] = peer.FingerprintHex,
            ["requesterRole"] = "receiver", ["responderRole"] = "sender",
            ["verifiedChunkCount"] = chunks, ["contiguousByteOffset"] = offset.ToString(),
            ["chunkSize"] = chunkSize, ["totalSizeBytes"] = item.SizeBytes.ToString(),
            ["chunkRootHashHex"] = root, ["sessionNonceHex"] = nonce, ["channelBindingHex"] = binding,
            ["capabilityHashHex"] = capHash, ["signatureHex"] = Convert.ToHexStringLower(trust.SignTranscript(transcript))
        });
        await channel.WriteEnvelopeAsync(request, token).ConfigureAwait(false);
        var accept = await sequence.ReadExpectedOneOfAsync(channel, [MessageTypes.ResumeAccept, MessageTypes.ResumeReject],
            session, token, _timeoutPolicy.CalculateFinalizationDeadline(item.SizeBytes)).ConfigureAwait(false);
        RequireResumeReply(accept, request.MessageId.Value, original, item);
        if (accept.Type == MessageTypes.ResumeReject)
            throw new ProtocolViolationException(WireErrorCodes.SourceUnavailable, "The sender cannot reopen the original item.");
        RequireExactLong(accept, "startChunkIndex", chunks);
        RequireExactLong(accept, "startByteOffset", offset);
        var digest = new Sha256Digest(item.Digest);

        if (item.SizeBytes > 0)
        {
            var start = await sequence.ReadExpectedAsync(channel, MessageTypes.ResumeStreamStart, session, token).ConfigureAwait(false);
            RequireResumeReply(start, request.MessageId.Value, original, item);
            RequireExactLong(start, "startChunkIndex", chunks);
            RequireExactLong(start, "startByteOffset", offset);
            RequireExactLong(start, "remainingBytes", item.SizeBytes - offset);
            RequireExactLong(start, "totalSizeBytes", item.SizeBytes);
            RequireExactLong(start, "chunkSize", chunkSize);
            RequireExactString(start, "wholeFileSha256Hex", digest.Value);
            RequireExactString(start, "chunkRootHashHex", root);
            var bulk = new BulkRef(start.Body["bulkRef"]!.ToString());
            var ready = ResumeReply(request.MessageId.Value, original, item);
            ready["bulkRef"] = bulk.Value;
            await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.ResumeStreamReady, session, ready), token).ConfigureAwait(false);
            using (var recorder = new ResumeRecordingStream(Stream.Null, store, original, item, offset))
            using (var deadline = CancellationTokenSource.CreateLinkedTokenSource(token))
            {
                long position = offset;
                progress(position);
                while (true)
                {
                    var record = await DesktopControlSequence.ReadOwnedRecordWithDeadlineAsync(channel, bulk, deadline,
                        token, _timeoutPolicy.NoPayloadProgressTimeout).ConfigureAwait(false);
                    if (record is LanRecord.OwnedData data)
                    {
                        using (data)
                        {
                            RequireResumePeer(trust, peer);
                            await recorder.WriteAsync(data.ChunkMemory, token).ConfigureAwait(false);
                            position += data.ChunkLength;
                            progress(position);
                        }
                    }
                    else if (record is LanRecord.StreamEnd end && end.BulkRef == bulk
                        && end.ActualBytes == item.SizeBytes - offset && position == item.SizeBytes)
                        break;
                    else
                        throw new IOException("Resume payload was truncated or out of order.");
                }
            }
            interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Verifying);
            string quarantine = local.StagingStore.PrepareResumeQuarantinePath(session, itemId);
            if (!await store.AssembleVerifiedReceiverAsync(original, itemId, digest, quarantine, token,
                    purgeOnSuccess: false).ConfigureAwait(false))
                throw new ProtocolViolationException(WireErrorCodes.IntegrityMismatch, "Resumed content did not pass verification.");
        }
        else
        {
            await using var empty = local.StagingStore.OpenQuarantineWrite(session, itemId);
        }

        RequireResumePeer(trust, peer);
        var commit = await local.StagingStore.CommitVerifiedQuarantineFileWithMetricsAsync(session, itemId,
            item.RelativePath ?? item.Name, digest, item.SizeBytes, token,
            onBeforePublish: () => interaction.OnPhase(DesktopTransferDirection.Receive, DesktopTransferPhase.Saving),
            onPublishCandidate: (path, cancellation) =>
                store.RecordBatchPublishCandidateAsync(original, itemId, path, cancellation))
            .ConfigureAwait(false);
        if (!await store.RecordVerifiedBatchCommitAsync(original, itemId, item.SizeBytes, digest.Value, CancellationToken.None)
                .ConfigureAwait(false))
            throw new IOException("The file was verified and saved, but the receipt could not be recorded.");
        await store.PurgeItemAsync(original, itemId, CancellationToken.None).ConfigureAwait(false);
        interaction.OnHistory(new DesktopHistoryEntry(DesktopTransferDirection.Receive, item.Name, item.SizeBytes,
            commit.CommittedPath, peer.DisplayName, DateTimeOffset.UtcNow));
        var result = ResumeReply(request.MessageId.Value, original, item);
        result["status"] = "verified";
        result["sha256"] = digest.Value;
        result["sizeBytes"] = item.SizeBytes.ToString();
        await channel.WriteEnvelopeAsync(sequence.Create(MessageTypes.ResumeResult, session, result), token).ConfigureAwait(false);
    }

    private static JsonObject ResumeReply(string requestId, SessionId original, ResumeBatchManifest.Item item) => new()
    {
        ["profile"] = IdentityResumeTranscript.ResumeProfile, ["requestMessageId"] = requestId,
        ["originalSessionId"] = original.Value, ["itemId"] = item.Id
    };

    private static void RequireResumeReply(Envelope envelope, string requestId, SessionId original, ResumeBatchManifest.Item item)
    {
        RequireExactString(envelope, "requestMessageId", requestId);
        RequireExactString(envelope, "originalSessionId", original.Value);
        RequireExactString(envelope, "itemId", item.Id);
    }
}
