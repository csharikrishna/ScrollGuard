# ScrollGuard — Battery/Performance Soak Test

Measure ScrollGuard's real battery, CPU, memory, and network behavior over extended real time, on a
real device or emulator, under realistic usage patterns — not a code read-through. This app has
multiple always-on background components; the job is to find out, with real measurements, whether
any of them are hotter than they should be.

---

## PART 0 — WHY THIS MATTERS HERE SPECIFICALLY (verify each of these, don't take them on faith)

- **Two independent 1-second `Handler` tick loops** can run simultaneously: `TimerService`'s own
  loop, and `BlockerAccessibilityService`'s `parentalTickRunnable`. Verify: does the parental tick
  loop actually start unconditionally in `onServiceConnected()` regardless of whether the device is
  paired/parental control is enabled, or is it gated? If unconditional, a device that never uses
  parental control may still be paying for a tick loop doing nothing useful.
- `checkAndBlockCurrentApp()` runs on every accessibility event (`TYPE_WINDOW_STATE_CHANGED` /
  `TYPE_WINDOWS_CHANGED`), not just once a second — it now does more work per call than before this
  session's changes (foreground-package tracking for the timer fix, two blocking engines, PiP
  overlay add/remove). Verify this doesn't become expensive under a heavy-notification or
  fast-app-switching device.
- If a device is a paired child, `SyncEngine.attachLiveConfigListener` may hold a live Firestore
  listener open for the life of the accessibility service. Verify what that actually costs in
  network/CPU/battery over time versus the `SyncWorker` ~15-minute periodic fallback.
- `TimerState`'s new usage-metering logic (`currentForegroundPackage`, the ALLOWED-phase accounting
  in `catchUp()`) is new code from this session — verify it doesn't introduce any unexpected
  per-event allocation or object churn that shows up under sustained load.
- Two static Kotlin `object` singletons (`TimerState`, `ParentalControlState`) hold in-memory state
  for the life of the process — verify neither grows unbounded over a long session (e.g. an
  ever-growing `graceUntilByPackage` map, or a `pendingDeltas` map that never fully drains).

---

## PART 1 — TEST MATRIX

Run each scenario for real, not simulated, and let it run long enough for the numbers to be
meaningful (a soak test that runs 3 minutes proves nothing — see Part 3 for how to structure this
around real wall-clock time without stalling the whole engagement on it).

1. **Baseline / control** — device idle, screen off, ScrollGuard installed but no session running
   (`TimerState.phase == IDLE`, not paired). This is your comparison floor.
2. **Personal focus-timer session, screen off, phone idle** — a session running (FREE/LOCKED/ALLOWED
   cycling) with the screen off and no user interaction, for several hours. Isolates the cost of
   `TimerService`'s tick loop + accessibility service under Doze/App Standby.
3. **Paired child device, restrictions enabled, screen off, phone idle** — parental control active,
   at least one app restricted, for several hours with no user interaction. Isolates the parental
   tick loop + any live Firestore listener + `SyncWorker`'s periodic wakeups.
4. **Active use — rapid app switching** — with a session/restrictions active, deliberately switch
   between several apps rapidly and repeatedly for 10–15 minutes (simulating a heavy
   notification/multitasking user) to stress the per-accessibility-event path specifically.
5. **Combined worst case** — personal session *and* paired parental restrictions both active
   simultaneously, screen off, for several hours.

For each scenario, capture a "before" and "after" snapshot (see Part 2) and compute the delta
attributable to ScrollGuard specifically, not just absolute numbers.

---

## PART 2 — TOOLS AND METRICS

**Battery attribution**
- `adb shell dumpsys batterystats --reset` before each scenario, then `adb shell dumpsys batterystats com.scrollguard` after, to get the OS's own per-app attribution (wakeups, CPU time foreground/background, wakelock time).
- Cross-check with `adb bugreport` + Battery Historian if available, or Android Studio's Energy Profiler for a live view during a shorter supervised run.
- Specifically check: wakelock hold time (any wakelock held for the full duration would be a red flag — the tick loops should not need one), alarm/wakeup count attributed to the app, and whether the app appears as a notable consumer relative to the OS/system baseline in scenario 1.

