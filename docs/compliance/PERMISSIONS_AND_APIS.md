# Permissions & Sensitive APIs Inventory

Technical facts only, verified directly against `app/src/main/AndroidManifest.xml` and the code
that uses each declaration. No policy interpretation here — see `GOOGLE_PLAY_COMPLIANCE.md` and
`ACCESSIBILITY_SERVICE_COMPLIANCE.md` for that.

## Manifest permissions

| Permission | Why | Declared | Used in | Essential? | User-facing control | If denied/revoked |
|---|---|---|---|---|---|---|
| `FOREGROUND_SERVICE` | Required to run `TimerService` while a Focus Timer session is active | `AndroidManifest.xml:5` | `TimerService.kt` | Yes, for the Focus Timer feature | Implicit (granted at install, normal permission) | Focus Timer sessions can't run |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required alongside `foregroundServiceType="specialUse"` on API 34+ | `AndroidManifest.xml:6` | `TimerService.kt:83-90` | Yes, for Focus Timer on Android 14+ | Implicit | Same as above on API 34+ |
| `RECEIVE_BOOT_COMPLETED` | Restart `TimerService` and re-hydrate parental state after reboot/app update | `AndroidManifest.xml:7` | `BootReceiver.kt` | Yes — without it, a reboot mid-session silently drops enforcement until the user reopens the app | Implicit | Enforcement doesn't resume automatically after reboot until the app is manually reopened |
| `SYSTEM_ALERT_WINDOW` | Draw the PiP/split-screen block overlay | `AndroidManifest.xml:8` | `BlockerAccessibilityService.showPipBlockOverlay()` | Only for the PiP/multi-window bypass backstop — core blocking works without it | User grants explicitly via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (`SetupGuideActivity.openOverlaySettings()`) | The PiP/split-screen overlay backstop doesn't render; the normal block screen still launches |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask the OS not to kill the background service | `AndroidManifest.xml:9` | `SetupGuideActivity.requestIgnoreBatteryOptimizations()`, `MainActivity` | Optional hardening, not core-functionality-blocking | User grants explicitly via system dialog | OEM battery optimization may kill the service more aggressively; the app's own health-check notification (`TimerService.checkAccessibilityHealth`) surfaces this |
| `POST_NOTIFICATIONS` | Show the ongoing session/health-alert notifications (API 33+) | `AndroidManifest.xml:10` | `TimerService.kt` | Optional but recommended — without it the foreground service still runs but is silent | User grants explicitly (system prompt) | No visible ongoing-session or health-alert notification |
| `PACKAGE_USAGE_STATS` | Optional usage-access data shown in the app picker | `AndroidManifest.xml:14` (protected permission, `tools:ignore="ProtectedPermissions"`) | `AppPickerActivity.kt` | Optional — display-only | User grants explicitly via `Settings.ACTION_USAGE_ACCESS_SETTINGS` | App picker simply doesn't show per-app usage minutes |
| `INTERNET` | Firebase Auth/Firestore network calls (parental-control sync only) | `AndroidManifest.xml:15` | `parental/*.kt` | Yes, for the parental-control feature only — the personal Focus Timer feature needs no network at all | Implicit | N/A — always granted (normal permission) |
| `ACCESS_NETWORK_STATE` | Used alongside `INTERNET` for connectivity-aware Firestore behavior | `AndroidManifest.xml:16` | Firebase SDK internals | Yes, alongside `INTERNET` | Implicit | N/A |
| `CAMERA` | Scan a pairing QR code | `AndroidManifest.xml:17` | `ParentalControlActivity.kt` via `zxing-android-embedded`'s `ScanContract` | Optional — pairing also supports typing the 6-character code manually | User grants explicitly (system prompt), with an in-app rationale dialog first (`camera_permission_rationale_title/body`) | QR scan unavailable; manual code entry still works |

`android.hardware.camera` / `camera.autofocus` are declared with `android:required="false"` — the
app installs and functions on devices without a camera.

## Special/non-manifest capabilities

| Capability | Declared | Scope requested | Actually used for | Verified unnecessary scope removed? |
|---|---|---|---|---|
| **AccessibilityService** | `AndroidManifest.xml:118-129`, `res/xml/accessibility_service_config.xml` | `typeWindowStateChanged\|typeWindowsChanged` events, `canRetrieveWindowContent=true`, `flagRetrieveInteractiveWindows` | Reading `rootInActiveWindow.packageName` / `windows[].root.packageName` only — see `ACCESSIBILITY_SERVICE_COMPLIANCE.md` for the full audit | Yes — `flagIncludeNotImportantViews` was removed this session; it broadens node-tree traversal the code never performs |
| **Device Admin** | `AndroidManifest.xml:145-157`, `res/xml/device_admin_rules.xml` | Empty `<uses-policies/>` — zero `DevicePolicyManager` capabilities | Personal Focus Timer's optional "Strict Mode" only (`MainActivity.kt:271-315`) — **not wired into parental-control pairing at all** | N/A — already minimal (zero policies) |
| **Foreground Service (`specialUse`)** | `AndroidManifest.xml:109-116` | `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "App usage timer and blocker"` | `TimerService` — session countdown + enforcement tick loop | See `GOOGLE_PLAY_COMPLIANCE.md` for Play-review risk framing |
| **WorkManager** | N/A (library, not a manifest permission) | `SyncWorker` — 15-min periodic, network-constrained | Fallback parental-config sync for when the live Firestore listener's owning process isn't alive | Not the primary sync path since this session's live-listener fix (`SyncEngine.attachLiveConfigListener`) |
| **Firebase Auth** | `firebase-auth-ktx` | Anonymous (child) + email/password (parent) | Identity for pairing | See `DATA_INVENTORY.md` |
| **Firebase Firestore** | `firebase-firestore-ktx` | Family/pairing/config/status/catalog/requests documents | Parental-control sync | See `FIRESTORE_SECURITY.md`, `DATA_INVENTORY.md` |
| **Firebase Crashlytics** | `firebase-crashlytics-ktx` | Crash/session diagnostics | Crash reporting | See `DATA_SAFETY.md` |
| **Firebase Analytics** | `firebase-analytics-ktx` (transitive Crashlytics dependency) | Collection disabled outright (`firebase_analytics_collection_enabled=false`, plus `google_analytics_adid_collection_enabled=false`) as of this session | Not used — no product justification was ever identified | Verified present in the actual packaged release manifest, not just source (`app/build/intermediates/packaged_manifests/release/AndroidManifest.xml`) |
| **Firebase Messaging (FCM)** | `firebase-messaging-ktx` dependency present | Not used anywhere — no `FirebaseMessagingService`, no manifest registration | Dead dependency from earlier planning; the sync-delay fix uses a Firestore listener instead (see `PLAY_POLICY_RESEARCH_NOTES.md` context) | Not removed this session — harmless unused dependency, flagged for future cleanup rather than treated as a compliance issue |

## `<queries>` scope
`AndroidManifest.xml:23-28` scopes app-enumeration to `ACTION_MAIN`/`CATEGORY_LAUNCHER` only —
deliberately narrower than `QUERY_ALL_PACKAGES`, which Play reviews far more strictly.

## No `isAccessibilityTool` declaration
Verified via grep across `app/`: the string `isAccessibilityTool` does not appear anywhere in the
manifest or any XML resource. The service is correctly *not* self-declared as an accessibility
tool (it isn't one).
