## Description
<!-- Provide a clear, concise summary of the changes and motivation. -->

## Type of Change
- [ ] Bug fix (non-breaking change fixing an issue)
- [ ] New feature (non-breaking change adding functionality)
- [ ] Breaking change (fix or feature causing existing behavior to change)
- [ ] Architecture / Refactoring (no functional change)
- [ ] Documentation / Contributor ecosystem update

## Platform Scope
- [ ] Android (`android/`)
- [ ] Windows Desktop (`desktop/windows/`, `Glide.Windows.UI`)
- [ ] Linux Desktop (`desktop/linux/`, `Glide.Linux.UI`)
- [ ] Cross-Platform Desktop Engine (`src/Glide.Core`, `src/Glide.Transport`, `src/Glide.Desktop`)
- [ ] Apple (`apple/`)
- [ ] Documentation / Tooling (`doc/`, `tools/`)

## Architecture & Security Verification
- [ ] Pure JVM isolation preserved in `:transfer-core` (no Android/GMS imports).
- [ ] Path sanitization enforced for all inbound files via `WindowsPathSanitizer`/`FilenameSanitizer`.
- [ ] Quarantine staging (`.part` streaming + SHA-256 verification + atomic commit) preserved.
- [ ] Zero unreviewed dependencies added.
- [ ] No hardcoded machine paths (`D:/...`, `C:\Users\...`) or private local IP addresses committed.
- [ ] No temporary files, binaries, or python bytecode (`*.pyc`) committed.

## Local Test & Verification Results
- [ ] `pwsh -File tools/check-architecture.ps1 -FailFast` passed (0 issues).
- [ ] `pwsh -File tools/test.ps1 -Target All` (or targeted suite) passed.
- [ ] `pwsh -File tools/lint.ps1` passed.

## Related Issues
<!-- e.g. Fixes #123, Addresses #456 -->
