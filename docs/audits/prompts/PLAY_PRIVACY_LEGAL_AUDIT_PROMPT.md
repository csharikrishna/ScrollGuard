# ScrollGuard — Google Play + Privacy + Legal Readiness Audit

This is a dedicated compliance workstream for ScrollGuard, separate from normal feature
development. The goal is to establish, with evidence, exactly what this application actually does,
what data it actually handles, and what that means for Google Play submission and responsible
operation — not to declare it "compliant."

## ROLE AND LIMITATIONS — READ FIRST

You are **not a lawyer** and must NOT declare:

* "ScrollGuard is legally compliant"
* "ScrollGuard is COPPA compliant"
* "ScrollGuard is GDPR compliant"
* "Google Play will definitely approve the app"

Those are legal/policy determinations that ultimately require the applicable official policies and,
where appropriate, qualified legal advice.

Your responsibility is to:

1. Audit the actual codebase.
2. Identify what the application actually does.
3. Identify what data it actually collects/stores/transmits.
4. Identify what Android APIs and sensitive permissions it actually uses.
5. Compare those technical facts against the current Google Play requirements where possible.
6. Identify gaps.
7. Fix technical issues that can safely be fixed.
8. Clearly identify things that require the owner's decision.
9. Clearly identify things that require legal advice or Play Console review.
10. Create professional documentation in the repository so the project has a proper compliance
    trail.

