# ScrollGuard — Production Readiness

Status as of this pass. Split into what got fixed (done, verified), and what only you can
actually close out (decisions, accounts, and physical devices I don't have access to).

## Fixed this pass

- **App Groups hidden, not shipped half-working.** Its custom per-category Free/Lock/Break
  durations were never read by the enforcement engine (`BlockerAccessibilityService`/
  `TimerState` only ever apply one global cycle) — a parent could configure a "Gaming" group's
  timing and it would silently do nothing. Entry point hidden (`menu_app_picker.xml`); code and
  Room table kept intact for a real follow-up implementation (see "Deferred" below — this is
  real, non-trivial work, not a quick fix).
- **The whole Parental Control feature is now committed to git.** `AppGroupAdapter`,
  `AppGroupsActivity`, `AppGroup`, `BlockEvent`, `ParentalAppRestriction`, `ParentalConfig`,
  `ParentalPickerAdapter`, two drawables, two layouts, and the Room schema exports had been
  sitting uncommitted since before this audit despite being real, tested, working code (its own
  unit test has been passing in every regression run reported all session). Two of those
  drawables were already referenced by layouts that *were* committed — a fresh clone of this
  repo would have failed to build with a missing-resource error until this commit.
- **Target/compile SDK bumped 34 → 36** (the latest platform available; Play Store requires a
  recent target API for new submissions/updates). Robolectric bumped 4.13 → 4.16.1 alongside it
  — 4.13 hard-errors on any targetSdk above 34 in JVM unit tests; confirmed by the test suite
  failing immediately after the SDK bump, then passing again after the Robolectric bump.
- **Firebase Crashlytics added.** There was no crash reporting at all — a production crash left
  no trace anywhere. Verified live: initializes cleanly on-device, and a release build
  successfully uploads its R8 mapping file to the real Firebase project.
- **The actual release build (R8-minified, resources-shrunk) was smoke-tested live for the
  first time this whole audit.** Every prior verification pass — PiP bypass, checkbox fix,
  parental control flows, all of it — was done on debug builds only. Signed the release APK
  with the debug keystore for local testing, installed it, and confirmed: normal navigation,
  Room reads/writes, and Firebase Auth account creation all work correctly under minification.
  (This codebase writes Firestore data as raw `mapOf(...)`, not auto-mapped POJOs, so the
  classic "Firestore + R8 reflection" crash class doesn't apply here — one less risk.)
- **Found and fixed a real bug while testing the above**: the not-yet-paired child screen
  (`ParentalControlActivity.loadInitialState()`) showed its pairing UI without checking
  `ParentalAuthManager.isSignedIn()` first, unlike the sibling branch right above it — a device
  with an invalid session got a pairing screen where every Firestore call silently failed
  (`PERMISSION_DENIED`). Now checks the same way.

Full regression after every change above: `testDebugUnitTest lintDebug assembleDebug
assembleRelease` — BUILD SUCCESSFUL, 0 lint errors, all tests passing.

## What you need to do

These aren't things I skipped — they require your credentials, your judgment as the business
owner, or physical hardware I don't have.

### Before you submit to Play Console
1. **Generate a real release keystore and keep it somewhere safe.** Right now `hasReleaseSigningConfig`
   correctly falls back to an unsigned build when no keystore secrets are present (this was
   already set up correctly — nothing to fix). You need to generate one (`keytool -genkeypair`)
   and supply `SCROLLGUARD_RELEASE_STORE_FILE`/`_STORE_PASSWORD`/`_KEY_ALIAS`/`_KEY_PASSWORD`.
   **Losing this keystore means you can never update the app under the same listing again** —
   back it up somewhere durable before you do anything else.
2. **Review Google's current policy on `AccessibilityService` usage.** This app's entire
   blocking mechanism depends on it. Google has repeatedly restricted/removed apps using this
   API outside narrow accessibility use cases — I can't get you clearance for this; check
   Play Console's policy center and consider whether the in-app justification text (already
   present in the Setup Guide) needs to be strengthened for the review process.
3. **Test on a real Android 15/16 device specifically for edge-to-edge rendering.** Targeting
   API 35+ makes edge-to-edge the default OS behavior on devices actually running Android 15+
   — this emulator is Android 14, so I could not observe or verify this class of visual change
   (status bar/nav bar color setters are now deprecated but still functional on this device;
   whether content collides with system bars on a real 15/16 device is unverified).
4. **Test on real devices generally, ideally a couple of OEMs** (Samsung, Xiaomi, etc.). Every
   verification this whole engagement — this pass and the two before it — was done on one
   Android 14 emulator. OEM battery-management is typically far more aggressive than stock
   Android at killing background services; this app's `TimerService`/`BlockerAccessibilityService`
   have never been tested against that.
5. **Consider bumping the Android Gradle Plugin.** AGP 8.2.2 doesn't officially recognize
   compileSdk 36 yet (the build succeeds with a soft warning, not an error) — worth updating
   AGP before final submission, as its own dependency-compatibility pass, not bundled into this one.

### Legal / compliance (I can't make this call for you)
6. **Privacy policy + Play Data Safety form.** This app now collects: device usage patterns
   (Firestore sync between parent/child), account email (Firebase Auth), and crash/device data
   (Crashlytics, just added, currently with zero in-app disclosure or opt-out). None of this is
   necessarily a problem, but it all needs to be accurately declared.
7. **COPPA / child-safety policy determination.** If a real minor is the child-side end user
   (which the whole feature is built around), COPPA and Play's child-safety requirements likely
   apply. This is a legal judgment about your business, not something I can decide or draft
   without real risk of getting it wrong.

### Your live Firebase project (`scrollguard-aba84`) — I touched it, you should know
8. **I created test data in your real, live Firebase project during verification**, not a
   local emulator — `firebase.json` points at production. Specifically: two throwaway parent
   accounts (`sgtest_<timestamp>@example.com`, `sgtest2_<timestamp>@example.com`, password
   `TestPass123`) and whatever family/pairing documents resulted from pairing-flow testing. I
   did not delete any of this myself — deleting data from your live project isn't something I'll
   do without you explicitly asking, since I don't know what else depends on that data. Let me
   know if you'd like me to clean it up, or do it yourself via the Firebase console.
9. **Firestore security rules exist (`firestore.rules`) and look solid** (family-scoped auth
   checks, immutable identity fields, no collection enumeration) — but I never ran
   `firebase deploy` and won't without you asking; confirm what's actually live for your
   project matches this file.
10. **Known, unfixed limitation in the rules**: `pairing/{code}` allows any authenticated user
    (trivially anyone, since anonymous auth is free) to `get()` a specific code if they guess
    it — no rate limiting, since Firestore rules can't count requests. At 32^6 possible codes
    and a 5-minute expiry this is a low-realistic-risk gap, not urgent, but a real fix needs
    Firebase App Check or a Cloud Function gatekeeping claims — out of scope for a rules-file edit.

### Deferred (real work, not a quick fix)
11. **App Groups enforcement wiring.** Making group-specific timers actually work means giving
    `TimerState` multiple independent phase-tracking cycles (currently one global set of
    fields) and having `BlockerAccessibilityService` look up an app's group before deciding
    its phase — a genuine architecture change to the core enforcement state machine, which is
    exactly the component this whole engagement was about hardening. It deserves its own
    dedicated implementation and full re-verification pass (re-running the Part 1 PiP-bypass
    repro, the checkbox-bug stress test, everything), not a rushed addition here. Ask whenever
    you want this built.
