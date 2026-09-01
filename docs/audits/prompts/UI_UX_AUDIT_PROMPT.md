# ScrollGuard — UI/UX Polish Audit + Multi-Window/PiP Enforcement-Bypass Investigation

You are acting as a senior product designer + UX engineer + Android frontend engineer doing a
production-readiness pass on **ScrollGuard**, a native Android app (Kotlin, View system + XML
layouts, Material Components, ViewBinding, Lottie for animation — there is no web/browser surface
anywhere in this app, see the note in Part 0 before you start).

Two things are in scope, and neither is optional:

1. A full UI/UX polish and consistency audit of every screen.
2. A specific, previously-reported functional/security bug: **a restricted app may be able to
   dodge the block screen by entering Picture-in-Picture or split-screen right as its time limit
   expires.** This is not a hunch — a preliminary code read below already found the relevant gap.
   Verify it for real, then close it.

Do not trust that something is fine because it currently renders or currently builds. Review every
screen as a real first-time parent or child user would experience it.

---

## PART 0 — TERMINOLOGY MAPPING (read first)

The underlying request for this audit was originally written in web/browser language ("viewport,"
"browser resize," "PiP," "split-screen," "pages," "components," "toasts," "modals"). This app has
no browser or web view. Map every one of those concepts to its real Android equivalent and audit
*that*, not a browser:

| Web term in the original ask | What it actually means here |
|---|---|
| Page | `Activity` + its layout XML (`MainActivity`/`activity_main.xml`, `BlockActivity`/`activity_block.xml`, `ParentalControlActivity`/`activity_parental_control.xml`, `ParentalAppPickerActivity`/`activity_parental_app_picker.xml`, `AppPickerActivity`, `UsageStatsActivity`, `AppGroupsActivity`, `PinActivity`, etc. — enumerate every `Activity` + layout pair under `app/src/main/java/com/scrollguard` and `app/src/main/res/layout` yourself; this list is a starting point, not exhaustive) |
| Component / Card / Dropdown | `RecyclerView` adapters and item layouts (`AppPickerAdapter`/item layout, `ParentalAppAdapter`/`item_parental_app.xml`, `ParentalPickerAdapter`/`item_parental_picker.xml`, `AppGroupAdapter`), `Spinner`/`AutoCompleteTextView` if used, `MaterialButton`, `Switch`/`SwitchMaterial`, `ProgressBar`/seekbars for remaining-time, dialogs (`AlertDialog`/`MaterialAlertDialogBuilder`) |
| Modal / Dialog / Toast | Android `Dialog`/`BottomSheetDialog`/`AlertDialog`, `Toast`/`Snackbar` |
| Responsive layout / mobile-tablet / viewport resize | Android configuration changes: screen rotation, different screen densities/sizes (phone vs. tablet, foldables), **and Android's own multi-window system**: split-screen (two apps side by side), freeform windows (some tablets/Samsung DeX/ChromeOS), and Picture-in-Picture (a small always-on-top floating window an app can request) |
| "Browser PiP mode" | Android's actual system **Picture-in-Picture** API (`Activity.enterPictureInPictureMode()`, `onPictureInPictureModeChanged()`), which video/call apps like YouTube use when the user presses Home or taps a PiP-eligible control |
| "Split-screen mode" | Android's actual system **multi-window mode** (`isInMultiWindowMode()`, `onMultiWindowModeChanged()`), entered via the Overview/Recents "split screen" gesture |
| Dark/light mode, RTL, localization | Android day/night theme resources (`values-night`), `android:supportsRtl`, `strings.xml` (no hardcoded UI strings) |
| Accessibility (screen reader) | TalkBack support: `contentDescription`, focus order, touch target sizes (48dp minimum), color contrast — **note this app also *has* an `AccessibilityService`, which is a different concept (`BlockerAccessibilityService`, used for enforcement, not for assistive tech) — don't conflate the two when auditing** |

Every instruction below that mentions "viewport," "PiP," "split-screen," or "responsive" means the
Android concept in the right-hand column, not a web one.

---

## PART 1 — THE HEADLINE BUG: MULTI-WINDOW / PiP ENFORCEMENT BYPASS

**Reported symptom (translate from the original ask):** a user in a restricted app, with the timer
about to hit zero, throws the app into Picture-in-Picture (e.g. tapping a PiP-capable video app's
own PiP trigger, or pressing Home while a PiP-eligible video is playing) or into split-screen (via
the Recents/Overview drag-to-split gesture) in the last second or two before the block should fire
— and the block does not actually stop the app from being used.