Do NOT make assumptions just because an earlier AI analysis (yours or another model's) claimed
something. Every one of this repo's prior audit artifacts, described below, is a *lead to verify*,
never a fact to cite as settled.

---

## PART 0 — PRIOR WORK IN THIS REPO (read before starting, trust nothing in it blindly)

This app has already been through two prior audit-and-fix passes this workstream should build on,
not repeat from zero:

- **`AUDIT_PROMPT.md` / `AUDIT_PROGRESS.md`** — a full production-readiness pass covering the
  parental-control MVP (sync, Firestore rules, timer math, lifecycle). Real code changes resulted
  (see `git log` / `git diff` on `firestore.rules`, `PairingManager.kt`, `SyncEngine.kt`,
  `BlockerAccessibilityService.kt`, `BlockActivity.kt`, `ParentalControlActivity.kt`).
- **`UI_UX_AUDIT_PROMPT.md` / `UI_AUDIT_PROGRESS.md`** — a UI/UX polish pass plus an investigation
  into a Picture-in-Picture/split-screen enforcement-bypass concern.
- **`legal.txt`** — a follow-up validation prompt ("don't assume the previous analysis is correct")
  covering sync delay, uninstall bypass, enforcement bypass, parent/child acknowledgement, lifecycle,
  onboarding friction, AccessibilityService compliance, foreground-service policy, monetization, and
  COPPA basics — **followed by a large block of pasted external commentary** (reads like output from
  a different AI/model, citing Play Console support-doc URLs) analyzing those same findings from a
  Play-policy/legal angle.

**Treat the pasted external commentary in `legal.txt` as an unverified secondary source, not ground
truth.** It may be accurate, stale, imprecise, or wrong about current Google Play policy — it is
exactly the kind of "random blog/article" Phase 3 below tells you not to rely on, just dressed up
with citations. Where it asserts a Play policy requirement, verify that requirement against current
official Google Play Console Help documentation yourself before treating it as real. Where it
asserts a *technical* fact about this codebase (e.g. "Analytics could collect the Advertising ID,"
"the child device name defaults to `$MANUFACTURER $MODEL`"), re-verify that fact against the current
code — some of what it describes may already be out of date relative to the fixes below.

Read `ScrollGuard_Parental_Control_MVP.md` for the product's own architectural intent, and
`walkthrough.md` for the (unverified, self-reported) history of the parental-control build.

---

## PART 0.5 — VERIFIED SEED FACTS (confirmed by direct inspection just before this prompt was written)

Use these as a starting inventory for Phase 1 rather than re-discovering them from scratch — but
still re-verify anything you rely on for a specific finding, since the codebase changes underneath
this document over time.

**Permissions declared in `AndroidManifest.xml`:** `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `POST_NOTIFICATIONS`, `PACKAGE_USAGE_STATS` (protected;
granted only via `Settings.ACTION_USAGE_ACCESS_SETTINGS`), `INTERNET`, `ACCESS_NETWORK_STATE`,
`CAMERA`. A `<queries>` block scopes app enumeration to `ACTION_MAIN`/`CATEGORY_LAUNCHER` only
(not `QUERY_ALL_PACKAGES`).

**AccessibilityService:** `accessibility_service_config.xml` requests only
`accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"`,
`canRetrieveWindowContent="true"`,
`accessibilityFlags="flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"`. It does not
request touch-exploration, gesture-performing, or key-filtering capabilities. The current in-app
disclosure string (`strings.xml`, `accessibility_description`) reads: *"ScrollGuard uses
accessibility services to detect which app is currently open, so it can block selected apps during
your focus sessions and enforce parent-set daily time limits."* Verify independently whether this
string is actually surfaced to the user *before* they're sent to the system Accessibility settings
screen (prominent in-app disclosure), or only exists as a description string the OS may or may not
display — that distinction is exactly what Phase 2/3 needs to settle.

**Device Admin:** `device_admin_rules.xml` requests an **empty** `<uses-policies />` — zero
DevicePolicyManager capabilities (no force-lock, no wipe, no password reset). Its own code comment
states uninstall-protection comes only from being an active admin, not from any policy capability,
and that no `DevicePolicyManager` policy APIs are ever called. Verify this is still true, and verify
exactly how/when `AdminReceiver` gets enabled in the pairing/setup flow (automatically vs. an
explicit, separate, honestly-labeled step per spec Issue J).

**Firestore rules (`firestore.rules`, current state):** the family-hijack and unauthorized
child-unpair issues found in the first audit pass have apparently already been closed —
`families/{familyId}` update is now restricted via `affectedKeys().hasOnly([...])` to exactly
`['parentUid']` (initial claim) or `['childDeviceName', 'currentPairingCode']` (child bookkeeping),
family `delete` is now `isParentOfFamily`-only, and `pairing/{code}` grants `get` (single-document
fetch) but never `list`. **Do not re-report these as newly discovered bugs.** Phase 12/13's job now
is a *fresh* adversarial pass against the *current* rule set (including the newer `config/current`
child-bootstrap `create` exception and the `requests/{requestId}` subcollection) — assume nothing
about it is correct just because the obvious old holes are gone.

**Analytics/Crashlytics:** `firebase-analytics-ktx` and `firebase-crashlytics-ktx` are present in
`app/build.gradle` (they were not part of the original app). `AndroidManifest.xml` already sets
`google_analytics_adid_collection_enabled=false` with a comment noting Crashlytics transitively
pulls in Analytics, which would otherwise collect the Advertising ID. Two things to establish in
Phase 6, not just the ad-ID point: (1) whether there is a genuine, articulated product need for
Analytics specifically (crash reporting has an obvious one; general usage analytics on a
parental-control app handling child data needs its own justification), and (2) whether these SDKs
were added because of an actual product decision versus because an earlier session pattern-matched
the assumptions embedded in `legal.txt`'s pasted external commentary (which discusses Analytics/
Crashlytics as though already integrated, in a document that predates their addition). If there's no
independently-justifiable need for general Analytics beyond Crashlytics, removing it outright is
likely simpler and lower-risk than fully compliance-hardening it — but that is a product decision to
surface to the owner (Phase 21), not one to make unilaterally.

---

## PART 0.75 — EXECUTION PROTOCOL

Same discipline that made the prior two audit passes on this repo effective:

- **Authorization.** You may edit code, resources, manifest entries, and Gradle config directly
  once a technical issue is confirmed per Phase 21's rules, and create local git commits at logical
  checkpoints with messages naming the requirement/gap addressed. Do not push to any remote, force-
  push, or rewrite history. Documentation-only changes (everything under `docs/`) can be committed
  freely as you go, independent of code-fix checkpoints.
- **Don't stall on ambiguity** in the documentation/inventory work — where a fact is genuinely
  uncertain, write it down as uncertain (per the CRITICAL PRINCIPLE below) rather than pausing the
  whole engagement. Only stop and ask the owner directly when a *technical* fix requires a product
  decision this document says is theirs to make (Phase 21), and keep working on everything else
  while that one item waits.
