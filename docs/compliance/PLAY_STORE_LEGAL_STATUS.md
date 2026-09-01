> **⚠️ SUPERSEDED — kept as a historical snapshot, not current status.** This document predates a
> later pass that implemented account deletion (in-app + external web page), fixed the orphaned-
> Firestore-subcollection cascade on unpair, and resolved the Analytics-justification question
> (collection disabled). Items 10, 11, and 13 below describe those as still-open — they are not.
> For current status, see `docs/compliance/README.md`, `ACCOUNT_DELETION.md`,
> `DATA_RETENTION_AND_DELETION.md`, and `PERMISSIONS_AND_APIS.md`. This file's still-valid
> contributions are the specific Google policy quotes in items 1–3 (AccessibilityService's
> "autonomous action" prohibition, the April 15 2026 foreground-service/geofencing policy update)
> — the second of which was not independently re-verified by the later pass and is worth
> confirming directly before relying on it for submission.

# ScrollGuard — Google Play & Privacy/Legal Status (Current Answers)

This answers the checklist you compiled, item by item. Every answer is labeled with its actual
epistemic status — that labeling is the point of this document, not a formality:

- **VERIFIED TECHNICAL FACT** — confirmed by reading this repo's actual code/config just now.
- **CURRENT OFFICIAL POLICY** — confirmed by fetching the live Google Play Console Help page
  today (2026-08-31), quoted/cited below. Policy pages change; re-check before relying on this
  for a submission months from now.
- **GENERAL UNDERSTANDING — VERIFY LIVE** — my best current understanding, not freshly fetched
  for this document. Re-verify against the live page before treating it as settled.
- **OWNER DECISION** — a product/business call only you can make.
- **REQUIRES LEGAL ADVICE** — a jurisdiction-specific legal determination. I am not a lawyer and
  am not making this determination; treat anything I say here as background, not an answer.

I am not telling you "you are compliant" anywhere in this document. That verdict doesn't exist yet.

---

## Google Play / policy questions

### 1. AccessibilityService

**Why does ScrollGuard need it? What does it access?** — VERIFIED TECHNICAL FACT. `BlockerAccessibilityService`
requests only `typeWindowStateChanged|typeWindowsChanged` events, `canRetrieveWindowContent="true"`,
and `flagRetrieveInteractiveWindows|flagIncludeNotImportantViews` (`accessibility_service_config.xml`).
It reads the foreground window's `packageName` (nothing else — no on-screen text, no node content
beyond identifying the window) to decide whether the current app is one the user or a parent has
restricted, then launches ScrollGuard's own `BlockActivity`. It does not perform gestures, does not
click/act on other apps' UI, and does not transmit anything off-device from this code path.

