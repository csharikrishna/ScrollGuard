# ScrollGuard Parental Control MVP — Audit Progress

Legend: `[ ]` pending · `[x]` resolved (Fixed / Confirmed-no-bug / Deferred-because-X, with file:line)

## Environment
- [x] JDK / gradlew toolchain resolution — Fixed-no-action-needed: JAVA_HOME=Android Studio JBR (JDK 21), `gradlew.bat --version` resolves cleanly (Gradle 8.4). `java`/`adb` not on PATH but not required since local.properties + JAVA_HOME already resolve.
- [x] Android SDK / adb / emulator or AVD availability — Confirmed present: SDK at %LOCALAPPDATA%\Android\Sdk, platform-tools/adb present, emulator present, TWO AVDs exist (Pixel_3a_API_35_extension_level_13_x86_64, scrollguard_test) — real emulator-based 2-device matrix testing is achievable.
- [x] firebase CLI + rules emulator + node/npm for rules test suite — firebase-tools 14.5.1, node v22.23.2, npm 10.9.8 all present. firebase.json has no "emulators" port config yet (needed before rules test suite) — to be added in testing phase.

## Part 0 — Non-negotiable invariants
- [ ] 1. Enforcement is local-only (zero network calls in BlockerAccessibilityService event path)
- [ ] 2. Offline continues working indefinitely
- [ ] 3. Config flows down / status flows up / enforcement reads local (no field ownership violation)
- [ ] 4. Existing functionality preserved (TimerState/TimerService/BlockerAccessibilityService/BlockActivity/PinActivity/app-selection/analytics/persistence/release config)
- [ ] 5. Parental-control state fully isolated from personal-timer state
- [ ] 6. Security boundary = Firebase Auth + Firestore Rules + App Check (google-services.json not a secret)
- [ ] 7. No per-second cloud traffic
- [ ] 8. Fail safe on every remote failure path

