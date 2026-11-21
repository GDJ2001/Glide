using System.Buffers;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Threading.Channels;
using Glide.Core.Models;
using Glide.Core.Transport.Lan;
using Glide.Desktop.Diagnostics;
using Glide.Transport.Framing;

namespace Glide.Desktop;

/// <summary>
/// Bounded producer/consumer payload streaming pipeline for sender and receiver items.
/// Caps in-flight memory via <see cref="TransferBufferPool"/> and overlaps disk I/O,
/// incremental SHA-256 hashing, and TLS framing.
/// </summary>
internal sealed class BoundedPayloadTransferPipeline
{
    public const int StreamBufferBytes = 65_520;

    private readonly DesktopTransferIoOptions _ioOptions;
    private readonly TransferBufferPool _bufferPool;
    private readonly DesktopTimeoutPolicy _timeoutPolicy;

    public BoundedPayloadTransferPipeline(
        DesktopTransferIoOptions ioOptions,
        TransferBufferPool bufferPool,
        DesktopTimeoutPolicy timeoutPolicy)
    {
        _ioOptions = ioOptions ?? throw new ArgumentNullException(nameof(ioOptions));
        _bufferPool = bufferPool ?? throw new ArgumentNullException(nameof(bufferPool));
        _timeoutPolicy = timeoutPolicy ?? throw new ArgumentNullException(nameof(timeoutPolicy));
    }

    public async Task<(long ActualBytes, long CumulativeTransferred)> StreamSenderItemAsync(
        Stream stream,
        IncrementalHash hash,
        LanRecordChannel channel,
        BulkRef bulkRef,
        PreparedSendItem prepared,
        string peerName,
        long cumulativeTransferred,
        long totalBytes,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        if (_ioOptions.EnableBoundedPipeline)
        {
            return await StreamSenderItemBoundedAsync(
                stream,
                hash,
                channel,
                bulkRef,
                prepared,
                peerName,
                cumulativeTransferred,
                totalBytes,
                interaction,
                diag,
                cancellationToken).ConfigureAwait(false);
        }

        long actualBytes = 0;
        long cumulative = cumulativeTransferred;
        byte[] buffer = ArrayPool<byte>.Shared.Rent(StreamBufferBytes);
        try
        {
            while (true)
            {
                long readStart = Stopwatch.GetTimestamp();
                int read = await stream.ReadAsync(
                    buffer.AsMemory(0, StreamBufferBytes),
                    cancellationToken).ConfigureAwait(false);
                diag.AddSourceRead(read, Stopwatch.GetTimestamp() - readStart);
                if (read <= 0)
                {
                    break;
                }

                actualBytes = checked(actualBytes + read);
                if (actualBytes > prepared.DeclaredSize)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.SourceUnavailable,
                        $"Source '{prepared.File.Name}' grew while it was being transferred.");
                }

                long hashStart = Stopwatch.GetTimestamp();
                hash.AppendData(buffer, 0, read);
                diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                long writeStart = Stopwatch.GetTimestamp();
                await channel.WriteDataChunkAsync(
                    bulkRef,
                    buffer.AsMemory(0, read),
                    cancellationToken).ConfigureAwait(false);
                diag.AddTlsWrite(read, Stopwatch.GetTimestamp() - writeStart);

                cumulative += read;
                interaction.OnProgress(
                    new DesktopTransferProgress(
                        DesktopTransferDirection.Send,
                        peerName,
                        prepared.File.Name,
                        cumulative,
                        totalBytes));
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }

