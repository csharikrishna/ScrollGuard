# ScrollGuard — UI/UX Polish Audit + Multi-Window/PiP Enforcement-Bypass Investigation

Final report. Full working log with exact device commands and intermediate evidence lives in
`UI_AUDIT_PROGRESS.md`; this document is the required 9-section summary.

## 1. Executive Summary

The headline concern was real: a restricted app could dodge ScrollGuard's block screen by
entering Picture-in-Picture (PiP) right as its time limit expired. This was verified on a real
emulator with a purpose-built test app, root-caused to an Android platform behavior (not a gap in
ScrollGuard's own detection logic), fixed with an accessibility-overlay backstop, and re-verified
against the identical reproduction sequence — confirmed closed for the PiP case.

Beyond that, 6 additional state-transition/timing/platform findings were investigated and
documented (2 real bugs fixed this session prior to this audit are not recounted here; this
audit found and reasoned through platform-adjacent gaps, one still-open cosmetic bug, and one
confirmed non-issue). A full UI/UX polish pass found and fixed 5 high-severity and 5
medium-severity issues (unreadable text, off-palette colors, missing scroll-safety in small
multi-window panes, a missing destructive-action confirmation, silent stepper clamps, and dead
UI). Three low-value items and one legitimate scope-boundary (no dark theme) were documented but
intentionally not changed.

Full regression (`./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`) passes
after all fixes: BUILD SUCCESSFUL, 29/29 tests, 0 lint errors.

**Production verdict: READY AFTER FIXES** (fixes already applied and verified this pass — see
§9 for the residual gaps that keep this from being an unqualified READY TO SHIP).

## 0. Terminology mapping

The original prompt used browser/web terms. ScrollGuard has no web/browser surface — mapped as:

| Prompt term | Real Android equivalent used in this audit |
|---|---|
| "PiP" | Android's actual Picture-in-Picture API (`enterPictureInPictureMode`, `PictureInPictureParams`) |
| "split-screen" | Android multi-window mode (two Activities sharing the screen) |
| "viewport resize" | Activity window resize under multi-window/freeform, and `Configuration` changes |
| "browser tab switch" | Activity/task switch, or a monitored app losing foreground focus |
| "responsive breakpoints" | Layout behavior under a small multi-window pane vs. full-screen |

## 2. Multi-Window/PiP Bypass Findings

**Root cause:** `BlockerAccessibilityService`'s all-windows fallback scan already correctly
detects a PiP window (confirmed via instrumented logging: `AccessibilityWindowInfo.type` returns
`1`/`TYPE_APPLICATION` on this Android 14 build, with a valid `root.packageName`), and
`BlockActivity` genuinely launches and gains focus (confirmed via `dumpsys window`). The bypass
is **platform-level, not a detection gap**: Android renders a PiP window in a pinned stack that
stays visually on top of ordinary windows regardless of focus, so the block screen exists and is
focused underneath, but the restricted app's PiP surface keeps rendering over it.

**Verified reproduction (exact steps, `scrollguard_test` emulator, Android 14/API 34):**
1. Built and installed a minimal throwaway PiP-capable test app (`com.piptest`) since no
   PiP-capable app was preinstalled.
2. Confirmed `com.piptest` was genuinely in `TimerState.monitoredApps` by reading the persisted
   `sg_state.xml` directly via `adb shell run-as com.scrollguard cat ...` — not by trusting the
   Settings-screen checkbox (which has its own separate rendering bug, see finding #2 below).
3. Started a focus session (1m/1m/1m, Nuclear mode) with `com.piptest` monitored.
4. Opened `com.piptest`, entered PiP during the FREE window.
5. Waited for FREE→LOCKED while the PiP window stayed on screen.
6. **Before the fix:** `mCurrentFocus` correctly showed `BlockActivity`, but the screenshot
   showed the PiP window's content still fully visible on top of it — a genuine, confirmed
   bypass of the effective block.

**Fix** (`BlockerAccessibilityService.kt`): when a blocked package is found only via the
all-windows fallback scan (the PiP/other-pane case, as opposed to being the true foreground
activity), the service now also raises a full-screen, opaque `TYPE_ACCESSIBILITY_OVERLAY` window
— a window type available to a bound `AccessibilityService` without any extra permission. It's
focusable and touch-consuming, so PiP's own controls can't be reached underneath either. It's
added/removed every check based on whether the condition still holds, and torn down in
`onDestroy()`.

**Re-verified against the identical reproduction sequence:** screen went fully solid black the
moment the same condition recurred. Additionally confirmed: the overlay does not appear for a
normal, non-PiP block (tested with Android's Clock app blocked while genuinely foregrounded —
no regression), and it correctly disappears once the PiP app is force-stopped (no stuck overlay).

**Not independently device-tested, reported honestly rather than assumed:**
- Split-screen (as distinct from PiP) — the fix is written generally (any window found only via
  the fallback scan, not PiP-specifically), which should cover it, but this is inference.
- Freeform/desktop windowing (DeX, ChromeOS) — unavailable on this emulator image; same
  inference-not-evidence caveat applies.

## 3. Additional Glitches Found

1. **App update/reinstall silently kills `TimerService` with no auto-restart**, freezing phase
   advancement until a fresh accessibility event or app reopen. `dumpsys activity services`
   confirmed the service absent after `adb install -r`. Classification: functional gap, already
   mitigated (not fully closed) by this session's earlier self-healing-tick fix. Not
   additionally fixed (scope creep for this audit); documented as a known residual gap.

2. **`AppPickerActivity`'s monitored-app checkbox can visually show the wrong state** after the
   search filter rebinds a row, even though persisted data (`sg_state.xml`, verified via
   `run-as`) is always correct. Root cause: a `SwitchMaterial` stale-drawable-state issue
   surviving `notifyDataSetChanged()`. Classification: UI/settings-verification bug — cosmetic
   but trust-damaging for a parental-control feature. Two mitigations attempted
   (`jumpDrawablesToCurrentState()`, disabling the RecyclerView's `itemAnimator`); both kept
   (harmless) but **neither fully closed the bug on re-test** — reported honestly as unresolved
   rather than claimed-fixed.

3. **App update/reinstall also disables the Accessibility Service** (standard Android behavior).
   `TimerService.checkAccessibilityHealth()` already detects and surfaces this — but only once
   `TimerService` itself is running, which per #1 isn't automatic after this specific trigger.
   Classification: platform limitation compounding with #1. Not fixed (narrow, infrequent
   window; existing health check already covers the common case).

4. **Freeform/desktop windowing not independently verified** — see §2.

5. **Rapid alternation between two different blocked packages — investigated, confirmed NOT
   exploitable.** The 500ms same-package debounce in `triggerBlock()` never suppresses a
   cross-package re-trigger; `BlockActivity` is `singleTask` and updates via `onNewIntent`.

6. **Parental quota engine's time-counting is 1-second-poll-based**, not event-exact — a
   sub-second amount of time could theoretically go uncounted per rapid app-switch.
   Classification: architecture-inherent limitation of any polling scheme, not a practically
   exploitable bypass. Not fixed — the cost of making this event-exact isn't justified by the
   near-zero benefit to someone trying to exploit it.

Screen-off/on-at-expiry and lock-screen interactions were reasoned through (both rely on the same
self-healing tick catch-up) rather than separately device-tested, given time spent on the
higher-priority findings above.

## 4. UI/UX Audit Findings

Design-language baseline established first (see §5) so findings are judged against the app's
own existing conventions, not external taste.

**High severity — fixed and device-verified this pass:**
- `UsageStatsActivity`'s chart axis/no-data/top-blocked-apps text was white-on-white (leftover
  dark-theme styling never updated when the card became a white surface) — recolored to
  `text_primary`/`text_secondary`.
- Off-palette pink/magenta (`#E1306C`/`#FF4081`, absent from `colors.xml`) in the block screen's
  progress ring and the usage bar chart — replaced with `@color/primary`.
- BlockActivity's ring conveyed no intentional urgency signal — resolved as a side effect of the
  color fix (now one deliberate brand color, not an arbitrary gradient).
- BlockActivity and PinActivity had no scroll container — every other screen in the app already
  does — at real risk of clipped content in a small multi-window pane. Wrapped both in
  `NestedScrollView`/`fillViewport="true"`.
- Deleting a parental app restriction had zero confirmation, unlike the equivalent group-delete
  flow elsewhere. Added a confirm dialog and retinted the delete icon to match.

**Medium severity — fixed:**
- Emoji rank medals in Top Blocked Apps replaced with plain "#1/#2/#3" text.
- Non-functional group color badge (never actually configurable) removed rather than left as
  dead UI.
- A hardcoded color literal was removed along with that badge.
- Hardcoded strings in the App Groups screen moved to `strings.xml`.
- Silent no-op at stepper min/max clamps (home screen and App Groups dialog) now gives a
  distinct "denied" haptic (API 30+, falls back to the normal per-tap haptic on older devices).

**Documented, not changed this pass:**
- No dark theme (`values-night`) exists anywhere in the module — a real gap, but a design
  decision + full re-audit, not a polish-pass fix. Flagged for the user's own prioritization.
- Redundant explicit `cardCornerRadius` on a few item layouts that already inherit it from
  their style — harmless, left as low-value cleanup.
- Home screen's three quick-action tiles use generic system icons — a suggestion, not a defect;
  left alone as a judgment call closer to redesign than polish.

**Confirmed already good, left untouched:** ParentalControlActivity's offline/sync indicator +
optimistic-UI-with-rollback + error taxonomy; SetupGuideActivity's status glyphs;
`TransitionUtil`'s API-34 branching and lifecycle-sensitive callbacks (no race found); the
alpha-tinted accent-card pattern for attention banners; the device-admin switch reconciliation;
`AppPickerActivity`'s `itemAnimator = null` (recognized as this session's own intentional fix).

