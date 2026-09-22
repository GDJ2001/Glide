# CI/CD plan
Planned, not installed. [Technology](../planning/TECHNOLOGY_RESEARCH.md) · [tests](../testing/GLIDE_TEST_STRATEGY.md) · [release](../release/GLIDE_RELEASE_PLAN.md).

## Pipelines
| Trigger | Jobs / gates | Outputs |
| --- | --- | --- |
| PR | Wrapper checksum, pinned dependency verification, compile, unit, Android lint, architecture/static checks | Test/lint reports, unsigned/debug APK |
| Protected branch | Same + emulator smoke API26/33/37 as feasible | Instrumentation and UI state evidence |
| Nightly/manual | Wider emulator matrix, fuzz seeds, dependency/security scanning, benchmarks on stable hardware | Regression report |
| Release tag + owner approval | All gates, release AAB/APK, R8 mapping, bundle validation, SBOM/checksums/provenance, signing in isolated environment | Exact approved artifacts |
| After release | Store install smoke, privacy-safe vitals/support review | Health/rollback decision |

Set default GITHUB_TOKEN read-only; grant security-events write only to scanning job. Pin Actions to immutable commit SHAs and review update PRs. Untrusted PR code gets no release secrets, write token or privileged pull_request_target execution. Cache keys include wrapper/dependency locks; no signing files in cache/artifacts. Concurrency cancels stale PR checks but never interrupts a signing publication mid-transaction.

Use Gradle dependency verification/locking where appropriate, protected wrapper checksums and license/transitive-diff review. Android lint is mandatory. A maintained Kotlin static checker may be added in GLIDE-055 only after exact-version research. Enable secret scanning/push protection and dependency review where repository plan supports them; if unavailable, document and implement an appropriate CI scanner instead of claiming protection enabled. CodeQL Java/Kotlin analysis must actually build Kotlin; a no-build Java-only result is insufficient. [CodeQL compiled languages](https://docs.github.com/en/code-security/concepts/code-scanning/codeql/codeql-for-compiled-languages).

Keep PR reports14 days, branch/nightly30 days, release artifacts/mapping/checksums for lifetime of supported release. Public artifacts must not include tokens, raw private logs, personal test content or signing material. Mapping files stored with restricted access when needed for crash diagnostics. Large device videos can use approved artifact storage with manifest links rather than Git.

Branch protection requires named build/lint/unit/security checks and review. Remote CI availability, billing/permissions and device-lab access are external facts to verify during GLIDE-003/055, not planning assumptions. Local green does not prove remote CI or Play readiness. No workflow or signing config was created in this planning run.

