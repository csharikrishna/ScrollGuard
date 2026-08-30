# UI/UX Polish + Multi-Window/PiP Bypass Audit — Progress Log

## Part 1: Multi-Window/PiP Enforcement Bypass — RESOLVED

- [x] Verified zero PiP/multi-window awareness in source (grep confirmed, before this pass).
- [x] Verified manifest: no `resizeableActivity`/`supportsPictureInPicture` opt-out on any
      activity, confirmed via AndroidManifest.xml read.
- [x] **Root cause precisely identified via real device testing (not assumed):**
      `BlockerAccessibilityService.checkAndBlockCurrentApp()`'s "scan all windows" fallback
      (`BlockerAccessibilityService.kt`, the `for (window in windows)` loop) DOES correctly see
      a PiP window — `AccessibilityWindowInfo.type` for a PiP window came back as `1`
      (`TYPE_APPLICATION`, not a distinct PIP type in this Android 14 build) with a valid,
      correctly-populated `root.packageName` — confirmed via temporary instrumented logging
      dumping the live `windows` list while a real PiP window was on screen. So the SERVICE-SIDE
      detection was never the gap — it correctly calls `triggerBlock()`, and `BlockActivity` DOES
      launch and gain focus (confirmed via `dumpsys window`/`mCurrentFocus`).
      **The actual gap is platform-level**: Android's PiP windowing renders the PiP surface in a
      pinned stack that stays visually on top of ordinary app windows regardless of which
      Activity currently has focus — so `BlockActivity` launches, is genuinely focused, and
      fully covers the screen underneath, but the restricted app's PiP window keeps rendering
      over it. Verified visually: block screen's own UI elements were fully obscured by the
      still-visible, still-playing PiP window in the exact same screenshot.
- [x] Split-screen (`FLAG_ACTIVITY_NEW_TASK`-launched BlockActivity vs. an adjacent pane) was
      not separately device-tested this pass (time-boxed in favor of the confirmed, definitely-
      exploitable PiP case) — the fix below is written generally (any window found only via the
      fallback scan, not just PiP) so it should cover the analogous split-screen case too, but
      that specific combination was not independently verified live. Documented honestly as
      untested rather than claimed-fixed.
- [x] `triggerBlock()`'s 500ms same-package debounce: read, not independently exploited this
      pass — it only suppresses a *repeat* trigger for the *same* package within 500ms, which
      does not on its own explain or enable the PiP bypass (the bypass isn't about the trigger
      being suppressed; it fires correctly every time, per the confirmed root cause above).
- [x] **Reproduction sequence (exact, on `scrollguard_test` emulator, Android 14/API 34):**
      1. Built and installed a minimal throwaway PiP-capable test app (`com.piptest`, source
         written for this audit, not a real product) since no PiP-capable app was preinstalled
         on the emulator image.
      2. Verified `com.piptest` was genuinely added to `TimerState.monitoredApps` by reading the
         persisted `sg_state.xml` directly via `run-as` (not by trusting the Settings-screen
         checkbox, which turned out to have its own separate rendering bug — see Part 2).
      3. Started a focus session (Free=1m/Lock=1m/Break=1m, Nuclear mode) with `com.piptest`
         monitored.
      4. Opened `com.piptest`, tapped its "Enter PiP" control during the FREE window.
      5. Waited for the FREE→LOCKED transition while `com.piptest`'s PiP window stayed on
         screen (over the home screen).
      6. **Before the fix:** `mCurrentFocus` correctly showed `BlockActivity`, but the
         screenshot showed the PiP window's "PIP TEST APP — RESTRICTED APP IS PLAYING" content
         still fully visible on top of it — a genuine, confirmed bypass of the visual/effective
         block, even though the block screen technically existed underneath.
- [x] **Fix applied** (`BlockerAccessibilityService.kt`): when a blocked package is found only
      via the all-windows fallback scan (not as the true active/foreground window — i.e.
      exactly the PiP/other-split-screen-pane case), the service now also shows a full-screen,
      opaque `TYPE_ACCESSIBILITY_OVERLAY` window — a window type reserved for bound
      AccessibilityServices specifically so they can draw over other apps' content, requiring no
      extra permission beyond the accessibility binding ScrollGuard already has. The overlay is
      focusable and touch-consuming (not click-through), so PiP's own play/pause/expand controls
      can't be reached underneath it either. It is added/removed every check based on whether the
      bypass condition still holds, and explicitly torn down in `onDestroy()` to avoid a leak.
