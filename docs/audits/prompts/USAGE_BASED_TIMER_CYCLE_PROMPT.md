# ScrollGuard — Fix the FREE/LOCKED/ALLOWED Cycle: Clock-Based → Usage-Based

A tester reported that the personal focus-timer cycle behaves like a fixed alternating schedule
("odd 5 minutes usable, even 5 minutes locked") instead of the intended **use → lock → use → lock**
model, where unused usable time should carry forward instead of just expiring on the clock.

## This is already confirmed by code inspection — start from that, don't re-litigate it

`app/src/main/java/com/scrollguard/TimerState.kt` is the personal focus-timer engine (`Phase.FREE` /
`Phase.LOCKED` / `Phase.ALLOWED`) — distinct from the parental daily-quota system
(`ParentalControlState.kt`), which is a separate mechanism (see the scoping note below).

- `transitionNext()` (~lines 183–217) advances `FREE → LOCKED → ALLOWED → LOCKED → ALLOWED → ...`
  purely by setting `phaseEndTimeWall`/`phaseEndTimeElapsed` to `now + <phase>Duration`.
- `catchUp()` (~lines 380–418) — called by `tick()` on every `TimerService` second **and by
  `BlockerAccessibilityService` on every accessibility event** — advances through phases whenever
  `nowWall`/`nowElapsed` have passed the current phase's end time. That's the only condition it
  checks.
- Nothing in this file reads which app is in the foreground, whether a monitored app is currently
  open, or whether the user is "using" anything at all. Foreground-package detection lives entirely
  in `BlockerAccessibilityService`, which only *consumes* `TimerState.phase`/`isAppBlocked()` to
  decide whether to show the block screen — it never reports usage back into `TimerState`.

Conclusion from this reading: the FREE and ALLOWED windows burn down in real time whether or not the
user ever opens a monitored app during them, and LOCKED always releases on schedule regardless of
whether it was "needed." This matches the tester's report exactly. Your first job is still to
**independently reproduce this on an emulator/device** (per the instruction below) before changing
anything, both to confirm this reading is still accurate in the current code and to have a concrete
before/after to verify the fix against — but do not spend time re-proving from scratch that the
mechanism is clock-based; that part is already established. What needs real investigation is
everything after that.

## Reproduction recipe (do this first, on-device)

Set short durations for fast iteration (e.g. `freeDuration = 60s`, `lockDuration = 60s`,
`allowDuration = 60s` — however these are actually configured in the running app, likely via
`AppPickerActivity`/settings UI; use whatever the real user-facing path is, not a code hack, so the
reproduction matches what a real tester saw). Start a session, and during an `ALLOWED` (or `FREE`)
window, **do not open any monitored app at all**. Confirm: does the phase still transition to
`LOCKED` exactly when the window's clock runs out, with zero usable time actually consumed? Then
separately test: start a window, actively use a monitored app the whole time, confirm current
behavior there too (this is the case that should look unchanged either way). Record both outcomes
with timestamps as your evidence baseline.

## Scoping: confirm which system the tester actually exercised

The tester's "5 minutes usable / 5 minutes locked" description structurally matches `TimerState`'s
FREE/LOCKED/ALLOWED cycle far better than the parental daily-quota system (which drains a single
allowance downward per app per day rather than repeating fixed-length windows, and — per
`ScrollGuard_Parental_Control_MVP.md` Part F — is *already specified and, per prior audit passes, at
least intended* to only accrue `consumedSeconds` while the restricted app is actually foregrounded).
Confirm this scoping explicitly: reproduce the report against `TimerState` (the personal
self-discipline feature) specifically. If, during reproduction, you find the *parental* system also
fails to be usage-gated (i.e. `consumedSeconds` increments or a block fires even when the child
device's restricted app isn't actually in the foreground), that is a separate, likely higher-severity
regression against an already-documented invariant — report it distinctly, don't fold it into this
fix, and cross-reference `TIME_LOGIC_AND_LOCKING_VALIDATION_PROMPT.md`'s Part 1, which already covers
that system's correctness in depth.

## The behavior change being requested (stated plainly, so it isn't lost in implementation)

FREE and ALLOWED are meant to be a **usable-time budget that only depletes while the user is
actually using a monitored app**, not a countdown that runs regardless. If the user doesn't touch a
monitored app during a usable window, that window's unused time should still be available the next
time they open one — it should not simply be forfeited because a clock ran out in the background.

This is a genuine behavioral change to how an existing, previously-audited feature works, not a
one-line bug fix — treat it with the corresponding care: minimal, targeted implementation; preserve
everything about this feature that isn't in scope here (persistence format compatibility where
reasonable, the GENTLE-mode grace-dismiss mechanism, the `strictMode`/`Strictness` behavior, the
existing `MainActivity`/`AppPickerActivity` configuration UI, `TimerService`'s role, the analytics
fields `accumulatedLockedMs`/`totalSecondsSaved`/`cycleCount`).

## Design questions to resolve before/while implementing — don't guess silently

The reproduction and the request above don't fully pin down the exact semantics. For each, pick the
smallest, most predictable, most defensible answer, implement it, and **state the choice and its
rationale explicitly in your final report** so it's a visible decision, not a buried assumption:

