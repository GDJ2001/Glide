using System.Buffers;
using Glide.Core.Models;
using Glide.Core.Storage;

namespace Glide.Desktop;

public sealed partial class DesktopTransferEngine
{
    private sealed class DesktopResumeCancellation(
        DesktopResumeStore? store, SessionId session, CancellationToken cancellationToken) : IAsyncDisposable
    {
        public async ValueTask DisposeAsync()
        {
            if (store == null || !cancellationToken.IsCancellationRequested)
                return;
            try
            {
                await store.PurgeOriginalSessionAsync(session, CancellationToken.None).ConfigureAwait(false);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private sealed class ResumeRecordingStream : Stream
    {
        private readonly Stream _destination;
        private readonly DesktopResumeStore _store;
        private readonly SessionId _session;
        private readonly ItemId _item;
        private readonly int _chunkSize;
        private readonly long _total;
        private byte[]? _chunk;
        private long _position;
        private int _buffered;

        public ResumeRecordingStream(Stream destination, DesktopResumeStore store, SessionId session,
            ResumeBatchManifest.Item item, long offset)
        {
            if (item.ChunkSize <= 0 || offset < 0 || offset > item.SizeBytes || offset % item.ChunkSize != 0)
                throw new ArgumentException("Invalid resume chunk boundary.");
            _destination = destination;
            _store = store;
            _session = session;
            _item = new ItemId(item.Id);
            _chunkSize = item.ChunkSize;
            _total = item.SizeBytes;
            _position = offset;
            _chunk = ArrayPool<byte>.Shared.Rent(_chunkSize);
        }

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer,
            CancellationToken cancellationToken = default)
        {
            byte[] chunk = _chunk ?? throw new ObjectDisposedException(nameof(ResumeRecordingStream));
            if (buffer.Length > _total - _position)
                throw new InvalidDataException("Resume data exceeds the approved length.");
            await _destination.WriteAsync(buffer, cancellationToken).ConfigureAwait(false);
            while (!buffer.IsEmpty)
            {
                int length = Math.Min(buffer.Length, _chunkSize - _buffered);
                buffer[..length].CopyTo(chunk.AsMemory(_buffered));
                buffer = buffer[length..];
                _position += length;
                _buffered += length;
                if (_buffered == _chunkSize || _position == _total)
                {
                    long index = (_position - _buffered) / _chunkSize;
                    if (!await _store.RecordReceiverChunkAsync(_session, _item, index,
                            chunk.AsMemory(0, _buffered), cancellationToken).ConfigureAwait(false))
                        throw new IOException("Could not retain the received chunk.");
                    _buffered = 0;
                }
            }
        }

        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            WriteAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => _chunk != null;
        public override long Length => throw new NotSupportedException();
        public override long Position
        {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() => _destination.Flush();
        public override Task FlushAsync(CancellationToken cancellationToken) => _destination.FlushAsync(cancellationToken);

        protected override void Dispose(bool disposing)
        {
            if (disposing && Interlocked.Exchange(ref _chunk, null) is { } chunk)
                ArrayPool<byte>.Shared.Return(chunk, clearArray: true);
            base.Dispose(disposing);
        }
    }
}
