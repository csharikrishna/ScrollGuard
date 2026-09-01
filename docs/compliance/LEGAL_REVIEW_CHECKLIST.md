# Legal Review Checklist

Every item here requires a qualified legal/privacy professional. This document does not, and
cannot, resolve any of them — it exists to hand counsel a precise, evidence-backed starting point
instead of a blank page. Format per item: **TECHNICAL FACTS → QUESTION → WHY IT MATTERS → OWNER →
STATUS**.

## 1. COPPA applicability
- **Technical facts**: the app has a parent-configured child-supervision feature; the child uses
  anonymous Firebase Auth (no email/name required of the child); the only child-editable field is
  a device-name label; usage/restriction data about the child's app usage is collected and synced
  to Firebase. See `DATA_INVENTORY.md`.
- **Question**: does this specific data flow constitute "collection of personal information from a
  child" under COPPA, and if so, does the parent-initiated (not child-initiated) nature of the
  setup satisfy COPPA's parental-consent mechanism, or is additional formal consent flow required?
- **Why it matters**: COPPA violations carry FTC enforcement risk and Play Store policy
  consequences.
- **Owner**: App owner + counsel.
- **Status**: UNRESOLVED — requires legal determination.

## 2. GDPR applicability
- **Technical facts**: no code currently restricts availability by region; Firebase project
  region/data-residency has not been specifically reviewed for GDPR adequacy.
- **Question**: if the app is offered to EU users, what GDPR obligations apply (lawful basis,
  data subject rights, potential need for an EU representative, DPA with Google/Firebase)?
- **Why it matters**: GDPR fines are substantial; also affects required Privacy Policy content.
- **Owner**: App owner + counsel.
- **Status**: UNRESOLVED.

## 3. Indian privacy/data-protection requirements
- **Technical facts**: the owner is understood to be operating from India (per prior session
  context); India's Digital Personal Data Protection Act (DPDPA) has specific provisions for
  children's data (verifiable parental consent, restrictions on tracking/behavioral monitoring of
  children).
- **Question**: does DPDPA apply to ScrollGuard's operation, and does the parent-configured
  supervision model satisfy its verifiable-consent requirements?
- **Why it matters**: DPDPA is a live, actively-enforced framework with specific child-data
  provisions that could apply more directly than COPPA/GDPR depending on where users are located.
- **Owner**: App owner + counsel (India-qualified).
- **Status**: UNRESOLVED.

## 4. International availability
- **Technical facts**: no Play Console country-availability restriction has been configured (this
  is a Play Console setting, not a code state, and wasn't reviewed this session).
- **Question**: should initial availability be restricted to reduce the number of jurisdictions'
  laws that apply at launch?
- **Why it matters**: simplifies the legal surface area for a first release.
- **Owner**: App owner (business decision) + counsel (legal input).
- **Status**: UNRESOLVED.

## 5. Data controller/processor responsibilities
- **Technical facts**: Firebase (Google) processes all data this app collects; no other
  third-party processor is integrated.
- **Question**: is the owner the data controller and Google/Firebase the processor under
  applicable law, and does the relevant Firebase Data Processing Addendum need to be reviewed/
  accepted?
- **Why it matters**: determines who bears primary compliance responsibility for each obligation.
- **Owner**: App owner + counsel.
- **Status**: UNRESOLVED.

## 6. Data retention
- **Technical facts**: see `DATA_RETENTION_AND_DELETION.md` — most data is deleted on
  unpair/account deletion; one residual case (abandoned, never-claimed pairing codes) has no
  automatic expiry since the owner has declined to enable Firebase billing for a TTL policy.
- **Question**: is the residual retention case legally acceptable, or does it need a workaround
  that doesn't require billing?
- **Why it matters**: indefinite retention of even minor identifiers can be a compliance gap
  depending on jurisdiction.
- **Owner**: App owner + counsel.
- **Status**: PARTIALLY RESOLVED technically (see note); legal acceptability unresolved.

## 7. Children's data deletion
- **Technical facts**: unpair/account deletion removes all Firestore data tied to a family,
  including child-associated data. The child's own anonymous Firebase Auth identity is not itself
  deleted by any flow (see `DATA_RETENTION_AND_DELETION.md`).
- **Question**: does an orphaned anonymous Auth identity (with no data left tied to it) constitute
  retained "children's data" in a legally meaningful sense?
- **Why it matters**: could affect COPPA/DPDPA deletion-completeness requirements.
- **Owner**: App owner + counsel.
- **Status**: UNRESOLVED.

## 8. Privacy Policy
- **Technical facts**: none exists yet. `docs/PRIVACY_POLICY_REQUIREMENTS.md` documents exactly
  what it needs to cover, based on verified app behavior.
- **Question**: does the eventual policy text satisfy all applicable jurisdictions' disclosure
  requirements?
- **Why it matters**: mandatory for Play Store listing; also the operative document for COPPA/
  GDPR/DPDPA disclosure obligations.
- **Owner**: App owner drafts from the requirements doc; counsel reviews final text.
- **Status**: NOT STARTED (requirements documented, text not written).

## 9. Terms of Service
- **Technical facts**: none exists.
- **Question**: is a ToS legally necessary/advisable given the parent/child data-sharing model
  (e.g., to define the parent's authority over the child's device, liability limitations)?
- **Why it matters**: a Privacy Policy alone may not adequately cover the parent-child
  relationship's terms.
- **Owner**: App owner + counsel.
- **Status**: NOT STARTED.

## 10. Consumer disclosures
- **Technical facts**: in-app disclosures exist for Accessibility use, camera use, and permission
  rationales generally (see `PERMISSIONS_AND_APIS.md`).
- **Question**: do these satisfy consumer-protection disclosure requirements beyond Play policy
  specifically (e.g., FTC truth-in-advertising for the "nothing is recorded" camera claim)?
- **Why it matters**: overlapping but distinct from Play policy compliance.
- **Owner**: App owner + counsel.
- **Status**: LIKELY LOW RISK (claims verified technically accurate) but not legally reviewed.

## 11. Parent/child authorization model
- **Technical facts**: see `FAMILIES_AND_CHILD_SAFETY.md` — parent-authorized, parent-configured;
  the previously-open unauthorized-child-unpair gap is closed.
- **Question**: does this model satisfy any legal requirement for demonstrating parental authority
  over a minor's device/data, should that ever be questioned (e.g., a dispute between parents)?
- **Why it matters**: the app has no verification that the "parent" account is actually the
  child's legal guardian — it's self-asserted.
- **Owner**: App owner + counsel.
- **Status**: UNRESOLVED — worth explicit counsel input given no identity verification exists.

## 12. Age/target-audience classification
- **Technical facts**: see `docs/GOOGLE_PLAY_TARGET_AUDIENCE.md`.
- **Question**: adults-only, or mixed-audience, in Play Console's declaration?
- **Why it matters**: changes ad-SDK restrictions (moot, no ads), identifier restrictions (already
  satisfied either way), and review scrutiny.
- **Owner**: App owner (marketing/positioning decision) + Play Console's own review.
- **Status**: UNRESOLVED — explicitly an owner decision, not resolved by this audit.
