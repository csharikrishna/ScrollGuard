# ScrollGuard — Final Production Readiness Check (Pre-Submission Gate)

This is not another exploratory audit. ScrollGuard has already been through several complete
audit-and-fix passes — a technical/parental-control MVP audit, a UI/UX + multi-window/PiP bypass
audit, a timer/locking cross-validation, and a full Google Play/Privacy/Legal compliance workstream
(16 documents under `docs/compliance/` plus `docs/PRIVACY_POLICY_REQUIREMENTS.md`,
`docs/GOOGLE_PLAY_DATA_SAFETY.md`, `docs/GOOGLE_PLAY_TARGET_AUDIENCE.md`). A large amount of real
work is already done and verified. Your job here is threefold, in order:

1. **Re-verify nothing has regressed** — confirm the fixes from every prior pass are still actually
   in the code/rules/config, not silently reverted or broken by a later change.
2. **Close the specific remaining known gaps** listed in Part 2 — these are enumerated precisely
   because they were found and explicitly left open by prior passes, not because anyone is guessing.
3. **Deliver one final, consolidated, evidence-based production verdict** covering every dimension:
   core functionality, security, reliability, UI/UX, and Play Store/legal readiness.

Do not repeat the full discovery work of the prior audits. Do not re-litigate anything marked
resolved below unless your re-verification actually finds it broken — if it finds that, treat it as
a regression and say so explicitly, don't just quietly re-fix it as if it were new.

---

## PART 0 — WHAT IS ALREADY DONE (verify, don't rediscover)

**Timer/locking core logic** (this session): `TimerState`'s FREE/LOCKED/ALLOWED cycle was
clock-based for all three phases, meaning the ALLOWED window expired on a fixed schedule regardless
of whether a monitored app was actually used — reported as an "odd/even minutes" bug. Fixed: FREE
and LOCKED remain pure clock-based waits; ALLOWED is now usage-metered (`usableRemainingMs`,
`currentForegroundPackage`), only depleting while a monitored app is genuinely foregrounded, freezing
otherwise. Verified via 36/36 Robolectric unit tests (including new tests for the exact invariants)
**and** a real on-device test on the `scrollguard_test` emulator: a monitored app used continuously
for ~123s against a 120s budget correctly locked; the same window left untouched for 150s (well past
120s) correctly did **not** re-lock; re-using the app afterward correctly depleted and locked again
after ~125s. See this session's transcript/commits for exact timestamps and screenshots if archived.

**PiP/split-screen enforcement bypass** (prior UI/UX audit pass, `docs/audits/reports/UI_AUDIT_PROGRESS.md`
Part 1): a genuine, device-confirmed bypass — a monitored app's Picture-in-Picture surface stayed
visibly playing on top of `BlockActivity` even though the block screen had real focus underneath.
Fixed: `BlockerAccessibilityService` now shows a full-screen, opaque, touch-consuming
`TYPE_ACCESSIBILITY_OVERLAY` window whenever a restricted package is found only via the all-windows
fallback scan (not as the true active/focused window) — this is what catches PiP. Re-verified live:
screen went solid black on the same repro, overlay doesn't fire for normal blocks, tears down
cleanly when the bypass condition ends. **Split-screen was explicitly left untested** (see Part 2).

**Firestore security** (`docs/compliance/FIRESTORE_SECURITY.md`): the original family-hijack hole
(any family member could rewrite `parentUid`/`childUid` post-pairing) and the unauthorized
child-initiated unpair path are closed via `affectedKeys()` field partitioning. A later pass found
and closed two more gaps (config-version forgery at bootstrap, request self-approval forgery) and
**actually ran the rules-emulator test suite for the first time this session — 46/46 passing**
after fixing one stale test and adding 13 new ones. `firestore-tests/test/rules.test.js` exists.

**Data lifecycle**: `PairingManager.unpair()` now cascades — deletes `config/current`,
`status/current`, `catalog/current`, `requests/*`, and the tracked `pairing/{code}` document, not
just the top-level `families/{familyId}` doc (an earlier orphaned-subcollection bug is fixed; see
`docs/compliance/DATA_RETENTION_AND_DELETION.md`). In-app account deletion exists in
`ParentalControlActivity` (re-auth required) plus an external web page at
`https://scrollguard-aba84.web.app/delete-account.html`, per Play's User Data policy requirement for
both an in-app and external deletion path.