        return (actualBytes, cumulative);
    }

    public async Task<(long ActualBytes, long CumulativeTransferred)> StreamSenderItemBoundedAsync(
        Stream stream,
        IncrementalHash hash,
        LanRecordChannel channel,
        BulkRef bulkRef,
        PreparedSendItem prepared,
        string peerName,
        long cumulativeTransferred,
        long totalBytes,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        int queueCapacity = Math.Clamp(_ioOptions.QueueBufferCount, 1, 64);
        int chunkBytes = Math.Clamp(_ioOptions.ChunkBufferBytes, 4096, Math.Min(StreamBufferBytes, _bufferPool.BufferBytes));

        var boundedQueue = Channel.CreateBounded<OwnedTransferBuffer>(
            new BoundedChannelOptions(queueCapacity)
            {
                FullMode = BoundedChannelFullMode.Wait,
                SingleWriter = true,
                SingleReader = true,
                AllowSynchronousContinuations = false
            });

        using var pipelineCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        long actualBytes = 0;
        long cumulative = cumulativeTransferred;
        int currentDepth = 0;
        int maxDepth = 0;

        Task producerTask = Task.Run(async () =>
        {
            Exception? producerError = null;
            try
            {
                while (true)
                {
                    OwnedTransferBuffer leased = await _bufferPool.RentAsync(chunkBytes, pipelineCts.Token).ConfigureAwait(false);
                    bool handedOff = false;
                    try
                    {
                        long readStart = Stopwatch.GetTimestamp();
                        int read = await stream.ReadAsync(
                            leased.GetAvailableMemory(chunkBytes),
                            pipelineCts.Token).ConfigureAwait(false);
                        diag.AddSourceRead(read, Stopwatch.GetTimestamp() - readStart);

                        if (read <= 0)
                        {
                            break;
                        }

                        actualBytes = checked(actualBytes + read);
                        if (actualBytes > prepared.DeclaredSize)
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.SourceUnavailable,
                                $"Source '{prepared.File.Name}' grew while it was being transferred.");
                        }

                        leased.Length = read;

                        int depth = Interlocked.Increment(ref currentDepth);
                        UpdateMax(ref maxDepth, depth);
                        diag.UpdateQueueOccupancy(depth, Volatile.Read(ref maxDepth), 0, 0, _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);

                        await boundedQueue.Writer.WriteAsync(leased, pipelineCts.Token).ConfigureAwait(false);
                        handedOff = true;
                    }
                    finally
                    {
                        if (!handedOff)
                        {
                            leased.Dispose();
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                producerError = ex;
                throw;
            }
            finally
            {
                boundedQueue.Writer.TryComplete(producerError);
            }
        }, CancellationToken.None);

        try
        {
            await foreach (OwnedTransferBuffer ownedChunk in boundedQueue.Reader.ReadAllAsync(pipelineCts.Token).ConfigureAwait(false))
            {
                using (ownedChunk)
                {
                    int depth = Interlocked.Decrement(ref currentDepth);
                    diag.UpdateQueueOccupancy(Math.Max(0, depth), Volatile.Read(ref maxDepth), 0, 0, _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);

                    long hashStart = Stopwatch.GetTimestamp();
                    hash.AppendData(ownedChunk.Span);
                    diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                    int len = ownedChunk.Length;
                    long writeStart = Stopwatch.GetTimestamp();
                    await channel.WriteDataChunkAsync(
                        bulkRef,
                        ownedChunk.ReadOnlyMemory,
                        pipelineCts.Token).ConfigureAwait(false);
                    diag.AddTlsWrite(len, Stopwatch.GetTimestamp() - writeStart);

                    cumulative += len;
                    interaction.OnProgress(
                        new DesktopTransferProgress(
                            DesktopTransferDirection.Send,
                            peerName,
                            prepared.File.Name,
                            cumulative,
                            totalBytes));
                }
            }

            await producerTask.ConfigureAwait(false);
            return (actualBytes, cumulative);
        }
        catch
        {
            pipelineCts.Cancel();
            DrainAndDisposeChannel(boundedQueue.Reader, ref currentDepth);
            try
            {
                await producerTask.ConfigureAwait(false);
            }
            catch
            {
                // Primary exception or producer exception handled by caller
            }

            if (cancellationToken.IsCancellationRequested)
            {
                cancellationToken.ThrowIfCancellationRequested();
            }

            throw;
        }
        finally
        {
            DrainAndDisposeChannel(boundedQueue.Reader, ref currentDepth);
            diag.UpdateQueueOccupancy(0, Volatile.Read(ref maxDepth), 0, 0, _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);
        }
    }

    public async Task<(long ReceivedBytes, long CumulativeTransferred)> StreamReceiverItemAsync(
        LanRecordChannel channel,
        Stream fileStream,
        IncrementalHash sha256,
        BulkRef bulkRef,
        DesktopOfferItem item,
        string peerName,
        bool discardStorage,
        long cumulativeTransferred,
        long totalBytes,
        CancellationTokenSource itemTimeoutCts,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        if (_ioOptions.EnableBoundedPipeline)
        {
            return await StreamReceiverItemBoundedAsync(
                channel,
                fileStream,
                sha256,
                bulkRef,
                item,
                peerName,
                discardStorage,
                cumulativeTransferred,
                totalBytes,
                itemTimeoutCts,
                interaction,
                diag,
                cancellationToken).ConfigureAwait(false);
        }

        long receivedBytes = 0;
        long cumulative = cumulativeTransferred;
        while (true)
        {
            long readStart = Stopwatch.GetTimestamp();
            LanRecord? record = await DesktopControlSequence
                .ReadOwnedRecordWithDeadlineAsync(
                    channel,
                    bulkRef,
                    itemTimeoutCts,
                    cancellationToken,
                    timeout: _timeoutPolicy.NoPayloadProgressTimeout).ConfigureAwait(false);
            long readTicks = Stopwatch.GetTimestamp() - readStart;
            if (record == null)
            {
                throw new EndOfStreamException("Connection terminated during payload transfer.");
            }

            if (record is LanRecord.OwnedData ownedData)
            {
                using (ownedData)
                {
                    int chunkLength = ownedData.ChunkLength;
                    diag.AddTlsRead(chunkLength, readTicks);
                    if (!string.Equals(ownedData.BulkRef.Value, bulkRef.Value, StringComparison.Ordinal))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            "Unmatched bulkRef in DATA record.");
                    }

                    receivedBytes = checked(receivedBytes + chunkLength);
                    if (receivedBytes > item.SizeBytes)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.LimitExceeded,
                            $"Item '{item.Name}' exceeded its declared size.");
                    }

                    if (!discardStorage)
                    {
                        long writeStart = Stopwatch.GetTimestamp();
                        await fileStream.WriteAsync(ownedData.ChunkMemory, cancellationToken).ConfigureAwait(false);
                        diag.AddStagingWrite(chunkLength, Stopwatch.GetTimestamp() - writeStart);
                    }

                    long hashStart = Stopwatch.GetTimestamp();
                    sha256.AppendData(ownedData.ChunkSpan);
                    diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                    cumulative += chunkLength;
                    interaction.OnProgress(
                        new DesktopTransferProgress(
                            DesktopTransferDirection.Receive,
                            peerName,
                            item.Name,
                            cumulative,
                            totalBytes));
                }
            }
            else if (record is LanRecord.Data dataRecord)
            {
                diag.AddTlsRead(dataRecord.Chunk.Length, readTicks);
                if (!string.Equals(dataRecord.BulkRef.Value, bulkRef.Value, StringComparison.Ordinal))
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        "Unmatched bulkRef in DATA record.");
                }

                receivedBytes = checked(receivedBytes + dataRecord.Chunk.Length);
                if (receivedBytes > item.SizeBytes)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.LimitExceeded,
                        $"Item '{item.Name}' exceeded its declared size.");
                }

                if (!discardStorage)
                {
                    long writeStart = Stopwatch.GetTimestamp();
                    await fileStream.WriteAsync(dataRecord.Chunk.AsMemory(), cancellationToken).ConfigureAwait(false);
                    diag.AddStagingWrite(dataRecord.Chunk.Length, Stopwatch.GetTimestamp() - writeStart);
                }

                long hashStart = Stopwatch.GetTimestamp();
                sha256.AppendData(dataRecord.Chunk);
                diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                cumulative += dataRecord.Chunk.Length;
                interaction.OnProgress(
                    new DesktopTransferProgress(
                        DesktopTransferDirection.Receive,
                        peerName,
                        item.Name,
                        cumulative,
                        totalBytes));
            }
            else if (record is LanRecord.StreamEnd streamEnd)
            {
                if (!string.Equals(streamEnd.BulkRef.Value, bulkRef.Value, StringComparison.Ordinal))
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        "Unmatched bulkRef in STREAM_END record.");
                }

                if (streamEnd.ActualBytes != receivedBytes || receivedBytes != item.SizeBytes)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.IntegrityMismatch,
                        $"STREAM_END actualBytes {streamEnd.ActualBytes} does not match expected {item.SizeBytes}.");
                }

                break;
            }
            else
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ProtocolOrder,
                    $"Unexpected record kind during data stream: {record.Kind}.");
            }
        }

        return (receivedBytes, cumulative);
    }

    public async Task<(long ReceivedBytes, long CumulativeTransferred)> StreamReceiverItemBoundedAsync(
        LanRecordChannel channel,
        Stream fileStream,
        IncrementalHash sha256,
        BulkRef bulkRef,
        DesktopOfferItem item,
        string peerName,
        bool discardStorage,
        long cumulativeTransferred,
        long totalBytes,
        CancellationTokenSource itemTimeoutCts,
        IDesktopTransferInteraction interaction,
        IDesktopTransferAttemptDiagnostics diag,
        CancellationToken cancellationToken)
    {
        int queueCapacity = Math.Clamp(_ioOptions.QueueBufferCount, 1, 64);

        var boundedQueue = Channel.CreateBounded<OwnedTransferBuffer>(
            new BoundedChannelOptions(queueCapacity)
            {
                FullMode = BoundedChannelFullMode.Wait,
                SingleWriter = true,
                SingleReader = true,
                AllowSynchronousContinuations = false
            });

        using var pipelineCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        using CancellationTokenRegistration abortRegistration =
            pipelineCts.Token.Register(channel.CancelAndAbortTransport);
        long receivedBytes = 0;
        long cumulative = cumulativeTransferred;
        int currentDepth = 0;
        int maxDepth = 0;

        Task consumerTask = Task.Run(async () =>
        {
            try
            {
                await foreach (OwnedTransferBuffer ownedChunk in boundedQueue.Reader.ReadAllAsync(pipelineCts.Token).ConfigureAwait(false))
                {
                    using (ownedChunk)
                    {
                        int depth = Interlocked.Decrement(ref currentDepth);
                        diag.UpdateQueueOccupancy(0, 0, Math.Max(0, depth), Volatile.Read(ref maxDepth), _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);

                        int chunkLength = ownedChunk.Length;

                        long hashStart = Stopwatch.GetTimestamp();
                        sha256.AppendData(ownedChunk.Span);
                        diag.AddHashCompute(Stopwatch.GetTimestamp() - hashStart);

                        if (!discardStorage)
                        {
                            long writeStart = Stopwatch.GetTimestamp();
                            await fileStream.WriteAsync(
                                ownedChunk.ReadOnlyMemory,
                                pipelineCts.Token).ConfigureAwait(false);
                            diag.AddStagingWrite(chunkLength, Stopwatch.GetTimestamp() - writeStart);
                        }

                        cumulative += chunkLength;
                        interaction.OnProgress(
                            new DesktopTransferProgress(
                                DesktopTransferDirection.Receive,
                                peerName,
                                item.Name,
                                cumulative,
                                totalBytes));
                    }
                }
            }
            catch
            {
                pipelineCts.Cancel();
                channel.CancelAndAbortTransport();
                throw;
            }
        }, CancellationToken.None);

        Exception? producerError = null;
        try
        {
            while (true)
            {
                long readStart = Stopwatch.GetTimestamp();
                LanRecord? record = await DesktopControlSequence
                    .ReadOwnedRecordWithDeadlineAsync(
                        channel,
                        bulkRef,
                        itemTimeoutCts,
                        pipelineCts.Token,
                        timeout: _timeoutPolicy.NoPayloadProgressTimeout).ConfigureAwait(false);
                long readTicks = Stopwatch.GetTimestamp() - readStart;

                if (record == null)
                {
                    throw new EndOfStreamException("Connection terminated during payload transfer.");
                }

                if (record is LanRecord.OwnedData ownedData)
                {
                    bool handedOff = false;
                    try
                    {
                        int chunkLength = ownedData.ChunkLength;
                        diag.AddTlsRead(chunkLength, readTicks);

                        if (!string.Equals(ownedData.BulkRef.Value, bulkRef.Value, StringComparison.Ordinal))
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.MalformedMessage,
                                "Unmatched bulkRef in DATA record.");
                        }

                        receivedBytes = checked(receivedBytes + chunkLength);
                        if (receivedBytes > item.SizeBytes)
                        {
                            throw new ProtocolViolationException(
                                WireErrorCodes.LimitExceeded,
                                $"Item '{item.Name}' exceeded its declared size.");
                        }

                        int depth = Interlocked.Increment(ref currentDepth);
                        UpdateMax(ref maxDepth, depth);
                        diag.UpdateQueueOccupancy(0, 0, depth, Volatile.Read(ref maxDepth), _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);

                        await boundedQueue.Writer.WriteAsync(ownedData.Buffer, pipelineCts.Token).ConfigureAwait(false);
                        handedOff = true;
                    }
                    finally
                    {
                        if (!handedOff)
                        {
                            ownedData.Dispose();
                        }
                    }
                }
                else if (record is LanRecord.Data dataRecord)
                {
                    int chunkLength = dataRecord.Chunk.Length;
                    diag.AddTlsRead(chunkLength, readTicks);

                    if (!string.Equals(dataRecord.BulkRef.Value, bulkRef.Value, StringComparison.Ordinal))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            "Unmatched bulkRef in DATA record.");
                    }

                    receivedBytes = checked(receivedBytes + chunkLength);
                    if (receivedBytes > item.SizeBytes)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.LimitExceeded,
                            $"Item '{item.Name}' exceeded its declared size.");
                    }

                    OwnedTransferBuffer copyBuffer = await _bufferPool.RentAsync(chunkLength, pipelineCts.Token).ConfigureAwait(false);
                    bool handedOff = false;
                    try
                    {
                        dataRecord.Chunk.AsSpan().CopyTo(copyBuffer.Span);
                        int depth = Interlocked.Increment(ref currentDepth);
                        UpdateMax(ref maxDepth, depth);
                        diag.UpdateQueueOccupancy(0, 0, depth, Volatile.Read(ref maxDepth), _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);
                        await boundedQueue.Writer.WriteAsync(copyBuffer, pipelineCts.Token).ConfigureAwait(false);
                        handedOff = true;
                    }
                    finally
                    {
                        if (!handedOff)
                        {
                            copyBuffer.Dispose();
                        }
                    }
                }
                else if (record is LanRecord.StreamEnd streamEnd)
                {
                    if (!string.Equals(streamEnd.BulkRef.Value, bulkRef.Value, StringComparison.Ordinal))
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.MalformedMessage,
                            "Unmatched bulkRef in STREAM_END record.");
                    }

                    if (streamEnd.ActualBytes != receivedBytes || receivedBytes != item.SizeBytes)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.IntegrityMismatch,
                            $"STREAM_END actualBytes {streamEnd.ActualBytes} does not match expected {item.SizeBytes}.");
                    }

                    break;
                }
                else
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.ProtocolOrder,
                        $"Unexpected record kind during data stream: {record.Kind}.");
                }
            }

            boundedQueue.Writer.TryComplete();
            await consumerTask.ConfigureAwait(false);
            return (receivedBytes, cumulative);
        }
        catch (Exception ex)
        {
            producerError = ex;
            boundedQueue.Writer.TryComplete(ex);
            pipelineCts.Cancel();
            DrainAndDisposeChannel(boundedQueue.Reader, ref currentDepth);

            try
            {
                await consumerTask.ConfigureAwait(false);
            }
            catch (Exception consumerEx) when (producerError is OperationCanceledException && consumerEx is not OperationCanceledException)
            {
                throw;
            }
            catch
            {
                // Primary exception handled below
            }

            if (cancellationToken.IsCancellationRequested)
            {
                cancellationToken.ThrowIfCancellationRequested();
            }

            throw;
        }
        finally
        {
            boundedQueue.Writer.TryComplete(producerError);
            DrainAndDisposeChannel(boundedQueue.Reader, ref currentDepth);
            diag.UpdateQueueOccupancy(0, 0, 0, Volatile.Read(ref maxDepth), _bufferPool.RentedBufferCount, _bufferPool.RentedBytes);
        }
    }

    private static void UpdateMax(ref int targetMax, int candidate)
    {
        int current = Volatile.Read(ref targetMax);
        while (candidate > current)
        {
            int prev = Interlocked.CompareExchange(ref targetMax, candidate, current);
            if (prev == current)
            {
                break;
            }
            current = prev;
        }
    }

    private static void DrainAndDisposeChannel(ChannelReader<OwnedTransferBuffer> reader, ref int currentDepth)
    {
        while (reader.TryRead(out OwnedTransferBuffer? leftover))
        {
            Interlocked.Decrement(ref currentDepth);
            leftover.Dispose();
        }
    }
}