- **Track progress durably.** Maintain `COMPLIANCE_PROGRESS.md` in the repo root: one line per
  numbered item in each phase's inventory/matrix, checked off as you complete it, with a one-line
  result and file:line or doc-section reference. This is what makes a long, multi-phase engagement
  resumable and is the working record Phase 22's report gets assembled from.
- **Work in this order:** Phase 1 → 2 (technical inventory, no edits) before Phase 3 (policy
  research) so the policy comparison is grounded in verified facts, not assumptions; Phases 4–16 can
  proceed in the order given, since each builds a specific piece of the data/authorization picture;
  do the documentation phases (9, 10, 11, 17, 18, 19) after the technical phases so the docs reflect
  verified reality rather than being drafted ahead of the facts; Phase 20's discipline applies
  throughout, not just at the end; Phase 21 (fixes) only after the relevant inventory/audit phase for
  that fix is actually done; Phase 22 last.
- **Parallelize the independent research/documentation tracks if you have subagent capability** —
  e.g. Phase 1/2 (technical inventory), Phase 4/5 (data flow mapping), and Phase 3/18/19 (policy and
  checklist drafting) can be investigated concurrently and folded into `COMPLIANCE_PROGRESS.md`;
  land any resulting code fixes through the main thread so commits stay coherent.
- **Definition of done:** every phase's required table/document exists and is evidence-backed (a
  code or config reference for every row — no guessed declarations, per Phases 1, 10, and 18's own
  rules), every phase 21-eligible technical fix is either made or explicitly deferred with a reason,
  and Phase 22's report is produced with all eight subsections and an honest verdict.

---

# PHASE 1 — COMPLETE TECHNICAL COMPLIANCE AUDIT

First inspect the entire codebase.

Do not modify anything initially.

Create a factual inventory of:

### Android capabilities

Identify every:

* Permission
* Special permission
* AccessibilityService
* Foreground service
* Overlay capability
* Device Admin functionality
* Notification permission
* Camera permission
* Background execution mechanism
* WorkManager usage
* Firebase functionality
* Other sensitive APIs

For each one explain:

* Why it exists
* Where it is declared
* Where it is used
* What data it accesses
* Whether it is essential
* Whether it is optional
* Whether the user explicitly enables it
* What happens if the user denies/revokes it

> Part 0.5 above already gives you the raw permission/service list — use it as your starting point
> and verify each entry against the current manifest rather than re-deriving it with a fresh grep.

---

# PHASE 2 — ACCESSIBILITYSERVICE AUDIT

This is one of the highest-priority areas.

Inspect the actual AccessibilityService implementation.

Document:

* Manifest declaration
* Accessibility service XML
* Requested capabilities/flags
* Event types
* Window information accessed
* Accessibility node information accessed
* Whether text/content is read
* Whether screenshots are taken
* Whether gestures/actions are performed
* Whether clicks/actions are performed
* Whether data leaves the device
* Whether information is stored
* Why AccessibilityService is technically necessary
* Exactly how it contributes to app blocking
* Whether any requested capability is unnecessary

The current implementation has previously been observed to primarily identify the foreground
application using package names and window information.

VERIFY THIS YOURSELF.

If there are unnecessary Accessibility flags/capabilities, remove them if doing so does not affect
functionality.

Do not broaden Accessibility access unnecessarily.

Also inspect whether the app incorrectly represents itself as an accessibility tool.

Do not change this merely based on guesswork.

Document the technical facts separately from the Google Play policy interpretation.

> See Part 0.5: the config XML currently requests a minimal capability set (no touch exploration,
> no gesture performance, no key filtering). Verify that's still true and that nothing in
> `BlockerAccessibilityService.kt` performs an action the declared flags wouldn't need. Also
> specifically check for any `isAccessibilityTool` manifest metadata or equivalent self-declaration.

---

# PHASE 3 — GOOGLE PLAY ACCESSIBILITY POLICY READINESS

Research the CURRENT official Google Play policy/documentation relevant to AccessibilityService.

Use official Google sources where possible.

Do not rely on random blogs or outdated articles — and treat `legal.txt`'s pasted commentary the
same way: it cites Play Console Help URLs, which is a reasonable starting pointer to go verify
directly, not a substitute for reading the current page yourself.

Create a requirements matrix:

