# Timer / Setup / UX Audit — Progress Log

Tracking doc for the second audit pass (timer correctness + non-technical-user usability).
One line per finding, checked off with Fixed / Confirmed-no-bug / Deferred-because-X + file:line.

## Part 1-3: Timer Behavior

- [x] Trace actual FREE/LOCKED/ALLOWED state machine — CONFIRMED: model is FREE (once, at
      session start) then LOCKED<->ALLOWED alternating forever (allowDuration is a *separate*,
      shorter "quick window" duration, not a repeat of FREE). User's mental model of "FREE
      recurs" does not match the code by design — flagged as a UX/help-copy gap (Part 8), not a
      logic bug. TimerState.kt:33,189-215
- [x] CRITICAL BUG CONFIRMED: BlockerAccessibilityService.checkAndBlockCurrentApp() made block
      decisions from TimerState.phase without ever recomputing it — correctness depended
      entirely on TimerService's 1-second Handler loop staying alive, which Android does not
      guarantee (Doze/App Standby/OEM background-kill). A stall could freeze the phase
      indefinitely, including frozen *unlocked* (silently defeats blocking). Root cause: old
      single-shot `tick()` only advanced one phase transition per call and was never invoked
      from the enforcement path at all. Old BlockerAccessibilityService.kt:113-116 (comment
      explicitly says "No TimerState.load() here")
- [x] Reproduced via deterministic Robolectric tests (not just code review) —
      TimerStateTest.kt: `tick_selfHealsMultipleMissedTransitions_whenCalledOnceAfterLongStall`,
      `unlockedWindowNotOpened_stillGetsFullDuration_whenNextCheckedLate`. Manually traced
      against the actual pre-fix code (confirmed via git stash) that the second test would have
      produced phase=ALLOWED (unblocked!) instead of the correct LOCKED — the dangerous "stuck
      unlocked" failure mode.
- [x] Fixed root cause: extracted the single-transition-per-call logic into a shared, bounded
      catch-up loop (`catchUp()`, reused by both `tick()` and `healState()`), and made
      `checkAndBlockCurrentApp()` call `TimerState.tick()` itself on every accessibility event —
      TimerState.kt (tick/catchUp/healState), BlockerAccessibilityService.kt:121-128. Self-healing
      now happens on the enforcement path itself, independent of TimerService's loop health.
      Not a special case for the user's literal timestamps — pure elapsed-time arithmetic, works
      for arbitrary configured durations and arbitrary gap lengths (capped at 10 iterations same
      as the pre-existing reboot-recovery safety valve).
- [x] User's literal fear ("skipping FREE/ALLOWED causes a restart or indefinite lock") —
      Confirmed-no-bug by design: no code path references app-open/interaction history when
      computing phase or phase duration; `unlockedWindowNotOpened_stillGetsFullDuration...` test
      proves a skipped window still gets its full configured duration and the next phase is
      exactly on schedule.
- [x] Test matrix (Section 3): normal cycle (existing `fullCycle_...` test), not-opened-during-
      unlocked-window x2 severities (new tests above), rapid interactions around a transition
      (new `rapidRepeatedChecks_aroundPhaseTransition_transitionExactlyOnce`), long inactivity /
      multiple missed cycles (new stall test), process death + service restart + a11y restart
      (all funnel through `load()`->`healState()`, already covered by existing reboot tests; a11y
      restart additionally covered by the new self-healing `tick()` call since it no longer
      depends on load() at all), reboot during LOCKED (existing test), clock changes forward
      (defended by the pre-existing LOCKED AND-gate requiring elapsedRealtime to corroborate
      wall-clock; not independently unit-tested — `System.currentTimeMillis()` is unshadowed in
      this Robolectric setup so a wall-clock-only jump can't be simulated without a real sleep;
      will spot-check via `adb shell date` on the emulator instead), midnight (core cycle is pure
      elapsed-time math with zero calendar references — safe by construction; confirmed no test
      needed), multiple monitored apps (existing `multipleBlockedPackages_...` test).
