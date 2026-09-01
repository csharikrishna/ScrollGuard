# Privacy Policy Requirements

This document specifies exactly what ScrollGuard's future Privacy Policy needs to cover, based on
the app's *actual, verified* behavior — not invented legal wording. **This is not a Privacy
Policy.** The owner should draft the real policy from this factual basis, and have it reviewed by
a qualified privacy professional before publishing — sections that specifically need that review
are marked below.

## 1. Application purpose
State plainly: ScrollGuard has two modes — a personal self-discipline "Focus Timer" (blocks
selected apps for a configurable period, entirely local/on-device, no account needed) and an
optional "Parental Control" mode (a parent's device supervises a child's device via a paired
account).

## 2. Parent account data
- Email address and password (Firebase Authentication).
- No other profile information is collected at account creation.
- **[Legal review]**: confirm whether email alone triggers any additional disclosure obligation
  in the target jurisdictions.

## 3. Child-device data
- An anonymous device identity (no email, name, or credentials).
- A device name field, defaulting to `"$MANUFACTURER $MODEL"` but editable — disclose that this
  field is user-controlled and could incidentally contain identifying text.
- Per-app restriction settings and usage/consumption data set by the parent and reported by the
  child device.
- The child's installed-app catalog (package names + labels), shared with the parent for
  restriction selection.
- **[Legal review]**: whether this constitutes "personal information from a child" under
  applicable child-privacy law, and what that requires.

## 4. Firebase Auth
Used for both parent (email/password) and child (anonymous) identity. State that Firebase
(Google) is the processor. Link to Google's own privacy policy for their handling of this data as
a sub-processor.

## 5. Firestore
State that family/pairing/restriction/usage data is stored in Google Cloud Firestore, in the
project's configured region, for as long as the pairing/account exists (see retention section).

## 6. Analytics
State clearly: **general usage analytics collection is disabled** (verified in the actual release
build's manifest, not just claimed) — this app does not use Firebase Analytics for behavioral
tracking.

## 7. Crashlytics
State that crash/session diagnostic data is collected via Firebase Crashlytics for stability
purposes, is not used for tracking, and follows Firebase's own retention policy.

## 8. AccessibilityService
Explain in plain language: the service is used only to detect which app is currently open (by
package name), for the purpose of showing a block screen — it does not read on-screen content,
does not take screenshots, does not perform actions, and nothing it reads leaves the device. This
must match the in-app disclosure shown before enabling it (see `SetupGuideActivity`) and the Play
Console accessibility declaration once filed.

## 9. Camera / QR scanning
State: the camera is used only to scan a pairing QR code during setup; frames are decoded
on-device and never saved, recorded, or transmitted. (Verified against the actual integration
code, not assumed from the scanning library's general behavior.)

## 10. Device information
Beyond the (editable) child device name, no other device identifiers (IMEI, MAC, advertising ID,
etc.) are collected — state this explicitly as a point in the user's favor.

## 11. Usage/restriction information
Describe what's synced (allowances, consumed time, block events) and what stays purely local
(the personal Focus Timer's own usage history and app selections never leave the device at all).

## 12. Data sharing
State plainly: no data is sold or shared with any third party beyond Google/Firebase acting as
the processor for the functions described above. No advertising SDK is integrated.

## 13. Data retention
Describe the actual lifecycle (see `docs/compliance/DATA_RETENTION_AND_DELETION.md`): most data is
deleted on unpair or account deletion; disclose the one known residual case (an abandoned,
never-claimed pairing code has no automatic server-side expiry, since the owner has not enabled
billing for a Firestore TTL policy) honestly rather than glossing over it.

## 14. Data deletion / Account deletion
Describe both paths now implemented: in-app ("Parental Control → Delete My Account") and the
external web page (https://scrollguard-aba84.web.app/delete-account.html). State what deletion
actually removes (see `docs/compliance/ACCOUNT_DELETION.md`) and what it does not (it doesn't
remove the app from a child's device or delete data stored only locally on that device).

## 15. Parent/child relationship
Describe the pairing model and, importantly, that ending pairing initiated by the child now
requires parent approval (a security/trust detail worth being transparent about, not just a
technical fact).

## 16. Children's privacy
**[Legal review — do not draft this section without counsel]**: this is the section most exposed
to COPPA/DPDPA/GDPR-children's-provisions risk. See `docs/compliance/LEGAL_REVIEW_CHECKLIST.md`
items 1–3.

## 17. Security practices
Can honestly state: Firestore security rules restrict all cross-family access, pairing codes are
single-use with a short TTL and cannot be enumerated, and this has been verified against an
automated test suite (46 passing cases as of this document). Avoid overstating this as
"encrypted"/"bank-level security" language — describe what's actually true.

## 18. Contact information
Requires: at minimum, the support email used for the account-deletion page
(scrollguardd@gmail.com) or a dedicated privacy contact if the owner prefers a different address.

## 19. Jurisdiction / legal review items
See `docs/compliance/LEGAL_REVIEW_CHECKLIST.md` in full — do not finalize the policy without
resolving those items with counsel.