| Requirement | Applies? | Current implementation | Gap | Required action | Evidence |
| ----------- | -------- | ---------------------- | --- | --------------- | -------- |

Specifically investigate:

* AccessibilityService API policy
* Accessibility declaration requirements
* Disclosure requirements
* Consent requirements
* Permitted uses
* Restrictions around preventing disabling/uninstalling
* Requirements relevant to parental-control applications
* Play Console declaration/review requirements
* Any relevant metadata/declaration requirements

IMPORTANT:

Separate:

**TECHNICALLY VERIFIED**

from

**POLICY INTERPRETATION**

from

**REQUIRES PLAY CONSOLE REVIEW**

from

**REQUIRES LEGAL REVIEW**

Do not falsely mark anything "compliant" simply because the implementation looks reasonable.

---

# PHASE 4 — PARENTAL CONTROL / CHILD SAFETY AUDIT

ScrollGuard is no longer only a personal productivity app.

It has a parent/child architecture.

Audit the complete parental-control model.

Document:

### Parent

* Authentication
* Account information
* Family relationship
* Parent permissions
* Parent-controlled settings

### Child

* Anonymous authentication
* Device identity
* Device name
* Pairing code
* QR pairing
* Restrictions
* Usage information
* Synchronization information
* Crash/analytics information
* Any other stored information

Determine exactly what is stored in Firebase.

Map the complete data flow:

```text
Parent device
    ↓
Firebase Auth / Firestore
    ↓
Family configuration
    ↓
Child device
    ↓
Room / local state
    ↓
Enforcement engine
```

Document every piece of data crossing this boundary.

---

# PHASE 5 — CHILDREN'S PRIVACY / COPPA TECHNICAL AUDIT

Do NOT conclude that anonymous authentication solves COPPA.

Instead, determine the actual technical facts.

Audit:

* What child-device data exists
* Whether the child can enter a name
* Device model/manufacturer information
* Firebase Auth identifiers
* Pairing identifiers
* Family identifiers
* Usage/restriction data
* Analytics
* Crashlytics
* Advertising identifiers
* Other device identifiers
* IP/network-related information where applicable
* Logs
* Retention
* Deletion

For every data element:

| Data | Source | Child/Parent | Stored? | Transmitted? | Purpose | Retention | Third party |
| ---- | ------ | ------------ | ------- | ------------ | ------- | --------- | ----------- |

Also inspect Firebase SDK behavior and configuration.

Do not rely only on our own source code.

---

# PHASE 6 — CHILDREN'S ADVERTISING / IDENTIFIER AUDIT

Pay particular attention to:

* Android Advertising ID
* Analytics
* Crashlytics
* Firebase SDK defaults
* Any advertising SDK
* Any tracking SDK

The app currently has no advertising use case.

If any unnecessary advertising identifier collection exists, remove it.

Verify after the change that:

* The app still functions
* Analytics/crash reporting still functions where intended
* No unnecessary identifier collection remains

Document exactly what changed.

> See Part 0.5: ad-ID collection already appears disabled via
> `google_analytics_adid_collection_enabled=false`. Verify that setting actually works as intended
> (it must be present in the *merged* manifest of the release build, not just source), and settle
> the open question of whether general Analytics collection (as opposed to Crashlytics) has any
> real justification on a child-data-handling app — flag it under Phase 21's "requires owner
> approval" bucket if not, rather than deciding to keep or remove it unilaterally.

---

# PHASE 7 — DATA RETENTION AND DELETION

Audit the complete lifecycle of user data.

Determine:

### Account creation

What is created?

### Pairing

What is created?

### Unpairing

What is deleted?

### Parent removes child

What is deleted?

### Parent deletes account

What is deleted?

### Child app uninstall

What remains in Firebase?

### Firestore cleanup

What happens to:

* Family document
* Child document
* App restrictions
* Pairing information
* Historical information
* Other associated records

Do not assume Firebase Auth deletion automatically deletes Firestore data.

Trace it.

If account deletion is incomplete, implement the technical deletion mechanism where appropriate.

---

# PHASE 8 — ACCOUNT DELETION

Determine whether the application currently provides:

1. In-app account deletion
2. A way for users to request deletion externally
3. Complete deletion of associated user data
4. Appropriate handling of parent/child relationships