**What a preliminary code read already found — verify each of these for real, don't assume they're
accurate or that they're the whole story:**

1. **Zero PiP/multi-window awareness anywhere in the codebase.** A search of all of
   `app/src/main/java/com/scrollguard` for `PictureInPicture`, `isInMultiWindowMode`,
   `onPictureInPictureModeChanged`, `onMultiWindowModeChanged` returns no results at all. No
   Activity, including `BlockActivity`, reacts to either mode in any way.
2. **No manifest opt-out.** `AndroidManifest.xml` declares no `android:resizeableActivity`,
   `android:supportsPictureInPicture`, or relevant `android:configChanges` on any `<activity>`.
   With `targetSdk 34` and no explicit opt-out, every activity — including `BlockActivity` and
   `PinActivity`, the two screens whose entire job is to be un-escapable — is resizable and can be
   placed into split-screen or covered/replaced by another app's PiP window under default platform
   behavior.
3. **Foreground-app detection in `BlockerAccessibilityService`** (see roughly lines 120–270) reads
   the active package from `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOWS_CHANGED` events via
   `rootNode?.packageName` and by iterating `windows` and reading `window.root?.packageName`.
   Determine:
   - Whether this logic distinguishes a normal full-screen window from a
     `AccessibilityWindowInfo.TYPE_PICTURE_IN_PICTURE` window, or whether a PiP window is treated
     identically (or missed entirely) — a PiP transition may or may not fire the same event types
     the service listens for (`accessibility_service_config.xml` only declares
     `typeWindowStateChanged|typeWindowsChanged`).
   - What happens when **two windows from two different packages are simultaneously visible and
     "resumed"** in split-screen — which one does the service treat as "the" active package for
     time-consumption and blocking purposes, and can a user exploit the selection order/timing to
     keep the restricted app's window active while some other window is what the service currently
     "sees" as foreground?
   - The `triggerBlock()` debounce (`if (packageName == lastLaunchedPackage && now - lastLaunch <
     500) return`) — determine whether this debounce window can be exploited by rapidly toggling
     multi-window/PiP state right at the moment of blocking to swallow the trigger.
4. **`BlockActivity` itself** is `launchMode="singleTask"`, `excludeFromRecents="true"`, and sets
   `FLAG_SHOW_WHEN_LOCKED`/`FLAG_TURN_SCREEN_ON`/`FLAG_KEEP_SCREEN_ON` plus transparent system bars
   (see its `onCreate`, around lines 300–320) — but has no `onPictureInPictureModeChanged` /
   `onMultiWindowModeChanged` overrides. Determine, concretely:
   - When `BlockActivity` is launched while the restricted app is currently in PiP, does
     `BlockActivity` actually gain full-screen top z-order and input focus, or can the PiP window
     remain visible/interactive on top of or alongside it?
   - When the restricted app is one pane of an active split-screen layout, does launching
     `BlockActivity` correctly take over that pane (or the whole screen) and prevent any further
     interaction with the restricted app's pane, or can the user keep interacting with the
     restricted app in the other pane, resize the split, or swap panes to dodge the block screen?
   - Whether `BlockActivity` can itself be dragged into split-screen or shrunk by the system's
     resize/drag affordances (since nothing opts it out of `resizeableActivity`), and if so what
     that does to its layout and its ability to actually block.

**Required output for this section:** a precise root-cause explanation (which of the above is
actually the mechanism — there may be more than one), an exact reproduction sequence you verified
on an emulator/device (state exactly what you did and what you observed, not what you expect to
happen), the concrete fix(es) applied (e.g. explicit PiP/multi-window lifecycle handling in
`BlockActivity`, `AccessibilityWindowInfo.type` checks and/or treating any visible restricted
package as active regardless of PiP/split state, manifest changes, closing the debounce gap), and
confirmation the fix was re-tested against the same reproduction sequence after the change.

If, after real verification, some part of this turns out not to be exploitable (e.g. Android's
platform behavior already forces PiP to yield when a new full-screen activity starts), say so
explicitly with evidence — do not "fix" something that isn't actually broken, but do not dismiss it
without having actually tried the reproduction sequence on a real emulator/device.

---

## PART 2 — FIND 5–10 MORE GLITCHES OF THE SAME CLASS