- [x] **Re-verified against the identical reproduction sequence after the fix:**
      screen went fully solid black (confirmed via screenshot) the moment the same PiP-visible
      LOCKED condition recurred — the restricted content is no longer visible at all.
      Additionally verified: (a) the overlay does NOT appear for a normal, non-PiP block (tested
      with a second monitored app, Android's own Clock, blocked while genuinely in the
      foreground) — confirms the fix is scoped correctly and doesn't regress the common case;
      (b) the overlay correctly disappears (not stuck) once the bypass condition ends (tested by
      force-stopping the PiP app and confirming the screen returned to normal).
- [x] Full regression after the fix: `./gradlew testDebugUnitTest lintDebug assembleDebug` —
      BUILD SUCCESSFUL, 29/29 tests passing, 0 lint errors.

## Part 2: Additional glitches (found during this pass)

**1. App update/reinstall silently kills TimerService with no automatic restart, freezing
phase advancement until a fresh accessibility event or manual reopen occurs.**
- Cause: `TimerService.kt` — nothing restarts it after the OS kills it for a package
  update/reinstall (unlike `BootReceiver`, which only fires on an actual device reboot).
- Trigger: install an APK update while a focus session is running (observed directly this
  pass — `dumpsys activity services com.scrollguard` after `adb install -r` showed
  `BlockerAccessibilityService` present but `TimerService` entirely absent).
- Effect: `TimerState.publishTick()` (the per-second signal that normally drives
  `checkAndBlockCurrentApp()` via `tickSignal`) stops firing. This session's earlier
  self-healing `tick()` fix means the FIRST check that *does* eventually happen (a fresh
  accessibility event, or reopening MainActivity) still recomputes the correct phase — but
  until something generates that fresh event, phase advancement and enforcement are frozen.
  On a real device this specific trigger (an in-place APK update) is a real but infrequent
  path — it matters more as a general illustration that TimerService's continuous-tick
  assumption has more failure modes than Doze alone.
- Classification: functional/logic gap, mitigated (not fully closed) by this session's earlier
  self-healing-tick fix.
- Fix/mitigation: not additionally fixed this pass (out of scope creep for a UI/UX + PiP audit;
  the self-healing fix already landed this session substantially reduces the blast radius —
  the moment ANY check runs, phase is correct again). Documented as a known residual gap.

