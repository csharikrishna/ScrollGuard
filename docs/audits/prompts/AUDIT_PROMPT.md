# ScrollGuard — Full Production Readiness Audit (Core App + Parental Control MVP)

You are performing an exhaustive, adversarial production-readiness audit of the ScrollGuard
Android app. This is **not** a fresh app — it is a mature local app-blocker that was previously
"production-audited," which has just been extended with a Parent → Firebase → Child parental
control feature by a prior implementation pass. Your job is to determine, with evidence, whether
that claim and the prior pass's own self-report are actually true.

**Do not trust, as-is:**
- Code comments, docstrings, or naming ("this is production-audited", "zero network dependency").
- `walkthrough.md` — this is the *implementing agent's own self-report* of the parental-control
  build. It claims tests passed and describes an architecture diagram. Re-verify every claim in it
  independently; do not cite it as evidence of correctness.
- `README.md` feature claims.
- The fact that the project builds or that unit tests reported "PASSED" previously — re-run
  everything yourself.

**Ground truth for intended behavior** is `ScrollGuard_Parental_Control_MVP.md` (the authoritative,
"v2 — architecturally corrected" spec — treat `FuturePlan.txt` as superseded/historical context
only, not a requirement source). Part A of that spec lists non-negotiable invariants; treat any
violation of them as **Critical**, regardless of whether it "works" in a happy-path demo.

---

## PART -1 — EXECUTION PROTOCOL (read this before touching any file)

This is a single, long, expensive engagement, not a conversation. Optimize for finishing it
correctly in one continuous pass rather than for cautious incrementalism.

**Authorization.** For this task specifically, you are pre-authorized to:
- Edit and fix code, config, `firestore.rules`, Gradle, and CI files directly as issues are
  confirmed — do not stop to ask permission before each fix.
- Create local git commits at logical checkpoints (one per fix or tightly related group of fixes,
  with a message describing the bug and the invariant/requirement it violated) so that progress
  survives a context compaction, a crash, or an interruption. Do **not** push to any remote, do not
  force-push, do not amend/rewrite history, and do not touch branches other than the current one.
- Install missing local tooling needed to actually run the audit (e.g. `npm i -g firebase-tools`
  for the rules emulator, an Android emulator system image) rather than skipping a check because
  the tool isn't present yet.

