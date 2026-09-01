# Google Play Data Safety — Source of Truth

Technical source-of-truth for completing the Play Console Data Safety form. Every row has a code
reference — nothing here is guessed. Cross-reference: `docs/compliance/DATA_INVENTORY.md` for the
full per-element table this summarizes into Play's own category structure.

| Data category | Specific type | Collected? | Shared? | Required/Optional | Purpose | Source (SDK/API) | On-device only? | Retention | Deletion mechanism | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|
| Personal info | Email address | Yes (parent only) | No | Required (for parent account creation) | Account authentication | Firebase Auth | No | Until account deletion | In-app "Delete My Account" + external web page | `ParentalAuthManager.createAccount()` |
| Personal info | Name | No | — | — | — | — | — | — | — | Never collected anywhere in the app |
| Device or other IDs | Device name/model string | Yes (child device, editable) | No | Optional (defaults automatically, user may edit) | Let the parent identify which device is which | `Build.MANUFACTURER`/`Build.MODEL`, app-local editing | No | Until unpair/account deletion | Same as above | `PairingManager.kt:47` |
| Device or other IDs | Firebase Auth UID (parent + child) | Yes | No | Required (functional necessity) | Identity binding for pairing/security rules | Firebase Auth | No | Until account deletion | Same as above | `ParentalConfig.kt` |
| Device or other IDs | Advertising ID (AAID) | **No — explicitly disabled** | — | — | — | Firebase Analytics (collection disabled) | — | — | — | `google_analytics_adid_collection_enabled=false`, verified in packaged release manifest |
| App activity | App interactions / in-app search history | No | — | — | — | — | — | — | — | No general Analytics collection at all (`firebase_analytics_collection_enabled=false`) |
| App activity | Per-app restriction/allowance settings | Yes | No | Required (core feature) | Enforce parent-set time limits | Firestore | No | Until unpair/account deletion | Same as above | `SyncEngine.writeAppRestriction()` |
| App activity | Per-app consumed-time / block events | Yes | No | Required (core feature) | Enforcement + parent visibility; local analytics ("Top Blocked Apps") | Firestore (synced data) + Room (local-only data) | Partially — personal Focus Timer's own usage history/block events never leave the device | Until unpair/account deletion (synced data); until app data cleared (local-only data) | Same + local data cleared by OS-level app-data-clear/uninstall | `BlockEvent.kt`, `UsageRecord.kt`, `ParentalDao.kt` |
| App activity | Installed-app catalog (package names + labels) | Yes (child device only, shared with parent) | No (not shared outside the family/Google) | Required (needed to let the parent choose restrictable apps) | Populate the parent's app-selection UI | `PackageManager` (on-device) + Firestore (synced) | No | Until unpair/account deletion | Same as above | `SyncWorker.buildAppCatalog()` |
| App info and performance | Crash logs | Yes | No | Optional from the user's perspective (can't be individually toggled off in-app) | Crash reporting / stability | Firebase Crashlytics | No | Firebase's own default retention (not further configured by this app) | Not user-deletable independent of account deletion | `firebase-crashlytics-ktx` dependency |
| App info and performance | Diagnostics (general usage analytics) | **No — explicitly disabled** | — | — | — | Firebase Analytics (collection disabled) | — | — | — | `firebase_analytics_collection_enabled=false` |
| Photos or videos | Camera frames (QR scan) | **No — never stored** | No | — | Decode a pairing QR code | `zxing-android-embedded`'s `ScanContract`, decode-in-process only | Yes (never leaves the device, never even persisted on it) | N/A | N/A | Verified against `ParentalControlActivity.kt` — no capture/save/upload code exists |
| Location | Any location data | No | — | — | — | — | — | — | — | No location permission declared |
| Contacts / SMS / Call logs | Any | No | — | — | — | — | — | — | — | No such permission declared |

## Families-specific note
If a Families/mixed-audience declaration is chosen (owner decision — see
`GOOGLE_PLAY_TARGET_AUDIENCE.md`), the specific restricted-identifier list (AAID, IMEI, IMSI, MAC,
SSID, BSSID, SIM serial, build serial) is **already fully satisfied** — none of these are
collected from any device, verified directly against the code, independent of which
target-audience declaration is ultimately chosen.

## What still needs a human, not more code
Transferring this table into the actual Play Console Data Safety form UI, and reconciling wording
with the final Privacy Policy text once it's written — Google requires the two to match, and
that's a manual cross-check once both exist in final form.
