# ScrollGuard — Implementation Plan: Reliability, Notifications, Tamper Protection, Compose, FCM

This is the plan, not the implementation. Nothing here should be built until this is agreed. It
answers your two open research questions first, then maps how the existing systems actually
interact (so later phases don't get built in isolation), then breaks down each phase with its own
audit checklist and scope.

## Status (updated after execution)

- **Phase 1 (Notifications) — done.** Audited against the full checklist below. Found and fixed a
  real bug introduced by the same-session timer fix: `usableRemainingMs` wasn't persisted between
  phase transitions, so a mid-window process/service kill could silently re-grant already-used
  time. Batched persistence added, covered by a new unit test. The originally reported "dismissed
  notification comes back stale" symptom did **not** reproduce on stock Android 14 — `setOngoing`
  correctly blocked the swipe — pointing to an OEM-specific notification-shade quirk rather than an
  app-level bug, folded into Phase 2.
- **Phase 2 (OEM reliability) — partially done.** Battery-optimization exemption request and
  Xiaomi-specific autostart handling already existed. Generalized to also cover Samsung and
  OnePlus, with the same honest "acknowledge, can't verify" pattern (Motorola excluded — no
  comparably documented separate settings screen exists). Verified on-device: no crash, correct
  card visibility for a non-matching manufacturer. Full field verification across real OEM hardware
  is out of scope for this environment.
- **Phase 3 (Tamper protection) — done.** Device Admin kept as-is (zero policies; stronger
  Device Owner mode doesn't fit this app's install model — see below). Found the parent-side
  detection chain (`accessibilityHealthy` → Firestore `status` → dashboard) was already solid.
  Closed one real gap: `AdminReceiver.onDisabled()` now immediately reports a specific tamper event
  to the parent via a new `SyncEngine.pushTamperAlert()`, instead of only surfacing via generic
  ~10-15 minute staleness. Build/lint/test-verified; not run through a full two-device live pairing
  cycle.
- **Phase 4 (Compose) — deferred, by decision.** No concrete new screen currently needs it;
  revisit when one does (e.g. an analytics dashboard), not before.
- **Phase 5 (FCM) — deferred, by decision.** Its own gate (real field data on service uptime after
  Phase 2) doesn't exist yet and can't be produced outside production usage. Current sync path
  (live Firestore listener while the service is alive + 15-minute `SyncWorker` fallback) stands
  until that data exists.

---

## Your two open questions, answered

### 1. FCM without Cloud Functions/billing — is there a free way?

**FCM itself is always free**, on every Firebase plan, with no per-message cost or count limit.
That was never the cost driver. The actual cost driver is **triggering** a send in response to a
Firestore write — the standard way to do that is a Firestore-triggered **Cloud Function**, and
Cloud Functions require the project to be on the **Blaze (pay-as-you-go) plan**, which requires a
linked billing account/card even if usage stays entirely within the free tier and nothing is ever
actually charged. For ScrollGuard's volume (one function invocation per parent config change,
essentially free-tier-only traffic), the realistic *dollar* cost would almost certainly be $0/month
— but the requirement to link a billing account at all is the real barrier, not the money.

There is no clean way to trigger an FCM send without *some* backend holding a credential (a Cloud
Function, or an equivalent self-hosted server). Having the parent's own app call the FCM send API
directly would mean embedding a service-account credential inside a distributed mobile APK, which
is a real security anti-pattern (any secret shipped in an APK can be extracted) — not a shortcut
worth taking to avoid Blaze.

**The good news that changes the calculus:** `SyncEngine.attachLiveConfigListener` already attaches
a live Firestore snapshot listener (400ms-debounced) whenever `BlockerAccessibilityService` connects
for a paired child — which is essentially "always," since it's a bound system service. **This means
near-real-time sync already exists today, with no FCM involved, for as long as that service is
alive.** The actual gap FCM would close is narrower than it looks: it only matters when the
accessibility service itself has been killed (OEM battery management) and the periodic `SyncWorker`
hasn't run yet. That gap is **Phase 2's problem**, not a distinct sync problem — which is exactly
why your instinct to sequence FCM last is right, and probably more right than you intended: fixing
Phase 2 may shrink FCM's remaining value to the point where it's not worth the Blaze conversation at
all. Re-evaluate after Phase 2 ships, with real numbers on how often the service actually gets
killed, not before.

### 2. Device Admin — how hard is real tamper protection, actually?