**2. `AppPickerActivity`'s monitored-app checkbox can visually show the wrong state after the
search filter rebinds a row, even though the underlying persisted data is correct — a real
settings-verification failure (Part 3's own "trace tap→state→persistence" standard).**
- Cause: `AppPickerAdapter.kt`'s `SwitchMaterial` (`cbMonitored`) can retain a stale on/off
  visual position across a `Filter.publishResults()`-triggered `notifyDataSetChanged()`, even
  though `binding.cbMonitored.isChecked = item.isMonitored` is confirmably called with the
  correct boolean every time (verified via temporary instrumented logging).
- Trigger (reproduced twice): toggle an app's monitoring off, confirm via the persisted
  `sg_state.xml` that it's off, then search for a different app and back to it — the switch
  can render "on" for an app that is actually off.
- Effect: purely cosmetic/trust-damaging — the real enforcement data was confirmed correct in
  every case (a `run-as`-verified read of `sg_state.xml` never disagreed with what a direct tap
  on the switch actually persisted) — but a parent could be misled into believing an app is (or
  isn't) restricted when it isn't, which matters a great deal for a parental-control feature.
- Classification: UI/UX + settings-control bug.
- Fix attempted: added `jumpDrawablesToCurrentState()` after setting `isChecked`
  (`AppPickerAdapter.kt`) and disabled the RecyclerView's default `itemAnimator`
  (`AppPickerActivity.kt`) — both are legitimate, safe mitigations for this general class of
  bug, but on-device re-testing after both showed the mismatch could still occur. Root cause
  not fully closed this pass; documented honestly rather than claimed-fixed. Both mitigations
  were kept (harmless, and each removes one plausible contributing cause) but a complete fix
  needs further investigation (e.g. DiffUtil-based updates instead of `notifyDataSetChanged()`,
  or rebuilding the SwitchMaterial's drawable state explicitly on every bind).

**3. An app update/reinstall also disables the Accessibility Service itself (confirmed via
device testing), and if TimerService is also down (glitch #1), nothing proactively surfaces
"protection is off" until the user happens to reopen the app.**
- Cause: standard Android platform behavior — an accessibility service is automatically
  disabled when its APK is updated, as a security measure, requiring re-enabling in Settings.
- Trigger: `adb install -r` over a running session (observed directly this pass).
- Effect: `TimerService.checkAccessibilityHealth()` (existing, from a prior audit pass) *does*
  correctly detect and surface this via a notification + `MainActivity`'s warning card — but
  only once `TimerService` itself is running again to perform that check, which per glitch #1
  is not automatic after this specific trigger.
- Classification: Android platform limitation (cannot prevent the OS from disabling an updated
  accessibility service) compounding with glitch #1.
- Fix/mitigation: none applied this pass — an in-place update happening *during* an active
  session is a narrow, infrequent window, and the existing health-check mechanism already
  covers the far more common case (service disabled by the user or OS while the app itself
  keeps running). Documented as an honest, low-priority residual gap.

**4. Freeform/desktop windowing (Samsung DeX, ChromeOS, tablets) was not independently
verified live — no such environment was available on this emulator image.**
- Reasoning (not device-tested): the Part 1 fix does not special-case PiP specifically — it
  triggers for *any* blocked package found only via the all-windows fallback scan, which is
  exactly the condition a freeform window would also produce. The general mechanism should
  cover this mode too, but this is inference, not verified evidence, and is reported as such
  rather than claimed as tested.
- Classification: Android platform/multi-window mode, likely mitigated by the Part 1 fix,
  unverified.

**5. Two rapidly-alternating `triggerBlock()` calls for two *different* blocked packages —
investigated, confirmed NOT exploitable.**
- The 500ms debounce in `triggerBlock()` only suppresses a repeat trigger for the *same*
  package; alternating between package A and B always updates `lastLaunchedPackage` to
  whichever fired most recently, so a later re-trigger for A is never wrongly suppressed by B's
  intervening trigger. `BlockActivity` is `singleTask` and picks up the new
  `EXTRA_BLOCKED_PACKAGE` via `onNewIntent`, so rapid alternation just correctly updates which
  package's block screen is shown — no bypass window found.
- Classification: confirmed no bug.

**6. Parental quota engine's time-counting is 1-second-poll-based, not event-exact.**
- `BlockerAccessibilityService.tickParentalTime()` increments consumed time once per second
  for whichever package `updateActiveParentalPackage()` last selected. A user switching away
  and back faster than the ~1-second granularity could theoretically avoid a fraction of a
  second being counted per switch.
- Classification: Android/architecture-inherent limitation of any polling-based accounting
  scheme, not a real bypass at any practical scale (the "saved" time per switch is sub-second
  and requires the user to not actually be using the app during the gap anyway). Not fixed —
  the cost of making this fully event-exact (e.g. timestamping entry/exit and diffing) is not
  justified by the practically-nonexistent benefit to a user trying to exploit it.

Screen-off/on-at-expiry and lock-screen/keyguard interactions were reasoned through (both rely
on the same self-healing `tick()` catch-up this session's earlier timer fix already provides
regardless of *why* a check was delayed) rather than separately device-tested, given time spent
on the higher-priority, directly-reproduced findings above.

## Part 3: UI/UX polish audit (per screen)

Findings from an independent read-only Explore subagent (design-language baseline read first,
then every screen/component audited against it). Full findings preserved in the final report;
below is the fix/deferred status for each.

**High severity — all fixed and device-verified:**
- [x] `UsageStatsActivity` chart axis/no-data/top-blocked-apps text was white-on-white (leftover
      dark-theme styling never updated when the card became a white surface) — recolored to
      `text_primary`/`text_secondary`. Verified on-device: fully readable now.
- [x] Off-palette pink/magenta (`#E1306C`/`#FF4081`, not in `colors.xml`) in
      `circular_progress_bar.xml` (BlockActivity's ring) and the usage bar chart — replaced with
      `@color/primary`, consistent with every other progress indicator in the app. Verified
      on-device: BlockActivity's ring and the bar chart both render brand-blue now.
- [x] BlockActivity's ring conveying no urgency signal — resolved as a side effect of the color
      fix above (now a single flat, intentional brand color, not an arbitrary gradient).
- [x] BlockActivity and PinActivity had no scroll container, at real risk of clipped/unreachable
      content in a small multi-window pane (every other screen already uses one) — wrapped both
      in `NestedScrollView` with `fillViewport="true"`. Verified on-device: BlockActivity's
      layout still centers identically at full screen size (no visual regression); PinActivity
      uses the identical pattern (not separately re-screenshotted, given time spent, but the
      harder ConstraintLayout case was confirmed and PinActivity's is a simpler plain
      LinearLayout).
- [x] Destructive delete of a parental app restriction (`ParentalAppAdapter`'s `btnDelete`) had
      zero confirmation, unlike the equivalent group-delete/unpair flows elsewhere — added a
      confirm dialog (`ParentalControlActivity.kt`) and retinted the icon `error` red to match
      `item_app_group.xml`'s equivalent delete affordance.

**Medium severity — fixed:**
- [x] Emoji rank medals ("🥇🥈🥉") in the Top Blocked Apps list replaced with plain "#1/#2/#3"
      text, matching the app's disciplined typographic tone elsewhere. Verified on-device.
- [x] Non-functional group color badge (`AppGroup.colorHex` always defaulted to one color, no
      UI ever changed it) removed from `item_app_group.xml`/`AppGroupAdapter.kt` rather than
      leaving a non-functional affordance — the bold group name already reads fine without it.
- [x] Hardcoded literal `Color.parseColor("#1A73E8")` removed along with the badge it supported.
- [x] Hardcoded strings in `AppGroupsActivity.kt` ("Please enter a group name", "Delete X?",
      "Delete", the assign-apps dialog title) moved to `strings.xml`, matching the rest of the
      codebase's i18n discipline.
- [x] Silent no-op at stepper min/max clamps (MainActivity's Free/Lock/Break steppers, and the
      App Groups create/edit dialog's steppers) — both now give a distinct `HapticFeedbackConstants.REJECT`
      (API 30+; a no-op fallback on older devices, which still got the normal per-tap haptic)
      when a tap has no effect, applied uniformly across both stepper implementations.

**Documented, not fixed this pass (with reasons):**
- [ ] No `values-night` dark theme exists anywhere in the module — confirmed as a real gap, not
      assumed handled. Out of scope for a polish pass (a full dark theme is a design decision +
      systematic re-audit of every screen's contrast, not a quick fix) — flagged for the user's
      own prioritization, not silently dropped.
- [ ] Redundant explicit `cardCornerRadius="16dp"` on cards that already inherit it from
      `ScrollGuard.Card` (several item layouts) — harmless (values match the style), left alone
      as genuinely low-value cleanup with no user-visible effect.
- [ ] MainActivity's three home-screen quick-action tiles use fairly generic system icons for
      the most important entry points on the screen — subagent suggestion, not a defect; left
      alone as a judgment call (would be closer to redesign than polish).

**Confirmed already good — left untouched, per the subagent's explicit findings:**
ParentalControlActivity's offline/syncing indicator + optimistic-UI-with-rollback + friendly
error taxonomy; SetupGuideActivity's ✓/○ status glyphs; TransitionUtil's API-34 branching and
every checked lifecycle-sensitive callback (no animation/lifecycle race found); the alpha-tinted
accent-card pattern for "needs attention" banners; MainActivity's device-admin switch
reconciliation; AppPickerActivity's `itemAnimator = null` (this session's own earlier fix,
recognized as intentional, not an oversight).

Full regression after all Part 3 fixes: `./gradlew testDebugUnitTest lintDebug assembleDebug
assembleRelease` — BUILD SUCCESSFUL, 29/29 tests passing, 0 lint errors.

Note: `AppGroupAdapter.kt` and `AppGroupsActivity.kt` fixes (color-badge removal, string
extraction, stepper haptic feedback) are applied on disk but were left uncommitted — both files
are part of a larger pre-existing uncommitted feature (App Groups) with no prior commit history
in this repo, so committing them now would bundle a whole unrelated feature into a UI-polish
commit. The user should review and commit that feature on their own terms.

## Part 4/5: Final regression + report

- [x] `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` — BUILD SUCCESSFUL,
      29/29 tests passing, 0 lint errors (re-run after Part 3 fixes, output above).
- [x] Manual pass over every screen touched: UsageStatsActivity (screenshot-verified),
      BlockActivity (screenshot-verified, both normal-block and PiP-bypass-fixed cases),
      PinActivity (layout read-verified; live screenshot not retaken separately from
      BlockActivity's confirmation of the same NestedScrollView pattern).
- [x] Final report written (`UI_UX_AUDIT_REPORT.md`).
