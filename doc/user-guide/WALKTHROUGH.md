# User guide deliverables — walkthrough

## Objective
Create readable, source-grounded annotated visual guides for Glide v1.0.0 in English and conversational Sinhala while preserving English UI labels.

## Delivered on documentation branch
- `doc/user-guide/USER_GUIDE_EN.md`
- `doc/user-guide/USER_GUIDE_SI_MIXED.md`
- Seven editable, numbered real-screenshot annotated SVGs in `doc/user-guide/assets/`
- Seven lossless PNG renders produced by `tools/generate-user-guide-assets.py` (see build status).
- `doc/user-guide/README.md` with source provenance and qualification checklist.

## Verification notes
- All badge legends were authored from repository screenshot/UI source inspection.
- The Android screenshot does not show an Apps category or Pause; pairing screenshot is from Ubuntu.
- Incoming offer and SAS verification are different UI stages.
- This work does not assert every dialog is captured, nor does it certify v1.0.0 release readiness.

## Remaining verification
1. Generate the seven PNG files using the renderer.
2. Inspect all PNGs for numbered badge legibility and alignment.
3. Run `pwsh -File tools/check-architecture.ps1 -FailFast` in a complete checkout with PowerShell.
4. Capture the remaining live Windows/Android/Linux dialogs against release binaries and add supplemental figures.
5. Verify Sinhala wording with a native-speaking reviewer.
