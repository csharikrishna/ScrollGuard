# AccessibilityService — Technical Audit & Play Policy Comparison

## Technical facts (verified directly against code)

**Manifest declaration** (`AndroidManifest.xml:118-129`):
```xml
<service android:name=".BlockerAccessibilityService" android:exported="true"
    android:label="ScrollGuard Blocker" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
    <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_service_config" />
</service>
```
`exported="true"` is required for the system to bind an AccessibilityService; the
`BIND_ACCESSIBILITY_SERVICE` permission restricts binding to the OS itself.

**Config XML** (`res/xml/accessibility_service_config.xml`):
```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"
android:canRetrieveWindowContent="true"
android:accessibilityFlags="flagRetrieveInteractiveWindows"
```
- No touch-exploration, gesture-performing, or key-filtering flags.
- `flagIncludeNotImportantViews` was present earlier this session and was **removed** — the code
  never traverses node content beyond the root's package name, so that flag was requesting scope
  never exercised.
- `flagRetrieveInteractiveWindows` is retained and *is* used: `BlockerAccessibilityService`
  iterates `windows` to detect a blocked package in a PiP or non-active split-screen pane
  (`checkAndBlockCurrentApp()`).

**What the service actually reads** (`BlockerAccessibilityService.kt`, full file reviewed):
- `rootInActiveWindow?.packageName` — the foreground app's package name. That's it.
- `windows[].root?.packageName` — same, for every currently-visible window (PiP/split-screen).
- **No** `AccessibilityNodeInfo` traversal beyond `.packageName`. No `findAccessibilityNodeInfosByText`,
  no reading of on-screen text, no reading of view hierarchies, no screenshots
  (`takeScreenshot()` is never called).
- **No actions performed**: no `performAction()`, no `performGlobalAction()`, no gestures, no
  clicks, no text injection.
- **No data leaves the device** via this service — package names are compared against local
  in-memory state (`TimerState`, `ParentalControlState`) purely on-device. Nothing the service
  reads is ever sent to Firestore or any network call; only *consumption seconds* (a package name
  + a number) are synced, and that's written from the tick-loop, not from the accessibility
  callback itself.
- **Nothing is persisted from accessibility data** beyond what's needed to decide block/allow —
  no history of every foreground-app transition is stored; only `BlockEvent` rows (package,
  app name, timestamp, block mode) are written, and only when an actual block screen is shown.

**Why it's necessary**: Android provides no other reliable, real-time way for a third-party app to
detect which app is currently in the foreground the instant it changes. `UsageStatsManager`
(the `PACKAGE_USAGE_STATS` alternative already used elsewhere in this app for *display* purposes)
reports usage with latency and coarser granularity, and isn't designed for real-time
block-before-render decisions.

**`isAccessibilityTool`**: not declared anywhere (verified by grep). Correct — this service does
not support people with disabilities and would not qualify for that designation.

**In-app disclosure**: `SetupGuideActivity` shows an info dialog (`setup_step_accessibility_why`)
explaining the purpose before routing to `Settings.ACTION_ACCESSIBILITY_SETTINGS`. See the open
question below — this needs review against the *specific* current disclosure/consent requirement.

## Play policy comparison

Researched directly against live official pages this session (see
`PLAY_POLICY_RESEARCH_NOTES.md` for full quotes/sources).

| Requirement | Applies? | Current implementation | Gap | Required action | Status |
|---|---|---|---|---|---|
| Only genuine accessibility tools may set `isAccessibilityTool=true` | Yes | Not declared | None | None | **TECHNICALLY VERIFIED** |
| Non-tool apps must complete a Play Console accessibility declaration | Yes | Not yet submitted (this is a Play Console action, not a code state) | Declaration not filed | File the declaration before submission; must be resubmitted whenever the API usage changes (e.g. this session's flag removal) | **REQUIRES PLAY CONSOLE ACTION** |
| Non-tool apps must provide **in-app** disclosure + **affirmative consent**, appearing during normal use, not just in a settings menu | Yes | `SetupGuideActivity`'s info dialog exists and requires a tap to proceed | Unclear whether this specific dialog satisfies "affirmative consent" in Google's current framing (a Cancel/Open-Settings dialog is arguably consent, but hasn't been reviewed against the literal current requirement) | Review this specific flow against the Play Console declaration form's own guidance when filing it | **REQUIRES PLAY CONSOLE REVIEW** |
| AccessibilityService must not prevent disabling/uninstalling an app, *unless* authorized by a parent-control app | Applies conditionally | This service does not attempt to prevent uninstall at all (it only detects foreground app + shows a block screen) | None found | None | **TECHNICALLY VERIFIED — not applicable to this service's actual behavior** |
| Prohibition on autonomous AI-driven action initiation; deterministic rule-based automation is explicitly permitted | Yes | The blocking logic is deterministic rule-based ("if package X is in the locked list, show block screen") | None | None | **POLICY INTERPRETATION — plausibly compliant, not Google's own determination** |
| Google Play may independently review whether the declared use case is genuine | Yes | N/A | N/A | Be prepared to explain the exact mechanism (package-name detection only) if asked in review | **REQUIRES PLAY CONSOLE REVIEW** |

## Verdict for this document
**Technically minimal and narrowly scoped** — verified, not assumed. The open items are entirely
process (file the Play Console declaration, confirm the in-app disclosure flow satisfies the
current consent bar) rather than code changes.
