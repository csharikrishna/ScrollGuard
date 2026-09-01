# ScrollGuard — Parental Control MVP
### Definitive Implementation Spec & Agent Prompt (v2 — architecturally corrected)

> **Mission:** Extend the existing, production-audited ScrollGuard Android app with a focused
> **Parent → Firebase → Child** parental-control feature that reliably synchronizes app
> restrictions and daily time limits, while the child device **enforces locally and offline**.
>
> This is an MVP, not a platform. Keep it small. Do not regress existing local blocking.

This document supersedes the earlier `FuturePlan.txt`. It restates the four solved traps and
adds every additional architectural issue found in review, with the definitive solution for each.
An implementing agent should treat **Part A (Invariants)** as non-negotiable and everything else
as the concrete design that satisfies those invariants.

---

## PART A — NON-NEGOTIABLE INVARIANTS

These are the rules the implementation may never violate. If a design decision conflicts with one
of these, the design decision is wrong.

1. **Enforcement is local-only.** The blocking decision in `BlockerAccessibilityService` reads an
   in-memory snapshot backed by Room. It performs **zero** network/Firebase calls, ever.
2. **Offline continues working.** With no internet, the child keeps enforcing the *last synchronized*
   configuration indefinitely. Losing the cloud never disables restrictions.
3. **Config flows down, status flows up, enforcement reads local.** (See Part C.) No device writes a
   field it does not own.
4. **Existing functionality is preserved.** `TimerState`, `TimerService`, `BlockerAccessibilityService`,
   `BlockActivity`, `PinActivity`, app-selection, analytics, persistence, and release config keep working.
   Local/offline usage requires **no login**.
5. **Parental-control state is separate from ScrollGuard personal-timer state.** Different models, different
   persistence, no shared mutation.
6. **Security is the Firebase boundary.** Authorization = Firebase Auth + Firestore Security Rules + App Check.
   The Firebase client config is **not** a secret; knowing a child ID must never be enough to control a device.
7. **No per-second cloud traffic.** Time is consumed and counted locally. Cloud writes happen on
   *configuration change* and *throttled status reports* only.
8. **Fail safe.** Every remote failure path falls back to the last valid local config. Never silently
   disable restrictions because the cloud is unreachable.

---

## PART B — RESOLVED ARCHITECTURAL ISSUE REGISTER

### B.1 The four original traps (confirmed solutions)

| # | Trap | Definitive solution |
|---|------|--------------------|
| 1 | Network calls inside the Accessibility Service | Strict one-way cache pipeline: **Firestore → SyncEngine → Room → in-memory cache**. The service reads memory in <1 ms with no I/O in the event callback. |
| 2 | Double counting / accessibility-event floods | Replace event-derived counting with a dedicated **1-second monotonic tick loop** keyed on `SystemClock.elapsedRealtime()`, tracking the single active restricted foreground package. Events only *select* the active package; they never *increment* time. |
| 3 | Conflating focus timers with daily quotas | `TimerState.kt` stays an independent focus-cycle engine. A new, isolated `ParentalControlState` + Room repository owns daily allowances/consumption. No shared state. |
| 4 | Permissive Firebase rules | Production Firestore rules with family-bounded, role-partitioned, field-level permissions and transactional single-use pairing tokens. No `allow read, write: if true`. |

### B.2 Additional issues found in review (must also be implemented)

