# Play Store Submission Checklist

## App content
| Item | Status | Notes |
|---|---|---|
| Target audience | ❌ Not decided | Owner decision — see `docs/GOOGLE_PLAY_TARGET_AUDIENCE.md` |
| Content rating questionnaire | ❌ Not completed | Play Console action |
| Families declaration (if applicable) | ❌ Depends on target audience decision | — |
| Privacy Policy | ❌ Not written | Requirements documented: `docs/PRIVACY_POLICY_REQUIREMENTS.md` |
| Data Safety form | ❌ Not completed | Source-of-truth table ready: `docs/GOOGLE_PLAY_DATA_SAFETY.md` |
| Accessibility declaration | ❌ Not filed | Facts ready: `ACCESSIBILITY_SERVICE_COMPLIANCE.md` |
| Sensitive permissions declarations | ⚠️ Ready to file | Full inventory: `PERMISSIONS_AND_APIS.md` |
| Foreground service declaration | ⚠️ Ready to file | `specialUse` justification documented in `GOOGLE_PLAY_COMPLIANCE.md` |
| Reviewer access instructions | ❌ Not prepared | Parental-control flow needs two accounts to fully exercise (parent + child) — prepare test credentials/instructions for the reviewer |

## Technical
| Item | Status | Notes |
|---|---|---|
| Release signing | ✅ Configured | `app/build.gradle` resolves via `keystore.properties`/env vars; verified this session (`app-release.apk`, not the unsigned-fallback name) |
| Target SDK | ✅ Current | `compileSdk`/`targetSdk` 36 |
| Android compatibility | ⚠️ Emulator-only | No real-device testing done this session — see `COMPLIANCE_PROGRESS.md` remaining risks |
| Release build | ✅ Passing | `assembleRelease` clean, R8 minification on |
| Crash reporting | ✅ Configured | Crashlytics active; general Analytics collection disabled |
| Privacy configuration | ✅ Ad-ID + Analytics collection disabled, verified in packaged manifest | — |
| Account deletion | ✅ Implemented this session | In-app + external web page — see `ACCOUNT_DELETION.md` |
| Data deletion | ✅ Implemented | Full Firestore cascade on unpair/account deletion |
| Firestore security rules | ✅ Hardened + tested | 46/46 emulator tests passing — see `FIRESTORE_SECURITY.md` |

## Store listing
| Item | Status | Notes |
|---|---|---|
| Product description accuracy | ⚠️ Needs review | `README.md`'s feature list predates the Parental Control feature entirely — needs a content update before it's usable as listing copy |
| Accessibility usage description | ✅ Accurate | `accessibility_description` string updated this session to mention both Focus Timer and Parental Control uses |
| Parental-control functionality description | ❌ Not drafted | No store listing copy exists yet |
| No misleading claims | ✅ Verified for the specific claims checked | Camera "nothing recorded" claim verified technically true |
| Screenshots match current UI | ❌ Not prepared | No screenshots exist yet |

## Summary
Technical readiness is materially ahead of process/documentation readiness. Nothing in the
"Technical" section is a blocker; nearly everything in "App content" and "Store listing" is a
Play Console/content-authoring task that hasn't been started, not a code gap.
