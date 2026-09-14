# ADR-0014 — Safe folder transfer extension

- Status: Accepted (future extension); validation pending implementation evidence.
- Date: 2026-09-14.
- Authority: [Safe folder transfer specification](../future/SAFE_FOLDER_TRANSFER_SPECIFICATION.md); [cross-platform path](../future/CROSS_PLATFORM_PATH.md); [decision index](README.md).

## Context
Glide V1 supports transferring multiple individual files and selecting files within directories, but purposefully flattens the file manifest to prevent recursive path traversal vulnerabilities (Zip Slip), symlink denial of service loops, and Storage Access Framework (SAF) IPC bottlenecks. A safe, high-performance extension is needed to allow users to transfer intact folder hierarchies across devices.

## Options considered
1. **Archive containerization (in-flight ZIP/TAR streaming):** Pack the folder tree into a single archive stream. Simple transport mapping, but requires either double disk space for archive extraction or high-risk streaming decompression vulnerable to uncompressed size bombs and Zip Slip attacks.
2. **Discrete item streaming with normalized POSIX relative paths:** Retain the proven V1 chunked streaming engine; extend the item manifest with a strictly sanitized `relative_path` attribute. Files are transferred individually with individual SHA-256 verification and atomic commit.
3. **Block-level or virtual filesystem synchronization:** Continuous block sync; rejected due to extreme complexity, lack of platform portability, and incompatibility with Android scoped storage.

## Decision
Adopt **Option 2: Discrete item streaming with normalized POSIX relative paths**, gated behind the explicit capability negotiation flag `CAP_FOLDER_TRANSFER = 0x01`.

## Reasoning
- Reuses the battle-tested V1 streaming pipeline, flow control, per-item SHA-256 checksums, and two-stage commit engine.
- Prevents archive decompression attacks entirely by handling items discretely.
- Maintains atomic recovery: an interrupted transfer fails individual uncommitted files into quarantine without corrupting the broader destination directory.

## Consequences
- **Bounded traversal:** Enforce strict caps: max recursion depth $\le 16$, max file count $\le 10,000$, max total directories $\le 1,000$, max path length $\le 1024$ characters.
- **Strict path sanitization:** Reject path traversal (`..`), current dir (`.`), leading slashes, Windows backslashes (`\`), drive letters, null bytes, and non-printable characters.
- **User Data Sanctity:** Collision with an existing folder automatically applies monotonic non-destructive renaming (e.g., `Folder (1)`) unless user explicitly chooses merge.
- **SAF optimization:** Android senders must query `ContentResolver` directly with `DocumentsContract.buildChildDocumentsUriUsingTree` and column projections, strictly avoiding high-latency `DocumentFile.listFiles()`.

## Alternatives and migration/fallback
Fallback to flat multi-file transfer if the peer does not advertise `CAP_FOLDER_TRANSFER`. No V1 wire protocol break occurs.
