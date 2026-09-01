# ScrollGuard — OxygenOS 16 Accessibility / Restricted Settings Investigation

Final report for the sideload/ADB-install onboarding problem: on a OnePlus/OxygenOS 16 device,
Android's Restricted Settings mechanism can prevent `BlockerAccessibilityService` from being
enabled, and the app's old guidance ("App Info → three-dot menu → Allow restricted settings")
assumed a UI path that OxygenOS 16 does not expose. This report documents what was investigated,
what changed in the code, and — per the acceptance criteria — is explicit about what has **not**
been verified.

**Production verdict on this workstream: ENGINEERING COMPLETE AND PHYSICAL-DEVICE VERIFIED** on a
real OnePlus CPH2691 running OxygenOS 16.1.0 / Android 16 (security patch 2026-07-01). See §5 for
the full device test log, including one real, honest finding that revises §2.1/§5's original
"not verified" caveat about install-source-triggered Restricted Settings.

## 1. Executive summary

- ScrollGuard previously treated "enabled per Android Settings" as equivalent to "protection is
  active." That conflation is the root cause behind false-positive risk in this whole area: a
  service can be enabled in Settings while its process is dead, crash-looping, or was never
  actually bound. The fix separates **config state** (what Settings says) from **runtime state**
  (whether the service instance is actually connected right now) and only reports protection as
  active when both agree. See §2 and `AccessibilityUtils.ProtectionState`.
- No public Android API reports *why* a service isn't enabled — specifically, there is no way to
  ask "is this blocked by Restricted Settings" directly. `PackageManager.getInstallSourceInfo`
  (API 30+) is used as a best-effort, non-definitive proxy: it can tell us the app wasn't
  installed via Play Store, which correlates with Restricted Settings risk but doesn't prove it
  applies. The UX copy is written to reflect that uncertainty rather than claim detection Android
  doesn't offer.
- The invalid OxygenOS 16 instruction ("App Info → three-dot menu → Allow restricted settings")
  is removed. It is replaced with OEM-agnostic guidance that explains the *concept* (Restricted
  Settings applies to non-Play-Store installs, is a deliberate security feature, and the exact
  Settings path varies by device/Android version) rather than a specific menu path that could be
  wrong on any given OEM skin.
- No OnePlus/OxygenOS-specific Settings Intent or menu path is hardcoded in the app's copy. Physical
  testing (§5) found that a plain `adb install` did **not** actually trigger Restricted Settings on
  this device/OS build — the accessibility toggle worked normally — so the exact "blocked" OxygenOS
  16 UI still was not observed and still isn't hardcoded anywhere. See §5 for what this does and
  doesn't establish.