## Part 1 — Seed leads
- [x] 1. firestore.rules families Case 2 hijack (no affectedKeys on update) — CONFIRMED CRITICAL. Old rule: `isFamilyMember(familyId)` alone with zero field restriction let an already-paired child (or parent) rewrite parentUid/childUid to hijack the family. FIXED in firestore.rules: Case 2 now requires isChildOfFamily + parentUid/childUid unchanged + affectedKeys().hasOnly(['childDeviceName']). Also found and fixed a SECOND, worse variant during the same review: the `create` rule never checked `parentUid == null`, so a malicious child could self-assign an arbitrary parentUid at family-creation time, bypassing the pairing code mechanism entirely — fixed by adding that check to `allow create`.
- [x] 2. pairing/{code} allow read: if isAuthenticated() — CONFIRMED, and worse than described: this grants `list`/query access too (Firestore `read` = `get`+`list`), so any authenticated (incl. anonymous) client could enumerate ALL pending pairing codes across ALL families, no brute force needed. FIXED: split into `allow get` only (no `list` granted anywhere), preserving the legitimate single-code-fetch claim flow while making enumeration impossible. Also fixed a related gap: the `create` rule didn't validate the creator owns `familyId` as its child — fixed by requiring `get(.../families/$(familyId)).data.childUid == request.auth.uid` at create time.
- [x] 3. Pairing codes logged at Log.i in PairingManager.kt — CONFIRMED. Fixed: removed the `$code` value from both the generate and claim log lines (PairingManager.kt). Kept familyId in logs (low sensitivity — an unguessable 20-char Firestore auto-ID; the child already knows its own familyId from Room, and post-fix, knowing it grants no capability without the actual code).
- [x] 3b. NEW related finding (not in seed list): the OLD `config/current` write rule (`isParentOfFamily` only) made the entire pairing bootstrap flow non-functional once rules are enforced — PairingManager.generatePairingCode() has the CHILD create the initial disabled config/current stub while parentUid is still null, which isParentOfFamily always rejects. This is a load-bearing correctness bug, not just a security gap: it would have blocked pairing from ever working end-to-end. FIXED: split into `allow create` (child, only when `enabled == false`) + `allow update` (parent-only, unchanged).
- [ ] 4. No FirebaseMessagingService registered — CONFIRMED via manifest + full grep (zero hits for FirebaseMessagingService/onMessageReceived/onNewToken). firebase-messaging-ktx dependency is present but entirely unused. Actual propagation today = SyncWorker's 15-min WorkManager floor + whatever sync-on-open exists (being verified by UI/UX subagent) — NOT the spec's primary "parent write → FCM push → one read" path. Deciding fix scope — true push needs a Cloud Function trigger (server-side deploy to live Firebase project, needs user's go-ahead re: Blaze billing) — plan: implement client-side receiving plumbing now (FirebaseMessagingService + token registration), ask user before writing/deploying any Cloud Function. Not yet implemented — in progress.
- [ ] 5. No Firestore rules emulator test suite exists — CONFIRMED absent (no package.json/rules test files). firebase.json also has no "emulators" port config. To be built in testing phase (Part 4) — will also empirically verify the rules reasoning above (bootstrap fix, Case 2 fix, enumeration fix).
- [x] 6. app/google-services.json untracked but not gitignored — CONFIRMED. Verified contents are genuine public client config only (api_key is Android-package-restricted, no OAuth secrets) — consistent with invariant #6. FIXED: added `app/google-services.json` to .gitignore, added `app/google-services.json.sample` redacted template (matches existing keystore.properties.sample convention). README update still pending.
- [ ] 7. ScrollGuardDatabase fallbackToDestructiveMigration + addMigrations(2_3,3_4) interaction; schema 3/4 json parity — delegated to data-layer subagent, awaiting report.
- [x] 8. AdminReceiver exported; confirm not load-bearing for enforcement/pairing — CONFIRMED NO DEPENDENCY: AdminReceiver.kt only touches TimerState (pre-existing local feature); grep shows no DevicePolicyManager/AdminReceiver reference anywhere in parental-control code (PairingManager/SyncEngine/ParentalAuthManager/ParentalControlState all clean). exported=true is required by the DEVICE_ADMIN_ENABLED system contract (protected by BIND_DEVICE_ADMIN permission, not exploitable via export alone) — same reasoning applies to BlockerAccessibilityService (BIND_ACCESSIBILITY_SERVICE-protected) and BootReceiver (BOOT_COMPLETED is a protected broadcast AND BootReceiver.kt explicitly checks `intent.action == ACTION_BOOT_COMPLETED` before acting, so an exported-but-explicit-intent poke is a no-op). Play-policy risk of shipping Device Admin API in 2026 remains a valid Release-Audit note (not a code bug) — carried to Part 7 Release Audit.
- [ ] 9. Play Store policy exposure: BIND_ACCESSIBILITY_SERVICE + PACKAGE_USAGE_STATS + CAMERA + parental monitoring — to be assessed in final report Release Audit section (policy/compliance judgment call, not a code fix).

## Part 2 — Full audit scope (by area)
- [ ] Core/business logic (MVP feature completeness, state mgmt, race conditions, lifecycle)
- [ ] Data & sync (Room migrations, SyncEngine, SyncWorker, no write-echo/clobbering)
- [ ] Security & auth (ParentalAuthManager, PairingManager, firestore.rules, exported components, secrets, logging)
- [ ] Enforcement (BlockerAccessibilityService, BlockActivity, time math, tick loop, grace, day-rollover, reboot, self-block safety)
- [ ] Notifications & background (FCM, TimerService, SyncWorker constraints, service restart)
- [ ] Android platform (manifest, permissions, queries, lifecycle, insets, dark/light, localization)
- [ ] Performance (leaks, threading, battery/CPU, Firebase read/write cost)
- [ ] Release engineering (build.gradle signing, R8/ProGuard, CI, Play readiness)
- [ ] UI/UX (every screen states, steppers, animations, accessibility)

## Part 3 — Real-world condition matrix
- [ ] process death mid-tick
- [ ] Activity recreation mid-pairing/mid-dashboard
- [ ] AccessibilityService killed & restarted by OS
- [ ] device reboot
- [ ] network loss mid-sync
- [ ] network reconnection after extended offline
- [ ] Firebase write/read latency spikes
- [ ] duplicate/out-of-order Firestore updates (configVersion)
- [ ] rapid +/- tapping
- [ ] concurrent writes from parent and child to same document
- [ ] stale local config after long offline period
- [ ] corrupted/malformed Room or Firestore data
- [ ] device clock changed forward/backward
- [ ] timezone change (travel)
- [ ] midnight/day rollover while restricted app foregrounded
- [ ] USAGE_STATS/accessibility/camera permission revoked at runtime
- [ ] accessibility service disabled by user mid-session
- [ ] app updated (schema migration path)
- [ ] low-memory kill of app process
- [ ] background execution restricted by OS/OEM
- [ ] parent/child persistently out of sync
- [ ] child clears app data (re-pairing)
- [ ] two children/parents pair simultaneously / colliding codes

## Part 4 — Testing
- [ ] ./gradlew clean
- [ ] ./gradlew testDebugUnitTest
- [ ] ./gradlew lintDebug lintRelease
- [ ] ./gradlew assembleDebug
- [ ] ./gradlew assembleRelease (unsigned)
- [ ] Firestore rules emulator suite (build it — absent) — allow/deny matrix per Part E.3/H
- [ ] Manual/emulator walkthrough (Part 3 matrix) — environment-dependent
- [ ] Regression pass after fixes

## Part 5 — Grep sweep
- [ ] TODO/FIXME/HACK/XXX
- [ ] catch (Exception) / catch (Throwable)
- [ ] printStackTrace
- [ ] Log.d / Log.v / Log.i (sensitive payloads)
- [ ] !!
- [ ] @Suppress / @SuppressLint
- [ ] allow read, write: if true
- [ ] QUERY_ALL_PACKAGES
- [ ] affectedKeys usage in firestore.rules
- [ ] addSnapshotListener scoping
- [ ] hardcoded secrets outside google-services.json

## New findings beyond the seed leads (found during static read)
- [x] ParentalControlState.refreshFromSync day-boundary bug — the "preserve fresher local consumedSeconds" max-merge compared raw values without checking `oldSnap.consumedEpochDay == r.consumedEpochDay`, so a stale in-memory count from before midnight (if resetDayIfNeeded() hadn't run yet in-memory) could survive a sync refresh into the new day via max(). FIXED: ParentalControlState.kt refreshFromSync now only takes the max when the old snapshot is from the same accounting day; otherwise trusts the incoming (already-correctly-reset) value.
- [x] SyncWorker cold-process hydration gap — doWork() checked `ParentalControlState.isPaired` (in-memory singleton, defaults false) without ever hydrating from Room first. If WorkManager starts the worker in a fresh process (e.g. after the app process was killed and the accessibility service hasn't reconnected yet), the safety-net sync would silently no-op exactly in the process-death scenario it exists to recover from. FIXED: SyncWorker.doWork() now calls ParentalControlState.hydrateFromRoom(dao) unconditionally before checking isPaired/role.
- [x] ParentalControlState.incrementConsumed narrow race — map lookup happened outside the synchronized block that guards restrictions-map swaps, so a concurrent hydrateFromRoom/refreshFromSync landing between the lookup and the increment could increment an orphaned snapshot instance, silently losing that tick's second. Low real-world impact (at most ~1s lost occasionally) but cheap to fix — FIXED: lookup moved inside the same synchronized block.
- [ ] SyncEngine writeGlobalEnabled/writeAppRestriction/removeAppRestriction each do 2 sequential non-atomic Firestore writes (app doc + configVersion bump on config/current) instead of a WriteBatch — a crash/network-drop between the two calls leaves configVersion slightly wrong, though the child's full-reread pull model means it doesn't actually desync the real config values. Medium severity, not yet fixed — candidate for fix pass.
- [ ] Undocumented "time requests" feature (BlockActivity.kt timeRequestListener + firestore.rules /requests subcollection) exists but is NOT part of ScrollGuard_Parental_Control_MVP.md's spec at all (spec Part I explicitly says keep MVP focused, no complex dashboards). Not a security bug (parent is already the trusted authority; no affectedKeys gap here is exploitable beyond what parent already controls) but is scope creep beyond the authoritative spec — flag in MVP Feature Audit rather than fix.

## Decisions / deferred-by-design (not bugs, scope calls made during the audit)
- Orphaned Firestore subcollection docs after unpair (config/current, status/current, catalog/current, apps/*, requests/*) are NOT recursively deleted by PairingManager.unpair() (which only deletes the top-level family doc — Firestore never cascade-deletes subcollections). Decided NOT to add delete rules + client-side recursive cleanup for this: it's a low-severity storage/hygiene cost (orphaned docs are unreachable and inert, not a security hole), and doing it properly needs either a Cloud Function or non-trivial client-side collection listing+batch-delete, which is disproportionate complexity for a low-severity item. Documented as a known limitation in the final report instead.
- FCM Cloud Function trigger (server-side, would need deployment to the live Firebase project `scrollguard-aba84` and Blaze billing plan) is being treated as a decision requiring explicit user go-ahead, not something to deploy unilaterally — consistent with treating deploys to shared/live infrastructure as needing confirmation. Client-side receiving plumbing (FirebaseMessagingService, token storage) is being implemented regardless since it's zero-risk/inert until a trigger exists.

## Notes / Assumptions Log
(populated as ambiguities are resolved)
