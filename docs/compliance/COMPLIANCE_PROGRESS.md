> Note: the canonical, actively-updated copy of this tracker lives at the repo root
> (`../../COMPLIANCE_PROGRESS.md`), per that workstream's own instruction to keep it there. This
> copy matched it as of this writing but may drift — check the root file for current status.

# ScrollGuard Compliance Workstream — Progress

Tracking file for the Google Play + Privacy + Legal Readiness Audit. See
`docs/compliance/README.md` for the full document index. Nothing here is a legal or Play-policy
determination — see `docs/compliance/LEGAL_REVIEW_CHECKLIST.md` and
`docs/compliance/PLAY_STORE_SUBMISSION_CHECKLIST.md` for what still needs the owner/legal/Play
Console.

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked/needs owner decision

## Phase 1 — Technical capability inventory
- [x] Full inventory with evidence — `docs/compliance/PERMISSIONS_AND_APIS.md`

## Phase 2 — AccessibilityService deep audit
- [x] Config re-verified, `flagIncludeNotImportantViews` removed (unused scope)
- [x] Confirmed package-name-only access, no content/gesture/screenshot capability
- [x] Confirmed no `isAccessibilityTool` declaration
- [x] `docs/compliance/ACCESSIBILITY_SERVICE_COMPLIANCE.md`

## Phase 3 — Google Play Accessibility policy research
- [x] Requirements matrix drafted from live official sources — `docs/compliance/PLAY_POLICY_RESEARCH_NOTES.md` + folded into `ACCESSIBILITY_SERVICE_COMPLIANCE.md`

## Phase 4 — Parental control data flow map
- [x] `docs/compliance/DATA_INVENTORY.md`

## Phase 5 — COPPA technical audit (data table)
- [x] `docs/compliance/DATA_INVENTORY.md`

## Phase 6 — Advertising/identifier audit
- [x] Ad-ID disabled, verified in packaged release manifest
- [x] General Analytics collection also disabled this round (owner confirmed no justification exists)

## Phase 7 — Data retention & deletion lifecycle
- [x] `docs/compliance/DATA_RETENTION_AND_DELETION.md`

## Phase 8 — Account deletion
- [x] In-app flow implemented (`ParentalControlActivity` — Delete My Account, with re-auth)
- [x] External web page deployed — https://scrollguard-aba84.web.app/delete-account.html
- [x] `docs/compliance/ACCOUNT_DELETION.md`

## Phase 9 — docs/PRIVACY_POLICY_REQUIREMENTS.md
- [x] Created

## Phase 10 — docs/GOOGLE_PLAY_DATA_SAFETY.md
- [x] Created

## Phase 11 — docs/GOOGLE_PLAY_TARGET_AUDIENCE.md
- [x] Created — classification left as `[!]` OWNER DECISION

## Phase 12 — Parental control authorization audit (fresh pass)
- [x] `docs/compliance/FAMILIES_AND_CHILD_SAFETY.md`

## Phase 13 — Firestore security audit (fresh adversarial pass)
- [x] Two additional gaps found and closed this round (config-version forgery at bootstrap,
  request self-approval forgery) — both deployed live
- [x] Rules-emulator test suite actually run (previously never executed this session) — 1 stale
  test fixed, 13 new tests added, 46/46 passing
- [x] `docs/compliance/FIRESTORE_SECURITY.md`

## Phase 14 — Foreground service / Android policy
- [x] `docs/compliance/GOOGLE_PLAY_COMPLIANCE.md`

## Phase 15 — Device Admin / uninstall protection
- [x] `docs/compliance/GOOGLE_PLAY_COMPLIANCE.md` — confirmed not wired to parental control; left as `[!]` OWNER DECISION whether it ever should be

## Phase 16 — Camera / QR privacy
- [x] Verified against actual integration code, not assumed — `docs/compliance/GOOGLE_PLAY_COMPLIANCE.md`

## Phase 17 — docs/compliance/ structure
- [x] Created — all 12 files present

## Phase 18 — LEGAL_REVIEW_CHECKLIST.md
- [x] Created — 12 items, none resolved (correctly, since none are resolvable without counsel)

## Phase 19 — PLAY_STORE_SUBMISSION_CHECKLIST.md
- [x] Created

## Phase 20 — Over-comply check
- [x] Reviewed — no functionality removed; only unused Accessibility scope and unjustified
  Analytics collection were trimmed, both with zero product impact

## Phase 21 — Fixes
- [x] Account deletion (in-app + external) implemented
- [x] Analytics collection disabled (owner-approved)
- [x] Two Firestore rule hardenings implemented, tested, and deployed
- [x] Stale + missing Firestore rules-test coverage added
- [!] Firestore TTL policy for pairing codes — **not pursued**, owner has explicitly declined to
  enable Firebase billing (no monetization path exists for this app); documented as an accepted,
  low-severity residual gap instead

## Phase 22 — Final report
- [x] Delivered in-conversation (see chat history for the full A–H report format)
