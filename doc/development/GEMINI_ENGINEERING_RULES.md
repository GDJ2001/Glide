# Gemini engineering contract
Read [implementation guide](GEMINI_IMPLEMENTATION_GUIDE.md), [architecture](../architecture/GLIDE_ARCHITECTURE.md) and the selected task before code.

1. UI/ViewModels never import Nearby/Bluetooth/Wi-Fi/socket APIs. Transport adapters never own UI/consent.
2. Keep domain, wire DTO, database entity and UI state distinct. Validate DTOs before domain mapping.
3. Core is Android-free; no Context, Uri, Parcelable, Room annotation or GMS ID in core public models.
4. One serialized owner mutates session state. Every callback carries session/attempt identity; terminal states absorb late events.
5. ViewModels expose immutable state; collect Flow with lifecycle awareness. No Android View references or global mutable singleton maps.
6. Service owns active transfer coroutine scope; screen scope owns only screen work. Explicit child failure/cancellation propagation and cleanup.
7. Blocking I/O off main; bounded memory and queues; descriptor closure on cancellation. Never buffer multi-GB files.
8. Both-side verification plus separate offer consent are mandatory. No trusted-by-name, autoaccept, custom crypto or plaintext fallback.
9. Completion requires protocol predicate and durable destination; SDK callback alone never means verified success.
10. Never display pause/resume or seamless process restoration in V1. Retry uses a new authenticated session.
11. Use SAF/Photo Picker/content grants; no legacy storage permission workaround or filesystem-path resolver.
12. Use documented FGS policy; no background-start tricks, hidden listeners, sticky reconnect or boot transfer.
13. Use stable pinned dependencies and AGP built-in Kotlin correctly. No casual libraries, alpha APIs or unrelated upgrades.
14. Single Activity/Compose/Navigation3/UDF; no Fragment-first, XML-primary, Material2, LiveData-primary or SharedPreferences new architecture.
15. Preserve accepted asset/ and design/brand/ masters. Derived platform resources must have provenance and human visual review.
16. No backend, account, telemetry SDK, ads or cross-platform feature implementation in Android V1 tasks.
17. No secrets, real user test files, raw authentication codes, filenames/URIs or hashes in release logs.
18. Follow accessibility/adaptive/design tokens from first UI task. No critical color-only or gesture-only action.
19. Inspect the current checkout; plans do not prove prior tasks complete. Do not overwrite unrelated user changes.
20. Build/test/review/evidence are required for COMPLETE. Missing hardware/credentials is an honest blocker.
21. Architectural contradiction follows [change control](CHANGE_CONTROL.md); do not silently redesign or work around security.
22. No implementation was authorized during the Astra planning run. These rules govern later user-authorized Gemini coding sessions.