**Advertising/analytics**: Advertising ID collection is disabled; general Firebase Analytics
collection was removed entirely this round (owner-confirmed: no justification existed beyond
Crashlytics, which remains).

**Explicitly, deliberately NOT fixed** (accepted residual risk, already decided — do not re-flag as
a gap): Firestore TTL policy for pairing codes was not pursued because it requires Firebase billing,
which the owner has explicitly declined given no monetization path exists for this app. Leave this
alone unless the owner's billing decision changes.

**Still open / owner or legal decision, correctly left unresolved**: target audience/Families
classification (`docs/GOOGLE_PLAY_TARGET_AUDIENCE.md` — `[!]` OWNER DECISION), whether Device Admin
should ever be wired into parental control (`docs/compliance/GOOGLE_PLAY_COMPLIANCE.md` — `[!]`
OWNER DECISION, currently not wired to anything), and every item in
`docs/compliance/LEGAL_REVIEW_CHECKLIST.md` (COPPA, GDPR, India, consent mechanics — correctly
unresolved, these need actual counsel, not code).

---

## PART 1 — REGRESSION RE-VERIFICATION (do this first)

For each item in Part 0 marked as fixed/verified, re-confirm it is **still true in the current
codebase**, not just true when it was written up:

1. Re-run `./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease` and confirm a
   clean pass. Report the actual numbers (test count, lint findings, build result) — not "should
   still pass."
2. Re-run the Firestore rules emulator test suite (`firestore-tests/test/rules.test.js` via
   `firebase emulators:exec`) and confirm 46/46 (or whatever the current count is) still passes.
3. Read the current `TimerState.kt` and confirm the usage-metering logic for ALLOWED
   (`usableRemainingMs`, `currentForegroundPackage`, the ALLOWED branch inside `catchUp()`) is
   still present and hasn't been altered in a way that reintroduces clock-based expiry.
4. Read the current `BlockerAccessibilityService.kt` and confirm `showPipBlockOverlay`/
   `hidePipBlockOverlay`/`requiresOverlayBackstop` are still present and still wired into both the
   parental and focus-timer blocking engines.
5. Read the current `firestore.rules` and confirm `affectedKeys()` partitioning on
   `families/{familyId}` updates and parent-only family deletion are still in place.
6. Read the current `PairingManager.unpair()` and confirm the subcollection cascade delete is still
   there.
7. Confirm the in-app and external account-deletion paths still exist and are reachable from the UI.

If any of these have regressed, treat it as a **Critical** finding (a previously-fixed, previously
device-verified issue coming back is worse than a never-fixed one) and fix it before proceeding to
Part 2.

---

## PART 2 — CLOSE THE REMAINING KNOWN GAPS

### 2.1 Split-screen enforcement bypass — get this to the same standard as PiP

The PiP bypass was found, fixed, and proven with a real repro-before/repro-after device test.
Split-screen was reasoned to share the identical fix (the overlay backstop triggers on "restricted
package found via the all-windows fallback scan, not as the active window" — a description that
doesn't distinguish PiP from a non-focused split-screen pane) but was **never independently device-
tested**, and an attempt to force genuine split-screen via `adb shell am start --windowingMode 4` on
the bare-AOSP `scrollguard_test` emulator image did not produce real adjacent panes (the "split"
window just replaced the other app at full-screen bounds — this image's SystemUI doesn't implement
split-screen). To close this properly:

- Try a Google Play–flavored emulator image (not bare AOSP) or a real device, where SystemUI's
  actual split-screen divider/drag affordance exists, and run the real repro: monitored app in one
  pane, another app in the other, trigger a block while the monitored app is the non-focused pane,
  confirm the overlay backstop fires and genuinely blocks input into that pane (not just visually,
  touch it and confirm no interaction reaches the restricted app).
- Also test the inverse: monitored app as the *focused* split-screen pane when the block fires —
  confirm `BlockActivity`'s launch (from the service, with its current `FLAG_ACTIVITY_NEW_TASK`-based
  flags) takes over appropriately and the user can't trivially drag/resize the split to shrink the
  block screen to nothing while keeping the restricted app usable.
- If a genuinely split-screen-capable environment still isn't available to you, say so honestly and
  explicitly in the final report — do not claim this is verified when it isn't. In that case,
  strengthen the report's confidence statement to be explicit about what's proven (PiP) vs. inferred
  from code-path equivalence (split-screen), exactly as it's described here — don't upgrade the
  inferred case to "verified" just because it would be convenient to report.