Beyond the PiP/split-screen bypass, actively hunt for **5 to 10 additional real glitches or
enforcement gaps** — favor things in the same spirit (a user finding a state transition, timing
window, or platform feature that lets them dodge, delay, or corrupt enforcement) over cosmetic
nitpicks. For each one found, report using this exact structure (mirroring the original ask):

1. **What causes it** (root cause, with file:line).
2. **How a user can trigger it** (exact reproduction steps).
3. **How it affects the product** (can it fully bypass a block? partially? just cosmetic confusion?).
4. **Classification** — UI/UX issue, functional/logic bug, or Android platform limitation you can
   only mitigate, not fully solve.
5. **The fix or mitigation applied** (or, if not fixable, the honest limitation to document).

Good places to look, based on this app's actual architecture (verify, don't assume any of these
pan out):

- Rapid app-switching or task-switching timed against the 1-second tick loop / 15-second batch
  persistence window in `BlockerAccessibilityService` — can a user avoid time being counted or
  avoid a block by switching away and back faster than the service's polling/debounce granularity?
- Freeform/desktop windowing mode (Samsung DeX, tablets, ChromeOS) — a third multi-window mode
  beyond split-screen and PiP that the same gaps in Part 1 likely also apply to.
- Screen-off / screen-on right at the moment of expiry — does the block correctly apply the instant
  the screen turns back on, or is there a window where the restricted app is briefly usable first?
- Force-stop or swipe-away of `BlockerAccessibilityService`'s host process at the moment of
  blocking, and what the child device shows/does when the service is down (does anything visibly
  indicate "unprotected" state, or does it fail silently?).
- Notification shade / quick settings / assistant overlays drawn on top of a restricted app —
  do they interfere with the service's window-state detection or the block screen's coverage?
- Two rapidly issued `triggerBlock()` calls for different packages in immediate succession (e.g.
  quickly alt-tabbing between two different restricted apps) — any chance of a stale
  `lastLaunchedPackage`/`lastLaunch` state suppressing a block that should fire?
- Lock-screen/keyguard interactions given `FLAG_SHOW_WHEN_LOCKED`/`FLAG_TURN_SCREEN_ON` — does the
  block screen behave correctly if the device is locked, then unlocked, while a block should be
  active?
- Split-screen where the *other* pane is ScrollGuard's own UI (e.g. `MainActivity` or
  `ParentalControlActivity`) next to the restricted app — does the accessibility service's active
  package selection get confused by ScrollGuard's own window being simultaneously visible?

You are not limited to this list — it's a starting point to prime the search, not the answer key.

---

## PART 3 — FULL UI/UX POLISH AUDIT

**Goal:** the app should feel clean, intuitive, professional, trustworthy, consistent, and
human-designed — not like a generic AI-scaffolded interface with visual effects layered on top.
Every change must have a clear reason; this is a polish and consistency pass, not a redesign.
Preserve what already works well.

**Before changing anything**, read the existing design language: `res/values/styles.xml` (or
`themes.xml`), `res/values/colors.xml`, `Theme.ScrollGuard` and its variants (including
`Theme.ScrollGuard.Block`), `res/drawable/bg_monogram.xml`, `res/drawable/bg_surface_rounded.xml`,
`TransitionUtil.kt`, and how Lottie is currently used. Establish what the *intended* system already
is before deciding what's inconsistent with it.

**Audit every screen and shared component** — enumerate them from the actual project (Activities +
layouts + adapters listed in Part 0's table, plus every dialog/bottom sheet/toast/snackbar in the
codebase) for:

- Visual consistency: spacing scale, corner radii, elevation/shadow usage, color usage (accent
  colors, semantic colors for on/off/warning/error/success), typography scale and weight usage,
  icon style consistency (filled vs. outlined, sizing), button styles/hierarchy (primary vs.
  secondary vs. destructive actions).
- Interaction clarity: is every ON/OFF state (parental restrictions toggle, per-app enabled
  switches) visually unmistakable at a glance; are the `+`/`−` time steppers' tap targets, feedback,
  and clamped-limit behavior (min/max) obvious; are destructive actions (unpair, delete a group)
  appropriately guarded and visually distinct from routine ones.
- State coverage on every screen that can have one: loading, empty (no apps selected, no child
  paired), error (with an explanation and a next action, not a bare failure), success/confirmation,
  offline/syncing indicators on the parental-control screens.
