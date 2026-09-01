# Google Play Compliance — Overview

This is the top-level compliance summary. For deep dives see:
`ACCESSIBILITY_SERVICE_COMPLIANCE.md`, `FAMILIES_AND_CHILD_SAFETY.md`, `DATA_SAFETY.md`,
`FIRESTORE_SECURITY.md`, `PERMISSIONS_AND_APIS.md`. Policy quotes/sources:
`PLAY_POLICY_RESEARCH_NOTES.md` (researched directly against live official Google pages this
session, not third-party summaries).

## Foreground Service (Phase 14)

**What exists**: one foreground service, `TimerService`, declared
`foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "App usage timer and
blocker"` (`AndroidManifest.xml:109-116`), with a correct API-level fallback chain in
`TimerService.kt:80-90` (specialUse on 34+, DATA_SYNC on 29-33, unset below that).

**Technical correctness**: verified correct implementation — matches the declared subtype,
includes the required property element, uses the permission (`FOREGROUND_SERVICE_SPECIAL_USE`).

**Play approval risk** (separated from technical correctness, per official guidance researched
this session): `specialUse` is officially framed as a last-resort catch-all, "reviewed when you
submit your app," with reviewers expecting "enough information to understand why a more specific
type doesn't apply." There is no standard Android foreground service type that fits "app usage
timer and blocker" — this is a genuine, disclosed review-risk area, not a violation. **Action**:
none required beyond what's already declared; be prepared for Play Console review to ask for
clarification.

## Device Admin / Uninstall Protection (Phase 15)

**What exists**: `AdminReceiver` + `device_admin_rules.xml` (empty `<uses-policies/>` — zero
`DevicePolicyManager` capabilities). Wired only to the personal Focus Timer's optional "Strict
Mode" toggle (`MainActivity.kt:271-315`). **Not connected to parental-control pairing at all** —
verified by grep, zero references to `AdminReceiver`/`DevicePolicyManager` in
`ParentalControlActivity.kt`.

**What it actually does**: nothing beyond the OS's own baseline behavior for any active Device
Admin — the user must deactivate admin status in system Settings before uninstalling. No policy
capability (force-lock, wipe, password reset) is ever requested or used.

**Play policy relevance**: the AccessibilityService policy contains an explicit carve-out allowing
"prevent disabling/uninstalling" *when authorized by a parent through a parental-control app" —
but ScrollGuard's AccessibilityService doesn't attempt to prevent uninstall at all, and its Device
Admin feature (which theoretically could, if extended) isn't even wired to the parental-control
feature. **This carve-out is not currently the operative one for anything this app does.**

**Product decision, explicitly not made unilaterally**: whether Device Admin should ever apply to
a paired child device is a real UX/support-burden tradeoff (forcing a system Device Admin prompt
during child setup adds friction and its own disclosure requirements) that belongs to the owner —
see `LEGAL_REVIEW_CHECKLIST.md` / product decisions.

## AccessibilityService
See `ACCESSIBILITY_SERVICE_COMPLIANCE.md` for the full audit. Summary: narrowly-scoped, not
declared as a tool, no data leaves the device, one open item (whether the existing in-app
disclosure dialog satisfies the *current* affirmative-consent bar — a Play Console review
question, not a code gap).

## Families / Target Audience
See `FAMILIES_AND_CHILD_SAFETY.md` and `docs/GOOGLE_PLAY_TARGET_AUDIENCE.md`. Classification is an
owner/Play Console decision.

## Data Safety / Account Deletion / Camera
See `DATA_SAFETY.md`, `ACCOUNT_DELETION.md` (account deletion is now implemented, in-app and
external — see that doc), and the Camera/QR section below.

## Camera / QR Privacy (Phase 16)
Verified: `zxing-android-embedded`'s `ScanContract` is used as-is in `ParentalControlActivity.kt`
— no wrapper code captures, saves, or uploads camera frames. The in-app rationale string
("Nothing is recorded or stored — it's only used to read the code on screen") is **technically
accurate**, verified against the actual integration code, not assumed true because the library
generally works that way.

## Monetization (Phase 9 of the original audit prompt)
**Explicit owner decision, recorded here**: ScrollGuard is free, with no monetization planned —
the owner has stated there is currently no way to earn from the app and does not want to enable
any billing (this also drove the decision not to enable Firebase's Blaze plan — see
`DATA_RETENTION_AND_DELETION.md`'s TTL note). No billing code exists; nothing in the architecture
would block adding it later if that ever changes, but nothing further should be built toward it
now.