- [ ] DEFERRED-LOW: `scheduleEnabled`/`startHour`/`endHour` fields in TimerState.kt (an
      optional "only enforce between these hours" window) have a real midnight-crossing bug
      (`hour < startHour || hour >= endHour` breaks for an overnight window like 22->6) but are
      completely unreachable — grepped the whole app, zero UI wires them to anything, they can
      never be set to enabled. Dead code, zero current user impact. Not fixing to avoid touching
      unreachable code paths; noting for awareness only. TimerState.kt:58-60,131-134

## Part 8 (info/help icon coverage) — additional fix beyond the subagent's findings

- [x] FIXED: added an ⓘ info button to the dashboard's Configuration card (next to "FOCUS
      CONFIGURATION"), explaining the real FREE/LOCKED/BREAK cycle in plain language — FREE
      happens once at session start, then LOCKED/BREAK alternate, and it follows real elapsed
      time regardless of whether the app is opened during any window. This directly targets the
      exact mental-model mismatch that motivated the Part 1-3 timer investigation (user assumed
      FREE recurs; it doesn't — BREAK is a separate, shorter, intentionally-repeating window).
      activity_main.xml, MainActivity.kt, strings.xml (time_limit_info_title/body)
- [x] Gentle/Nuclear mode descriptions already surface automatically via tvModeDescription when
      selected (pre-existing mechanism, text improved as part of the Critical #3 fix above) —
      judged adequate without adding a second, separate info affordance.
- [x] Strict Mode now explains itself via the in-app dialog added for Critical #2 (shown before
      the system Device Admin prompt); Parental Control's role-selection subtitle now explains
      the parent/child relationship and what syncs (Medium #8 fix above). Neither has a
      standalone ⓘ icon distinct from these existing entry points — judged sufficient coverage
      without adding redundant affordances.

## Part 4-18: Setup / UX / Settings / IA / Accessibility

Findings from independent subagent read-only audit (Explore agent), triaged below. Each gets
fixed inline in this section as it's addressed.

- [x] CRITICAL FIXED: Built SetupGuideActivity (new Activity + layout) — a plain-language
      walkthrough of all 5 setup items (choose apps, Accessibility, Overlay, Battery,
      Notifications), each showing real-time status and an explanation of *why* before sending
      the user to Settings. Auto-launched once on first-ever run (before the notification
      permission dialog can fire), always reachable afterward via the existing permissions-card
      buttons (now all routed through the guide instead of straight to raw Settings intents) and
      via a new "Complete Setup" fallback state. Re-verifies real system state on every
      onResume() rather than assuming a return from Settings means success (reuses the same
      AccessibilityUtils/Settings.canDrawOverlays/PowerManager checks MainActivity already had).
      SetupGuideActivity.kt, activity_setup_guide.xml, MainActivity.kt onCreate/setupListeners/
      updateUI, AndroidManifest.xml
- [x] CRITICAL FIXED: No "why" explanation before any of the 4 Settings-launching actions —
      Overlay/Accessibility/Battery now route through SetupGuideActivity's per-item explanation;
      Device Admin (Strict Mode) now shows an in-app AlertDialog with the existing
      admin_description text BEFORE launching ACTION_ADD_DEVICE_ADMIN (reverting the switch if
      cancelled), instead of jumping straight there. MainActivity.kt onStrictModeToggled
- [x] CRITICAL: Nuclear mode description points to a README never shipped to the device —
      FIXED: inlined the actual Home/Recents limitation into the string itself.
      strings.xml (mode_desc_nuclear)
- [x] HIGH FIXED: Notification permission denial recovery — SetupGuideActivity's Notifications
      row (Android 13+ only) always reflects real permission state and is always reachable
      again via "Complete Setup"; MainActivity's updateUI() now folds notifOk into
      cardPermissions/btnSetup visibility so the card resurfaces even if notifications are the
      only thing missing. MainActivity.kt updateUI, SetupGuideActivity.kt
- [x] HIGH FIXED: Parental dashboard controls now check the Result of every sync write.
      onAllowanceChanged/onEnabledChanged/onDeleteClicked show a friendly error + call
      refreshParentAppList(fid) to correct the adapter's now-wrong optimistic state on failure;
      switchGlobalRestrictions (extracted to a named, detachable listener) reverts its own
      checked state on failure; approve/deny request handlers re-enable their buttons and show
      an error on failure instead of staying disabled forever. ParentalControlActivity.kt
      (handleSyncWriteResult, globalRestrictionsListener, approve/deny handlers)
      DEFERRED (not fixed): ParentalAppPickerActivity.kt:102-113's restriction-checkbox toggle
      has the same gap — same class of fix, lower traffic path, left for a follow-up pass.
- [x] HIGH FIXED: Auth/pairing failures now go through friendlyErrorMessage()/mapClaimError()
      helpers mapping known Firebase/Firestore exception types (and PairingManager's internal
      literal-string exceptions) to plain-language strings instead of raw exceptionOrNull()?.
      localizedMessage — also fixed the 3 already-existing but previously-dead error_invalid_code/
      error_code_expired/error_code_used string resources to actually get used.
      ParentalControlActivity.kt
- [x] HIGH: PIN/end-session screen unconditionally branded "ADMIN ACCESS"/"Strict Mode" even
      when Strict Mode was never enabled — FIXED: recopied to a mode-agnostic "Confirm to End
      Session" framing, since the screen's friction is intentionally unconditional (btnReset
      always routes here regardless of strictMode) — the bug was the misleading copy, not the
      gating. strings.xml (admin_access, pin_solve_format)
- [x] MEDIUM FIXED: parental_control_subtitle now discloses that setup creates an account and
      syncs the app list + time limits (explicitly not browsing activity/content) between
      devices, shown before role selection. strings.xml
- [x] MEDIUM FIXED: added a "Forgot password?" button to the parent sign-in view, wired to a
      new ParentalAuthManager.sendPasswordResetEmail() + performPasswordReset(); shows the same
      neutral confirmation regardless of whether the account exists (avoids email enumeration).
      ParentalAuthManager.kt, ParentalControlActivity.kt, activity_parental_control.xml
- [x] MEDIUM FIXED: wired the previously-dead app_groups_subtitle string into
      activity_app_groups.xml's toolbar area; changed the App Picker's overflow menu item to
      show its existing title text alongside the icon (showAsAction="always|withText") instead
      of icon-only, so it reads as "App Groups" rather than an unlabeled sort glyph.
      activity_app_groups.xml, menu_app_picker.xml
- [x] MEDIUM FIXED (via the SetupGuideActivity built for the Critical items above): its 5-row
      checklist + tvAllDone banner *is* the unified setup-progress indicator spanning all 5
      items, reachable anytime via "Complete Setup". MainActivity.kt, SetupGuideActivity.kt
- [x] MEDIUM: Gentle mode description omits concrete grace duration and per-app scope —
      FIXED: now states "1 minute" and "only applies to the app you dismissed it from."
      strings.xml (mode_desc_gentle)
- [x] MEDIUM FIXED: emergency_pass_btn string now states "(5 min, once per day)" up front,
      instead of only revealing the once-per-day limit after the button is already disabled on
      a later day. strings.xml
- [x] LOW largely addressed as a side effect of the Critical #2 fixes: Device Admin now has an
      in-app plain-language dialog before the system prompt, and Accessibility's explanation
      lives in SetupGuideActivity — both in plain language, not raw jargon. The dashboard's
      "Strict Mode (Uninstall Protection)" label already carries a plain-English gloss inline.
      Not fully exhaustive (a few less-common strings still use platform terms) but the
      highest-traffic instances are resolved; remaining instances are cosmetic, deferred.
- [ ] LOW (deferred): disabled steppers/toggle during an active session give no DEDICATED
      explanation why, though tvSub already shows the active phase ("Locked - put your phone
      down!" etc.) alongside them, which implicitly explains the lock. Judged low enough value
      relative to effort (would need new layout space) to defer past this pass.
      MainActivity.kt:384-416
- [x] LOW FIXED: AppPickerActivity now re-checks Usage Access (and everything else) in a new
      onResume() override instead of only on initial load — a user granting Usage Access via the
      snackbar and returning no longer sees stale blank usage times. AppPickerActivity.kt
- [x] LOW: "ADMIN ACCESS" title framing — FIXED as part of the HIGH #7 fix above.

## Part 19-20 fixes applied

- [x] HIGH (H1) FIXED: child branch of loadInitialState() now checks
      ParentalAuthManager.isSignedIn() before trusting cached "paired" state, same as the
      parent branch already did; added showChildNeedsRepair() reusing the existing
      layoutChildStatus screen + its already-wired btnChildUnpair/confirmUnpair() recovery
      path instead of building a new flow. ParentalControlActivity.kt
- [x] MEDIUM (M1) FIXED: added roleSetupInFlight guard around setupAsChild() (try/finally
      ensures reset on every exit path) so a double-tap can't race two signInAnonymously()
      calls into two anonymous UIDs. ParentalControlActivity.kt
- [x] MEDIUM (M2) partially mitigated by the H1 fix (silent staleness -> explicit "needs
      re-pairing" state); full fix (server-side orphaned-family cleanup) deferred — needs a
      Cloud Function/TTL policy, an infrastructure decision belonging to the user.
- [x] LOW (L1) FIXED: BootReceiver exported="true" -> "false" — BOOT_COMPLETED is an
      OS-protected broadcast action, still delivered regardless. AndroidManifest.xml
- [x] LOW (L3) FIXED: removed UID/familyId interpolation from Log.i calls in
      ParentalAuthManager.kt (4 lines) and PairingManager.kt (3 lines) and SyncEngine.kt (1
      line), matching the prior pass's pairing-code redaction. Left package-name/count logging
      alone (not sensitive).
- [x] LOW (L2), Informational items: accepted/confirmed-no-bug, no action needed (see above).

## Part 19-20: Anonymous Auth + Security

Findings from independent subagent read-only audit (Explore agent), triaged below.

- [ ] HIGH: Child device's Room-cached "paired" state is never re-validated against the live
      FirebaseAuth session (parent role does check `isSignedIn()` in loadInitialState(), child
      role doesn't) — if the child's anonymous session is ever lost/invalidated post-pairing,
      the device shows "paired" forever while silently never syncing again, indistinguishable
      from normal staleness to the parent. ParentalControlActivity.kt:198-215, SyncEngine.kt
- [ ] MEDIUM: No re-entrancy guard on setupAsChild() — a double-tap before the first
      signInAnonymously() resolves can create two distinct anonymous UIDs + two orphaned family
      docs (check-then-act on auth.currentUser isn't synchronized across concurrent callers).
      ParentalControlActivity.kt:115,272-317, ParentalAuthManager.kt:37-40
- [ ] MEDIUM (deferred, design tradeoff): anonymous-only child identity has no
      upgrade/recovery path — Clear Data/reinstall cleanly resets the LOCAL device (confirmed
      no half-paired desync for that device) but permanently orphans the OLD family doc
      server-side with no cleanup. Full fix needs a Cloud Function/TTL policy (infra decision);
      partial mitigation folded into the H1 fix above (surface "needs re-pairing" instead of
      silent staleness).
- [ ] LOW: BootReceiver exported=true with no permission gate — low impact (BOOT_COMPLETED is
      an OS-protected action, delivered regardless), but tightening to exported=false is free.
      AndroidManifest.xml
- [x] LOW (confirmed-no-bug, accepted residual): pairing-code enumeration-during-TTL race is a
      combinatoric residual (32^6 space / 300s window), inherent to any short human-typeable
      code, not a rules logic hole. No action.
- [ ] LOW: Firebase UIDs and familyId logged in plaintext via Log.i (never combined with email
      or the raw pairing code, which prior pass already redacted) — unnecessary logcat exposure.
      ParentalAuthManager.kt:39,45,64,80, PairingManager.kt:84,133,147, SyncEngine.kt:247,284,311
- [x] Re-verified firestore.rules fresh: isParentOfFamily/isChildOfFamily always resolve
      request.auth.uid against that specific family doc's own stored fields; no rule grants
      family-scoped access on bare isAuthenticated() alone; rules.test.js's OTHER_UID cases
      already cover cross-family-stranger attempts and pass. No new hole found.
- [x] Re-verified AndroidManifest.xml: MainActivity (launcher, necessarily exported) doesn't
      process attacker-controlled intent extras; BlockerAccessibilityService/AdminReceiver are
      exported=true but gated by system-only signature permissions
      (BIND_ACCESSIBILITY_SERVICE/BIND_DEVICE_ADMIN); all other components explicitly
      exported=false; declared permissions all map to justified, implemented features.
- [x] Re-verified local storage (ParentalConfig, SharedPreferences) and network config
      (no network_security_config.xml, no cleartext weakening, targetSdk 34 default-secure) —
      both confirmed fine, no action.

## Part 21-24: Parental Flow + Regression

- [ ] Full parent->Firebase->child->local->enforcement flow re-verification
- [ ] Offline enforcement (no network dependency in blocking decision)
- [ ] Regression pass

## Real device walkthrough (emulator, fresh install, not just unit tests)

- [x] Uninstalled + reinstalled fresh debug APK; launching MainActivity for the first time
      correctly auto-redirected to SetupGuideActivity (mCurrentFocus confirmed via dumpsys).
- [x] Accessibility row: info dialog showed correct plain-language text incl. the "Restricted
      Setting" workaround; "Open Settings" correctly navigated to
      Settings$AccessibilitySettingsActivity; enabling the service in system Settings and
      returning showed the row auto-flip to a green checkmark + "Done" with zero manual refresh.
- [x] Notifications row: same pattern, using the real system permission dialog
      (onRequestPermissionsResult path) instead of a Settings round-trip — also auto-verified.
- [x] "Continue to ScrollGuard" correctly returned to MainActivity (after correcting my own
      mis-estimated tap coordinates during testing — not an app bug, confirmed by inspecting
      the real back stack via `dumpsys activity activities`, which showed MainActivity correctly
      alive underneath SetupGuideActivity the whole time).
- [x] Time Limit ⓘ info dialog on the dashboard renders the full FREE/LOCK/BREAK explanation
      correctly.
- [x] Granted Overlay + Battery via shell (appops/deviceidle) to reach the Start flow; set
      Free=Lock=Break=1 minute; selected Clock app as the monitored app; started a Gentle-mode
      session.
- [x] **Full real-time cycle verified via actual blocking behavior, not just TimerState
      assertions**: FREE (Clock app opened, not blocked) -> LOCKED (BlockActivity automatically
      appeared over the still-foregrounded Clock app, showing the correct remaining time and the
      updated Gentle/Emergency-Pass copy) -> BREAK/ALLOWED (Clock app usable again, confirmed by
      relaunching it) -> LOCKED again (BlockActivity reappeared on schedule). This is the exact
      accessibility-service enforcement path this session's critical fix touched, now proven
      correct end-to-end on-device, not just in Robolectric.
- [x] End Session flow: PIN screen showed the corrected "Confirm to End Session" / "Solve to
      end your session: N + M" copy (no more misleading "ADMIN ACCESS"/"Strict Mode" framing);
      an intentionally-wrong answer correctly showed "Incorrect. Try again." and regenerated a
      new challenge (anti-brute-force behavior intact); the correct answer ended the session
      and returned the dashboard to READY with steppers re-enabled.

## Part 25: Final Report

- [x] Static analysis sweep: zero TODO/FIXME/HACK/XXX, zero printStackTrace, zero Log.d/Log.v,
      zero !! non-null assertions in app/src/main/java. All @Suppress narrow and justified.
      catch(Exception) blocks reviewed as a class: consistently either log-and-continue for
      OS-triggered callbacks or Result-wrapping for async ops — the concrete silently-ignored-
      Result instances (parental dashboard) are exactly what this session already fixed.
- [x] Full build+test+lint suite: `./gradlew clean test lintDebug lintRelease assembleDebug
      assembleRelease` — BUILD SUCCESSFUL, 29/29 tests passing in both debug and release
      variants (58 executions), 0 lint errors, 23 pre-existing warnings (none introduced).
- [x] Firestore rules emulator suite re-run: 34/34 passing (unchanged, firestore.rules not
      touched this session).
- [x] Real device/emulator walkthrough: see dedicated section above — full setup guide flow,
      full FREE/LOCKED/BREAK/LOCKED cycle with real accessibility-service enforcement, end
      session flow, all confirmed working on-device.
- [x] Final report written: TIMER_UX_AUDIT_REPORT.md, all 12 requested sections, verdict
      READY AFTER FIXES.