## 5. Design System Notes

Baseline read before judging anything: a single light theme (`colors.xml` defines `primary`,
`text_primary`/`text_secondary`/`text_muted`, `bg_surface`/`bg_surface_intense`, `error`, no dark
variants); `ScrollGuard.Card`/`ScrollGuard.Button` styles used consistently for surfaces and CTAs;
Lottie animations reserved for a small set of meaningful states (lock, warning), not decorative
motion; icons are a consistent single-style set except the three generic home-tile icons noted
above; spacing follows a small set of repeated dp values rather than arbitrary numbers. This is a
disciplined, intentional design language — the audit's fixes bring outliers back into it rather
than introducing anything new (no gradients, glassmorphism, or added motion were introduced by
this pass).

## 6. Accessibility Findings

- All interactive icons audited this pass already carry `contentDescription` (PIN cancel/delete
  buttons, etc.) — no gaps found in the screens touched.
- The stepper haptic-feedback fix improves accessibility for clamp boundaries (a non-visual
  signal that a tap had no effect), on top of its polish value.
- The white-on-white text fixed in §4 was also an accessibility defect (a 1:1 contrast ratio,
  not merely a stylistic mismatch) — now meets normal contrast against `bg_surface`.
- Not audited this pass: full TalkBack traversal order and screen-reader-specific labels across
  every screen — out of scope for the time available; a dedicated accessibility pass is
  recommended separately if TalkBack support is a product requirement.