1. **What counts as "using" the app for this purpose?** The natural reading is: the foreground app
   is one of `TimerState.monitoredApps`. Confirm this is knowable at the point `TimerState` needs it
   (today only `BlockerAccessibilityService` knows the current foreground package) and design the
   minimal plumbing to get that signal into `TimerState`'s tick path without duplicating or
   conflicting with the parental system's own separate foreground-tracking in
   `BlockerAccessibilityService`.
2. **Does LOCKED stay a pure clock-based wait?** The default/expected answer is yes — LOCKED is a
   deliberate cooldown regardless of activity, and only the *usable* side (FREE/ALLOWED) becomes
   usage-metered. State this explicitly as the chosen model rather than leaving it implicit.
3. **Does unused usable time roll over without limit, or is there a cap?** An unbounded bank (e.g. a
   user who never opens a monitored app for a week accumulating a week's worth of usable minutes)
   may not be the intended product behavior. Propose a sensible bound (e.g. capped at one window's
   worth of duration, or capped at some small multiple) if the request doesn't specify one, and
   document that choice clearly as something the product owner may want to revisit.
4. **Does this apply to both FREE and ALLOWED, or only ALLOWED?** FREE is the long initial "browse
   freely" period before the first lock; ALLOWED is the short recurring check-in window after each
   lock. Determine whether "usage-based" is meant for both or just the recurring ALLOWED window (the
   one the tester's example describes), and implement consistently with whichever you choose.
5. **How does this interact with reboot/process-death recovery (`healState`/`catchUp`)?** The
   existing reboot-recovery logic assumes phase deadlines are pure wall-clock targets it can
   fast-forward through. A usage-metered budget can't be "caught up" the same way (there's no
   elapsed-usage data for time the process wasn't running to observe). Design explicitly for what
   happens to a partially-consumed usable budget across a process death or reboot — don't let the
   existing catch-up loop silently misbehave against the new model.
6. **How does this interact with the existing analytics fields** (`accumulatedLockedMs`,
   `totalSecondsSaved`, `cycleCount`)? Determine whether their meaning needs to change and keep them
   internally consistent with the new model rather than silently becoming inaccurate.

If, after working through these, you believe any single one of them cannot be resolved with a
reasonable default and genuinely needs the product owner's input before you implement it, say so
explicitly in your report and implement the rest — don't block the whole fix on one open question.

## Implementation & verification

- Implement the minimal change that satisfies the resolved design above. Prefer extending the
  existing `Phase`/tick model over introducing a parallel state machine.
- Add unit tests to `TimerStateTest.kt` covering: a monitored app not opened during a usable window
  does not burn that window's time; opening it does consume it correctly; LOCKED still elapses on
  its fixed schedule regardless of activity; the rollover/cap behavior from question 3; reboot/
  process-death behavior from question 5; and that this change does not alter `ParentalControlState`
  or its tests in any way.
- Run `./gradlew testDebugUnitTest` and confirm both old and new tests pass.
- Re-run the exact on-device reproduction recipe from above and confirm the behavior now matches the
  intended model, with before/after evidence in your report.

## The broader locking-mechanism review (do this pass regardless of the above finding)

Separately from the specific fix, review the full locking mechanism end to end and confirm:

- The locking logic (for **both** `TimerState` and the parental system) is clearly and consistently
  defined — no ambiguity about what triggers a lock or how remaining time is computed, post-fix.
- The UI clearly communicates, at all times, whether the app is currently in a usable or locked
  state, how much usable/locked time remains, and — now that the usable side is usage-metered —
  *why* the remaining time might not be counting down when the user isn't in a monitored app. This
  is a real UX risk introduced by this exact fix: a user watching a "usable time remaining" display
  that doesn't move while they're outside a monitored app could easily read that as broken rather
  than correct. Make sure the UI/copy makes the mechanic legible rather than confusing.
- Transitions between FREE/LOCKED/ALLOWED (and the parental enabled/disabled, allowance-exhausted
  states) are correct and match what's actually implemented, not what a stale screen/string implies.
- The lock cannot be accidentally or trivially bypassed — cross-check this specifically against
  `TIME_LOGIC_AND_LOCKING_VALIDATION_PROMPT.md` Part 2 (debounce exploitability, PiP/split-screen,
  service restart, reboot) rather than re-deriving that list from scratch; note whether this cycle
  fix introduces any *new* bypass surface (e.g. a way to keep a monitored app just barely out of
  foreground detection to keep "banking" usable time indefinitely).
- Behavior is correct and consistent after app restart, process death, reboot, and background/
  foreground transitions, specifically re-tested against the new usage-based model (not just the old
  clock-based one).
- Onboarding/help text actually explains the cycle correctly to the user (find wherever this feature
  is currently explained in the UI/strings and confirm it matches the fixed behavior — update it if
  it still describes the old fixed-schedule mental model).

## Report

State: the confirmed root cause (with file/line evidence), the design decisions made for each
numbered question above (with rationale), any question deliberately left for the product owner, the
implementation summary, tests added and their results, the on-device before/after reproduction
evidence, the broader locking-review findings, and an honest verdict on whether the cycle now matches
the intended use → lock → use → lock model.
