# ScrollGuard Compliance Workstream

Google Play + privacy + legal readiness documentation. This is a *technical audit trail*, not a
legal opinion — nothing here declares the app "compliant" with any law or guarantees Play Store
approval. See the "Not a legal determination" note in every document that touches legal territory.

Active progress tracker: `../../COMPLIANCE_PROGRESS.md` (repo root).

## Documents in this folder

| Document | Covers |
|---|---|
| `GOOGLE_PLAY_COMPLIANCE.md` | Top-level summary: foreground service, Device Admin, AccessibilityService, camera, monetization |
| `ACCESSIBILITY_SERVICE_COMPLIANCE.md` | Deep audit of the AccessibilityService implementation vs. current Play policy |
| `FAMILIES_AND_CHILD_SAFETY.md` | Parental-control authorization model, target-audience considerations |
| `DATA_SAFETY.md` | Pointer to the full Data Safety table (lives at `../GOOGLE_PLAY_DATA_SAFETY.md`) |
| `PRIVACY_REQUIREMENTS.md` | Pointer to the full Privacy Policy requirements (lives at `../PRIVACY_POLICY_REQUIREMENTS.md`) |
| `DATA_INVENTORY.md` | Complete per-data-element table with evidence |
| `DATA_RETENTION_AND_DELETION.md` | Full lifecycle trace: creation → pairing → unpairing → account deletion → uninstall |
| `ACCOUNT_DELETION.md` | The in-app + external deletion flows implemented this session |
| `FIRESTORE_SECURITY.md` | Rule-by-rule "who can do what and why," verified against a passing emulator test suite |
| `PERMISSIONS_AND_APIS.md` | Every permission/sensitive API, why it exists, what happens if denied |
| `LEGAL_REVIEW_CHECKLIST.md` | Everything that needs a qualified legal professional, not this audit |
| `PLAY_STORE_SUBMISSION_CHECKLIST.md` | What's ready vs. outstanding for actual submission |

Also relevant, one level up (`docs/`, not `docs/compliance/`, by the workstream's own design):
`../PRIVACY_POLICY_REQUIREMENTS.md`, `../GOOGLE_PLAY_DATA_SAFETY.md`, `../GOOGLE_PLAY_TARGET_AUDIENCE.md`.

Supporting research: `PLAY_POLICY_RESEARCH_NOTES.md` — direct quotes and URLs from official Google
Play Console Help / developer.android.com pages, fetched live rather than taken from third-party
summaries. Treat it as a citation source, not a conclusion.

## What this workstream is NOT
- Not a claim of COPPA, GDPR, or any other legal compliance.
- Not a guarantee of Play Store approval.
- Not a substitute for qualified legal review before a real public launch — see
  `LEGAL_REVIEW_CHECKLIST.md` for exactly what still needs that review.

## Where the code stands vs. where the docs stand
Per the audit's own stated principle: if code and documentation ever disagree, the code is the
truth and the document is wrong. Every technical claim in this folder is backed by a specific file
reference verified during this workstream, not carried forward from an earlier, unverified
analysis.