If these are missing and can be safely implemented in the current MVP, implement them.

Do not delete data blindly.

Build a deliberate deletion flow and test it.

Document the deletion architecture.

---

# PHASE 9 — PRIVACY POLICY REQUIREMENTS

Create a repository document describing exactly what the future Privacy Policy needs to cover.

DO NOT invent legal wording and call it legally sufficient.

Instead create:

`docs/PRIVACY_POLICY_REQUIREMENTS.md`

It should contain:

* Application purpose
* Parent account data
* Child-device data
* Firebase Auth
* Firestore
* Analytics
* Crashlytics
* AccessibilityService
* Camera/QR scanning
* Device information
* Usage/restriction information
* Data sharing
* Data retention
* Data deletion
* Account deletion
* Parent/child relationship
* Children's privacy
* Security practices
* Contact information requirements
* Jurisdiction/legal review items

Clearly mark which sections require legal review.

---

# PHASE 10 — GOOGLE PLAY DATA SAFETY

Create:

`docs/GOOGLE_PLAY_DATA_SAFETY.md`

This should be a technical source-of-truth document for eventually completing the Play Console Data
Safety form.

For every collected/shared data type document:

* Data category
* Specific data type
* Collected?
* Shared?
* Required or optional?
* Purpose
* Where it comes from
* Which SDK/API collects it
* Whether it is processed on-device
* Retention
* Deletion mechanism
* Evidence in code

Do NOT fill in guesses.

Every declaration should have a code/configuration reference.

---

# PHASE 11 — GOOGLE PLAY TARGET AUDIENCE / FAMILIES

Create:

`docs/GOOGLE_PLAY_TARGET_AUDIENCE.md`

Document the product's actual audiences:

* Adult self-discipline users
* Parents
* Child-managed devices

Then identify the questions that need to be answered before Play Console submission:

* Is the app directed toward children?
* Are children a target audience?
* Is it a mixed-audience application?
* How is the product marketed?
* What child-related functionality exists?
* What data is processed from child devices?
* What Families Policy requirements could apply?

IMPORTANT:

Do not choose the final Play Console classification for me unless the facts make it unambiguous.

Flag it as:

**OWNER / LEGAL / PLAY CONSOLE DECISION**

where appropriate.

---

# PHASE 12 — PARENTAL CONTROL AUTHORIZATION

Audit whether the application establishes a valid authorization model between:

**Parent → Family → Child device**

Verify:

* Who can create the family
* Who can pair a child
* Who can modify restrictions
* Who can unpair
* Who can delete the family
* Whether child-side destructive actions are protected
* Whether parent authorization is required where appropriate
* Whether the child can understand that the device is parent-managed

The previously discovered unauthorized child-side unpair path must remain closed.

Also inspect Firestore rules, not just UI.

A UI restriction is NOT sufficient if the child can reproduce the operation directly against
Firestore.

> See Part 0.5: the direct child-delete-the-family path was closed in a prior pass — family
> `delete` is now `isParentOfFamily`-only, with a child's only route to end pairing being a
> `requests/{requestId}` entry the parent must approve. Verify this is still true, verify the child
> genuinely cannot reach the same outcome some other way (e.g. corrupting `config/current` or
> `status/current` into a state that functions like an effective unpair), and verify the *UI* also
> reflects this correctly (does the child-side UI still expose a button implying it can unpair
> unilaterally, even if the backend now blocks it — a misleading affordance is its own UX/trust bug).

---

# PHASE 13 — FIRESTORE SECURITY AUDIT

Perform a dedicated security review of Firestore rules.

Focus on:

* Family membership
* Parent permissions
* Child permissions
* Read/write/delete permissions
* Pairing code access
* Unauthorized unpairing
* Family deletion
* Cross-family access
* Enumeration
* Guessing attacks
* Rate limiting
* App Check
* Authentication assumptions

For every rule:

Explain:

**Who can do what and why.**

Pay particular attention to pairing-code guessing/rate limiting.

Determine whether the risk is real and what the appropriate architecture should be.

Do not automatically add Cloud Functions or App Check without understanding the current threat
model.