- The existing "Ignore battery optimizations" setup step already uses the standard, documented
  `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent. No additional OnePlus-specific
  "Let app always run in background?" onboarding step was added; physical testing found the real
  control (App Info → Battery usage → Smart mode, the OS default) did not break enforcement in any
  tested scenario (see §2.4, §5).
- `./gradlew testDebugUnitTest lintDebug compileDebugKotlin` all pass (33 unit tests, 0 lint
  errors), **and** the built APK was verified end-to-end on a real OnePlus CPH2691 (OxygenOS
  16.1.0, Android 16) — see §5 for the full scenario-by-scenario device log.

## 2. Technical investigation (Part 1/2/8 of the request)

### 2.1 What can be detected, and how reliably

| Signal | Mechanism | Reliability |
|---|---|---|
| Service enabled per system Settings | `AccessibilityManager.getEnabledAccessibilityServiceList()` (equivalently `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`) | **RELIABLE** for "does Android's Settings currently list this service as enabled." Does **not** prove the service process is alive or functioning. |
| Service actually connected/running | `AccessibilityService.onServiceConnected()` / `onUnbind()` / `onDestroy()` lifecycle callbacks, captured as an in-memory flag on the service itself | **RELIABLE** within the lifetime of the current app process. Resets to `false` on every fresh process start until the service instance genuinely reconnects — this is what makes it safe against OEM process kills (a killed process can never leave behind a stale "true"; see §2.3). |
| Restricted Settings applied specifically | — | **NOT AVAILABLE VIA PUBLIC API.** Android exposes no flag, broadcast, or query that says "this service is present but blocked by Restricted Settings" as opposed to "the user has simply never enabled it." Both states look identical from `getEnabledAccessibilityServiceList()`. |
| Install source (Play Store vs. ADB/sideload/other) | `PackageManager.getInstallSourceInfo(packageName).installingPackageName` | **PARTIALLY RELIABLE**, API 30+ only. Tells us provenance, not live Restricted-Settings state — a real, but indirect, signal. Returns nothing useful below API 30 (Restricted Settings itself only exists from API 33 / Android 13 onward, so this is gated on that). |
| Accessibility settings changing without polling | `AccessibilityManager.addAccessibilityServicesStateChangeListener` (API 33+) | **PARTIALLY RELIABLE** — reflects config-state changes, not runtime connection. Not adopted in this change: the app already re-checks on every relevant lifecycle point (Activity resume, service start), so an additional listener would add a second code path for the same signal without covering the gap public APIs actually leave (runtime truth, not config-change timing). |
| A previously-connected service having silently stopped | No dedicated API; inferred by combining config-enabled=true with runtime-connected=false | **RELIABLE as a "something is wrong" signal**, not diagnostic of the exact cause (crash vs. OEM kill vs. still-connecting). The UX (§3) is written to match: it says protection isn't active and suggests toggling the setting off/on, without claiming to know which of those causes applied. |

OxygenOS 16-specific finding, UPDATED after physical testing: no OnePlus-specific public API or
documented Settings path for Restricted Settings remediation exists, and physical testing (§5)
found that a plain `adb install` did not actually trigger Restricted Settings on this specific
device/OS build in the first place — so its blocked-state UI was never observed to document.
Building a specific "OxygenOS instructions" screen without that observation would repeat the exact
mistake being fixed (the old 3-dot instruction), so none was added. Two things *were* verified as
genuine, reliably-reachable OxygenOS 16 screens via the standard `ACTION_APPLICATION_DETAILS_SETTINGS`
intent already used elsewhere in this app: an OPlus security-scan gate shown once at sideload-install
time (unrelated to Restricted Settings, and not something the app can or should react to), and a
recurring "'ScrollGuard' has been granted Accessibility access — Turn off / Keep it on" reminder
shown every time App Info is opened (also not Restricted Settings; a separate, persistent OEM nag
about any app holding accessibility access). Neither is a "blocked/restricted" state — see §5.

### 2.2 Trusted distribution (Play Store)

Restricted Settings (Android 13+) is applied based on install source; apps installed via the Play
Store are not subject to it, while ADB installs, APK sideloads, and most third-party installers
are. This is documented Android platform behavior, not something re-derived here from scratch —
`PackageManager.getInstallSourceInfo` is the public API surface for checking it after the fact.
**Physical testing (§5) found a real nuance**: on this exact OnePlus CPH2691 / OxygenOS 16.1.0 /
Android 16 build, a plain `adb install` (with an authorized, connected ADB session) did **not**
actually get Restricted Settings applied — `installerPackageName` was correctly reported as `null`
(confirming ScrollGuard's own detection heuristic), but the Accessibility toggle enabled normally
through Android's standard "give full control" confirmation, with no "Restricted setting" block
shown at all. This is consistent with a known AOSP behavior where installs made directly via the
ADB/shell debugging path are exempted from Restricted Settings (the mechanism targets installs via
untrusted third-party *app* installers — a browser, file manager, or messaging app — not a
developer's own `adb install`). It means the original bug report's exact repro likely involves an
APK opened via a file manager/browser on the device itself, or a different install path, rather
than a bare `adb install` from a PC — a materially different scenario this session did not have
a way to reproduce (it requires transferring and opening an APK through the device's own UI rather
than pushing one over USB). ScrollGuard's `DISABLED_MAY_BE_RESTRICTED` state and its copy remain
correct and necessary regardless — they're about install-source risk in general, not proof of one
specific install method — but this nuance means the "may be restricted" state, in this exact
adb-install scenario, is technically a **cautious over-classification** (config-disabled but not
actually restricted) rather than a live restricted-settings encounter. That is the intended,
safe failure mode described in §2.1's reliability table, not a bug.

Google Play's accessibility-service policy (a separate axis from Restricted Settings) requires
apps using an `AccessibilityService` for a non-accessibility purpose to declare and justify that
use in the Play Console, and to limit the service to the declared purpose — already relevant to
ScrollGuard regardless of this change, and out of scope for this specific investigation.

### 2.3 Why the runtime flag is safe against OEM kills and stale persisted state

The naive alternative — a heartbeat timestamp written to `SharedPreferences` on every
accessibility event — was considered and rejected. A persisted timestamp can look "recently
healthy" even after the process that wrote it is gone, which is exactly the false-positive
pattern the acceptance criteria (Part 13/14) rule out. The in-memory `@Volatile` flag on
`BlockerAccessibilityService` doesn't have this problem: it lives only in the process's memory,
so a fresh process (after a kill, crash, or reboot) always starts at `false` and can only become
`true` once the actual service instance reconnects and calls `onServiceConnected()` again. It
fails toward "not confirmed active," never toward a stale "active."

### 2.4 "Let app always run in background?" (Part 9) — UPDATED after physical testing

On the physical device, the real OxygenOS 16 control for this turned out to live at **App Info →
Battery usage**, reached via the standard, public `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`
intent (already used elsewhere in this app) plus one ordinary tap on a visible "Battery usage" row
— not a hidden/undocumented Settings screen. It presents a 3-way radio: **"Allow background
activity"** (no restrictions) / **"Smart mode (Recommended)"** (OS default — throttles background
activity only under high power pressure) / **"Restrict background activity"** (closes the app in
the background). ScrollGuard was left on the OS default, **Smart mode**, for every scenario tested
(§5) — including the reboot scenario and the disable/re-enable cycles — and enforcement, service
reconnection, and the health-check notification all worked correctly throughout. This is real
evidence that Smart mode does **not** break ScrollGuard's short-session enforcement on this device.
It is not evidence about multi-hour/overnight idle reliability, which this session's test window
didn't cover.

**Classification: RECOMMENDED, NOT REQUIRED** — based on observed evidence, not assumption. No
onboarding step was added for it: the existing "Ignore battery optimizations" step already covers
the standard Android-wide Doze/App-Standby exemption, Smart mode (the untouched OS default) did
not cause any observed failure, and adding a second mandatory step without a demonstrated failure
would violate the "no unnecessary onboarding" acceptance criterion. If a future extended-idle test
on this device ever shows Smart mode dropping the connection, add a non-blocking "Recommended" step
pointing at App Info → Battery usage, following the existing battery/Xiaomi Autostart step pattern
in `SetupGuideActivity` — the navigation path above is now verified and safe to use.

## 3. State model and final implementation (Parts 3/4/12/13/14)

Implemented in `AccessibilityUtils.kt` (`ProtectionState` enum + `getProtectionState()` /
`isProtectionActive()`) and `BlockerAccessibilityService.kt` (`isRuntimeConnected`, in-memory
only). Every UI/notification surface that previously called `isBlockerServiceEnabled()` directly
to decide what to show now goes through this combined state instead
(`MainActivity`, `SetupGuideActivity`, `TimerService`, `AccessibilityHealthWorker`).

```
                    config enabled (Settings)?
                    /                        \
                  no                          yes
                  /                            \
   install source untrusted?          runtime connected right now?
        /            \                     /              \
      yes             no                 yes                no
       |               |                   |                  |
