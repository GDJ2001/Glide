# Safe Folder Transfer Extension Specification

**Document Status:** Proposed / Milestone M13 Future Extension  
**Authority:** [ADR-0014](../adr/ADR-0014-safe-folder-transfer-extension.md) | [Cross-Platform Path](CROSS_PLATFORM_PATH.md)  
**Milestone:** M13 — Future work (outside V1)  
**Task:** GLIDE-069  
**Version:** `0.1.0-draft`  

---

## 1. Executive Summary & Objectives

This specification defines the safe, high-performance folder transfer extension for **Glide**. In Glide V1, file transfers are deliberately restricted to flat manifests or bounded directory selections to prevent recursive path traversal vulnerabilities (Zip Slip), symlink cycles, and Android Storage Access Framework (SAF) IPC bottlenecks. 

This extension introduces structured, recursive folder hierarchy transfers while guaranteeing:
1. **Zero Path Traversal:** Cryptographically and syntactically isolated relative paths preventing directory escapes.
2. **User Data Sanctity:** Automatic non-destructive monotonic collision resolution (e.g., `Folder (1)`) without overwriting existing files.
3. **High-Performance SAF Traversal:** Direct `ContentResolver` queries with projection optimization, bypassing high-latency `DocumentFile.listFiles()` IPC overhead.
4. **Bounded Resource Consumption:** Strict depth, file count, and path length caps preventing "directory bomb" denial of service attacks.
5. **Clean V1 Interoperability:** Discrete item streaming with backwards-compatible capability negotiation (`CAP_FOLDER_TRANSFER = 0x01`).

---

## 2. Wire Protocol & Capability Negotiation

### A. Capability Flag
Peers indicate support for folder transfers during peer discovery and connection initialization:
- **Capability Bitmask:** `CAP_FOLDER_TRANSFER = 0x00000001` (Bit 0 of `capabilities` integer in `DISCOVERY_ANNOUNCE` and `DISCOVERY_RESPONSE`).
- **Negotiation Rule:** If both sender and receiver advertise `CAP_FOLDER_TRANSFER`, the sender may propose folder structures. If the receiver does not support `CAP_FOLDER_TRANSFER`, the sender MUST flatten the transfer manifest or notify the user before transmission.

### B. Extended Item Manifest
The V1 item metadata schema is extended with an optional `relative_path` attribute:

```json
{
  "itemId": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "landscape.jpg",
  "fileSize": 4194304,
  "mimeType": "image/jpeg",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "relativePath": "Vacation/Photos/landscape.jpg"
}
```

- For standalone root items (standard V1 files), `relativePath` is `null` or identical to `fileName`.
- For items nested inside a folder, `relativePath` specifies the POSIX relative subpath rooted at the top-level transferred folder.

---

## 3. Strict Canonical Path Normalization & Security Rules

To prevent path traversal, directory escapes, and cross-platform filesystem corruption, every `relativePath` MUST satisfy the following invariants:

### A. Structural & Character Invariants
1. **POSIX Separator Standard:** The path MUST use the forward slash `/` (`0x2F`) as the delimiter. Windows backslashes `\` (`0x5C`) MUST be rejected or converted.
2. **No Traversal Segments:** The path MUST NOT contain `..` (parent directory) or `.` (current directory) segments anywhere in the sequence (e.g., `../file`, `a/../b`, `./file` are strictly rejected).
3. **No Root or Trailing Slashes:** The path MUST NOT start with a leading slash `/` or backslash `\` (must be strictly relative).
4. **No Windows Drive Letters or UNC:** Paths starting with drive specifications (e.g., `C:`) or UNC prefixes (`\\`) are strictly rejected.
5. **No Null Bytes or Control Characters:** The path MUST NOT contain null bytes (`0x00`) or any ASCII control characters (`0x01` through `0x1F`).
6. **No Illegal Filename Characters:** Path components MUST NOT contain characters forbidden across common platforms: `:`, `*`, `?`, `"`, `<`, `>`, `|`.
7. **No Reserved Names:** Path segments MUST NOT match reserved Windows/DOS filenames (case-insensitive): `CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`.

### B. Bounded Complexity Limits
To prevent denial of service via memory exhaustion, deep recursion stacks, or filesystem inode starvation, the following hard boundaries are enforced:

| Constraint Category | Maximum Bound | Rationale / Mitigation |
|:---|:---:|:---|
| **Max Recursion Depth** | $\mathbf{\le 16\text{ levels}}$ | Prevents stack overflow and excessive directory nesting. |
| **Max Total Files per Folder** | $\mathbf{\le 10,000\text{ files}}$ | Prevents inode exhaustion and memory exhaustion in transfer queue. |
| **Max Total Subdirectories** | $\mathbf{\le 1,000\text{ directories}}$ | Bounded directory tree structure. |
| **Max Relative Path Length** | $\mathbf{\le 1024\text{ bytes}}$ | Prevents filesystem buffer overflow and database column clipping. |
| **Max Single Segment Length** | $\mathbf{\le 255\text{ bytes}}$ | Conforms to standard ext4, F2FS, and NTFS filename length limits. |

---

## 4. Android Storage Access Framework (SAF) Performance Plan