### 2.2 AccessibilityService in-app disclosure — confirm it actually meets the current bar

`docs/compliance/ACCESSIBILITY_SERVICE_COMPLIANCE.md` documents that `SetupGuideActivity` shows an
info dialog (`setup_step_accessibility_why`) before routing to system Accessibility settings, but
flags this as needing review against the *specific* current requirement: disclosure must (a) appear
within the app, (b) display during normal usage without menu-diving, (c) describe what data is
accessed and how it's used, (d) require an **affirmative user action** (a tap/checkbox, not just
"dismiss"), and (e) be **kept separate from other data-collection disclosures**. Read the actual
`SetupGuideActivity` implementation and confirm all five hold — specifically whether the dialog has
a real "I understand / Continue" affirmative action distinct from just closing it, and whether it's
combined with any other consent ask on the same screen. Fix if it falls short; this is a concrete,
buildable UI requirement, not a legal question.

### 2.3 Confirm the two remaining owner-decision items are still surfaced, not silently resolved

Verify `docs/GOOGLE_PLAY_TARGET_AUDIENCE.md` and the Device Admin wiring decision in
`docs/compliance/GOOGLE_PLAY_COMPLIANCE.md` are still explicitly marked as open owner decisions in
whatever document a submission checklist would actually be read from, and that nothing in the code
has silently made a choice on either (e.g., Device Admin should still not be auto-enabled anywhere
in the pairing flow; the app's store-listing copy, if any exists in this repo, shouldn't imply a
target-audience stance the owner hasn't actually chosen yet).

### 2.4 Stale anonymous child Auth users after unpair

`docs/compliance/DATA_RETENTION_AND_DELETION.md` notes a real but low-risk residual gap: a child's
anonymous Firebase Auth identity is never deleted by unpair (there's no child-facing account-deletion
concept). Given the owner has already declined Firebase billing/Cloud Functions for the pairing-code
TTL issue (Part 0), a server-side cleanup job is likely the same non-starter here — confirm that
reasoning applies consistently rather than proposing a Cloud Function here while a functionally
identical one was declined elsewhere. Document the final decision either way; don't leave it silently
inconsistent between two compliance documents.

---

## PART 3 — EXECUTION PROTOCOL

- You're pre-authorized to edit code/config/rules to close 2.1–2.4, and to make local git commits
  at logical checkpoints. No push, no force-push, no history rewrite.
- Maintain `PRODUCTION_CHECK_PROGRESS.md` at the repo root: one line per Part 1 regression check and
  per Part 2 item, each resolved to Confirmed-intact / Regression-found-and-fixed / Closed-with-
  evidence / Honestly-still-open, with file:line or screenshot/log reference.
- Order: Part 1 (regression re-verification) fully before Part 2 (new work) — no point closing new
  gaps on top of a codebase that might have silently regressed something load-bearing.
- Definition of done: every Part 1 item re-confirmed with fresh evidence (not "per prior docs"),
  every Part 2 item either closed with real evidence or explicitly and honestly left open with a
  stated reason, full build/test/lint/rules-test suite passing, and the Part 4 report delivered.

---

## PART 4 — FINAL REPORT

1. **Regression Results** — one line per Part 1 check, with fresh evidence.
2. **Gap Closure Results** — one section per Part 2 item (2.1–2.4), with what was done and the
   actual evidence (test output, screenshots, code references) — or an honest statement of what
   remains unverifiable and why.
3. **Consolidated Status Table** — every prior audit track (technical/MVP, UI/UX, timer/locking,
   Play/Privacy/Legal) in one table: area, status, evidence, remaining action if any.
4. **Remaining Blockers** — must be explicit; distinguish engineering blockers (fixable now) from
   owner-decision blockers (target audience, Device Admin) from legal blockers (everything in
   `LEGAL_REVIEW_CHECKLIST.md`).
5. **Production Verdict** — exactly one of: **READY TO SHIP** / **READY AFTER FIXES** / **NOT READY
   TO SHIP**. Do not select "READY TO SHIP" while the Legal Review Checklist has any unresolved item
   requiring counsel, while the target-audience/Families classification is undecided, or while any
   Part 1 regression remains unfixed. A verdict of "ready" on the *engineering* work with legal/owner
   decisions still pending should be stated as exactly that — e.g. "technically ready; submission is
   blocked on the target-audience decision and legal review" — not rounded up to a bare "ready."