**Don't stall on ambiguity.** If a design decision in the spec is underspecified, make the smallest
reasonable, most-secure choice, implement it, and record the assumption in the final report's
Executive Summary — do not pause the audit to ask. Only genuinely stop and ask if you hit something
no reasonable default resolves (e.g. you need a real Firebase project's credentials you don't have
and can't proceed at all without them for a specific sub-check) — and in that case, keep auditing
everything else while flagging that one item as blocked, rather than halting the whole engagement.

**Track progress durably.** Maintain `AUDIT_PROGRESS.md` in the repo root (create it now) as a
checklist mirroring Parts 1–5 below, one line per seed lead / checklist item / condition-matrix
row, each checked off with a one-line result (Fixed / Confirmed-no-bug / Deferred-because-X) and a
file:line reference as you go — update it continuously, not just at the end. This is what lets you
(or a resumed session) pick up correctly if this run gets cut off, and it is what the final report
in Part 7 is assembled from. Also use your own task-tracking tool if you have one, in addition to
this file — the file is the durable record, the tool is the working state.

**Work in this order** (cheapest/highest-signal first, so a token or time budget cut short still
leaves you with a real result rather than half a static read-through):
1. Environment check: confirm JDK/Gradle/Android SDK availability, whether `firebase` CLI and an
   emulator or physical device/AVD are available. Note gaps now so later phases don't stall
   silently.
2. Static audit: Parts 1, 2, and 5 (seed leads, full scope read-through, grep sweep) — read code,
   record findings in `AUDIT_PROGRESS.md`, do **not** fix yet. Finish forming the full picture
   before editing anything, so fixes don't contradict each other.
3. Triage: sort every recorded finding into the Part 6 priority order.
4. Fix pass: work top-down through the triage, committing as you go, re-running the relevant unit
   test after each fix.
5. Build/test verification (Part 4's gradlew commands), then the Firestore rules emulator suite
   (build it if it doesn't exist — confirmed absent as of this writing).
6. Manual/emulator run-through of Part 3's condition matrix, to the extent the environment allows;
   for anything requiring a second physical device or Play Console/App Check console access you
   don't have, say so explicitly in the report rather than skipping it silently.
7. Regression pass: re-run the full test/build suite once more after all fixes.
8. Assemble the Part 7 final report from `AUDIT_PROGRESS.md`.

**Use subagents for independent tracks if you have that capability.** Firestore rules/security,
timer/enforcement math, UI/UX, and release/build config are largely independent to *investigate*
(not to fix — fixes should land through the main thread so commits stay coherent and you don't get
duplicate/conflicting edits). Parallelizing the read-only investigation phase across subagents and
folding their findings into `AUDIT_PROGRESS.md` is an efficient use of a large context/token budget
here; doing so is encouraged, not required.

**Definition of done.** This engagement is not finished until: every row in `AUDIT_PROGRESS.md` is
resolved (Fixed / Confirmed-no-bug / explicitly Deferred with a stated reason — never silently
dropped), the full test/build suite in Part 4 has been run for real after the last fix, and the
Part 7 report has been produced with an explicit verdict. Do not end the session early because
"most" issues are addressed.

---

## PART 0 — NON-NEGOTIABLE INVARIANTS (from the spec — verify each one, don't assume it)

1. **Enforcement is local-only.** `BlockerAccessibilityService`'s blocking decision must perform
   **zero** network/Firebase calls in the event-handling path — it reads an in-memory snapshot
   backed by Room only.
2. **Offline continues working indefinitely.** No internet → child keeps enforcing the last
   synchronized configuration forever, not just for a grace window.
3. **Config flows down, status flows up, enforcement reads local.** No device writes a field it
   does not own (parent owns config, child owns status/catalog).
4. **Existing functionality is preserved.** `TimerState`, `TimerService`,
   `BlockerAccessibilityService`, `BlockActivity`, `PinActivity`, app-selection, analytics,
   persistence, and release config still work exactly as before for local/offline (no-login) usage.
5. **Parental-control state is fully isolated from ScrollGuard's personal-timer state** — different
   models, different persistence, no shared mutation.
6. **Security boundary is Firebase Auth + Firestore Security Rules + App Check** — the Firebase
   client config (`google-services.json`) is not a secret; knowing a child's ID/UID must never be
   enough to control the device.
7. **No per-second cloud traffic.** Time is consumed and counted locally; cloud writes happen on
   config change and throttled status reports only.
8. **Fail safe.** Every remote failure path falls back to the last valid local config. The cloud
   being unreachable must never silently disable restrictions.

If you find a design or code path that violates any of these, it is Critical severity even if the
happy path demo works.

---

## PART 1 — SEED LEADS FROM A PRELIMINARY PASS (verify each — confirm or refute with evidence)

A first-pass read of the current tree surfaced the following. **Do not take these as confirmed
bugs** — some may be non-issues once you read the full context (e.g. Firestore rule evaluation
semantics, other code that mitigates them). Investigate each fully, cite the exact file/line, and
report your actual finding (confirmed / refuted / partially true) rather than repeating this list.

1. **`firestore.rules` — family document hijack via Case 2.** In `match /families/{familyId}`,
   the `allow update` rule's second branch is just `isFamilyMember(familyId)` with no
   `request.resource.data.diff(resource.data).affectedKeys()` restriction. `PairingManager.kt`'s
   claim transaction only ever writes the single `parentUid` field, but the *rule itself* would
   also permit an already-paired child (or parent) to rewrite `parentUid`/`childUid`/any other
   field on that document after the fact. The spec (Part E.3) explicitly requires
   `parentUid`/`childUid` to be immutable after set and requires field-level `affectedKeys` checks
   throughout — grep the whole rules file for `affectedKeys` and see what you find. Determine
   whether a compromised or malicious child device can currently reassign control of the family to
   an attacker-controlled `parentUid`.
2. **`pairing/{code}` — `allow read: if isAuthenticated();`** with no ownership scoping. Determine
   whether this permits an authenticated (including anonymous-auth) client to list/enumerate the
   entire `pairing` collection rather than only `get()` a code it already knows, which would leak
   pending pairing codes for unrelated families. Also assess brute-force exposure: codes are
   6-char base32-ish (`PairingManager.generateCode()`), ~1.07e9 combinations, 5-minute TTL, but
   there is **no Firebase App Check dependency anywhere in `app/build.gradle`** and no rate
   limiting — check whether that leaves claim attempts economically/practically guessable at
   scale, contrary to spec Issue K.
3. **Pairing codes are logged at `Log.i` level in `PairingManager.kt`** (`"Pairing code generated:
   $code for family $familyId"` and `"Pairing code $code claimed..."`). A pairing code *is* the
   bearer secret that grants remote control of a child's device. Determine whether this is
   reachable in release logcat / bug reports and whether it should be removed or redacted.
4. **No `FirebaseMessagingService` is registered anywhere** (searched for
   `FirebaseMessagingService`, `MESSAGING_EVENT`, `onMessageReceived`, `onNewToken` — zero hits,
   and no such `<service>` in `AndroidManifest.xml`). The spec's Issue C explicitly designs the
   primary propagation path as "parent write → FCM data push to child → one read," with
   sync-on-app-open and the 15-min `SyncWorker` as fallbacks only. Read `SyncEngine.kt` and
   `ParentalControlActivity.kt`/`BlockActivity.kt`'s `addSnapshotListener` usage and determine what
   the *actual* propagation mechanism is today, how long a config change can realistically take to
   reach an already-running child (app backgrounded, no UI open), and whether that matches what
   `walkthrough.md` documents/implies.
5. **No Firestore Security Rules test suite exists** (no `package.json`, no rules-emulator test
   files anywhere in the repo). The spec's Part H and its final gate ("Do not declare readiness
   until ... Firebase Security Rules are emulator-tested") require this. Confirm it's genuinely
   absent, and if so this alone blocks any "ready" verdict regardless of how the rules read.
6. **`app/google-services.json` is untracked but not covered by `.gitignore`** (unlike
   `keystore.properties`/`*.jks`, which are correctly ignored). Determine current policy intent and
   whether it's at risk of being committed by a future `git add -A`.
7. **`ScrollGuardDatabase.kt`** calls both `.fallbackToDestructiveMigration()` **and**
   `.addMigrations(MIGRATION_2_3, MIGRATION_3_4)`. Confirm this can't silently wipe a user's
   `parental_config`/`parental_app_restrictions`/`app_groups`/`block_events` data on some reachable
   upgrade path, and cross-check `MIGRATION_2_3`/`MIGRATION_3_4`'s raw SQL against the committed
   `app/schemas/.../3.json` and `4.json` for exact column/type/default parity.
8. **`AdminReceiver` (Device Admin) is exported and declared** in the manifest. The spec (Issue J)
   says this must be optional hardening only, not load-bearing for core enforcement, with an
   honest "accessibility disabled" health-signal alternative. Verify nothing in the enforcement or
   pairing/setup flow actually depends on Device Admin being enabled, and separately flag the Play
   Console policy risk of shipping the Device Admin API in a consumer (non-EMM) app in 2026.
Every seed lead above must end this engagement in `AUDIT_PROGRESS.md` as Fixed,
Confirmed-no-bug (with the specific reason it's actually fine), or explicitly Deferred with a
stated blocker — never silently dropped.

9. **Sensitive-permission combination for Play policy:** `BIND_ACCESSIBILITY_SERVICE` +
   `PACKAGE_USAGE_STATS` + `CAMERA` + a parental-monitoring/remote-control feature is squarely in
   Play's higher-scrutiny category (Accessibility API declaration form, Families/child-safety
   policies if marketed at monitoring minors, restricted permissions justification). This needs an
   explicit Play Store readiness assessment, not just a manifest lint pass.

---

## PART 2 — FULL AUDIT SCOPE

Cover all of the following. Do not limit yourself to compiler/lint errors — actively hunt for
logical and behavioral bugs that only surface under real-world conditions.

**Core & business logic:** MVP feature completeness vs. `ScrollGuard_Parental_Control_MVP.md`,
state management (`TimerState`, `ParentalControlState`, `ParentalControlActivity`/StateFlow/
LiveData), edge cases, logical bugs, runtime bugs, race conditions, lifecycle issues.

**Data & sync:** Room persistence and migrations (`AppDao`, `ParentalDao`, `ScrollGuardDatabase`),
offline behavior, Firebase/Firestore synchronization (`SyncEngine`, `SyncWorker`), parent/child
communication correctness (down = config, up = status/catalog, no write-echo loops, no clobbering).

**Security & auth:** `ParentalAuthManager` (anonymous child auth, parent email/password), pairing
(`PairingManager`), `firestore.rules`, exported Android components, intents/broadcasts
(`BootReceiver`, `AdminReceiver`, `BlockerAccessibilityService` all `exported=true` — confirm each
is *necessarily* exported and can't be abused), hardcoded secrets, insecure local storage, excessive
permissions, sensitive logging, debug config leaking into release.

**Enforcement:** app blocking (`BlockerAccessibilityService`, `BlockActivity`), time-limit math
(`ParentalControlState` — allowance/consumed/remaining derivation, "changing allowance never resets
consumed," clamping, no negative/overflow), the 1-second tick loop and its debounce/selection logic,
grace-period rounding, day-rollover (`consumedEpochDay`), timezone/DST/clock-tamper handling,
reboot/process-death survival (`BootReceiver`, `elapsedRealtime()` reset semantics), block-loop/
self-block safety (ScrollGuard's own UI, launcher, System UI, Settings must never be blockable).

**Notifications & background:** FCM (or its absence — see Seed Lead 4), `TimerService` foreground
service behavior, `SyncWorker` WorkManager constraints/backoff, accessibility service being killed
by the OS and its restart/rehydration path, permissions being revoked mid-session.

**Android platform:** manifest correctness, permissions, package-visibility (`<queries>`), Android
lifecycle interactions across Activities/Services/BroadcastReceivers, app updates, low-memory
conditions, background execution limits on modern Android versions, edge-to-edge/insets, dark/light
mode, different screen sizes, localization readiness (hardcoded strings vs. `strings.xml`).

**Performance:** memory leaks (listener/coroutine scope lifecycle in `SyncEngine`,
`ParentalControlActivity`, `BlockActivity`), threading/concurrency (main-thread I/O, coroutine
cancellation, concurrent state updates to `ParentalControlState`), battery/CPU cost of the tick loop
and any listeners, Firebase read/write cost (no per-second writes — Issue L cadence).

**Release engineering:** `app/build.gradle` signing config resolution (env vars vs.
`keystore.properties`, unsigned-release fallback), R8/ProGuard rules for Room/Firebase/Firestore/
WorkManager/zxing/Lottie/MPAndroidChart, `firebase.json`/`.firebaserc`, `.github/workflows/
android-ci.yml` (does CI actually gate merges on lint+test+build; does the tag-triggered signed
release job fail safely when secrets are absent), dependency versions/staleness, Play Store
readiness (data safety form implications, Accessibility API declaration, Device Admin policy risk —
see Seed Lead 8/9).

**UI/UX** (as a senior product designer would review it): every screen's purpose, ON/OFF states
unmistakable, touch target sizes, `+`/`−` stepper behavior and clamping feedback, loading/empty/
error/disabled states on every parental-control screen (Loading · No child connected · Connected ·
Offline · Syncing · Error · No restricted apps), animations/transitions
(`TransitionUtil`, Lottie usage) not introducing lifecycle races, navigation sense, visual hierarchy,
spacing/typography consistency, accessibility (content descriptions, TalkBack, contrast).

---

## PART 3 — REAL-WORLD CONDITION MATRIX

For each of the following, state precisely what happens today (trace the actual code path, don't
guess) and whether it's correct per the invariants in Part 0:

process death mid-tick · Activity recreation (rotation/config change) mid-pairing or mid-dashboard ·
`BlockerAccessibilityService` killed and restarted by the OS · device reboot · network loss mid-sync
· network reconnection after an extended offline period · Firebase write/read latency spikes ·
duplicate/out-of-order Firestore updates (`configVersion` handling) · rapid `+`/`−` tapping ·
concurrent writes from parent and child to the same document · stale local config after a long
offline period · corrupted/malformed Room or Firestore data · device clock changed forward/backward
· timezone change (travel) · midnight/day rollover while a restricted app is in the foreground ·
`PACKAGE_USAGE_STATS`/accessibility/camera permission revoked at runtime · accessibility service
disabled by the user mid-session · app updated (schema migration path) · low-memory kill of the app
process · background execution restricted by the OS/OEM battery managers · parent and child devices
persistently out of sync (one never comes back online) · child clears app data (re-pairing
requirement per spec's own documented limitation) · two children/parents attempt to pair
simultaneously with the same or colliding codes.

---

## PART 4 — TESTING (do, don't just inspect)

Run, in order, and report actual output (not assumed output):

```bash
./gradlew clean
./gradlew testDebugUnitTest
./gradlew lintDebug lintRelease
./gradlew assembleDebug
./gradlew assembleRelease   # confirm it succeeds unsigned when no keystore secrets are present
```

Then:
- Set up the Firestore emulator (`firebase emulators:start --only firestore`) and write/run a rules
  test suite proving the allow/deny matrix in the spec's Part E.3 and H (valid parent, unrelated
  parent, the child, unauthenticated user, malicious child attempting a parent-field write,
  expired/reused pairing code). This does not currently exist (Seed Lead 5) — building it is part
  of the audit, not optional.
- Install the debug build on an emulator/device and manually walk: local blocking flows
  (unaffected by this feature), the full pairing flow, app selection, ON/OFF toggle, `+`/`−`
  controls, forced offline (airplane mode) continuity, reconnect reconciliation, force-stop/restart
  of the accessibility service, and a reboot.
- If a bug is found, reproduce it before fixing, fix it, then re-run the relevant test, then do a
  regression pass over the rest of the suite above.

---

## PART 5 — RE-AUDIT GREP SWEEP

Search the full source tree (`app/src/main`, `firestore.rules`) for each of these and investigate
every hit — do not blindly delete or blindly ignore:

```text
TODO
FIXME
HACK
XXX
catch (Exception)
catch (Throwable)
printStackTrace
Log.d(
Log.v(
Log.i(          # already known to leak pairing codes — check for other sensitive payloads
!!
@Suppress
@SuppressLint
allow read, write: if true
QUERY_ALL_PACKAGES
affectedKeys        # expected to appear in firestore.rules per spec Part E.3 — check if it's actually used anywhere
addSnapshotListener # confirm none are scoped to live inside a Service/AccessibilityService long-term
hardcoded secrets / API keys committed outside google-services.json's expected public client config
```

---

## PART 6 — IMPLEMENTATION RULES

Prioritize, in order: (1) critical bugs / invariant violations, (2) security issues, (3) data/state
corruption, (4) core feature failures, (5) sync/reliability issues, (6) lifecycle/threading
problems, (7) performance, (8) UI/UX, (9) maintainability, (10) cosmetic cleanup.

- Preserve working functionality; don't regress existing local/offline ScrollGuard behavior.
- Don't weaken security to make something "work" (e.g. don't loosen a Firestore rule instead of
  fixing the client to comply with a correct rule).
- Don't hide errors behind `@Suppress`/`@SuppressLint` or swallow exceptions to silence a crash.
- Don't fake functionality or claim something works without having actually run it.
- Don't make unrelated cosmetic changes while fixing a bug.
- Fix root causes, not symptoms — e.g. if a rule allows privilege escalation, fix the rule with
  `affectedKeys()` field partitioning, don't just avoid triggering it from the current client code.

---

## PART 7 — FINAL AUDIT REPORT

Produce a report with these exact sections:

1. **Executive Summary** — overall health.
2. **Critical Issues** — must-fix before release, each with file:line, the failure scenario, and
   the fix applied (or why it wasn't).
3. **High-Severity Issues**
4. **Medium/Low Issues**
5. **MVP Feature Audit** — every feature from `ScrollGuard_Parental_Control_MVP.md`: implemented?
   actually works? works under edge cases? UI wired to logic? persists correctly? works offline
   where required? syncs correctly where cloud is involved? secure? production-quality?
6. **UI/UX Audit**
7. **Security Audit** — explicitly resolve each Seed Lead in Part 1, plus anything else found.
8. **Reliability Audit** — resolve each item in Part 3's condition matrix.
9. **Performance Audit**
10. **Release Audit** — build/signing/R8/dependencies/CI/manifest/permissions/Play Store readiness.
11. **Recommended Fix Order** — prioritized implementation plan for anything not already fixed.
12. **Production Verdict** — exactly one of:
    - **READY TO SHIP**
    - **READY AFTER FIXES**
    - **NOT READY TO SHIP**

    Do not select "READY TO SHIP" while any Critical or High-severity issue remains open, and do
    not select it without having actually run the test/build commands in Part 4 and gotten real
    (not assumed) results, including a Firestore rules emulator test pass. Do not produce this
    report while any row in `AUDIT_PROGRESS.md` is still unresolved — finish or explicitly defer
    every row first, per the Definition of Done in Part -1.
