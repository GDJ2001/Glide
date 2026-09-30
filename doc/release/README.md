# Release evidence status

There is no current Glide cross-platform release checksum catalog. The 2026-09-30 catalogs were withdrawn because they included stale packages and did not reliably match the files on disk. Their bytes are retained under [`doc/evidence/historical-release-checksums/`](../evidence/historical-release-checksums/) for audit only.

A new catalog must be generated from final artifacts built from one clean commit on the required hosts, then independently checked before release.
