# ScrollGuard

**Take back your screen time.** ScrollGuard is an Android app that helps you (or a friend) break free from addictive apps by enforcing a structured time-based lock cycle.

---

## Features

- **Configurable Focus Timer** — Set custom free time, lock duration, and break windows (1–1440 minutes each)
- **Fullscreen Block Screen** — Immersive lock screen with countdown timer that can't be dismissed (in Nuclear mode)
- **GENTLE / NUCLEAR Modes** — Gentle shows a dismiss button after 15s; Nuclear is inescapable
- **Multi-App Monitoring** — Select any installed apps to block during lock phases
- **Uninstall Protection** — Optional Device Admin prevents the app from being removed
- **Survives Reboots** — Background service auto-restarts after device boot
- **Usage Analytics** — Bar chart showing daily time saved and cycles completed
- **Math Puzzle Reset** — Admin reset requires solving a math puzzle (no PIN to forget)
- **Schedule Support** — Optional hour-based schedule for when blocking is active
- **Offline-First** — All animations and resources are bundled locally; no internet required

---

## How It Works

```
IDLE ──▶ FREE (configurable) ──▶ LOCKED (configurable) ──▶ ALLOWED (configurable) ──┐
                                         ▲                                            │
                                         └────────────────────────────────────────────┘
```

1. **FREE Phase** — Use your phone normally. Timer counts down in the notification.
2. **LOCKED Phase** — Blocked apps trigger a fullscreen lock screen. Can't escape it.
3. **ALLOWED Phase** — Short break window. Use the app briefly before it locks again.
4. **Repeat** — The LOCKED → ALLOWED cycle continues until you reset via the math puzzle.

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
| `data/*` | Room database — `AppEntry`, `UsageRecord`, `AppDao`, `DataRepository`, `ScrollGuardDatabase` |

---

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer — [Download](https://developer.android.com/studio)
- **JDK 17** (bundled with Android Studio)
- **Android SDK 34** (API 34)
- **Min SDK**: Android 8.0 (API 26)

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

## First-Time Setup (On Target Device)

> **Important**: These steps must be done once on the device where ScrollGuard will run.

1. **Open ScrollGuard** — The permissions card will show any missing permissions
2. **Enable Overlay** — Tap the button → allow "Display over other apps"
3. **Enable Accessibility** — Tap the button → find "ScrollGuard Blocker" → turn it ON
   - On Android 13+: If "Restricted Setting" appears, go to App Info → three dots → "Allow restricted settings"
4. **Ignore Battery Optimization** — Tap the button → allow (prevents Android from killing the service)
5. **Select Apps** — Tap the "Apps" card → search and check the apps you want to block
6. **Configure Timers** — Set Free Time, Lock Time, and Break Window (in minutes)
7. **Choose Mode** — GENTLE (dismissible after 15s) or NUCLEAR (inescapable)
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
| Block screen not appearing | Accessibility service is OFF — re-enable ScrollGuard in Accessibility Settings |
| Service killed by Android | Enable "Ignore Battery Optimization" and disable any OEM battery savers |
| Block screen dismissed easily | Switch to NUCLEAR mode in the configuration |
| Timer resets after reboot | Make sure BootReceiver is working — check battery optimization settings |

---

## Project Structure

```
ScrollGuardFixed/
├── app/
│   └── src/main/
│       ├── java/com/scrollguard/
│       │   ├── MainActivity.kt          # Dashboard UI
│       │   ├── BlockActivity.kt         # Fullscreen lock screen
│       │   ├── PinActivity.kt           # Math puzzle admin gate
│       │   ├── AppPickerActivity.kt     # App selector
│       │   ├── AppPickerAdapter.kt      # RecyclerView adapter
│       │   ├── UsageStatsActivity.kt    # Analytics screen
│       │   ├── TimerState.kt            # Core state machine (singleton)
│       │   ├── TimerService.kt          # Foreground timer service
│       │   ├── BlockerAccessibilityService.kt  # App detection
│       │   ├── AdminReceiver.kt         # Device admin receiver
│       │   ├── BootReceiver.kt          # Boot-completed receiver
│       │   └── data/
│       │       ├── AppDao.kt            # Room DAO
│       │       ├── AppEntry.kt          # Monitored app entity
│       │       ├── AppPickerItem.kt     # UI model for picker
│       │       ├── UsageRecord.kt       # Usage analytics entity
│       │       ├── DataRepository.kt    # Data access singleton
│       │       └── ScrollGuardDatabase.kt  # Room database
│       ├── res/
│       │   ├── layout/                  # XML layouts
│       │   ├── drawable/                # Backgrounds, icons
│       │   ├── raw/                     # Bundled Lottie animations
│       │   ├── values/                  # Strings, themes, colors
│       │   └── xml/                     # Accessibility & admin config
│       └── AndroidManifest.xml
├── build.gradle                         # Root build config
├── settings.gradle
└── README.md
```

---

## License

This project is provided as-is for personal use.