**A — Sync is not truly unidirectional; partition field ownership.**
The parent bar ("32 min remaining") requires the child to report consumption upward. So config flows
**down** and status flows **up** — over *disjoint* fields. Without partitioning you get write-echo loops
(a device's own write re-triggers its listener) and clobbered updates. Enforce ownership in Security Rules
(Part E). Enforcement itself still reads only local state, so invariant #1 holds.

**B — Do not store `remaining`; derive it.**
Storing both `allowedMinutes` and `remainingMinutes` creates two sources of truth that drift. Store
`allowanceSeconds` (parent-owned) and `consumedSeconds` + `consumedEpochDay` (child-owned).
`remaining = max(0, allowanceSeconds − consumedSeconds)`. Never negative. **Changing the allowance never
resets consumed** (original spec §12). If allowance drops below consumed, remaining clamps to 0.

**C — Fast propagation without a 24/7 listener → use FCM data messages.**
A persistent Firestore snapshot listener living in the child process 24/7 drains battery and costs reads.
Preferred MVP: **parent write → FCM data push to child → child performs one read → writes Room → refreshes
memory cache.** Fallbacks that must also exist: sync-on-app-open and a periodic **WorkManager** sync
(≈15 min, network-constrained) as a safety net. Do **not** host a live listener inside the Accessibility Service.

**D — Firestore already provides the offline write queue; don't hand-roll one.**
Enable Firestore offline persistence. Parent writes made offline queue automatically and flush on reconnect
with built-in backoff. The "SyncEngine" is a thin orchestrator (Room mirroring + FCM handling + WorkManager
scheduling), **not** a reimplementation of an outbox/retry system. This also removes the "infinite retry loop"
risk.

**E — Reboot/process-death time integrity.**
`elapsedRealtime()` resets to 0 at boot. **Never persist a session-start `elapsedRealtime` across a reboot.**
Persist `consumedSeconds` *incrementally* per tick to Room (batched write, e.g. flush every ~15 s and on
lifecycle stop). On boot / process start, reset the in-memory session anchor before counting resumes.

**F — Timezone / DST / day-boundary correctness.**
Key the accounting day on the device's current-zone `LocalDate.toEpochDay()`, stored as `consumedEpochDay`.
On a new epoch-day (or detected tamper), reset `consumedSeconds` to 0. DST is a non-issue because we key on
calendar day, not on a fixed 24 h window. Cross-timezone travel can shorten/lengthen one local day — accepted
for MVP and documented. **Clock-tamper detection:** when online, anchor the day using a Firestore
`serverTimestamp`; a large backward wall-clock jump vs. monotonic time flags tampering and does **not** grant
extra time (treat as same-day continuation).

**G — Package visibility (Android 11+).**
Enumerate launchable apps via a `<queries>` element with a `LAUNCHER` intent filter. **Avoid
`QUERY_ALL_PACKAGES`** (Play-policy risk). Exclude system/non-launchable packages and ScrollGuard itself.
Handle apps installed/removed after the catalog was synced (listen for package add/remove, re-sync catalog).

**H — Cross-device icons.**
The parent phone usually does **not** have the child's apps installed, so it cannot render their real icons.
MVP: sync `{packageName, label}` only and render generic/monogram icons on the parent. Optionally sync a small
(<8 KB) compressed PNG per app if real icons are desired — never large bitmaps. Documented UX limitation.

**I — Block-loop / self-block safety.**
The service must never block: ScrollGuard itself (especially `PinActivity`/`BlockActivity`), the launcher,
System UI, or the Settings screens needed to grant permissions. Debounce window-state events. The block screen
must **not** count time and must **not** re-trigger itself.

**J — Anti-uninstall (Device Admin) is optional hardening, not a guarantee.**
Device Admin uninstall protection is heavily restricted by Play policy and bypassable (safe mode, disabling
accessibility). Keep it **optional**; core enforcement must not depend on it. Ship an "accessibility disabled"
health signal (parent alert) instead of relying on it. Listed honestly under Limitations.

**K — Security hardening: field-level rules + App Check + pairing brute-force.**
Rules validate `request.resource.data.diff(resource.data).affectedKeys()` so a child cannot write parent
fields and vice versa (prevents privilege elevation, invariant #6). Pairing codes are single-use, short-TTL,
transactionally consumed, with enough entropy (≥8 chars base32, or 6 digits **plus** App Check + rate limiting).
Enable **Firebase App Check** so only genuine app instances reach the backend. Bind the child's Auth UID to the
`childId` at pairing; rules check UID membership in the family.

**L — Upward consumption-report cadence.**
Report `consumedSeconds` on: switch away from a restricted app, delta ≥ 60 s, going to background, and a
periodic cap (every 2–5 min while a restricted app is active). This keeps the parent bar fresh without
per-second writes (invariant #7).

**M — Enforcement source of truth = in-memory cache hydrated from Room at service start.**
On service (re)start, hydrate memory from Room **before** enforcing. The event callback only reads memory.
Sync updates Room then atomically swaps/refreshes the memory snapshot.

**N — "OFF" suspends, never deletes.**
The global parental-control toggle OFF suspends enforcement but preserves app selection and allowances so ON
restores the prior state. Kept fully separate from ScrollGuard personal-timer/session state.

**O — Grace rounding.**
Apply a small grace (e.g. block at `consumed ≥ allowance` evaluated on whole-second ticks, with a ~2 s grace)
so a child is never blocked on a sub-second overrun. The tick loop and the block threshold must use the same unit.

---

## PART C — CANONICAL DATA FLOW

```
        PARENT DEVICE                    FIREBASE                     CHILD DEVICE
  ┌────────────────────┐        ┌────────────────────┐        ┌────────────────────────┐
  │ UI: pick apps,     │  down  │ Firestore          │  push  │ FCM receiver           │
  │ set allowance,     │ ─────► │  (config, parent-  │ ─────► │   → one read           │
  │ ON/OFF, +/−        │        │   owned fields)    │        │   → SyncEngine         │
  └────────────────────┘        │                    │        │        ↓               │
            ▲                    │  (status, child-   │        │   Room (durable)       │
            │  up (throttled)    │   owned fields)    │  up    │        ↓               │
  ┌────────────────────┐ ◄───── │  catalog, consumed,│ ◄───── │  In-memory cache       │
  │ UI: remaining bar, │        │  lastSeen, state   │        │        ↓               │
  │ connection status  │        └────────────────────┘        │  AccessibilityService  │
  └────────────────────┘                                       │        ↓               │
                                                               │  Block enforcement     │
                                                               └────────────────────────┘

ENFORCEMENT PATH (no network):  Room → memory → AccessibilityService → block.
```

- **Down (parent-owned):** `enabled`, per-app `enabled`, `allowanceSeconds`, app selection, `configVersion`.
- **Up (child-owned):** app catalog, `consumedSeconds`, `consumedEpochDay`, `lastSeen`, device/sync state, health.
- **Enforcement:** reads memory/Room only. Cloud is never in the blocking decision.

---

## PART D — FIRESTORE DATA MODEL (corrected)

Field ownership is annotated: **[P]** parent-writable, **[C]** child-writable, **[S]** server/rules-controlled.

```
families/{familyId}
    parentUid            [S]   // set at creation, immutable
    childUid             [S]   // bound at pairing, immutable
    childDeviceName      [C]
    createdAt            [S]

    config/current                      // parent → child
        enabled          [P]   bool     // global ON/OFF (suspend, don't delete)
        configVersion    [P/S] int       // monotonic; bump on every parent change
        updatedAt        [P/S] serverTimestamp
        apps/{packageName}
            enabled          [P] bool
            label            [C] string  // from child catalog; parent may cache
            allowanceSeconds [P] int     // 0..MAX (e.g. 24h)

    status/current                      // child → parent
        consumedByPackage/{packageName}
            consumedSeconds  [C] int     // resets on new consumedEpochDay
        consumedEpochDay     [C] int
        lastSeen             [C] serverTimestamp
        syncState            [C] enum(SYNCED|OFFLINE|STALE)
        accessibilityHealthy [C] bool

    catalog/current                     // child → parent (launchable apps)
        apps: [ {packageName, label} ]  [C]   // sanitized, no system apps

pairing/{code}                          // ephemeral, single-use
    familyId             [S]
    parentUid            [S]
    createdAt            [S] serverTimestamp
    expiresAt            [S] timestamp  // short TTL, e.g. 5 min
    consumed             [S] bool       // flipped in a transaction on claim
```

**Notes**
- `remaining` is **never stored** — computed as `max(0, allowanceSeconds − consumedSeconds)` (Issue B).
- `consumedByPackage` is written throttled (Issue L), not per second.
- The child's Auth UID must equal `families/{familyId}.childUid` for it to touch `status`/`catalog`.
- Keep the tree shallow to minimize reads; the child listens to / reads `config/current` only.

---

## PART E — AUTH, PAIRING & SECURITY RULES

### E.1 Identity
- **Local-only users:** no auth required (invariant #4).
- **Remote control:** both devices use Firebase Auth. Anonymous auth is acceptable for MVP **provided**
  the UID is bound into the family at pairing and App Check is enabled. (A parent email/password account is a
  fine upgrade path but not mandatory for MVP.)
- Distinguish clearly: **parent identity** (`parentUid`), **child device identity** (`childUid`),
  **pairing relationship** (`familyId`).

### E.2 Pairing flow
**Child:** Parental Control → "Set up as Child" → app creates a `families/{familyId}` (parent slot empty) and
a `pairing/{code}` doc (short TTL) → displays code + QR. Child shows "Waiting to be paired".

**Parent:** Parental Control → "Add Child" → scan QR / enter code → a **transaction** validates the code
(exists, not expired, not consumed), sets `families.parentUid`, marks `pairing.consumed = true`, and deletes/
expires the code. Parent now sees the child.

**Requirements:** codes expire; single-use (transactional consume, Issue K); no silent hijack; unpairing
supported (clears family + rotates child auth); stale pairings invalidatable. No multi-parent/multi-child.

### E.3 Security Rules (must be authored and emulator-tested)
Rules must enforce, at minimum:

- `config/current` (and `apps/*`): **read** by family parent or child; **write** only by `parentUid`, and
  only to parent-owned fields (`affectedKeys` ⊆ parent set). Child writes here → denied.
- `status/*` and `catalog/*`: **read** by family parent or child; **write** only by `childUid`, restricted to
  child-owned fields. Parent writes here → denied.
- `families/{familyId}`: readable only by its `parentUid`/`childUid`; `parentUid`/`childUid` immutable after set.
- `pairing/{code}`: creatable by an authenticated child for its own family; consumable only via the claim
  transaction; never world-readable by code guessing beyond a single claim; expired/consumed → denied.
- No unauthenticated access anywhere. App Check enforced on all reads/writes.
- A child can never assume parent role and vice versa (validated by `affectedKeys` diffs).

Deliver the rules file plus an emulator test suite (Part H) proving each of the above allow/deny cases.

---

## PART F — CHILD ENFORCEMENT & TIME ACCOUNTING

**Persistence:** dedicated Room entities/DAO for `ParentalControlConfig`, per-app allowance/enabled, and
per-app `consumedSeconds`+`consumedEpochDay`. Separate from existing ScrollGuard persistence.

**Memory cache (Issue M):** a single source object the service reads. Hydrated from Room at
service start; refreshed atomically after each successful sync.

**Tick loop (Trap 2, Issues E/O):**
1. On window-state-changed, resolve foreground package. Debounce; ignore excluded packages (Issue I).
2. If the foreground app is restricted, enabled globally + per-app, and `remaining > 0`, mark it the active
   package and run a 1 s tick incrementing `consumedSeconds` via `elapsedRealtime()` deltas (guarded against
   reboot resets). Batch-persist to Room (~15 s and on stop).
3. When `consumed ≥ allowance` (with grace), launch the existing block flow. The block screen does not count
   and does not re-trigger.
4. On new `consumedEpochDay`, reset consumed to 0 before counting.

**Reboot / process death (Issue E):** on `BOOT_COMPLETED` and service create, reset session anchor, rehydrate
from Room, resume enforcement. Restrictions survive reboot and process death because they live in Room.

**Offline (invariants #2/#8):** no connectivity → keep enforcing memory/Room config; keep counting; queue
`status` writes (Firestore handles this). On reconnect, flush status, re-read config, refresh memory.

---

## PART G — PARENT UI (use existing ScrollGuard visual language)

Sections and states to implement (do not overdesign):
- **Child card:** device name + connection status (`Connected` / `Last seen …` / `Syncing` / `Offline`).
- **Global Restrictions ON/OFF** toggle (suspends, never deletes — Issue N).
- **Per restricted app:** label + (generic) icon, remaining bar (`remaining/allowance`), `−` / `+` controls.
- **Manage Apps** → picker sourced from the synced child catalog (Issue H), reusing existing app-picker logic.
- `+`/`−` adjust `allowanceSeconds` with sensible increments; clamp `[0, MAX]`; no overflow; immediate optimistic
  UI, persisted + synced; offline changes queue and reconcile (Issue D).

**Every screen handles:** Loading · No child connected (setup instructions) · Child connected · Offline ·
Syncing · Error (cause + action) · No restricted apps (empty state → Manage Apps). Surface only meaningful
connection states; don't spam transient connectivity changes.

---

## PART H — TESTING

**Unit / instrumentation**
- Pairing: valid, expired, invalid, duplicate/again, unpair, unauthorized claim.
- Time: remaining math, midnight rollover, reboot, process death, timezone change, no double counting,
  allowance-change-doesn't-reset-consumed, clock-tamper does not grant time.
- Restrictions: add/remove app, enable/disable, allowance change, `+`/`−`, OFF preserves config.
- Offline: child offline continuity, parent offline queue, reconnect reconcile, stale config handling.
- Enforcement chain: `remote config → Room → memory → AccessibilityService → block`, with cloud never in the
  blocking decision.

**Firestore rules (emulator)** — allow/deny for: valid parent, unrelated parent, the child, unauthenticated,
malicious child attempting parent-field writes, App Check absent, expired/reused pairing code.

**2-device physical matrix** (parent + child):
1. Pair devices. 2. Parent selects app → child receives catalog-based config. 3. Parent ON → child restricts.
4. Parent `+`/`−` → child updates allowance (no consumed reset). 5. **Disconnect child internet** → child keeps
enforcing. 6. Reconnect → reconcile. 7. Kill/restart child process → restrictions remain. 8. Reboot child →
restrictions remain. 9. Parent OFF → child unrestricted (config preserved). 10. Unpair → child knows it's
unpaired. Test the **release** build where practical.

---

## PART I — EXPLICITLY OUT OF SCOPE (do not build)

Chat, location tracking, browsing history, screenshots, social features, AI features, advanced analytics,
multi-parent/multi-child administration, complex dashboards, subscriptions, ads, extra backend services.
Keep the MVP focused.

---

## PART J — FINAL AUDIT & VERIFICATION

**Audit grep** (investigate every hit): `TODO FIXME HACK`, `catch (Exception)`, `catch (Throwable)`,
`printStackTrace`, `Log.d`, `Log.v`, `!!`, `@SuppressLint`, hardcoded secrets, `allow read, write`,
`QUERY_ALL_PACKAGES`, any Firebase call reachable from `BlockerAccessibilityService`.

**Commands**
```bash
./gradlew clean
./gradlew test
./gradlew lintDebug lintRelease
./gradlew assembleDebug assembleRelease
firebase emulators:exec "npm test"   # or your rules-test runner
```

**Production checklist:** no compiler/lint errors · tests pass · rules tested, no holes · App Check on ·
no cloud dependency in enforcement · no silent sync failures · no race conditions · no duplicate time
accounting · no fake-functional controls · no misleading connection status · no secrets committed ·
no per-second writes · no regressions in existing ScrollGuard functionality.

---

## FINAL REPORT TEMPLATE (fill after implementation)

- **Implemented** — every MVP feature actually completed.
- **Architecture** — Parent · Firebase · Child · local persistence · sync (down/up + FCM) · enforcement.
- **Security** — Auth, pairing (transactional, single-use), Security Rules (field-level), App Check.
- **Offline behavior** — exactly what continues without internet.
- **Tests** — exact commands + real-device matrix results.
- **Additional issues found** — anything beyond this spec.
- **Remaining limitations** — honest Android/Firebase/platform notes, including at least:
  Device Admin anti-uninstall is bypassable and Play-policy-sensitive (Issue J); cross-device icons are
  generic unless synced (Issue H); cross-timezone travel can shorten/lengthen one local accounting day (Issue F);
  anonymous auth ties the child to app-data persistence (clearing data forces re-pair, Issue K).
- **Production verdict** — exactly one of:
  **READY FOR MVP TESTING** / **NOT READY FOR MVP TESTING**

Do not declare readiness until parent↔child sync is verified on real devices **and** Firebase Security Rules
are emulator-tested. Goal: a small, reliable parental-control MVP — not a large platform.
```
