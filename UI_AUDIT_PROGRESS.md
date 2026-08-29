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

## Part 2: Additional glitches (5-10, same class)

- [ ] Rapid app-switching vs tick loop / batch persistence
- [ ] Freeform/desktop windowing mode
- [ ] Screen-off/screen-on at expiry moment
- [ ] Force-stop of accessibility service host process
- [ ] Notification shade/quick settings/assistant overlays
- [ ] Two rapid triggerBlock() calls for different packages
- [ ] Lock-screen/keyguard interactions
- [ ] Split-screen with ScrollGuard's own UI in the other pane

## Part 3: UI/UX polish audit (per screen)

- [ ] Design language baseline read (themes.xml, colors.xml, styles, drawables, TransitionUtil)
- [ ] MainActivity / activity_main.xml
- [ ] BlockActivity / activity_block.xml
- [ ] PinActivity / activity_pin.xml
- [ ] AppPickerActivity + item layout
- [ ] UsageStatsActivity
- [ ] AppGroupsActivity + item_app_group.xml + dialog_edit_group.xml
- [ ] SetupGuideActivity / activity_setup_guide.xml (new this session)
- [ ] ParentalControlActivity / activity_parental_control.xml
- [ ] ParentalAppPickerActivity / activity_parental_app_picker.xml + item_parental_picker.xml
- [ ] item_parental_app.xml (ParentalAppAdapter)
- [ ] Dialogs/Toasts/Snackbars sweep

## Part 4/5: Final regression + report

- [ ] ./gradlew testDebugUnitTest lintDebug assembleDebug
- [ ] Manual pass over every screen touched
- [ ] Final report written