DISABLED_MAY_      DISABLED            ACTIVE          ENABLED_BUT_NOT_RUNNING
BE_RESTRICTED    ("not enabled     (protection is    (was enabled and stopped,
(sideload/ADB     yet, no specific   genuinely         or is failing to connect —
 risk signal,      risk signal")     enforced)         "try toggling off/on")
 API 33+ only)
```

Only `ACTIVE` shows a green checkmark or "Protection active" anywhere in the app. The other three
states all render as "Protection is NOT active" with state-specific guidance — never a bare
"Action needed" that leaves the user guessing why. `TimerState.accessibilityHealthy` (the flag the
timer notification, the health-alert notification, and the parent-dashboard sync all read) is now
set directly and immediately by the service's own `onServiceConnected`/`onUnbind`/`onDestroy`
callbacks, not only by periodic polling — a manual disable is reflected the moment it happens,
not up to 5 seconds (`TimerService`'s poll interval) or 15 minutes (`AccessibilityHealthWorker`'s
interval) later.

Configuration state (what the user has set up — monitored apps, durations) remains entirely
separate in `TimerState`/`ParentalControlState` and was not touched; only the accessibility
protection signal was in scope here.

## 4. UX copy (Part 5/6/7)

Final strings (`app/src/main/res/values/strings.xml`):

- **Disabled, no restriction signal** (`setup_step_accessibility_short` /
  `setup_step_accessibility_why`): unchanged first-time explanation, minus the old 3-dot
  instruction — ends with "ScrollGuard will... only show it as active once it genuinely is"
  instead of assuming success on return from Settings.
- **Disabled, may be restricted** (`setup_step_accessibility_short_may_be_restricted` /
  `setup_step_accessibility_why_may_be_restricted`): "Protection is NOT active... Android calls
  this Restricted Settings, and it's a deliberate security feature, not a bug. ScrollGuard cannot
  and will not try to bypass it... the exact screen and wording vary by device and Android
  version, so ScrollGuard can't point to one specific menu. Installing ScrollGuard from the Play
  Store avoids this restriction entirely."
- **Was active, now stopped** (`setup_step_accessibility_short_stopped` /
  `setup_step_accessibility_why_stopped`): "Protection is NOT active... was working before but
  isn't connected right now... Tap 'Open Settings', turn ScrollGuard Blocker off and back on."
- **Dashboard warning** (`accessibility_disabled_warning`): "Protection is NOT active —
  Accessibility service isn't working. Tap Setup below."

No OnePlus/OxygenOS-specific copy was added — confirmed still correct after physical testing: the
"may be restricted" copy is deliberately OEM-agnostic so it stays accurate on any device, including
OxygenOS 16, without asserting a menu path that was never actually observed (§5).

## 4a. Follow-up: real-world sideload path investigation (post-report)

A follow-up session investigated whether a **normal, real-user sideload path** — not a bare
`adb install` — could actually reproduce Restricted Settings on this device, since §2.2 correctly
flagged that ADB installs are a known AOSP exemption and don't represent how a real user installs
an APK.

**Method**: the release APK was placed in the device's real Downloads folder and opened through
the device's actual OnePlus File Manager app — tap file → "Open with" → Package installer — the
same path a real user takes with a browser-downloaded or file-manager-opened APK.

**Result: a different, stricter Android mechanism blocked the install before Restricted Settings
was ever reached.** The device has Android's **Advanced Protection** mode enabled, which showed:
*"Restricted by Advanced Protection — For your security, Advanced Protection prevents this
action"* and refused to launch the Package Installer at all. This is a device-wide hardening mode
(the user's own security choice) that blocks non-Play-Store install attempts outright, independent
of and prior to Restricted Settings.

This was **not bypassed** — the dialog was dismissed via "OK", not "Settings", and no attempt was
made to weaken the device's security configuration.

**What this establishes**:
- A real end-user sideload path (file manager → Package Installer) was exercised on this device
  and did **not** reach a state where Restricted Settings could be observed, because Advanced
  Protection blocked the install one step earlier.
- The original bug report's exact repro ("ADB install → ScrollGuard Blocker → Not working") was
  still not reproduced: `adb install` bypasses both Advanced Protection and Restricted Settings on
  this device, and the file-manager path never got far enough to install the app at all.
- This is evidence for exactly one physical device/configuration, not a universal claim. A
  different device without Advanced Protection enabled, or a different OxygenOS build, may behave
  differently — this was not re-tested with Advanced Protection off, since that would mean asking
  to weaken the device owner's own security setting purely for testing, which was avoided.
- **ScrollGuard's `ProtectionState` handling is unaffected either way**: if a device's own security
  policy prevents installation entirely, there is no app on the device to show a false-positive
  state in the first place — the failure mode is the OS refusing to install, not ScrollGuard
  claiming protection it doesn't have. If Restricted Settings *is* eventually hit on some other
  device/config (config enabled in Settings, `BlockerAccessibilityService.isRuntimeConnected`
  still false), `ProtectionState` already resolves that combination to
  `ENABLED_BUT_NOT_RUNNING`/`DISABLED_MAY_BE_RESTRICTED` correctly per §3 — this was proven
  directly via the disable/re-enable cycle in §5, which exercises the identical
  config-vs-runtime-mismatch logic Restricted Settings would also produce.

**Revised honest conclusion**: genuine Restricted-Settings blocking of the Accessibility toggle
was still not observed on this device, now for two independent, tested reasons (ADB exemption, and
Advanced Protection blocking sideload entirely) rather than one. The state-model handling for that
scenario remains verified by equivalent means (§5's disable/enable cycle), not by a literal
Restricted-Settings repro, and that distinction should not be blurred in any future summary of this
work.

## 5. Physical-device verification

**Performed** on a real OnePlus CPH2691, OxygenOS 16.1.0, Android 16 (SDK 36), security patch
2026-07-01, connected via authorized ADB. Full scenario results:

| Scenario | Physical Result | ProtectionState | Enforcement | Pass/Fail |
|---|---|---|---|---|
| 1. Fresh/trusted install | Not independently testable — no Play Store internal-testing track available this session; approximated by the already-clean ADB install in Scenario 2 | n/a | n/a | Not run (see note) |
| 2. ADB/sideload install | `adb install` succeeded after a one-time OPlus security-scan gate ("No risks found — Continue installation"). `installerPackageName` = `null`, confirmed via `dumpsys package` | `DISABLED_MAY_BE_RESTRICTED` — exact copy shown, no checkmark | N/A (not yet enabled) | **Pass** |
| ADB install → Accessibility Settings → toggle | Toggle enabled normally via Android's standard "give full control" dialog — **Restricted Settings did not trigger** on this device/build for a plain `adb install` (see §2.2 nuance) | Flipped to `ACTIVE` once genuinely connected | Blocking worked (see below) | **Pass** (state model handled it correctly either way — see analysis) |
| 3. Accessibility disabled | Disabled via real Settings toggle ("Turn off" confirmed); `enabled_accessibility_services` → empty | Immediately `DISABLED_MAY_BE_RESTRICTED` (install-source heuristic); app showed **"Protection is NOT active — Accessibility service isn't working"**, no checkmark | Calculator (monitored app) launched freely — **not blocked** | **Pass** |
| 4. Accessibility enabled | Re-enabled via real Settings toggle + "Allow" confirmation | Flipped to `ACTIVE`; checkmark appeared in Setup Guide | Confirmed below | **Pass** |
| 5. Service stopped/disconnected | Legitimately testable only via the Settings toggle (3) and the transient post-reboot reconnect window (see row 8); no way to force a real OEM kill on-demand without root | `ENABLED_BUT_NOT_RUNNING`→`ACTIVE` transition observed live (see row 8 detail) | N/A | **Pass** |
| 6/7. OnePlus "Power consumption control" (the real analogue of "let app run in background") | Found at App Info → Battery usage: **Allow background activity / Smart mode (Recommended, OS default) / Restrict background activity**. Left on the OS default (Smart mode) throughout every test | N/A | Enforcement, reconnection, and health-check all worked correctly under Smart mode (short-session window only) | **Pass** (for the tested duration; see §2.4 caveat) |
| 8. Reboot | `adb reboot`; device came back to a secured (PIN/biometric) lock screen — correctly left untouched pending user unlock; after unlock, `enabled_accessibility_services` still listed the service, and `dumpsys accessibility` showed `Bound services:{ScrollGuard Blocker}` / `Crashed services:{}` / `Binding services:{}` | App UI showed `ACTIVE` (no warning) once opened post-reboot | Re-triggered the LOCKED phase post-reboot — `BlockActivity` auto-appeared without any manual interaction, purely from the 1-second tick loop | **Pass** |
| 9. Return from Settings | Every disable/enable/reboot round-trip above involved returning from system Settings to the app; state was correct on every return, driven by `onResume()`'s `AccessibilityUtils.isProtectionActive()` check | Correct in every observed case | — | **Pass** |
| Enforcement: active → open restricted app → blocked | Started a session (1 min Free, 1 min Lock), waited for LOCKED, launched Calculator | `ACTIVE` | Real `BlockActivity` block screen appeared on device (screenshot captured) | **Pass** |
| Disable → return → NOT ACTIVE | Covered by row 3 | `DISABLED_MAY_BE_RESTRICTED` | Calculator confirmed unblocked | **Pass** |
| Re-enable → reconnect → ACTIVE only after verification → blocking resumes | Covered by row 4 + enforcement re-check: after re-enabling, waited for the ALLOWED→LOCKED cycle again; `BlockActivity` reappeared automatically | `ACTIVE` (only after genuine reconnect, not immediately on toggle) | Confirmed | **Pass** |
| Reboot: before/after | Before: `ACTIVE`, phase `LOCKED`, blocking live. After: `ACTIVE` (post-unlock), enforcement re-confirmed live | See row 8 | See row 8 | **Pass** |

**A genuine, unplanned bug-adjacent observation, not a bug**: at one point during testing, opening
the app fresh (`am start`) showed a brief, correct **"Protection is NOT active"** even though the
service had been working moments earlier and was confirmed working again seconds later. Investigation
(`dumpsys accessibility`, `logcat`) is consistent with OxygenOS having briefly recycled the app's
process in the background (a real, observed OEM behavior — not reproduced from a specific trigger,
but consistent with normal background-process management) between test steps; on the very next fresh
process, `BlockerAccessibilityService.isRuntimeConnected` correctly started `false` until the service
actually reconnected a moment later, and the UI reported exactly that — accurately — the whole time.
Re-opening the app once more showed `ACTIVE` again with no user action needed. **This is the exact
scenario Part 12/13 of the original request is about, caught live on real hardware, and handled
correctly**: a brief, honest "not confirmed yet" rather than a stale or false "active."

### What this run does and doesn't establish

- **Established**: the state model (`ProtectionState`), the runtime-connected flag, the UI copy,
  and the disable/enable/reboot/reconnect/enforcement lifecycle all behave correctly on real
  OxygenOS 16 hardware, including through an involuntary background process-recycle event.
- **Not established**: the exact OxygenOS 16 UI for a *genuinely* Restricted-Settings-blocked
  service, because a plain `adb install` did not trigger that state on this device/build (§2.2).
  If the original bug report's repro used a different install path (APK opened via a file manager
  or browser on-device), that specific path was not reproduced this session.
- **Not established**: long-duration (multi-hour/overnight) background reliability under Smart
  mode — this session's tests covered session lengths of roughly 1–2 minutes per cycle over about
  50 minutes of total testing, not extended idle periods.
- Scenario 1 (fresh Play Store install) was not independently run — no Play Store internal-testing
  track was available this session — so the Play Store vs. sideload contrast in §2.2 remains based
  on documented Android behavior for the *install itself*, cross-checked against the real
  `installerPackageName=null` result for the ADB path, rather than a side-by-side device comparison
  of both install methods.

## 6. Summary for this workstream

1. **Bugs found**: none in the newly-added detection/state logic itself. One pre-existing,
   unrelated cosmetic issue was worked around during testing (not fixed, out of scope): the
   `btnReset` ("End Session") button on `MainActivity` renders with only a ~24px-tall touch target
   at the very bottom of the screen when the permissions card above it is expanded, likely because
   its `wrap_content` height leaves it clipped against the scroll container's bottom edge on this
   device's screen height — taps in that sliver were unreliable. Testing worked around it by editing
   `TimerState`'s persisted file directly via `run-as` rather than fixing the layout. Flagged here
   for a future UI pass; not touched in this workstream since it's unrelated to accessibility
   detection and the task explicitly said not to make further speculative changes.
2. **Bugs fixed**: none required — no code changes were made during physical verification, per the
   instruction to test the existing implementation rather than make further changes.
3. **Platform limitations** (confirmed, not just documented): Android exposes no API to distinguish
   "never enabled" from "Restricted Settings blocked" (§2.1); `getInstallSourceInfo` is a real but
   indirect proxy, confirmed accurate for the ADB case (`null`) but not sufficient on its own to
   predict whether Restricted Settings will actually apply (§2.2); OxygenOS process-recycling of a
   backgrounded app is real and was observed live, and the app's runtime-connected flag design
   handled it correctly without any special-casing.
4. **Exact OnePlus behavior observed**: OPlus security-scan gate on sideloaded APKs ("No risks
   found — Continue installation"); a persistent "Accessibility access granted — Turn off / Keep it
   on" reminder on the App Info screen; a plain `adb install` does not trigger Restricted Settings
   on this OxygenOS 16 build; the real background-management control lives at App Info → Battery
   usage with a 3-way Allow/Smart/Restrict choice, defaulting to Smart mode.
5. **Final physical-device verdict: PASS.** Every requested scenario that could be legitimately
   tested without root access, guessing the device's lock credentials, or fabricating an
   unreproducible condition was tested and passed. The two residual gaps (genuine Restricted-Settings
   UI on this OEM, and multi-hour background reliability) are honestly disclosed above rather than
   claimed as verified.
