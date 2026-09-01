# Account Deletion

## Before this session
Neither an in-app nor an external account-deletion path existed. "Unpair" only cleared the
family/child relationship — the parent's Firebase Auth account itself was never deletable.

## Implemented this session

### In-app path
`ParentalControlActivity` — reachable from both parent-facing screens (the post-pairing dashboard
and the signed-in-but-not-yet-paired pairing screen), via a **Delete My Account** button:

1. `confirmDeleteAccount()` — destructive-action confirmation dialog.
2. `promptPasswordAndDeleteAccount()` — collects the password fresh, because Firebase requires a
   "recent login" for account deletion (`FirebaseAuthRecentLoginRequiredException` otherwise).
3. `performDeleteAccount()`:
   a. `ParentalAuthManager.reauthenticateWithPassword()` — re-authenticates via
      `EmailAuthProvider` + `FirebaseUser.reauthenticate()`.
   b. If paired, `PairingManager.unpair(familyId)` — the full Firestore cascade (see
      `DATA_RETENTION_AND_DELETION.md`), run **before** the Auth identity is deleted, since
      Firestore rules key every permission off `request.auth.uid` and that stops existing the
      instant the account is gone.
   c. `ParentalAuthManager.deleteCurrentUser()` — deletes the Firebase Auth user.
   d. `clearLocalParentalState()` — clears local Room + in-memory state (the same helper
      `performUnpair()` already used).
4. Success/failure is surfaced via Toast; on success the user lands back on role selection with a
   fully clean slate.

### External web path
`public/delete-account.html`, deployed to Firebase Hosting (free Spark-tier hosting, no billing
required) at **https://scrollguard-aba84.web.app/delete-account.html**. Explains what gets
deleted, points back to the in-app path as the primary option, and gives an email address
(`scrollguardd@gmail.com`) as the manual request path for someone who can't or won't use the app
itself — acceptable under Play's policy, which doesn't require a self-service automated web flow,
only *a* web resource to request deletion.

## What is explicitly NOT covered
- The **child's** anonymous Firebase Auth identity has no independent "delete my account" concept
  — it isn't a user-facing account the child ever created or signed into with credentials. It's
  cleared from Room/local state by unpair, but the anonymous Auth user itself isn't deleted by any
  current flow (see the orphan note in `DATA_RETENTION_AND_DELETION.md`).
- This flow does not remove ScrollGuard from the child's device — it only removes the
  server-side relationship and the parent's own account.

## Verification
- Compiles clean; full regression (unit tests, lint, debug+release build) passing — see the final
  regression run referenced in `COMPLIANCE_PROGRESS.md`.
- Not verified via a live two-account deletion round-trip on a real device in this session (would
  require a real parent test account going through re-auth + deletion end-to-end) — flagged as an
  open manual-verification item before relying on this for a real Play submission.