> See Part 0.5 for the current rule set's shape (`affectedKeys()` partitioning now used on
> `families/{familyId}` updates, `pairing/{code}` restricted to `get` with no `list`, ownership-
> checked `create`). Do this as a genuinely fresh adversarial pass against *today's* rules —
> including the newer `config/current` child-bootstrap `create` exception (only legal when
> `enabled == false` — verify a child can't smuggle other fields into that same create call) and the
> `requests/{requestId}` subcollection's delete semantics (child may delete only a non-`PENDING`
> request — verify there's no way to flip a request's status client-side to unlock that). Still has
> no App Check integration and no rules-emulator test suite as of this writing (per `AUDIT_PROMPT.md`
> Seed Lead 5) — decide whether closing that gap belongs in this workstream or the technical one.

---

# PHASE 14 — FOREGROUND SERVICE / ANDROID POLICY

Audit the current foreground service implementation.

Verify:

* Service type
* Manifest declarations
* Special-use subtype
* Runtime behavior
* Android 14+
* Android 15
* Android 16
* Whether the declared purpose matches actual behavior
* Whether the service is actually necessary

Separate:

**technical correctness**

from

**Google Play approval risk**.

---

# PHASE 15 — DEVICE ADMIN / UNINSTALL PROTECTION

Audit the current Device Admin implementation.

Determine:

* Why it exists
* Which feature uses it
* Whether parental control uses it
* Whether it is necessary
* Whether pairing enables it
* Whether it prevents uninstall
* What happens if disabled
* What Google Play policy implications exist
* Whether using it for child protection is appropriate

Do NOT automatically enable it for every child device.

Do NOT make product/policy decisions without documenting the tradeoff first.

> See Part 0.5: `device_admin_rules.xml` currently requests zero policy capabilities
> (`<uses-policies />` is empty) and its own code comment states no `DevicePolicyManager` policy API
> is ever called — meaning whatever protection it provides is limited to the OS's own "can't remove
> an active admin without deactivating it first" friction, not anything stronger. Verify this claim
> is still accurate, and specifically verify whether/how it's wired into the child pairing flow
> (spec Issue J requires this stay optional hardening, never load-bearing for core enforcement).

---

# PHASE 16 — CAMERA / QR PRIVACY

Verify exactly what happens during QR scanning.

Determine:

* Whether camera frames are processed locally
* Whether images are saved
* Whether anything is uploaded
* Whether recording occurs
* What permissions are requested
* Whether the rationale matches actual behavior

If the app says:

> "Nothing is recorded or stored"

verify that this is technically true.

> The pairing/QR flow uses the `zxing-android-embedded` dependency — verify whether ScrollGuard's
> own integration code around it adds any capture/save/upload behavior beyond that library's default
> on-device decode-and-discard flow, rather than assuming the library's defaults automatically make
> the claim true for this specific integration.

---

# PHASE 17 — COMPLIANCE DOCUMENTATION STRUCTURE

Create a professional repository structure:

```text
docs/
├── compliance/
│   ├── README.md
│   ├── GOOGLE_PLAY_COMPLIANCE.md
│   ├── ACCESSIBILITY_SERVICE_COMPLIANCE.md
│   ├── FAMILIES_AND_CHILD_SAFETY.md
│   ├── DATA_SAFETY.md
│   ├── PRIVACY_REQUIREMENTS.md
│   ├── DATA_INVENTORY.md
│   ├── DATA_RETENTION_AND_DELETION.md
│   ├── ACCOUNT_DELETION.md
│   ├── FIRESTORE_SECURITY.md
│   ├── PERMISSIONS_AND_APIS.md
│   └── LEGAL_REVIEW_CHECKLIST.md
```

If some documentation already exists, improve/merge it rather than creating duplicates. (As of this
writing, `docs/` does not yet exist in this repo — this phase is creating it fresh, not merging into
prior work; note `PHASE 9`/`PHASE 10`/`PHASE 11`'s documents live at `docs/` root per their own
instructions above, while this phase's list lives under `docs/compliance/` — keep that split as
given rather than "fixing" it into one location.)

---

# PHASE 18 — LEGAL REVIEW CHECKLIST

Create:

`docs/compliance/LEGAL_REVIEW_CHECKLIST.md`

This must clearly identify things that require a qualified legal/privacy professional.

Include at minimum:

* COPPA applicability
* Children's privacy obligations
* Parental consent requirements
* GDPR applicability
* Indian privacy/data-protection requirements
* International availability
* Data controller/processor responsibilities
* Firebase/third-party processor considerations
* Data retention
* Children's data deletion
* Privacy Policy
* Terms of Service
* Consumer disclosures
* Parent/child authorization model
* Age/target-audience classification

Do NOT provide fake certainty.

For each item use:

**TECHNICAL FACTS → QUESTION → WHY IT MATTERS → OWNER → STATUS**

---

# PHASE 19 — PLAY STORE SUBMISSION CHECKLIST

Create:

`docs/compliance/PLAY_STORE_SUBMISSION_CHECKLIST.md`

Include:

### App content

* Target audience
* Content rating
* Families declaration if applicable
* Privacy policy
* Data Safety
* Accessibility declaration
* Sensitive permissions declarations
* Foreground service declarations
* App access instructions for reviewers
* Reviewer credentials/instructions where necessary

### Technical

* Release signing
* Target SDK
* Android compatibility
* Release build
* R8
* Crash reporting
* Privacy configuration
* Account deletion
* Data deletion

### Store listing

* Product description accurately describes functionality
* Accessibility usage accurately described
* Parental-control functionality accurately described
* No misleading claims
* Screenshots match current UI

---

# PHASE 20 — DO NOT OVER-COMPLY BY BREAKING THE PRODUCT

This is important.

Do NOT remove major functionality merely because it involves a sensitive Android API.

Instead:

1. Understand why it exists.
2. Determine whether it is allowed/appropriate.
3. Minimize unnecessary access.
4. Provide proper disclosure.
5. Implement proper consent.
6. Document the purpose.
7. Verify policy requirements.
8. Escalate uncertain legal/policy decisions.

The goal is:

**Minimum necessary access + maximum transparency + correct documentation.**

---

# PHASE 21 — AFTER THE AUDIT

Only after the audit:

### Fix technical issues that are clearly confirmed.

Examples:

* Unnecessary Accessibility flags
* Unnecessary identifier collection
* Missing deletion mechanisms
* Incorrect Firestore authorization
* Incorrect privacy-related behavior
* Missing disclosures that are technically required
* Security issues

Do NOT implement:

* Monetization
* Legal policy decisions
* Target-audience decisions
* Business decisions
* Forced Device Admin behavior

unless the technical audit demonstrates that an implementation change is necessary and the owner
explicitly approves the product decision where required.

---

# PHASE 22 — FINAL REPORT

At the end, provide:

## A. Technical Compliance Status

| Area | Status | Evidence | Action |
| ---- | ------ | -------- | ------ |

## B. Google Play Issues

Separate into:

### Must fix before submission

### Must disclose/declare

### Requires Play Console decision/review

### Requires external verification

## C. Legal / Privacy Issues

Separate into:

### Technical issue we can fix

### Requires policy/business decision

### Requires legal advice

## D. Data Inventory

Give the complete data map.

## E. Changes Made

List every code/configuration change.

## F. Tests

Include:

* Unit tests
* Lint
* Debug build
* Release build
* Security/rules tests
* Device verification

## G. Remaining Blockers

Be explicit.

## H. Production / Play Readiness

Use one of:

**NOT READY**

**TECHNICALLY READY — EXTERNAL COMPLIANCE REVIEW REQUIRED**

**READY FOR PLAY SUBMISSION**

Do NOT use "legally compliant" as a verdict.

---

# CRITICAL PRINCIPLE

This is not a documentation exercise.

The documentation must reflect the **actual application**.

If the code says one thing and the document says another, the code wins and the document must be
corrected.

If a policy requires something technically and it can be implemented safely, implement it.

If something requires a legal/business decision, don't make the decision for the owner.

If something is uncertain, explicitly mark it as uncertain.

If a previous AI analysis — including anything in `legal.txt`, `AUDIT_PROGRESS.md`,
`UI_AUDIT_PROGRESS.md`, or this document's own Part 0.5 — was wrong, say so, with the evidence that
shows it.

This repository should become the **single source of truth for ScrollGuard's technical, Google
Play, privacy, and legal-readiness status**.

Do a serious, evidence-based audit.

Do not optimize for making the report look clean.

Optimize for making the application genuinely ready for a responsible production launch.
