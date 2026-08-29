# ScrollGuard

**Take back your screen time.** ScrollGuard is an Android app that helps you (or a friend) break free from addictive apps by enforcing a structured time-based lock cycle.

---

## Features

- **Configurable Focus Timer** — Set custom free time, lock duration, and break windows (1–1440 minutes each), with direct numeric entry (tap the number) in addition to the +/- steppers
- **Fullscreen Block Screen** — Immersive lock screen with countdown timer
- **GENTLE / NUCLEAR Modes** — Gentle grants a temporary, per-app unlock window after you dismiss (default 60s) before locking again; Nuclear never offers a dismiss option. Neither mode can block the Home button or Recents — see [Nuclear Mode: What It Actually Guarantees](#nuclear-mode-what-it-actually-guarantees)
- **Multi-App Monitoring** — Select any launchable installed app to block during lock phases
- **Uninstall Protection** — Optional Device Admin prevents the app from being removed; toggling it off in-app actually revokes admin (not just a UI flag)
- **Survives Reboots** — Background service auto-restarts after device boot
- **Accessibility Health Alerts** — If the OS or user disables the blocking service mid-session, you get a distinct high-priority notification rather than silent, undetected non-enforcement
- **Usage Analytics** — Bar chart of time actually spent LOCKED per day (not total session time) and cycles completed
- **Math Puzzle Reset** — Admin reset requires solving a math puzzle, with a short cooldown after repeated wrong answers (no PIN to forget or leak)
- **Schedule Support** — Optional hour-based schedule for when blocking is active
- **Offline-First** — All animations and resources are bundled locally; no internet permission requested

---

## Download & Install

The easiest way to get ScrollGuard is to download the pre-built APK from the Releases page.

1. Go to the [Releases](https://github.com/csharikrishna/ScrollGuard/releases) page.
2. Download the latest `app-release.apk` (or `app-debug.apk`).
3. Open the downloaded file on your Android device and tap **Install** (you may need to allow "Install from unknown sources" in your settings).

---

## How It Works

```
IDLE ──▶ FREE (configurable) ──▶ LOCKED (configurable) ──▶ ALLOWED (configurable) ──┐
                                         ▲                                            │
                                         └────────────────────────────────────────────┘
```

1. **FREE Phase** — Use your phone normally. Timer counts down in the notification.
2. **LOCKED Phase** — Blocked apps trigger a fullscreen lock screen.
3. **ALLOWED Phase** — Short break window. Use the app briefly before it locks again.
4. **Repeat** — The LOCKED → ALLOWED cycle continues until you reset via the math puzzle.

### GENTLE mode's dismiss, precisely

Tapping dismiss doesn't just close the block screen — it grants the specific app you dismissed a temporary bypass window (`TimerState.grantGrace`, 60s by default). This is deliberate: simply closing the block screen would reveal the still-foreground blocked app, which the accessibility service would immediately re-detect and re-block in a tight loop. The grace window:
- is per-package, not global — dismissing Instagram doesn't unlock TikTok
- is cleared whenever a new LOCKED phase begins, when a session resets, or when the process restarts — it can never carry over into a later lock cycle or survive a reboot
- expires on its own; there is no way to make it permanent from the UI

### Nuclear Mode: What It Actually Guarantees

NUCLEAR mode never shows a dismiss option and blocks the in-app Back button. It does **not** and cannot prevent the Home button, Recents/app-switcher, or pulling down the notification shade — no Android app can, without Device Owner + Lock Task (kiosk) mode, which requires provisioning the device as managed (e.g. via a QR-code setup during device reset) and isn't something a normal installed app can obtain for itself. If you press Home during a NUCLEAR block, you'll land on the home screen; reopening the blocked app re-triggers the block immediately, but the phone itself isn't locked into ScrollGuard's UI. Treat NUCLEAR as "no self-serve escape hatch," not "kiosk mode."

---

## Architecture

| Module | Purpose |
|---|---|
| `TimerState` | Singleton state machine — phase management, timer math, SharedPreferences persistence |
| `TimerService` | Foreground service — 1-second tick loop, notification updates |
| `BlockerAccessibilityService` | Monitors foreground app via accessibility events; launches block screen |
| `BlockActivity` | Fullscreen immersive lock screen with GENTLE/NUCLEAR mode support |
| `MainActivity` | Dashboard — configuration, permissions, start/reset |
| `PinActivity` | Admin reset gate via math puzzle challenge |
| `AppPickerActivity` | RecyclerView-based app selector with search/filter |
| `UsageStatsActivity` | Analytics — MPAndroidChart bar chart, total time saved |
| `AdminReceiver` | Device admin — prevents uninstallation |
| `BootReceiver` | Restarts TimerService after device reboot |
| `AccessibilityUtils` | Shared check for whether the blocker service is enabled (used by both the UI and TimerService's health check) |
| `TransitionUtil` | SDK-aware activity fade transitions (API 34's `overrideActivityTransition` vs. the deprecated pre-34 API) |
| `data/*` | Room database — `AppEntry`, `UsageRecord`, `AppDao`, `DataRepository`, `ScrollGuardDatabase` |

State communication between `TimerService`, `BlockActivity`, `MainActivity`, and `BlockerAccessibilityService` is an in-process `StateFlow` (`TimerState.tickSignal`), not a broadcast — there is no `com.scrollguard.TICK` broadcast anymore.

---

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer — [Download](https://developer.android.com/studio) (optional — everything below also works from the command line via `gradlew`)
- **JDK 17**
- **Android SDK 34** (API 34)
- **Min SDK**: Android 8.0 (API 26)

---

## Testing & Verification

```bash
./gradlew testDebugUnitTest   # TimerState state-machine + GENTLE-mode grace-window tests (Robolectric)
./gradlew lintDebug           # must report 0 errors
./gradlew lintRelease         # must report 0 errors
./gradlew assembleDebug
./gradlew assembleRelease     # unsigned unless signing credentials are configured (see above)
```

Unit tests cover: phase transitions (FREE→LOCKED→ALLOWED→LOCKED, cycle counting), reset/clamp behavior, corrupted-preferences recovery, the GENTLE-mode grace window (grant/expire/per-package isolation/cleared-on-new-lock-cycle/cleared-on-reset/not-inherited-across-process-reload), and concurrent access to the monitored-apps set. They can't exercise the Accessibility Service or Device Admin flows directly (those need a real or emulated device) — verify those manually per the flows above after any change to `BlockerAccessibilityService`, `BlockActivity`, or the Strict Mode toggle in `MainActivity`.

---

## Build & Install

### 1. Clone / Open Project
```bash
git clone <repo-url>
```
Open the project folder in Android Studio. Wait for Gradle sync to complete (~2 minutes on first run).

### 2. Build APK
- **Menu**: `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
- Wait for the build to finish (~1-2 minutes)
- APK location: `app/build/outputs/apk/debug/app-debug.apk`

Or via command line:
```bash
./gradlew assembleDebug
```

### 3. Install on Device
- Transfer the APK to the target device (WhatsApp, ADB, USB, etc.)
- On the device: open the APK → allow "Install from unknown sources" → install

---

## Firebase Setup

The parental-control feature (Parent → Firebase → Child sync) needs a `google-services.json` from your own Firebase project. It's not committed — it's project-specific, not a secret (the security boundary is Firestore Rules + Auth + App Check, not this file's contents), so it follows the same local-file + committed-sample convention as release signing:

```bash
cp app/google-services.json.sample app/google-services.json
# replace it with the real file downloaded from your Firebase project's
# Project settings -> General -> Your apps -> google-services.json
```

Local/offline ScrollGuard usage needs no login and doesn't touch Firebase at all; this file is only required for the app to compile once the `google-services` Gradle plugin is applied (which is unconditional, so even a build that never opens Parental Control needs a valid file present).

## Release Signing

Release builds are unsigned by default (`app-release-unsigned.apk`) so a fresh clone always builds without needing production credentials. To produce an installable, signed release, provide signing credentials one of two ways — nothing is ever committed to the repo:

**Option A — local `keystore.properties`** (git-ignored):
```bash
cp keystore.properties.sample keystore.properties
# edit keystore.properties with your real values
```

**Option B — environment variables** (what CI uses):
```
SCROLLGUARD_RELEASE_STORE_FILE
SCROLLGUARD_RELEASE_STORE_PASSWORD
SCROLLGUARD_RELEASE_KEY_ALIAS
SCROLLGUARD_RELEASE_KEY_PASSWORD
```
Environment variables take precedence over `keystore.properties` if both are set.

**Generating a keystore**, if you don't already have one:
```bash
keytool -genkeypair -v -keystore scrollguard-release.jks -alias scrollguard \
  -keyalg RSA -keysize 2048 -validity 10000
```
Keep this file and its passwords somewhere safe outside the repo (a password manager, CI secrets) — losing it means you can never publish an update under the same app listing again.

Then:
```bash
./gradlew assembleRelease   # signed APK, if credentials are present
./gradlew bundleRelease     # signed AAB, for Play Store upload
```
Release builds run R8 minification and resource shrinking; the deobfuscation map is written to `app/build/outputs/mapping/release/mapping.txt` — keep it per release if you ever need to symbolicate a release-build crash report.

CI (`.github/workflows/android-ci.yml`) builds, lints, and tests on every push/PR, and additionally produces a signed release APK/AAB when a `v*` tag is pushed, provided the repo secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` are configured (Settings → Secrets and variables → Actions).

Every CI job also needs a `GOOGLE_SERVICES_JSON_BASE64` secret — `app/google-services.json` is required by the Firebase Gradle plugin but isn't committed (see "Firebase Setup" below), so CI writes it from this secret before building. Generate it with `base64 -w0 app/google-services.json` (macOS: drop `-w0`) and store the output as that secret. Without it, every job fails immediately, including plain unit tests.

---

## First-Time Setup (On Target Device)

> **Important**: These steps must be done once on the device where ScrollGuard will run.

1. **Open ScrollGuard** — The permissions card will show any missing permissions
2. **Enable Overlay** — Tap the button → allow "Display over other apps"
3. **Enable Accessibility** — Tap the button → find "ScrollGuard Blocker" → turn it ON
   - On Android 13+: If "Restricted Setting" appears, go to App Info → three dots → "Allow restricted settings"
4. **Ignore Battery Optimization** — Tap the button → allow (prevents Android from killing the service)
5. **Select Apps** — Tap the "Apps" card → search and check the apps you want to block
6. **Configure Timers** — Set Free Time, Lock Time, and Break Window (in minutes)
7. **Choose Mode** — GENTLE (dismissible after 15s, with a short grace window) or NUCLEAR (no dismiss button on the block screen itself — see [Nuclear Mode: What It Actually Guarantees](#nuclear-mode-what-it-actually-guarantees) for what it can't stop)
8. **Start** — Tap "START FOCUS SESSION"

---

## Resetting a Session

To end a session early:
1. Open ScrollGuard → tap "End Session (Verification Required)"
2. Solve the randomly generated math puzzle (e.g., `347 + 218`)
3. Session ends and all timers reset

> **Note**: There is no PIN. The math puzzle is randomly generated each time, so there's nothing to remember or leak.

---

## Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the timer running in the background |
| `SYSTEM_ALERT_WINDOW` | Displays the fullscreen block screen over other apps |
| `RECEIVE_BOOT_COMPLETED` | Restarts the service after device reboot |
| `POST_NOTIFICATIONS` | Shows the ongoing timer notification (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevents the OS from killing the service |
| `BIND_ACCESSIBILITY_SERVICE` | Detects when a blocked app opens in the foreground |
| Device Admin (optional) | Prevents the app from being uninstalled |

---

## Troubleshooting

| Problem | Solution |
|---|---|
| "App not installed" | Settings → Apps → Install unknown apps → allow for your file manager |
| Block screen not appearing | Accessibility service is OFF — re-enable ScrollGuard in Accessibility Settings. If it turns off mid-session you'll get a "ScrollGuard can't block apps right now" notification — tap it to fix |
| Service killed by Android | Enable "Ignore Battery Optimization" and disable any OEM battery savers |
| Escaped a NUCLEAR block via Home/Recents | Expected — see [Nuclear Mode: What It Actually Guarantees](#nuclear-mode-what-it-actually-guarantees) |
| Timer resets after reboot | Make sure BootReceiver is working — check battery optimization settings |
| Strict Mode switch won't turn off | Some OEMs/managed profiles restrict in-app admin removal; remove it manually from Settings ▸ Security ▸ Device Admin Apps — the app will tell you if this happens |

---

## Known Limitations

These are platform constraints, not bugs to be fixed later:

- **NUCLEAR mode cannot block Home/Recents/notification shade.** True kiosk behavior needs Device Owner + Lock Task mode, which a normally-installed app cannot self-provision. See the section above.
- **OEM background-kill of the Accessibility service can't be fully prevented**, only detected after the fact (health check runs every ~5 seconds while a session is active, with a notification if it goes down). Aggressive OEMs (some Xiaomi/Huawei/Samsung skins) may still kill it despite battery-optimization exemption; disabling any OEM-specific "battery saver"/"app freeze" feature for ScrollGuard helps.
- **The v1→v2 Room schema migration is destructive** (wipes usage history) because schema export was off prior to this version and there's no committed schema to migrate from safely. Schema export is on from v2 onward, so every future change ships a real, tested migration instead.
- **The math puzzle is light friction by design**, not a security boundary — unlimited difficulty scaling would risk locking someone out of their own device.

---

## Project Structure

```
ScrollGuardFixed/
├── .github/workflows/
│   └── android-ci.yml                   # Build + lint + test on push/PR; signed release on tags
├── app/
│   ├── schemas/                         # Room schema history (exportSchema=true), committed
│   ├── proguard-rules.pro               # R8 keep/dontwarn rules for release minification
│   └── src/
│       ├── main/
│       │   ├── java/com/scrollguard/
│       │   │   ├── MainActivity.kt          # Dashboard UI
│       │   │   ├── BlockActivity.kt         # Fullscreen lock screen
│       │   │   ├── PinActivity.kt           # Math puzzle admin gate
│       │   │   ├── AppPickerActivity.kt     # App selector
│       │   │   ├── AppPickerAdapter.kt      # RecyclerView adapter
│       │   │   ├── UsageStatsActivity.kt    # Analytics screen
│       │   │   ├── TimerState.kt            # Core state machine (singleton)
│       │   │   ├── TimerService.kt          # Foreground timer service
│       │   │   ├── BlockerAccessibilityService.kt  # App detection
│       │   │   ├── AdminReceiver.kt         # Device admin receiver
│       │   │   ├── BootReceiver.kt          # Boot-completed receiver
│       │   │   ├── AccessibilityUtils.kt    # Shared accessibility-enabled check
│       │   │   ├── TransitionUtil.kt        # SDK-aware activity transitions
│       │   │   └── data/
│       │   │       ├── AppDao.kt            # Room DAO
│       │   │       ├── AppEntry.kt          # Monitored app entity
│       │   │       ├── AppPickerItem.kt     # UI model for picker
│       │   │       ├── UsageRecord.kt       # Usage analytics entity
│       │   │       ├── DataRepository.kt    # Data access singleton
│       │   │       └── ScrollGuardDatabase.kt  # Room database
│       │   ├── res/
│       │   │   ├── layout/                  # XML layouts
│       │   │   ├── drawable*, mipmap*        # Icons (density-bucketed) and backgrounds
│       │   │   ├── raw/                     # Bundled Lottie animations
│       │   │   ├── values/, values-v27/     # Strings, themes, colors (+ API 27 theme override)
│       │   │   └── xml/                     # Accessibility & admin config
│       │   └── AndroidManifest.xml
│       └── test/java/com/scrollguard/
│           └── TimerStateTest.kt        # State machine + GENTLE-mode grace unit tests
├── build.gradle                         # Root build config
├── settings.gradle
├── keystore.properties.sample           # Template for local release signing (see below)
└── README.md
```

---

## License

This project is provided as-is for personal use.