## 7. Regression/Verification Results

Actual command and output, re-run after all Part 3 fixes:

```
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
BUILD SUCCESSFUL
29/29 unit tests passing
0 lint errors
```

Manual on-device verification (screenshots taken on `scrollguard_test`, Android 14/API 34):
- `UsageStatsActivity`: bar chart renders with a readable blue bar, dark-gray axis/date/value
  labels; "Most Blocked Apps" list shows clean "#1 PipTest — 5 blocks" / "#2 Clock — N blocks"
  text with no emoji.
- `BlockActivity`: solid brand-blue progress ring (no more pink/magenta gradient); layout still
  centers identically to before the `NestedScrollView` wrap (no regression at full screen size);
  all text/buttons intact.
- `PinActivity`: layout XML confirmed to use the identical `NestedScrollView`/`fillViewport`
  pattern verified live on the harder (ConstraintLayout) BlockActivity case; not independently
  re-screenshotted given time spent, so this is a read-verification, not a live-render
  confirmation, and is reported as such.
- Part 1's PiP-bypass fix: re-verified against the exact original reproduction sequence (§2) —
  confirmed closed.

## 8. Remaining Limitations

- Split-screen and freeform/desktop windowing were not independently device-tested for the Part 1
  fix (unavailable test environment / time-boxed); the fix's generality argues it should cover
  them, but this is inference, not verified evidence.
- `AppPickerActivity`'s checkbox visual-state bug (finding #2, §3) remains open — two mitigations
  applied, neither fully closed it on re-test.
- No dark theme exists; out of scope for this pass by design.
- `PinActivity`'s scroll-safety fix was read-verified but not live-screenshotted separately from
  BlockActivity's confirmation of the same pattern.
- `AppGroupAdapter.kt`/`AppGroupsActivity.kt` fixes (dead color-badge removal, string extraction,
  stepper haptics) are applied on disk but were **not committed** — both files are part of a
  larger pre-existing uncommitted feature with no prior commit history in this repo; committing
  them now would bundle an entire unrelated feature into a UI-polish commit. Recommend the user
  review and commit that feature separately, on their own terms.
- TalkBack/screen-reader traversal was not audited beyond the spot-checks in §6.

## 9. Production Verdict

**READY AFTER FIXES.**

The Part 1 PiP bypass — the one condition that would make an unqualified READY TO SHIP verdict
wrong — has been verified, root-caused, fixed, and re-verified against its exact original
reproduction sequence; it is resolved for the PiP case. It is not marked fully closed for every
multi-window variant (split-screen, freeform) since those weren't independently device-tested, so
this stops short of an unqualified clean bill of health. Combined with one still-open cosmetic
settings-display bug (#2, §3) and the uncommitted App Groups feature noted in §8, the honest
verdict is "ready after fixes" rather than an unconditional "ready to ship" — the fixes in
question are already applied and verified in this repo; what remains is user review of the
uncommitted App Groups file and, if the user considers it worth the effort, closing out the
checkbox bug and independently confirming split-screen/freeform coverage before treating every
multi-window mode as verified rather than inferred.
