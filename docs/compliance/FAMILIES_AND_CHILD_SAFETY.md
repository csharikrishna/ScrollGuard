# Families & Child Safety

## Parental-control authorization model (Phase 12)

**Parent → Family → Child** — verified against both UI and Firestore rules (a UI restriction alone
is never trusted as sufficient; see `FIRESTORE_SECURITY.md` for the rules-level verification).

| Question | Answer | Evidence |
|---|---|---|
| Who can create the family? | The child device, during initial setup | `PairingManager.generatePairingCode()`; rules require `childUid == request.auth.uid` |
| Who can pair a child? | The parent, by claiming a pairing code | `PairingManager.claimPairingCode()`, transactional |
| Who can modify restrictions? | Parent only | `firestore.rules` — `config/current` update/write is parent-only everywhere |
| Who can unpair? | Parent, always self-service. Child, only via parent-approved request (except the disconnected-session repair screen) | This session's fix — see `FIRESTORE_SECURITY.md` |
| Who can delete the family doc? | Parent only | Closed this session |
| Are child-side destructive actions protected? | Yes, for the one that mattered (unpair). Force-stop/uninstall/clear-app-data remain unprotected — see below | — |
| Is parent authorization required where appropriate? | Yes for all configuration changes; the one gap (unauthorized unpair) is closed | — |
| Does the child understand the device is parent-managed? | The child-status screen shows restriction state and a device name; there's no explicit "this device is supervised by [parent]" framing beyond that | Worth a UX review, not a security issue |

## Known, unfixable platform limitation
A child with physical access to their own device can always force-stop the app, disable the
Accessibility Service, or clear app data/uninstall — no non-Device-Owner Android app can prevent
this. This is **not a ScrollGuard-specific bug**; it applies to every consumer parental-control
app that isn't enrolled via Android's Device Owner/MDM provisioning (a much larger UX and support
undertaking, and one this codebase's Device Admin implementation deliberately does not attempt —
see `GOOGLE_PLAY_COMPLIANCE.md`'s Device Admin section). Mitigation in place: the parent is told
if the Accessibility Service goes unhealthy (`accessibilityHealthy` status field, wired to the
real value this session), so tampering is visible rather than silent — it just can't be prevented.

## Target audience considerations (see also `docs/GOOGLE_PLAY_TARGET_AUDIENCE.md`)
ScrollGuard has two distinct usage modes:
1. **Personal Focus Timer** — a general self-discipline tool, adult or teen self-directed, no
   parent/child relationship involved at all.
2. **Parental Control** — explicitly parent-configured: the parent creates their own account
   (email/password), pairs with a child device, and sets restrictions. The child does not sign up,
   does not enter identifying information by choice (the one editable field, device name, is a
   device label the *parent* would typically see and could in principle be changed to contain a
   name — see below), and does not make any of the actual decisions the feature exists to enforce.

**This matters for Families Policy classification**: per the official policy language researched
this session, target audience is about who the app is *designed for and marketed to*, not merely
whether a child's device is somewhere in the data flow. A parent-configured supervision tool is
a materially different case from an app designed for children to use directly. **This is still an
OWNER/PLAY CONSOLE decision, not something this audit resolves** — see
`docs/GOOGLE_PLAY_TARGET_AUDIENCE.md`.

## Specific child-data caution
`childDeviceName` defaults to `"$MANUFACTURER $MODEL"` but is user-editable
(`ParentalControlActivity.kt` device-name update path). Nothing prevents someone from changing it
to contain a child's real name. This is a real, if minor, technical fact worth reflecting in the
Privacy Policy (a free-text field a family member controls could incidentally contain identifying
information) — not something to "fix" by removing the editability (it's a legitimate feature: a
parent should be able to label which of their devices is which).

## Mixed-audience-specific requirements, IF that classification applies
Per the policy research (see `PLAY_POLICY_RESEARCH_NOTES.md`): a mixed-audience declaration would
require a neutral age screen, restrict ads shown to children to Families-certified SDKs (moot here
— no ads/ad SDK exists), and prohibit transmitting AAID/IMEI/IMSI/MAC/SSID/BSSID/SIM-serial/build-
serial from children or users of unknown age. **Already verified**: none of those identifiers are
collected from any device in this app (see `DATA_INVENTORY.md`), and AAID collection is explicitly
disabled. So even if a mixed-audience classification is chosen, this specific requirement is
already satisfied today — the outstanding item is the age-screen UX, which does not exist and
would need building if that classification path is chosen.
