# Data Safety (Compliance Workstream Summary)

The full, Data-Safety-form-ready table lives at **`../GOOGLE_PLAY_DATA_SAFETY.md`** (per the
audit's own instruction to place that document directly under `docs/`, not under
`docs/compliance/`). This file is a short pointer plus the parts specific to this compliance
workstream's own tracking.

## Quick summary
Every data category ScrollGuard touches maps to Google/Firebase (Auth, Firestore, Crashlytics) as
the sole processor. General Analytics collection and Advertising ID collection are both explicitly
disabled (verified in the packaged release manifest, not just source — see `PERMISSIONS_AND_APIS.md`).
Full per-element detail: `DATA_INVENTORY.md`.

## What's left before the real Play Console Data Safety form can be filed
1. Owner decision on target audience (affects whether the Families-specific Data Safety questions
   apply) — see `docs/GOOGLE_PLAY_TARGET_AUDIENCE.md`.
2. Transfer the verified table in `../GOOGLE_PLAY_DATA_SAFETY.md` into the actual Play Console
   form UI (a manual data-entry step, not something this repo can automate).
3. Cross-check the final form against the Privacy Policy once it's written, since Google requires
   the two to match.
