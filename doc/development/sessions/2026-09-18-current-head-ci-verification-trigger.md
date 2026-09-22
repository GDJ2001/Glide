# Current-head CI verification trigger

**Date:** 2026-09-18  
**Base:** `e7db0212c69e924d133f16f786291cdaa174eedd`  
**Purpose:** Trigger the repository's pull-request CI against the current HARD-008/HARD-009/HARD-011 source graph without altering production behavior.

This branch contains no application or protocol change. It exists only to obtain current-head CI evidence because push-triggered workflow runs stopped appearing after CI run 35293020239.

Required evidence before advancing the hardening roadmap:

- Android build, documentation/link checks, architecture checks, LAN gate, ordinary unit tests, expected-red HARD-003 regressions, and lint.
- Windows full solution build and ordinary .NET tests.
- Linux shared .NET build/tests and package build.
- HARD-011 shared-engine tests and architecture guards must compile and execute as part of the ordinary .NET suites.

Do not merge this documentation-only trigger solely for its own sake. Use the resulting CI evidence to update the authoritative HARD ledger on a substantive hardening branch.
