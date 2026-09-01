# ScrollGuard Documentation Index

## audits/
History of this project's audit-and-fix passes.

- **`prompts/`** — the audit/instruction prompts as given, verbatim, kept for reference: general
  production-readiness (`AUDIT_PROMPT.md`), UI/UX + PiP bypass (`UI_UX_AUDIT_PROMPT.md`), a
  validation follow-up plus pasted external commentary (`legal.txt`), the Google Play/privacy/legal
  compliance workstream (`PLAY_PRIVACY_LEGAL_AUDIT_PROMPT.md`), this repository-organization
  pass (`ORGANIZE_PROMPT.md`), a follow-up production-readiness re-check
  (`PRODUCTION_CHECK_PROMPT.md`), and a battery/performance soak-test brief (`SOAK_TEST_PROMPT.md`
  — prompt only; no soak test has actually been run against it yet, see below).
- **`reports/`** — the resulting progress logs and reports: `AUDIT_PROGRESS.md` / `AUDIT_REPORT.md`,
  `TIMER_UX_AUDIT_PROGRESS.md` / `TIMER_UX_AUDIT_REPORT.md`, `UI_AUDIT_PROGRESS.md` /
  `UI_UX_AUDIT_REPORT.md`, `PRODUCTION_READINESS.md` (what was fixed vs. what remains the
  owner's own action item — release keystore, Play Store policy review, real-device testing, etc),
  and `PRODUCTION_CHECK_PROGRESS.md` (the re-check's own findings). There is no soak-test report —
  `SOAK_TEST_PROMPT.md` above was never executed.

These are historical records of completed passes — treat them as evidence of what was found and
fixed at the time, not as a live status of the app today. `COMPLIANCE_PROGRESS.md` (repo root) is
the one actively-maintained tracker as of this writing.

## product/
- `ScrollGuard_Parental_Control_MVP.md` — the original architectural spec for the parental-control
  feature.
- `walkthrough.md` — a self-reported build history of that feature (unverified against the code
  independently — cross-check against the actual source before relying on it).
- `FuturePlan.txt` — product/feature ideas not yet built.

## compliance/
The Google Play + privacy + legal readiness workstream's output: technical audits, data inventories,
Firestore security review, and the legal/Play-Console items that remain the owner's or counsel's
decision. See `compliance/README.md` for its own index. None of this workstream's documents declare
the app "legally compliant" — that determination is explicitly out of scope for an AI audit; see
`compliance/LEGAL_REVIEW_CHECKLIST.md`.

## This directory's own root-level docs (deliberately not under `compliance/`)
- `PRIVACY_POLICY_REQUIREMENTS.md`, `GOOGLE_PLAY_DATA_SAFETY.md`, `GOOGLE_PLAY_TARGET_AUDIENCE.md` —
  placed directly under `docs/` rather than `docs/compliance/`, per the compliance workstream's own
  instructions, which deliberately split its output across both locations.

## Kept at the repo root (not under `docs/`) on purpose
- `../README.md` — main project README (standard location).
- `../COMPLIANCE_PROGRESS.md` — the compliance workstream's active, frequently-updated tracker; kept
  at the repo root per that workstream's own instructions so it stays easy to find while in progress.