**Is this permitted under Google's current AccessibilityService policy?** — CURRENT OFFICIAL POLICY,
fetched from [Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
today. The policy's core prohibition is: *"Any use of the Accessibility API that enables an app to
autonomously initiate, plan, and execute actions or decisions is strictly prohibited."* ScrollGuard's
use — passively reading which window is in front, then launching its own screen — is a read-and-react
pattern, not autonomous action against other apps' UI, which is the pattern the policy actually
targets (the announced 2026 tightening is aimed at AI agents that read the screen and tap buttons on
a user's behalf, and at RPA tools). This is a reasonable read of the policy text, not a guarantee —
Play Console's own review process is the actual authority here.

**Is the in-app disclosure/consent sufficient?** — This is the one open technical question, and it
matters more than it might look. The same policy page states apps *not* eligible for
`isAccessibilityTool="true"` must provide disclosure that: appears **within the app itself**, displays
**during normal usage without menu navigation**, describes what data is accessed and how it's used,
and requires an **affirmative user action** (a tap/checkbox), kept **separate from other data
disclosures**. `strings.xml`'s `accessibility_description` string exists and is accurate in content
("ScrollGuard uses accessibility services to detect which app is currently open, so it can block
selected apps during your focus sessions and enforce parent-set daily time limits") — but whether it
is actually *shown to the user, in-app, during normal use, with an explicit tap-to-consent step*
before they're sent to the system Settings screen, versus merely existing as a string Android may or
may not surface, has not been confirmed by reading the onboarding flow's actual screen sequence.
**Action:** trace `AppPickerActivity`'s (or wherever the accessibility-enable prompt lives) exact
flow and confirm a dedicated in-app disclosure screen with an explicit consent tap precedes the
system settings redirect. If it doesn't exist yet, this is a real gap to close before submission —
not a legal question, a build-it-or-don't one.

**Is ScrollGuard eligible for `isAccessibilityTool="true"`?** — CURRENT OFFICIAL POLICY: no. The
fetched policy page explicitly lists **"monitoring apps"** among app types ineligible for that
attribute (alongside antivirus, automation tools, password managers, launchers). ScrollGuard is a
monitoring/parental-control app, so it must go through the disclosure-and-consent path above, not
claim the accessibility-tool exemption. VERIFIED TECHNICAL FACT: nothing in this codebase currently
sets `isAccessibilityTool` at all (correct — it shouldn't).

**Is the Play Console Accessibility declaration accurate?** — REQUIRES PLAY CONSOLE REVIEW. This is
filled out at submission time in Play Console itself; the honest, accurate answer given everything
above is "not an accessibility tool; uses the API to detect the foreground app for parental/focus
time-limiting, with in-app disclosure and consent" — but the actual form and Google's review of it
can't be resolved from source code alone.

### 2. Parental-control use of AccessibilityService (uninstall/disable protection)

CURRENT OFFICIAL POLICY: the fetched policy page for AccessibilityService itself contains **no**
uninstall-prevention rules or parental-control carve-out language (I checked specifically — it isn't
there). The uninstall/disable-prevention carve-out language often quoted online (*"cannot be used to
prevent disabling/uninstalling unless authorized by a parent/guardian through a parental-control
app"*) may live on a different, more specific policy page (e.g. Device Admin or a Families-specific
policy) — I have not located and fetched that exact page, so treat that specific carve-out as
GENERAL UNDERSTANDING — VERIFY LIVE, not confirmed today. What I can confirm as VERIFIED TECHNICAL
FACT: ScrollGuard's `AdminReceiver`/`device_admin_rules.xml` requests **zero** DevicePolicyManager
policy capabilities (`<uses-policies />` is empty, and its own code comment states no policy API is
ever called) — so today's implementation isn't relying on Device Admin to actually *enforce*
anything beyond the OS's own "deactivate before removing an active admin" friction. That's a
narrower, lower-risk claim than "uninstall protection," and should be described that way everywhere
(Play listing, in-app copy, this document) rather than oversold.

### 3. Foreground Service

VERIFIED TECHNICAL FACT: `TimerService` declares `android:foregroundServiceType="specialUse"` with
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "App usage timer and blocker"`, and the code branches correctly
by SDK version (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+, `DATA_SYNC` below that historically
— confirm this branch still matches current `compileSdk`/`targetSdk` 34 usage). The April 15, 2026
Play policy announcement I fetched today updates foreground-service policy specifically around
**geofencing** (no longer an approved `specialUse` justification; use the Geofence API instead) —
that update doesn't apply to ScrollGuard's use case (app-usage timing/blocking), so it isn't a new
risk here, but it's a reminder that `specialUse` justifications do get periodically tightened and
worth re-checking at each Play Console review.

### 4. Target audience / Families policy

OWNER DECISION, informed by these VERIFIED TECHNICAL FACTs: ScrollGuard has two real audiences today
— an adult self-discipline user (no login, fully local) and a parent/child pair (Firebase-backed,
requires parent auth + a paired child device). The child side does not let a child enter a name (the
device name defaults to `$MANUFACTURER $MODEL`, editable — see item 10 below), and there's no
onboarding path where a child, unsupervised, sets up the app for themselves as a "child" account
without a parent completing the pairing. CURRENT OFFICIAL POLICY (fetched today, [Google Play
Families Policies](https://support.google.com/googleplay/android-developer/answer/9893335?hl=en)):
the target-audience declaration is made in Play Console's "Target audience and content" section, and
"if you choose to include imagery and terminology in your app that could be considered targeting
children, this may impact Google Play's assessment of your declared target audience" — meaning your
actual marketing/store-listing copy matters, not just the code. **This is exactly the decision I
flagged earlier as consequential:** positioning ScrollGuard as "a self-discipline app for adults that
also offers parental controls" versus "an app for kids" are different Families-policy postures, and
you should decide and lock in that positioning (store copy included) before filling out the Target
Audience form — not after.

### 5. Children's data

See the Phase 5-style inventory in item 10 below and in `PLAY_PRIVACY_LEGAL_AUDIT_PROMPT.md` Part
0.5. One VERIFIED TECHNICAL FACT worth restating here specifically: `google_analytics_adid_collection_enabled`
is already set to `false` in `AndroidManifest.xml`, with a code comment explaining Crashlytics
otherwise transitively pulls in Advertising ID collection. That directly addresses the Families-policy
concern CURRENT OFFICIAL POLICY raises about device identifiers not being transmitted for children/
unknown-age users — but it only covers the Advertising ID specifically. It does not, on its own,
cover the other identifiers Families policy also restricts (IMEI, MAC, SSID/BSSID, SIM/Build serial,
etc.) — this codebase doesn't appear to collect those either (nothing in the audited manifest/code
requests `READ_PHONE_STATE`, MAC/serial access, etc.), which is good, but that absence should be
explicitly confirmed and documented in `docs/GOOGLE_PLAY_DATA_SAFETY.md` (per the audit prompt)
rather than left as an inference.

---

## Privacy / legal questions

### 6. COPPA — REQUIRES LEGAL ADVICE

I am not determining whether COPPA applies to ScrollGuard. What I can hand a lawyer, as VERIFIED
TECHNICAL FACT: the child side collects an anonymous Firebase Auth UID, a device name (parent- or
child-editable string, defaulting to manufacturer/model), a family/pairing relationship, per-app
restriction config, and consumption/usage timestamps — no name, no email, no photo, no precise
location is collected from the child device by this codebase. Whether that data set, this
architecture, and how the app is marketed together trigger COPPA's "personal information from a
child" threshold and its parental-consent mechanics is exactly the kind of determination that needs
a privacy lawyer, not an AI reading source code.

### 7. GDPR / other privacy laws — REQUIRES LEGAL ADVICE

Same posture: applicability depends on where you actually make the app available and to whom, which
is a business/legal question, not something derivable from the repository.

### 8. India's privacy requirements — REQUIRES LEGAL ADVICE

Same posture, and worth flagging as its own line item precisely because it's easy to reflexively
reach for COPPA/GDPR and forget the jurisdiction the business actually operates from.

### 9. Privacy Policy — ACTIONABLE NOW, NOT YET DONE

VERIFIED TECHNICAL FACT: no privacy policy exists in this repo yet; `docs/PRIVACY_POLICY_REQUIREMENTS.md`
(scoped in `PLAY_PRIVACY_LEGAL_AUDIT_PROMPT.md` Phase 9) hasn't been created. This is a document you
can have written accurately once the data inventory (item 10) is finalized — the requirements list,
not the legal wording itself, is something that can be produced from code today.

### 10. Data retention / deletion

VERIFIED TECHNICAL FACT, traced from `PairingManager.kt` and `firestore.rules`: `unpair()` currently
calls `firestore.collection("families").document(familyId).delete()` — a single top-level document
delete. **Firestore does not cascade-delete subcollections when you delete a parent document.**
`config/current`, `status/current`, `catalog/current`, and any `requests/{requestId}` documents living
under that `families/{familyId}` path are subcollections, not fields — deleting the parent leaves
them **orphaned in Firestore, still holding data, just unreachable through the normal app UI**. This
is a concrete, fixable technical gap, not a legal one: a real unpair/account-deletion flow needs to
explicitly delete each subcollection's documents (or a Cloud Function/scheduled cleanup job needs to
do it), not rely on the parent-document delete alone. I'd flag this as one of the highest-value fixes
in this whole checklist precisely because it's unambiguous and fully within engineering's control.

### 11. Account deletion

VERIFIED TECHNICAL FACT: I found no in-app "delete my account" flow, and no external web-based
deletion path, anywhere in the audited code (`ParentalAuthManager.kt`, `ParentalControlActivity.kt`).
GENERAL UNDERSTANDING — VERIFY LIVE: Google Play's User Data policy requires apps that support
account creation to offer both an in-app deletion path and an external (web) one, with the
associated data actually deleted (subject to disclosed legitimate retention). Building this is
squarely engineering work once item 10's orphaned-subcollection issue is fixed — an account-deletion
flow built on top of the current `unpair()` would inherit the same incompleteness.

### 12. Camera/QR privacy

VERIFIED TECHNICAL FACT: pairing/QR scanning goes through `com.journeyapps:zxing-android-embedded`
(a wrapper around ZXing), which decodes frames on-device and does not itself persist or upload
images by default. I have not re-verified that ScrollGuard's own integration code around that
library doesn't add any capture/save/upload step on top of the library's default behavior — that's a
five-minute code check (search for any `Bitmap`/file-write/upload call near the QR scanning
activity), not a research question, and should be confirmed explicitly before your privacy policy
asserts "nothing is recorded or stored" as fact.

### 13. Third-party SDK data (Firebase Auth, Firestore, Analytics, Crashlytics, FCM)

VERIFIED TECHNICAL FACT: `app/build.gradle` currently includes `firebase-auth-ktx`,
`firebase-firestore-ktx`, `firebase-messaging-ktx`, `firebase-analytics-ktx`, and
`firebase-crashlytics-ktx` (Analytics and Crashlytics were added at some point after the app's
original local-only version — they are not incidental, someone deliberately added them). One thing
worth your explicit attention, not just a compliance checkbox: **is there an articulated product
reason for general Analytics (not just Crashlytics) on an app that handles a child's usage data?**
Crash reporting has an obvious justification; usage analytics on a child-data-handling app needs its
own, separate one. If there isn't a real one, removing `firebase-analytics-ktx` outright is simpler
and lower-risk than fully documenting and justifying it — but that's your call to make (OWNER
DECISION), not mine.

---

## What's already been technically addressed

Advertising ID collection: **already disabled** (`google_analytics_adid_collection_enabled=false` in
the manifest, verified today) — this was correctly identified and fixed in a prior pass, not still
open. Don't re-flag it as outstanding; do keep it as evidence in the Data Safety documentation.

## What is NOT a legal issue (confirmed scoping, matches your own list)

Release keystore, Android 15/16 testing, Firebase test-data cleanup, pairing-code rate limiting,
FCM/live sync, unpair authorization, timer/locking logic, UI/UX, and parental-control functionality
itself are all engineering/product/release work — correctly excluded from the legal/Play checklist.
(Two of these — unpair authorization and timer/locking logic — are being actively worked on in this
same session; see `AUDIT_PROGRESS.md` and this session's `TimerState.kt` changes.)

---

## Consolidated checklist, with status

**Play Store**
- [x] Advertising ID collection disabled (verified)
- [ ] AccessibilityService in-app disclosure + affirmative consent flow — **verify it exists as a
      dedicated screen, build it if it doesn't**
- [ ] Accessibility declaration in Play Console — pending submission, informed by the above
- [ ] Parental-control + AccessibilityService: description should say "detects foreground app,"
      not "prevents uninstall" (Device Admin currently grants no such capability — keep the claim
      matched to reality)
- [x] Foreground-service `specialUse` declaration — technically correct as implemented
- [ ] Target audience classification — **OWNER DECISION**, needs to be made before Play Console
      submission and needs your marketing copy to match it
- [ ] Families policy applicability — follows from the target-audience decision
- [ ] Children's data requirements — data set is minimal and identifier collection is already
      restricted; formalize into `docs/GOOGLE_PLAY_DATA_SAFETY.md`
- [ ] Data Safety declaration — not yet written

**Privacy/legal**
- [ ] COPPA applicability — **REQUIRES LEGAL ADVICE**
- [ ] GDPR/other jurisdictions — **REQUIRES LEGAL ADVICE**
- [ ] Indian privacy requirements — **REQUIRES LEGAL ADVICE**
- [ ] Privacy Policy — not yet written; requirements are derivable from code now
- [ ] Children's data/parental consent requirements — **REQUIRES LEGAL ADVICE** for the consent
      *mechanism*; the underlying data facts are already established above
- [ ] Data retention — **orphaned-subcollection bug found** (item 10) — this is an engineering fix,
      not a policy question, and should happen before the retention story can even be written up
- [ ] Account/data deletion — no in-app or external flow exists yet; blocked on the same fix
- [ ] Firebase/third-party SDK data handling — mostly inventoried above; confirm the Analytics
      justification question
- [ ] Camera/privacy disclosure — verify no custom capture/upload code around the QR scan step

---

## Bottom line

The biggest **unknowns** are exactly the three you named: AccessibilityService disclosure
sufficiency, target-audience/Families classification, and COPPA/privacy applicability — none of
which this document resolves, because none of them can be resolved from source code or general
policy text alone. The biggest **new, concrete, fixable finding** from this pass is the orphaned
Firestore subcollections on unpair (item 10) — that one has a clear engineering owner and no legal
ambiguity at all. Don't tell yourself "we're legally clear" based on this document; it narrows what's
still genuinely open, it doesn't close it.

Sources:
- [Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Policy announcement: April 15, 2026](https://support.google.com/googleplay/android-developer/answer/16926792?hl=en)
- [Google Play Families Policies](https://support.google.com/googleplay/android-developer/answer/9893335?hl=en)
