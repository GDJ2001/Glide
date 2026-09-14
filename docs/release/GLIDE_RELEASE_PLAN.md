# Release plan
[DoD](../DEFINITION_OF_DONE.md) · [CI/CD](../development/CI_CD_PLAN.md) · [brand gaps](../design/BRAND_ASSET_CHECKLIST.md) · [risks](../RISK_REGISTER.md).

## Tracks and gates
Developer debug builds use provisional package with debug suffix and isolated discovery service ID. Internal testers get reproducible APKs or Play internal track; closed track exercises signed release behavior. Optional open beta follows privacy/quality readiness, not a way to bypass unfinished security. Production requires GLIDE-064 owner approval of exact artifact/version.

Use versionName1.0.0 with prerelease labels for testers and strictly increasing integer versionCode. Preserve schema/protocol compatibility through updates. AAB is Play submission; APK is controlled tester/sideload output. R8/minification, release manifest and dependency graph must be tested, not merely debug APK.

[Play App Signing](https://developer.android.com/studio/publish/app-signing) separates app-signing and upload keys. Owner controls custody/recovery and Play account access. No keystore/password/service-account secret in source; signing job uses protected environment and explicit approval. Never publish provisional dev.glide.app; final publisher-owned ID is irreversible in practice after release and must be finalized before public artifacts. Product remains Glide; Android image-loading library name collision is handled with clear packages and optional Coil, not a rename.

## Store readiness checklist
- [x] Publisher identity/developer verification, package ID, name/trademark and artwork/license rights reviewed ([KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md](KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md)).
- [x] Current target requirement checked: researched Play phone/tablet minimum 36 for new apps/updates from 2026-08-31; plan targets 37. Revalidated against API 37 ([GLIDE-061-store-assets-legal-listing-report.md](../testing/evidence/GLIDE-061-store-assets-legal-listing-report.md)).
- [x] Signed AAB, upload certificate, Play App Signing and update-install path verified ([GLIDE-062-signed-rc-artifacts-report.md](../testing/evidence/GLIDE-062-signed-rc-artifacts-report.md)).
- [x] Store title/short/full descriptions describe Android↔Android, GMS requirement, local transfer and actual tested limits ([STORE_LISTING_METADATA.md](store_assets/STORE_LISTING_METADATA.md)).
- [x] 512px icon existing export reviewed; feature graphic and real phone/tablet screenshots prepared to current Console specifications ([store_assets/](store_assets/)).
- [x] Accessible public privacy-policy URL and in-app privacy text consistent; account deletion not claimed as a feature for an account-free app ([PRIVACY_POLICY.md](PRIVACY_POLICY.md)).
- [x] Data Safety answers based on actual shipped SDKs, including Nearby diagnostics; no blanket “no data collected” assertion ([DATA_SAFETY_DECLARATION.md](DATA_SAFETY_DECLARATION.md)).
- [x] Foreground service type/declaration and demonstration video show user-started ongoing peer transfer and cancellation ([FOREGROUND_SERVICE_DECLARATION.md](FOREGROUND_SERVICE_DECLARATION.md)).
- [x] Content rating, target audience, ads declaration (none), permissions and app-access guidance completed ([GLIDE-061-store-assets-legal-listing-report.md](../testing/evidence/GLIDE-061-store-assets-legal-listing-report.md)).
- [x] Testers have two devices/instructions; Play reviewers receive usable local-transfer steps without account credentials ([CLOSED_TESTING_PROTOCOL.md](CLOSED_TESTING_PROTOCOL.md)).
- [x] OSS license notices generated from actual dependencies; repository/application license decision made by owner ([OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md)).
- [x] Prelaunch reports, crashes/ANRs, physical transfer, accessibility, security and performance gates passed ([PRODUCTION_RC_APPROVAL.md](PRODUCTION_RC_APPROVAL.md)).

Official [target policy](https://support.google.com/googleplay/android-developer/answer/11926878), [FGS declarations](https://support.google.com/googleplay/android-developer/answer/13392821), [bundle upload](https://developer.android.com/studio/publish/upload-bundle). Requirements can change; record check date and Console evidence in GLIDE-061.

For qualifying personal developer accounts created after2023-11-13, current [testing policy](https://support.google.com/googleplay/android-developer/answer/14151465) requires at least12 continuously opted-in closed testers for14 days before applying for production access. Account type/eligibility is not known here; inspect Console rather than promising automatic approval after14 days.

## Rollout and incident response
Where staged rollout is available, progress5%→20%→50%→100% with at least24h review per step and adequate sample size. Initial releases may not support the same staged controls; verify Console and use closed/bounded distribution before full launch. These are operational targets, not scheduled automations.

Halt expansion on any verified data corruption, consent bypass, unverified-file exposure, critical vulnerability, or material crash/ANR regression. Review Android vitals and user reports daily initially; no own crash SDK required. Reports contain consented redacted diagnostics only. Low volume means insufficient evidence, not zero failure rate.

Rollback means halt rollout and ship a higher-version forward fix; Android/Play versioning and data schemas make downgrade unreliable. Preserve compatible database migrations, do not delete user files, retain last-known-good source/artifacts/signing access and reproduce failure on exact version. Owner approves publication/halt actions. Support process handles suspected wrong-recipient sharing and corrupt files urgently; never request private file contents by default.

After launch:7-day health review, monthly dependency/policy review, patch security issues promptly, maintain conformance fixtures and device coverage. Future scope remains separately authorized. No production publication has occurred in this planning run.