- Motion: is every animation/transition (`TransitionUtil`, Lottie usage, any `ObjectAnimator`/
  `ViewPropertyAnimator`/`MotionLayout`) purposeful and subtle, or decorative/excessive? Do any
  animations risk a lifecycle race (an animation callback firing after the hosting Activity/View is
  destroyed, e.g. rotation or back-press mid-animation)?
- Avoid the specific "AI-generated UI" smells called out in the original ask, translated to
  Android: elevation/rounded-corner `CardView`/`MaterialCardView` overused where a plain `View`
  would be more honest about hierarchy; gradient `drawable`s or blur/scrim overlays used
  decoratively rather than functionally; every button turned into a pill/fully-rounded shape
  regardless of context; more than one accent color competing for attention; inconsistent spacing
  units (mixing arbitrary dp values instead of a consistent scale); icons mixed from different
  visual styles; screens that each look like they came from a different design system.
- Accessibility: `contentDescription` on meaningful icons/images, minimum 48dp touch targets,
  sufficient color contrast (including in `values-night` dark theme, if present — check whether one
  exists and is actually applied consistently), logical TalkBack focus order, no information
  conveyed by color alone (e.g. the remaining-time bar shouldn't rely purely on a color change to
  signal "almost out of time").
- Layout robustness: rotation, different screen sizes/densities, long strings (localization
  headroom — no hardcoded truncation-prone layouts), and — tying back to Part 1 — how every screen
  actually renders and behaves when resized into split-screen or a small multi-window pane, since
  the manifest currently allows any activity to be resized into arbitrary dimensions.

**Do not:** introduce gradients, glassmorphism/blur effects, or new decorative motion that isn't
already this app's language; replace the existing design system wholesale; add animation for its
own sake; change things that are already consistent and working just to make the diff look bigger.

---

## PART 4 — EXECUTION PROTOCOL

Same discipline as any large one-shot audit-and-fix engagement on this codebase:

- **Authorization.** You are pre-authorized to edit code, layouts, drawables, and styles directly
  once an issue is confirmed, and to make local git commits at logical checkpoints (bug fix,
  UI-consistency fix, etc.) with messages describing what was wrong and why. Do not push to any
  remote, force-push, or rewrite history.
- **Don't stall on ambiguity.** Where the original ask is subjective (e.g. exact spacing values),
  make the smallest change consistent with this app's *existing* design language, apply it
  everywhere it should apply for consistency, and note the judgment call in your final report.
- **Track progress durably.** Maintain `UI_AUDIT_PROGRESS.md` in the repo root: one line per screen
  audited, per Part 2 glitch investigated, and for the Part 1 bug — each checked off with a
  one-line result and file:line reference, updated continuously so the work is resumable.
- **Work in this order:** (1) verify the Part 1 bug on a real emulator/device first — it's the
  highest-severity item and the one most likely to need device testing time; (2) sweep for the
  Part 2 glitches; (3) do the full Part 3 screen-by-screen UI/UX read-through; (4) triage and fix,
  most severe first (functional/security bypass > confusing/broken interaction > inconsistency >
  cosmetic polish); (5) re-verify the Part 1 reproduction sequence after your fix; (6) full
  regression: `./gradlew testDebugUnitTest lintDebug assembleDebug`; (7) manual pass over every
  screen touched.
- **Definition of done:** the Part 1 bug has been reproduced-or-refuted with real evidence and
  fixed-or-explained, 5–10 Part 2 glitches are documented in the required 5-point format, every
  screen in `UI_AUDIT_PROGRESS.md` has a recorded outcome, and the build/test suite passes after
  the last change.

---

## PART 5 — FINAL REPORT

Produce, in order:

1. **Executive Summary.**
2. **Multi-Window/PiP Bypass Findings** — root cause, verified reproduction, fix applied, re-test
   result (Part 1).
3. **Additional Glitches Found** — 5–10 entries in the required 5-point format (Part 2).
4. **UI/UX Audit Findings** — organized by screen/component, each with what was wrong and what was
   changed (or why it was left alone).
5. **Design System Notes** — what the app's actual design language is now (post-fixes), so future
   work stays consistent.
6. **Accessibility Findings.**
7. **Regression/Verification Results** — actual command output, not assumed.
8. **Remaining Limitations** — anything genuinely un-fixable at the platform level (be honest about
   Android multi-window/PiP platform constraints you can mitigate but not eliminate).
9. **Production Verdict** — exactly one of: **READY TO SHIP** / **READY AFTER FIXES** / **NOT READY
   TO SHIP**. Do not choose "READY TO SHIP" while the Part 1 bug is unresolved or unverified.
