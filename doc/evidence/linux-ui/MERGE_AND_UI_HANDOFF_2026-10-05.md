# Merge and manual UI handoff — 2026-10-05

Product/package candidate: `86894bd341ec8c266364ec269250caee4e364d45`. Later documentation and handoff-tool commits do not change the compiled application candidate.

## Merge

The three requested branches were in one ancestor chain, so `main` was advanced with a fast-forward merge, preserving their history and avoiding duplicate merge commits. The recovered Linux changes are included.

| GitHub branch | Audited remote tip | Contained in main |
|---|---|---|
| `fix/native-completion-hardening` | `19b54de6fdabfe3f5271edc9914ea3ddc776f19b` | Yes |
| `codex/development-virtual-validation` | `bf5d91c2d105c2031b8f496d7f359322aed902a0` | Yes |
| `codex/linux-ui-rebuild` | `e50cdc1d845838531c7d6bc6e194316458235829` | Yes |

These three remote branches can be removed by the user once the final push is confirmed. They were not deleted by this task. Check for later branch changes before deleting; the table records the audited tips.

## Automated and package evidence

- Windows: `dotnet test desktop/Glide.slnx -c Release --no-restore` passed 310 desktop/core tests and 1 Windows UI test at `53681ff`. The later candidate changes Android packaging metadata/version input only. An initial parallel build/test run collided over a shared PDB; the sequential rerun passed.
- WSL `Glide-Test-Ubuntu-24.04`: `dotnet test desktop/tests/Glide.Tests/Glide.Tests.csproj -c Release` passed 310/310 with no skips.
- `tools/check-architecture.ps1 -FailFast`: 7 checks passed.
- Windows package: NSIS 3.12, .NET SDK 10.0.103, self-contained installer and portable ZIP, unsigned development.
- Android package: Gradle 9.6.1, debug APK/AAB version 1.0.0. The packager now records the actual wrapper version and passes the requested version to Gradle. The APK is locally debug signed; the AAB is not a tap-install file.
- Linux package: WSL Ubuntu 24.04, .NET SDK 10.0.401, x64 `.deb`, `.rpm`, and portable archive. The package script compares extracted executables against publish output. RPM installation and Fedora qualification are not claimed.
- All three package manifests identify the candidate above. The handoff assembler verifies existing package catalogs before copying a fixed list of files, then hashes every handoff file. It excludes older similarly named installers.

## Portable handoff

Generated location: `build/manual-ui-handoff-86894bd/`. Copy the whole folder to any location on the second PC; its commands use relative paths. It contains:

- `Installers/Windows/Glide-Setup-1.0.0-win-x64.exe` and portable ZIP;
- `Installers/Android/app-debug.apk` and AAB;
- `Installers/Linux/glide_1.0.0_amd64.deb`, RPM, and portable archive;
- `START-HERE.md`, `Verify-Copy.ps1`, `HANDOFF.json`, and per-platform source manifests;
- the UI runbook, CSV run sheet, and 45 synthetic payload files (115,402,833 bytes), including zero-byte, Unicode, nested folders, duplicate leaves, 35-item pagination, and progress files.

The master fixture and all 68 handoff files passed SHA-256 verification. The output is a development testing candidate, not a validated release.

## Ubuntu VM installation

Target: VirtualBox `ubuntu-26.04.1`. Final `.deb` is staged at `/home/gdj/Downloads/glide_1.0.0_amd64-86894bd.deb`.

Expected SHA-256: `cfe49d0d6d566f3fff939069d084d126cbfbdc7eb19c33e1b6ce35805ee71351`.

Installation uses normal `sudo apt install` authentication. Final installation/source readback is pending until the VM owner completes the password prompt. No password is collected by the repository or chat.

At the user's request, the VM is reserved for their human UI tests. The final candidate's native responsive appearance, keyboard/Orca walkthrough, physical transfers, and full install lifecycle remain unverified. Its current NAT adapter is suitable for downloading packages; cross-PC/phone discovery tests need the LAN setup in the runbook, normally Bridged Adapter. No VM network setting was changed during final installation preparation.
