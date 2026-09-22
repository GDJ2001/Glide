# Repository audit
Checked 2026-09-12. This is a local checkout audit, not a remote deployment or application security certification. [Master plan](../GLIDE_MASTER_PLAN.md) · [Asset inventory](../design/BRAND_ASSET_INVENTORY.md).

## Current state
The checkout is on main at 3b3ad6a, “Add Glide brand asset pack and build scripts”. Local origin/main points to the same commit; origin/HEAD points to origin/main. No fetch, push, or commit was performed. Earlier history includes cbdc6e5 branding and 1e4be6e IntelliJ configuration. There were 201 tracked files and a clean worktree before planning.

The complete tracked tree, non-Git working tree, ignored-file listing, Git history, text configuration, asset metadata, and export tooling were inspected. There is no Android application, Gradle wrapper, settings/build script, version catalog, Kotlin/Java production source, Compose/XML screen, Android manifest, app resources, database, unit/instrumented tests, CI workflow, root README, root license, or Android package ID. No implementation TODO backlog or architectural ADR existed. Absence of app code means no build or application test can currently run.

| Area | Evidence / conclusion | Treatment |
| --- | --- | --- |
| asset/ | 20 raster masters, 156 PNG exports, 2 ICOs, catalog, config, manifest, prompts, reference board | Canonical accepted brand; preserve |
| design/brand/ | Original presentation and design notes | Historical source; preserve |
| .idea/Glide.iml | Generic JAVA_MODULE | Not an Android module |
| .idea/misc.xml | JDK_25 / jbr-25 and output/out | Conflicts with proposed reproducible JDK 17 build; Gemini reconciles in GLIDE-001 |
| Other tracked .idea XML | Modules, VCS, Markdown, device-manager/cache settings | IDE metadata, not architecture |
| .idea/caches/deviceStreaming.xml | Cached device catalog | Does not prove any physical test device is available |
| .idea/workspace.xml | Ignored local IDE state | Not a handoff authority; do not commit |
| .gitattributes | Text auto normalization | Reusable |
| .idea/.gitignore | IDE-local ignore rules | Root Android ignore rules still needed |
| asset/tools/*.ps1 | Windows System.Drawing exporter, catalog generator, contact-sheet generator | Documentation/brand tooling only; not app build tooling |

## Reusable and incomplete components
Reusable: artwork, exact export dimensions and SHA256 provenance, offline catalog, mechanically reproducible raster exports. Existing documentation explicitly discloses independent master geometry variations and absence of vector masters. No application component can be credited as implemented.

Read-only validation command: `& .\asset\tools\Export-GlideAssets.ps1 -ValidateOnly`.
Result: 20 masters + 156 PNG exports + 2 ICO containers validated; 178 manifest graphics; 20,751,293 bytes. Decoding, sizes, transparency and checksums passed. This is asset integrity evidence, not adaptive-icon, accessibility, trademark, or Play approval evidence.

## Debt and structural risks
- No build baseline, CI, license decision, package identity or signing custody exists.
- Tracked IDE caches may create unrelated churn. Future cleanup must be a scoped task; they are not deleted here.
- Artwork is raster. Small icon legibility and cross-variant geometry need human review; do not “fix” by generating replacement logos.
- Flat launcher PNGs are not adaptive/themed resources. Full [gap list](../design/BRAND_ASSET_CHECKLIST.md) remains actionable.
- Export tooling is Windows-specific and assumes trusted reviewed configuration; do not bundle it into the APK or invoke mutating export steps during app CI.
- No actual transfer, OEM reliability, background survival, performance or security claim has been demonstrated.
- No remote branch freshness, GitHub settings, Play account, owned domain or physical hardware availability is asserted.

## Decisions already present and conflicts
The name Glide, flowing blue/cyan channel symbol, indigo wordmark, raster master provenance, singular asset/ directory, and existing variant filenames are established. Future agents must not rename the product, replace the artwork, assume the generic IDE project is Android, use JDK 25 merely because the IDE names it, or equate platform exports with integrated apps. Planned Android-only V1 supersedes any implication that existing desktop/iOS/web exports represent platform implementation.

Questions requiring explicit evidence rather than reinterpretation: final publisher-owned application ID, brand/legal clearance, available test phones, distribution terms for included artwork/dependencies, and whether Nearby STREAM performance meets the release gate. See [open questions](../OPEN_QUESTIONS.md). All application implementation remains unstarted.

