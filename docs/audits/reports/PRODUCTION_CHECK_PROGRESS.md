# Production Readiness Check — Progress

Status legend: `[ ]` not started · `[~]` in progress · `[x]` confirmed-intact / closed-with-evidence · `[!]` regression-found / honestly-still-open

## Part 1 — Regression re-verification
- [x] 1. Full clean build/test/lint suite — `BUILD SUCCESSFUL`, 0 lint errors, 33 unit tests passing
  (7 ParentalControlStateTest + 26 TimerStateTest, debug variant). Note: Part 0 claimed "36/36" —
  actual current count is 33; minor discrepancy in the prior write-up's own numbers, not a
  regression (both files present, both fully passing).
- [x] 2. Firestore rules emulator suite — re-ran fresh: **46/46 passing**, matches claim exactly.
- [x] 3. `TimerState.kt` usage-metered ALLOWED logic — confirmed present: `usableRemainingMs`,
  `currentForegroundPackage`, `isUsingMonitoredApp()`, and the ALLOWED branch inside `catchUp()`
  (lines 478-492) correctly deplete only while a monitored app is foregrounded. No clock-based
  regression found.
- [x] 4. `BlockerAccessibilityService` PiP overlay wiring — confirmed present:
  `showPipBlockOverlay`/`hidePipBlockOverlay`/`requiresOverlayBackstop` (lines 171-216), wired into
  both the parental (line 193) and focus-timer (line 209) fallback-scan branches.
- [x] 5. `firestore.rules` — confirmed `affectedKeys().hasOnly([...])` partitioning on
  `families/{familyId}` update (lines 76-90) and parent-only family `delete` (line 96) both intact.
- [x] 6. `PairingManager.unpair()` — confirmed subcollection cascade (pairing code, config/apps,
  status, catalog, requests, family doc) still intact (lines 211-234).
- [x] 7. Account deletion — in-app buttons (`btnDeleteAccountFromDashboard`,
  `btnDeleteAccountFromPairing`) confirmed present in the layout; external page confirmed live
  (HTTP 200 at https://scrollguard-aba84.web.app/delete-account.html).

**No regressions found in Part 1.**

## Part 2 — Gap closure
- [x] 2.2 AccessibilityService in-app disclosure — **real gap found and fixed**: the primary
  "Enable" action button bypassed the disclosure dialog entirely (only the secondary "(i)" info
  icon showed it). Now both paths route through the same disclosure dialog with an affirmative
  "Open Settings" tap required first. Also strengthened the disclosure string to explicitly state
  what data is/isn't accessed (was implicit before). Verified: compiles clean.
- [x] 2.3 Owner-decision items still surfaced — confirmed `docs/GOOGLE_PLAY_TARGET_AUDIENCE.md` and
  `docs/compliance/GOOGLE_PLAY_COMPLIANCE.md` still mark target-audience and Device Admin wiring as
  open owner decisions; confirmed via grep that `AdminReceiver`/`DevicePolicyManager` still has zero
  references in `ParentalControlActivity.kt` (nothing silently wired). **Found and fixed a related
  issue while checking this**: `docs/compliance/PLAY_STORE_LEGAL_STATUS.md` — a document from an
  earlier, separate pass — described account deletion, the orphaned-subcollection cascade, and the
  Analytics decision as still-open, when all three were since resolved. Added a superseded banner
  pointing to current docs rather than leaving stale contradictory guidance in the repo. Also noted
  `docs/compliance/COMPLIANCE_PROGRESS.md` is a duplicate of the root tracker — added a pointer
  rather than treating it as a second source of truth.
- [x] 2.4 Stale anonymous child Auth users — confirmed `DATA_RETENTION_AND_DELETION.md` already
  applies the same no-billing reasoning consistently (no Cloud Function proposed here either);
  no inconsistency found between documents.
- [~] 2.1 Split-screen bypass — bare-AOSP emulator (`android-34/default`, the only image
  installed) confirmed to still lack real split-screen SystemUI, consistent with the prior
  finding. A `google_apis_playstore` x86_64 system image download was started in the background to
  attempt genuine device-level verification — see the final report for its outcome (may complete
  after this document is written; check the final report's own Section on 2.1 for the actual
  result rather than this line).

## Part 4 — Final report
- [x] Delivered (see conversation)
