# Change control
[ADRs](../adr/README.md) · [Tasks](../TODO_MASTER.md).

When an assumption is impossible, stale or unsafe:
1. Stop reliance on that assumption; do not bypass the requirement.
2. Reproduce the conflict and collect official documentation/version/device evidence.
3. Create a short proposal: current rule, evidence, options, recommendation, security/UX/performance/compatibility impact, affected task IDs.
4. Update or supersede ADR with status Proposed; preserve prior decision history.
5. Obtain accountable architecture review for wire, trust, storage, SDK minimum, permission, package identity or scope changes. User already-authorized routine implementation choices do not need repeated approval.
6. Once decided, update canonical specification, task dependencies/acceptance, tests and sources together, then implement.
7. Record deviation and evidence in session report. No contradictory duplicate specification.

Patch-level compatible fixes within locked policy can be reviewed in the task PR. New unstable API requires feature justification, stable alternative failure, isolation boundary, migration and fallback. Never auto-upgrade all dependencies to resolve one compiler issue. Emergency security fix still needs documented evidence and follow-up review; it does not authorize silent protocol downgrade.

Statuses: Proposed, Accepted (planning), Validated (implementation evidence), Superseded, Rejected. Planning Accepted means a selected design, not physical proof. Owner/publication decisions remain explicit release blockers; time elapsed is not approval.

