# Google Play Target Audience / Families

## The product's actual audiences (as built, verified against the code)

1. **Adult/general self-discipline users** — the personal Focus Timer feature. No account, no
   parent/child relationship, entirely local. Anyone who installs the app can use this
   independent of the Parental Control feature.
2. **Parents** — create an email/password account and configure restrictions for a paired child
   device. They are the ones who make every decision the Parental Control feature enforces.
3. **Child-managed devices** — the device being supervised. The child does not create an account,
   does not consent to or configure anything, and does not provide identifying information by
   design (the one editable field, device name, is parent-visible device labeling, not a profile).

## Questions the audit prompt asked to be answered (owner/Play Console decisions, not resolved
here)

| Question | What the code shows | Decision needed |
|---|---|---|
| Is the app directed toward children? | The Focus Timer is general-audience; the Parental Control feature is parent-configured, not child-directed in the sense of being designed for a child to use themselves | **OWNER DECISION** |
| Are children a target audience? | Children are the *subject* of one feature's supervision, not the app's designed user for that feature | **OWNER DECISION** |
| Is it a mixed-audience application? | Plausibly yes, given the two distinct modes — but per Google's own stated standard (researched this session, see `docs/compliance/PLAY_POLICY_RESEARCH_NOTES.md`), mixed-audience declaration requires the app to be *actually designed for and appropriate for* both groups, not merely used by both | **OWNER DECISION**, informed by marketing/positioning |
| How is the product marketed? | Not yet determined — no store listing exists yet (see `docs/compliance/PLAY_STORE_SUBMISSION_CHECKLIST.md`) | **OWNER DECISION** |
| What child-related functionality exists? | Parental Control: pairing, per-app time limits, usage reporting to the parent (see `docs/compliance/DATA_INVENTORY.md`) | Documented above |
| What data is processed from child devices? | Device name (editable), per-app restriction/usage data, installed-app catalog — no identifiers restricted for Families apps (AAID, IMEI, etc.) are collected regardless of classification | Documented above |
| What Families Policy requirements could apply? | If declared mixed-audience: a neutral age screen (not built), Families-certified ad SDKs only (moot — no ads exist), and the identifier restrictions (already satisfied) | **OWNER/PLAY CONSOLE DECISION** — if this path is chosen, the age-screen UX is a real, not-yet-built requirement |

## What this document deliberately does not do
It does not choose adults-only vs. mixed-audience for you. The facts are genuinely ambiguous
enough (a general-audience feature and a parent-configured child-supervision feature in the same
app) that this is a real business/marketing decision with downstream technical consequences (the
age-screen requirement specifically), not something the code settles unambiguously.

## Recommendation framing (not a decision)
If the app is marketed and positioned primarily as *"a self-discipline app for adults, which also
lets a parent supervise a child's device"* — the adults-only classification is more defensible.
If it's marketed primarily as *"a parental-control/screen-time app for kids"* — mixed-audience (or
even children-primary) framing becomes more appropriate, and the age-screen work becomes
necessary before submission. **This should be decided before completing the Play Console Target
Audience and Data Safety forms**, since those forms and the eventual store listing all need to
agree with each other.