### A. The SAF Latency Problem
Android's `DocumentFile.listFiles()` incurs severe performance degradation when traversing large directories because each file query performs a distinct IPC Binder transaction to the underlying `DocumentsProvider`. For a directory of 5,000 files, `DocumentFile.listFiles()` can take over 30 seconds simply to enumerate file names.

### B. Optimized Direct ContentResolver Traversal
The sender MUST implement a low-level traversal crawler utilizing `DocumentsContract.buildChildDocumentsUriUsingTree`:

```kotlin
val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
    treeUri,
    parentDocumentId
)

val projection = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_FLAGS
)

contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
    // Batch process child documents
}
```

### C. Loop Detection & Cycle Prevention
To guard against cyclic filesystem loops (such as circular symlinks or virtual folder aliases):
- The crawler maintains a `HashSet<String>` of visited `documentId` values.
- If a directory `documentId` has already been visited during the current traversal session, it is immediately skipped with a warning log.

### D. Memory Budget & Backpressure Streaming
- The crawler streams items into a Kotlin `Flow<ItemOffer>` with a bounded backpressure buffer (`buffer(capacity = 64)`).
- Memory footprint for traversal metadata MUST remain below $\mathbf{16\text{ MB}}$ regardless of total folder size.

---

## 5. Destination Collision & Non-Destructive Resolution Policy

In strict accordance with Glide's **User Data Sanctity Invariant** (committed user files must never be silently overwritten or deleted):

1. **Top-Level Directory Conflict:**
   - If the receiver already has a directory named `Documents` in the target download location (`Downloads/Glide/Documents`):
   - The receiver automatically resolves the collision by appending a monotonic counter: `Downloads/Glide/Documents (1)`.
   - All child files and subdirectories are placed within the disambiguated root `Documents (1)/...`.
2. **Per-Item Conflict within an Existing Hierarchy:**
   - If a specific file already exists within a merged destination:
   - Monotonic renaming is applied to the filename: `report (1).pdf`.
3. **User Choice Prompt:**
   - If interactive mode is enabled, the UI may present the user with:
     - **Create New Copy:** `Folder (1)` (Default & Safe).
     - **Merge:** Merge new contents into existing folder, auto-renaming colliding individual files.
     - **Cancel:** Decline folder transfer.

---

## 6. Two-Stage Commit & Sandboxed Staging Mechanics

1. **Isolated Cache Staging:**
   - In-flight folder items are written to internal app cache:
     `cacheDir/staging/<session_id>/<item_id>.tmp`.
   - Under no circumstances are partial or unverified files written into the public user destination directory.
2. **Verification & Atomic Promotion:**
   - As each item completes, its SHA-256 hash is computed and compared against the manifest.
   - Upon verification success:
     1. The destination subdirectory path is resolved and created (e.g. `Downloads/Glide/Folder (1)/sub/`).
     2. The temporary file is atomically moved or streamed into destination storage via `ContentResolver.openOutputStream()`.
     3. Successful commit is recorded in Room database history.
3. **Quarantine on Disruption or Failure:**
   - If link drops or hash mismatch occurs:
     - The failing item is quarantined in `cacheDir/staging/quarantine/`.
     - Already-committed items in the folder remain intact.
     - Staged uncommitted files are purged.

---

## 7. Threat Model Coverage Matrix

| Threat ID | Threat Name | Vector / Mechanism | Mitigation Strategy |
|:---|:---|:---|:---|
| **T01** | Manifest Tampering | Attacker injects malformed or oversized `relativePath`. | Strict length ($\le 1024$), depth ($\le 16$), and character regex validation. |
| **T02** | Path Traversal (Zip Slip) | Relative path containing `../` escaping target folder. | Immediate rejection of any path containing `..` or leading `/`. |
| **T03** | Symlink Loop Denial of Service | Circular directory link causing infinite traversal loop. | Visited `documentId` tracking in sender crawler; recursion depth cap $\le 16$. |
| **T04** | Directory Bomb (Inode Exhaustion) | Massive nested folder structure crashing receiver. | Hard cap of $10,000$ files and $1,000$ subdirectories per transfer. |
| **T05** | Destination Overwrite | Attacker overwrites sensitive user files in Downloads. | Non-destructive monotonic collision renaming (`Folder (1)`); two-stage commit. |
| **T06** | Special Device Name Exploit | Filenames like `CON`, `PRN`, `AUX`, `NUL` causing OS crash. | Blacklist of reserved DOS/Windows device names. |
| **T07** | Partial Staging Leakage | Failed transfer leaves unverified data exposed. | Sandboxed staging in internal `cacheDir`; unverified blocks quarantined. |
| **T08** | Memory Exhaustion (OOM) | Buffering entire 10,000-item tree in JVM heap. | Flow backpressure with bounded queue capacity ($< 16\text{ MB}$ memory footprint). |

---

## 8. Milestone M13 Delivery Roadmap

- **GLIDE-069:** Safe Folder Transfer Extension Specification, Threat Model, ADR-0014, and Path Validation Fixtures (COMPLETE ✅).
- **Future Implementation (V2 Phase):**
  - Implement SAF low-level tree crawler in `:data` or `:transport-nearby`.
  - Implement relative path resolution and tree creation in `FileStagingManager`.
  - Add UI folder picker and folder badge indicator in transfer progress sheet.
