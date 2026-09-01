# Firestore Security Audit

Rule-by-rule "who can do what and why," verified against the **current** `firestore.rules` (not a
historical snapshot), and against a passing rules-emulator test suite
(`firestore-tests/`, 46/46 passing as of this session).

## `pairing/{code}`
| Op | Who | Why |
|---|---|---|
| `create` | The child who owns the referenced family, and only in the unclaimed shape (`consumed=false`, `parentUid=null`, `expiresAt` in the future) | Prevents forging a code pointing at someone else's family |
| `get` | Any authenticated user | The parent must already know the exact code (from the child's screen/QR) |
| `list` | **Nobody** | Deliberately ungranted — the pairing collection can never be enumerated/harvested |
| `update` | The claiming parent, restricted to exactly `consumed` + `parentUid`, only while unconsumed and unexpired | Prevents smuggling other field changes into a claim |
| `delete` | The claiming parent, anyone once expired, or the owning child | Lets `PairingManager` clean up a still-valid code during regenerate/unpair without waiting for expiry |

**Guessing/rate-limiting**: a 6-character code from a 32-character alphabet is ~2<sup>30</sup>
combinations with a 5-minute TTL and no `list` access — brute-forcing requires ~2<sup>30</sup>
individual `get` calls within 5 minutes, which Firestore's own request-rate characteristics make
impractical without triggering abuse detection. **Assessed as low real-world risk** given the
`list`-block already closes the practical harvesting vector; App Check or a Cloud Function rate
limiter would add real protection but isn't justified by the current threat model, especially
given the owner's explicit no-billing constraint (Cloud Functions require Blaze).

## `families/{familyId}`
| Op | Who | Why |
|---|---|---|
| `create` | The child, with `childUid == request.auth.uid` and `parentUid == null` | A device can never self-assign a parent |
| `read` | Parent or child of that family | — |
| `update` (Case 1) | The claiming parent, exactly once, only `parentUid` | One-time bind |
| `update` (Case 2) | The child, only `childDeviceName` and/or `currentPairingCode` | Bookkeeping only — closed this session to also cover the cleanup pointer field |
| `delete` | **Parent only** | Closed this session — previously any family member could delete, letting a child destroy the parent's own record unilaterally with zero consent |

## `families/{familyId}/config/current`
| Op | Who | Why |
|---|---|---|
| `create` | The child, only if `enabled=false` **and `configVersion=0`** | The version constraint was added this session — previously a child could self-write an arbitrary starting `configVersion` at bootstrap |
| `update` | Parent only | The child cannot modify its own restrictions |
| `delete` | Parent only | Was previously missing entirely — every unpair silently failed to actually delete this document until fixed this session |
| `apps/{packageName}` `write` | Parent only | Per-app restriction data is parent-owned |

## `families/{familyId}/status/current`, `catalog/current`
Child-owned (`write` restricted to `isChildOfFamily`); parent read-only. Correct — these are
child-reported telemetry, not parent-set configuration.

## `families/{familyId}/requests/{requestId}`
| Op | Who | Why |
|---|---|---|
| `create` | The child, **only if `status='PENDING'` and `type` is `TIME` or `UNPAIR`** | The status/type constraint was added this session — previously a child could self-forge an already-`APPROVED` request. Low real-world severity even before the fix, since the actual allowance data lives in `config/current` (parent-only regardless), but closed anyway |
| `update` | Parent only | Only the parent may approve/deny |
| `delete` | Parent (any status), or the child but **only once resolved** (`status != 'PENDING'`) | Lets both sides clean up resolved requests without letting a child withdraw/hide one still awaiting a decision |

## Cross-cutting checks performed this session (fresh adversarial pass, not assumed-correct)
- Confirmed a child cannot reach an "effective unpair" by corrupting `config/current` or
  `status/current` instead of deleting the family doc directly — those paths are locked to
  parent-only writes (`config/current`) or don't affect pairing state at all (`status/current`).
- Confirmed via the emulator test suite that every one of the above holes, once closed, actually
  stays closed (`firestore-tests/test/rules.test.js` — 46 passing cases spanning valid parent,
  unrelated parent, the child, unauthenticated access, and each specific "malicious child"/"fixed
  hole" scenario).
- Confirmed the UI doesn't present an affordance the backend no longer allows: the child's
  "Unpair" button now reads "Request to Unpair" and goes through the approval flow — it does not
  claim a capability the rules have since revoked.

## Not implemented, and why
- **App Check**: not integrated. Would raise the bar against non-app clients hitting Firestore
  directly, but the current rule set already defends against every specific threat identified
  (enumeration, forgery, cross-family access, family hijack) at the rules layer itself, which is
  the correct first line of defense regardless of App Check. Worth revisiting if real abuse is
  ever observed, not preemptively.
- **Cloud Functions-based rate limiting on pairing codes**: requires the Blaze plan, which the
  owner has explicitly declined given no monetization path exists. The `list`-block + 5-minute TTL
  + code-space size is judged adequate for the current threat model.
- **Rules-emulator CI integration**: the test suite exists and passes, but isn't wired into
  `.github/workflows/android-ci.yml` — it only ran manually this session (requires a JDK for the
  Firestore emulator, which the CI runner would need setting up). Worth adding as a follow-up.