**CPU**
- `adb shell dumpsys cpuinfo | grep scrollguard` sampled periodically during each scenario.
- `adb shell top -m 10` snapshots to catch any sustained high-CPU periods, not just averages.
- A Perfetto trace (`adb shell perfetto ...` or via Android Studio) during scenario 4 specifically, to see exactly what `checkAndBlockCurrentApp()` and the tick loops cost per invocation under load.

**Memory**
- `adb shell dumpsys meminfo com.scrollguard` sampled every 15–30 minutes during the multi-hour
  scenarios (2, 3, 5). Plot PSS over time — flat or sawtooth (GC working normally) is fine; a
  steady upward trend is a leak. Specifically inspect whether `graceUntilByPackage`,
  `pendingDeltas`, or any Firestore listener registration grows without bound.
- If a leak is suspected, integrate LeakCanary temporarily (debug build only) to pinpoint it rather
  than guessing from heap size alone.

**Network**
- `adb shell dumpsys netstats` (or the app's row in Settings > Data usage) before/after a multi-hour
  paired scenario, to quantify what the Firestore live listener + `SyncWorker` actually cost in
  bytes over real time — cross-check against the parental-control spec's own invariant of "no
  per-second cloud traffic."

**Service health / Doze interaction**
- Confirm the foreground service notification stays alive and accurate throughout an overnight idle
  run (scenario 2/3/5) — a silently-killed service would show as a suspicious *drop* in CPU/battery
  attribution partway through, not a spike; treat an unexplained flatline as suspicious, not good
  news, and verify via `adb shell dumpsys activity services com.scrollguard` that everything expected
  is still actually running at the end.
- Confirm no ANRs or dropped/frozen frames occurred (`adb logcat` filtered for ANR, and
  `dumpsys gfxinfo com.scrollguard` for jank) — particularly relevant for `BlockActivity`'s launch
  path given its animation/flag setup.

---

## PART 3 — HOW TO STRUCTURE THIS AROUND REAL TIME

A soak test runs on wall-clock time that doesn't fit into a single tool call. Structure it like this
rather than trying to compress hours into minutes or stalling the whole engagement waiting on one:

1. Kick off each multi-hour scenario as a background-monitored run (periodic `dumpsys` snapshots on
   a timer, logged to a file) rather than a single blocking command.
2. While a long scenario runs, do the things that don't need to wait for it: the short scenario 4
   (rapid switching) can be done first since it only takes 10–15 minutes and produces immediate
   results; static verification of Part 0's specific code questions can happen any time.
3. Check back on a long-running scenario periodically rather than polling tightly — a soak test's
   signal doesn't change meaningfully minute to minute.
4. If a true overnight (8+ hour) run isn't practical in this environment, run the longest scenario
   you reasonably can (aim for at least 1–2 hours minimum per multi-hour scenario — most real leaks
   and wakeup-flood problems are visible well before 8 hours if they exist at all) and say explicitly
   in the report that this is a shorter accelerated soak, not a full overnight one, so the confidence
   level is reported honestly rather than implied to be more thorough than it was.

---

## PART 4 — REPORT

For each scenario: duration actually run, battery delta (and how that compares to the baseline
control), CPU attribution, memory trend (flat/sawtooth/leaking, with the actual PSS numbers over
time), network bytes (for paired scenarios), and any anomalies (ANRs, jank, service drops, unbounded
map growth). Then:

- **Findings** — anything that stood out as disproportionate, with the specific component
  responsible (which tick loop, which listener, which code path) traced from the profiling data,
  not guessed.
- **Fixes applied**, if any were clearly warranted and safely fixable (e.g., gating the parental
  tick loop behind an actual pairing check if it's currently unconditional) — keep changes minimal
  and targeted, consistent with how the rest of this project has been handled; don't restructure
  working code because a profiler flagged something within normal bounds.
- **Verdict**: is ScrollGuard's background battery/performance footprint reasonable for an
  always-on utility app of this kind, or is there a specific, evidenced problem that needs
  addressing before shipping? Don't hedge this — give a real answer backed by the numbers above.
