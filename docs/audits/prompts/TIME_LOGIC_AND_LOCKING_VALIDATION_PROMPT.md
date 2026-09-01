# ScrollGuard — Time Logic & Locking Mechanism Cross-Validation

ScrollGuard has **two parallel timer/enforcement systems** that must each be independently correct
and must never interfere with each other:

1. **The personal focus timer** (`TimerState.kt`, `TimerService.kt`) — free/lock/allow cycles.
2. **The parental daily-quota system** (`ParentalControlState.kt`,
   `data/parental/ParentalAppRestriction.kt`, `data/parental/ParentalDao.kt`) — per-app daily
   allowance/consumed accounting.

Both ultimately drive the same locking mechanism (`BlockerAccessibilityService.kt` →
`BlockActivity.kt`). This is a **validate-first** pass: don't assume either system is correct just
because it currently builds, passes its existing unit tests, or "looks right" on a read-through.
Trace the actual arithmetic and state transitions against concrete worked examples, and cross-check
the implementation against `ScrollGuard_Parental_Control_MVP.md` (especially Part B.2 issues B, E,
F, L, O and Part F) for the parental side.

Existing coverage to build on, not just re-read: `TimerStateTest.kt` and
`ParentalControlStateTest.kt` (which already covers the 2-second grace trigger, "changing allowance
doesn't reset consumed," "remaining is never negative," and state clearing). Identify what's
*missing* from that coverage rather than only re-verifying what's already tested.

---

## PART 1 — TIME ACCOUNTING CORRECTNESS

For **both** systems independently, trace the actual code and construct concrete worked examples
(specific numbers, specific timestamps) proving or disproving each of these:

* **No double counting.** A rapid sequence of accessibility events for the same foreground app
  increments consumed time only once per real elapsed second — not once per event.
* **No missed counting.** Time actually spent in a restricted/monitored app is not silently dropped
  (e.g. during the batch-persistence window, or across an app-switch-and-back within it).
* **Remaining is derived, never stored as a separate mutable field that can drift.** Confirm
  `remaining = max(0, allowance − consumed)` is computed on read everywhere it's needed, with no
  code path that persists or caches a `remaining` value that could go stale.
* **Changing the allowance never resets or corrupts consumed time**, in either direction (increase
  and decrease), including when the change arrives while the app is actively being used.
* **Day-boundary rollover happens exactly once, at the right moment, using the device's local
  calendar day** (`consumedEpochDay` for the parental system) — not the UTC day, not a rolling 24h
  window. Construct the exact case: a session that's active as midnight passes — verify consumed
  time correctly splits (post-midnight usage counts against the new day, not the old one), and that
  the reset doesn't fire twice or on every tick that day.
* **Timezone changes don't grant free time or falsely reset/inflate consumption.** Trace what
  happens if the device's timezone changes mid-session (travel, or a manual clock change) — does the
  epoch-day calculation shift in a way that could double-grant or double-charge a day?
* **Clock-tamper / backward time jumps don't grant extra time.** If the wall clock is moved
  backward, does anything derive "remaining" or "day" in a way a user could exploit by manually
  setting the clock back?
* **Reboot / process death never resets consumed time to zero and never grants a free session.**
  `elapsedRealtime()` resets to 0 at boot — verify no code path anchors a session-start time across
  a reboot such that a huge negative or huge positive delta gets computed and either wipes or
  inflates `consumedSeconds` on the next tick after boot.
* **Batch persistence (~15s per the implementation) can't be gamed.** If the process is killed
  between persistence flushes, verify how much usage can be "hidden" from Room by that gap, whether
  that gap is small enough to not matter, and whether a user force-killing the app repeatedly near
  that interval could meaningfully evade accounting.
* **Grace period units are consistent.** The tick loop and the block-threshold comparison must use
  the same unit/granularity — verify the ~2-second grace can't be stacked or repeatedly re-triggered
  (e.g. by an action that resets whatever the grace check is measuring from).
* **The two systems never share or corrupt each other's state.** Confirm `TimerState`'s focus-cycle
  accounting and `ParentalControlState`'s daily-quota accounting are fully isolated — a focus-cycle
  transition should never affect a parental quota's consumed time or vice versa, even when both
  could theoretically apply to the same foreground app at the same moment.

---

## PART 2 — LOCKING MECHANISM CORRECTNESS

For `BlockerAccessibilityService.kt` → `BlockActivity.kt`, construct concrete scenarios (not just a
code read) proving or disproving each of these:

* **A block fires exactly once per genuine threshold crossing** — not zero times (missed) and not
  multiple times (duplicate launches, flickering). Pay specific attention to the
  `lastLaunchedPackage`/`lastLaunch` 500ms debounce: verify it can't suppress a legitimate re-block
  (e.g. user briefly leaves and returns to the same restricted app while still over quota) and can't
  be exploited to swallow a block that should fire.
* **The block screen actually blocks.** Once `BlockActivity` is shown for a given trigger, verify
  the restricted app is genuinely unusable — not just visually covered — for both `FOCUS_TIMER` and
  `PARENTAL_LIMIT` modes, including after screen-off/screen-on, after rotation, and after the
  lock/keyguard state changes mid-block (it sets `FLAG_SHOW_WHEN_LOCKED`/`FLAG_TURN_SCREEN_ON`/
  `FLAG_KEEP_SCREEN_ON` — verify these actually hold up across those transitions rather than just
  reading correctly in `onCreate`).
* **`BlockActivity`'s `singleTask` + `onNewIntent` handling is correct.** Since it can be
  re-delivered an intent instead of recreated, verify the displayed reason/mode/remaining-time always
  reflects the *latest* trigger and never shows stale data from a previous block instance.
* **Multi-window/PiP cannot be used to dodge or delay a block.** Cross-check whatever was found and
  fixed (or not) by the prior `UI_UX_AUDIT_PROMPT.md` investigation into this exact concern — re-run
  its reproduction sequence fresh here as part of this validation rather than trusting its prior
  report, since this is precisely a locking-mechanism correctness question.
* **Service restart correctly rehydrates before enforcing.** After
  `BlockerAccessibilityService` is killed and restarted by the OS, verify it hydrates its in-memory
  state from Room *before* it starts making block/no-block decisions, so there's no window where it
  incorrectly allows a genuinely-exhausted app.
* **Boot correctly restores enforcement immediately**, not just eventually — verify there's no gap
  after `BOOT_COMPLETED` where a restricted app is briefly usable before `BootReceiver`'s rehydration
  completes.
* **The block screen cannot become self-blocking or unreachable.** Confirm ScrollGuard's own UI
  (including `BlockActivity`/`PinActivity` themselves), the launcher, System UI, and the Settings
  screens needed to grant permissions can never be classified as "the restricted app" and blocked.

---

## PART 3 — CROSS-VALIDATE AGAINST THE SPEC

Go through `ScrollGuard_Parental_Control_MVP.md` Part B.2 (issues B, E, F, L, O) and Part F line by
line against the actual current implementation. For each one, state explicitly whether the code
*currently* satisfies it, with the specific file/line as evidence — do not accept the spec's own
description of the "definitive solution" as proof the code implements it that way.

---

## METHOD

1. **Don't fix anything first.** For each property above, classify it as **CONFIRMED CORRECT**
   (with the worked example that proves it), **CONFIRMED BROKEN** (with the exact reproduction),
   **UNVERIFIED — couldn't test in this environment** (say what would be needed to test it), or
   **PARTIALLY CORRECT** (state exactly what part fails).
2. **Add the missing unit tests** for whichever properties above aren't already covered by
   `TimerStateTest.kt`/`ParentalControlStateTest.kt`, so this validation is repeatable, not a
   one-time manual exercise.
3. **Only after the validation pass**, fix confirmed-broken issues — minimal, targeted fixes, no
   architecture changes for their own sake.
4. **Re-run the full test suite** (`./gradlew testDebugUnitTest`) after fixes and confirm the new
   tests plus all existing ones pass.
5. Report using the classification table above (one row per property checked), followed by: fixes
   made (problem → root cause → fix → verification), and anything left as a documented, honest
   limitation.
