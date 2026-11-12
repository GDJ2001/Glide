# User guide deliverables — walkthrough

## Objective
Create readable, source-grounded annotated visual guides for Glide v1.0.0 in English and conversational Sinhala while preserving English UI labels.

## Delivered on documentation branch
- `doc/user-guide/USER_GUIDE_EN.md`
- `doc/user-guide/USER_GUIDE_SI_MIXED.md`
- Seven editable, numbered real-screenshot annotated SVGs in `doc/user-guide/assets/`
- Seven lossless PNG renders produced by `tools/generate-user-guide-assets.py` (successful GitHub Actions run #43166094150).
- `doc/user-guide/README.md` with source provenance and qualification checklist.

## Verification notes
- All badge legends were authored from repository screenshot/UI source inspection.
- The Android screenshot does not show an Apps category or Pause; pairing screenshot is from Ubuntu.
- Incoming offer and SAS verification are different UI stages.
- This work does not assert every dialog is captured, nor does it certify v1.0.0 release readiness.

## Remaining verification
1. **PASS:** Seven PNG images generated and saved under `doc/user-guide/assets/` by GitHub Actions run #43166094150.
2. **PASS (sample visual checks):** Home, Incoming Offer, and Ubuntu Pair devices annotated PNGs were inspected; remaining images require a full release visual review.
3. **PASS:** `pwsh -File tools/check-architecture.ps1 -FailFast` succeeded under GitHub Actions run #43166227206.
4. **OPEN:** Capture missing live Windows/Android/Linux dialogs against release binaries and add supplemental figures.
5. **OPEN:** Verify Sinhala wording with a native-speaking reviewer.