What ScrollGuard already has (`AdminReceiver`, `device_admin_rules.xml`) is the *classic* Device
Admin API with **zero policies requested** (`<uses-policies />` is empty) — it grants no actual
enforcement capability, only the OS's own "must deactivate admin before you can disable/uninstall"
friction, which a moderately determined user defeats in under a minute via Settings. That part is
already built and is exactly as weak as it looks.

Going further has a hard architectural wall, not just more work: the *stronger* Android APIs
(**Device Owner** mode) require the app to be provisioned as device owner **during initial device
setup** (factory-reset provisioning, typically via a QR code or NFC bump at the Android setup
wizard) — it fundamentally cannot be granted to an app installed onto an **already-set-up** personal
phone without wiping it first. That doesn't fit ScrollGuard's actual install flow ("parent installs
this on a kid's phone they already use") at all, so it isn't a "harder version of the same feature"
— it's a different product (something closer to an MDM/EMM enrollment flow) that would require
redesigning the entire child-device setup experience around a factory reset. Classic Device Admin is
also broadly being de-emphasized/deprecated by Google in favor of Device Owner/work-profile modes
for exactly this reason, so investing further in the legacy API has a shrinking ceiling too.

Realistic scope for Phase 3, given this: **don't chase stronger prevention that doesn't fit the
product.** The spec's own Issue J already reached this conclusion — Device Admin is optional
hardening, not a guarantee, and the honest alternative is a clear **health-signal to the parent**
("accessibility disabled," "app force-stopped," "child device hasn't checked in") rather than a false
promise of unbypassable protection. Phase 3 should audit and strengthen *detection and disclosure*
of tampering, and make a deliberate, documented call on whether Device Admin is worth keeping at all
in its current (policy-free) form versus just removing it — not attempt to make it stronger than the
platform allows for this install model.

---

## How the existing systems actually interact (read this before scoping any phase)

- **`TimerService`** owns the ongoing foreground notification (`NOTIF_ID=1`, channel
  `scrollguard_channel`) and its own 1-second `Handler` tick loop, which calls
  `TimerState.tick()` **and** `updateNotification()` every second. It also runs its own
  `checkAccessibilityHealth()` every 5 ticks, which can post/cancel the alert notification
  (`ALERT_NOTIF_ID=2`, channel `scrollguard_alerts`).
- **`AccessibilityHealthAlert`** is a *second, independent* source of that same alert notification
  (same channel, same ID) — extracted specifically so a periodic WorkManager job
  (`AccessibilityHealthWorker`, implied by the doc comment) can post it even if `TimerService`'s
  whole process is dead. **This means two different code paths, on two different lifecycles, can
  post/cancel the same notification ID without necessarily knowing about each other's last known
  state** — a very plausible source of the stale/flapping notification behavior you described, and
  the first thing Phase 1 should trace concretely rather than assume.
- **`BlockerAccessibilityService`** independently runs its *own* 1-second tick loop
  (`parentalTickRunnable`) for the parental engine, separate from `TimerService`'s. It also holds the
  live Firestore listener for a paired child (see above), and owns the PiP/split-screen overlay
  backstop.
- **`SyncEngine`** feeds Room, which feeds `ParentalControlState`'s in-memory cache, which
  `BlockerAccessibilityService` reads for blocking decisions — this path is already designed to be
  one-way and offline-safe; don't let notification or reliability work leak writes back into it.
- **Net effect:** notifications, the two tick loops, and sync all currently assume the process/
  service stays alive continuously. Phase 2 (service reliability) and Phase 1 (notification
  correctness under restart/kill) are therefore **not independent** — a fix to one that doesn't
  account for the other will likely just move the bug. This is exactly the "don't make isolated
  fixes" concern you raised, and it's the reason Phase 1 is scoped below to include restart/kill
  behavior explicitly, not just notification content.

---

## Phase 1 — Full notification audit + fixes

**Inventory to verify (don't assume this list is complete — confirm via code, then confirm via
device):**
1. Ongoing status notification (`TimerService`, `NOTIF_ID=1`) — phase + remaining time, updated
   every second.
2. Accessibility-lost alert (`ALERT_NOTIF_ID=2`) — posted from **two** places:
   `TimerService.checkAccessibilityHealth()` and `AccessibilityHealthAlert` (used by a periodic
   WorkManager worker). Confirm the worker's actual schedule and whether the two paths can
   disagree about current health state.
3. Any notification related to parental-limit blocking specifically (the "you have X minutes
   left" wording you described) — confirm exactly which of the above it maps to, or whether it's a
   distinct path not yet located.

**Your ten questions, as the literal audit checklist — answer each with evidence, not assumption:**
- What notifications do we currently show? (enumerate exhaustively, including channels/IDs)
- Which are actually necessary vs. incidental?
- Which should be persistent (`setOngoing`) vs. dismissible?
- How long should each live, and does that match what `setOngoing`/`setAutoCancel`/priority
  actually produce today?
- What happens when the user manually clears a dismissible one — does anything assume it's still
  there?
- Which should update dynamically in place vs. being a fresh, replaceable one-shot?
- Are we recreating a "fresh" notification when we should be updating an existing one (or vice
  versa) — specifically check the two-source alert-notification overlap above for this.
- Which should survive `TimerService`/`BlockerAccessibilityService` restart or process death, and
  do they actually, today, or do they show stale content until the next tick?
- What belongs in the persistent status notification vs. a transient/one-shot one — is
  "remaining time" (which changes every second) even appropriate content for a notification at all
  on modern Android, given system trimming and update-throttling behavior on some OEMs?
- Reproduce the exact reported bug (dismiss the remaining-time notification, observe it return with
  a stale value) on a real device/emulator before fixing anything, and identify which of the two
  code paths (or a third one) is actually responsible.

**Scope of fix:** consolidate to a single, clearly-owned source of truth per notification (likely:
one shared object like `AccessibilityHealthAlert` for the alert, called from both `TimerService` and
the worker rather than each maintaining separate logic), correct any ongoing/dismissible mismatch,
and eliminate the stale-value bug at its actual root cause. Add tests/manual verification proving the
fix, not just a plausible-looking patch.

## Phase 2 — Service reliability / OEM battery optimization

- Audit `AccessibilityHealthWorker`'s current schedule/constraints and confirm it's the primary
  safety net it's designed to be.
- Add an explicit battery-optimization-exemption request/guidance step to the child-device setup
  flow (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is already a declared permission — confirm it's
  actually used in onboarding, not just declared).
- Research and document per-OEM quirks (Samsung, Xiaomi/MIUI, OnePlus/OxygenOS, Motorola) — most
  have a manufacturer-specific "autostart"/"protected apps"/"battery saver exception" screen beyond
  the standard Android one; where a reliable deep-link intent exists for each, wire it in with a
  graceful fallback (open general battery settings) where it doesn't. Verify these on real devices
  where possible — OEM behavior here is notoriously undocumented and version-dependent.
- Re-confirm, after these changes, whether the accessibility service and its live Firestore listener
  actually survive longer in practice — this directly informs the Phase 5 FCM decision above.

## Phase 3 — Tamper protection

- Document current Device Admin behavior precisely (already zero-policy) and make an explicit,
  recorded decision: keep it as a minimal speed-bump, remove it, or invest in something else —
  don't leave it in an ambiguous "sounds important, unclear if it does anything" state.
- Focus the real engineering effort on **detection and disclosure**: does the parent reliably learn
  when accessibility is disabled, the app is force-stopped, or the child device stops checking in
  (this overlaps directly with Phase 1's health-alert consolidation and the parental `status`
  fields already in Firestore)? This is the honest, buildable version of "tamper protection" for an
  app installed on an already-set-up device.
- Explicitly do not pursue Device Owner/factory-reset-provisioning mode unless the product direction
  changes to support a guided factory-reset child-device setup — flag that as a possible future
  product decision, not something to build now.

## Phase 4 — Jetpack Compose

Agreed with deferring: no functional problem today requires it, and rewriting working XML screens
for its own sake would be exactly the kind of change this project has consistently avoided. Scope it
narrowly when it actually happens: new screens (an analytics dashboard, richer charts) in Compose,
existing screens left alone, no wholesale migration. Nothing to plan further right now.

## Phase 5 — FCM real-time sync (revisit after Phase 2)

Re-open this only after Phase 2 ships and you have real data on service uptime. At that point, the
decision is squarely yours: link Blaze (near-certainly $0/month in practice, but a real billing
account on the project) for a Cloud Function, or accept the residual gap (live listener while the
service is alive + 15-minute `SyncWorker` fallback otherwise) as good enough for this MVP's scale.

---

## Next step

This is the plan — I haven't written any code against it. Tell me whether you want me to start
executing **Phase 1** directly in this session (I have the emulator and build tooling set up
already), or whether you'd rather I turn each phase into a standalone prompt file the way the prior
audits were done, so they can be run independently.
