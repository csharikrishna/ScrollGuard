# Data Retention & Deletion Lifecycle

Traced directly against code — not assumed. "Firebase Auth deletion automatically deletes
Firestore data" is **false** and was explicitly not assumed; see the Account Deletion flow below,
which deletes Firestore data *before* the Auth identity specifically because of this.

## Account creation
**Parent**: `ParentalAuthManager.createAccount()` creates a Firebase Auth user only — no
Firestore document is created at this point (a `families/{id}` doc only exists once the parent
pairs or a child creates one). **Child**: `signInAnonymously()` creates an anonymous Firebase Auth
user; `PairingManager.generatePairingCode()` then creates the `families/{id}` doc, its
`config/current` and `status/current` subdocuments, and a `pairing/{code}` doc.

## Pairing
Creates: `families/{id}` (childUid, parentUid=null, childDeviceName), `config/current` (enabled=false),
`status/current`, `pairing/{code}` (5-min TTL by design), and — once claimed —
`families/{id}.parentUid` is set via a tightly-scoped transaction (`PairingManager.claimPairingCode`).

## Unpairing (`PairingManager.unpair()`)
Deletes, in order: the tracked `currentPairingCode` doc (if any), `config/current/apps/*`,
`config/current`, `status/current`, `catalog/current`, all of `requests/*`, and finally the
`families/{id}` doc itself. All of this is now actually enforced by matching `firestore.rules`
delete permissions (verified via the rules-emulator test suite, `firestore-tests/`).

**Who can trigger it**: the parent, always, self-service. The child, only via a parent-approved
`requests/{id}` UNPAIR request (this session's fix — see `FIRESTORE_SECURITY.md` for why the
previous unrestricted child-delete was closed) — except on the "connection lost" repair screen,
where the child's own local session is already unusable and an immediate local-only recovery is
appropriate instead.

## Parent removes child / parent deletes account
As of this session, both paths converge on the same flow: `ParentalControlActivity.performUnpair()`
for the former, and the new `performDeleteAccount()` for the latter — which does the same
Firestore cascade via `PairingManager.unpair()`, then additionally calls
`ParentalAuthManager.deleteCurrentUser()` to remove the Firebase Auth identity itself. See
`ACCOUNT_DELETION.md`.

## Child app uninstall (without unpairing first)
**What remains in Firebase**: everything — the family doc, config, status, catalog, and any
pending requests are never touched by an uninstall (there is no server-side hook for "an app was
uninstalled"). This is a genuine, unavoidable residual-data case for any client-only-cleanup
architecture. Mitigations in place: (1) the parent can still unpair from their own device even if
the child device is gone, running the exact same full cascade; (2) a pairing *code* that was never
claimed and never explicitly cleaned up would ideally expire via a Firestore TTL policy on
`expiresAt` — **this requires the Firebase project to be on the Blaze (pay-as-you-go) plan, which
the owner has explicitly decided not to enable** (no monetization path exists for this app). The
TTL config is written and ready (`firestore.indexes.json`) but not deployed. In practice this
residual case is a handful of small, inert documents — not an ongoing privacy exposure, since
nothing reads or acts on an abandoned pairing code after its 5-minute logical expiry is checked
client-side on every claim attempt.

## Firestore cleanup — per-document summary

| Document | Deleted on unpair? | Deleted on account deletion? | Orphan risk |
|---|---|---|---|
| `families/{id}` | Yes | Yes (same cascade) | None once unpair/delete runs |
| `families/{id}/config/current` (+ `apps/*`) | Yes | Yes | None (rules gap that silently blocked this delete was fixed this session) |
| `families/{id}/status/current` | Yes | Yes | None |
| `families/{id}/catalog/current` | Yes | Yes | None |
| `families/{id}/requests/*` | Yes | Yes | None (resolved requests are also now self-cleaned by whichever side consumes them, before any unpair even happens) |
| `pairing/{code}` | Yes, via tracked `currentPairingCode` pointer | Yes, same path | Only if abandoned pre-claim and pre-unpair — see TTL note above |
| Firebase Auth user (parent) | No (unpair alone doesn't delete the account) | Yes | None once deletion flow completes |
| Firebase Auth user (child, anonymous) | No — the anonymous identity itself is never deleted by unpair | Not applicable (child doesn't have a deletion flow — there's no child-facing "delete my account" concept since the child never created an account) | A stale anonymous Firebase Auth user persists in the project after unpair; low-risk (no PII on an anonymous identity by itself), but a real minor accumulation over time worth noting for a future cleanup pass |
| Local Room data (all tables) | Cleared for parental tables via `clearLocalParentalState()` | Same | The personal Focus Timer's own local tables (`UsageRecord`, `AppEntry`, `BlockEvent`) are unaffected by any parental-control action — by design, they're a separate feature's data |

## Retention outside explicit deletion
No data has a defined automatic retention/expiry window beyond the pairing code's intended 5
minutes (enforced logically, not yet by an active TTL policy — see above). Crashlytics diagnostic
data retention follows Firebase's own default retention policy, not a setting this app configures.
