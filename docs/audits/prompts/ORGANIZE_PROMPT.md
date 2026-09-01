# ScrollGuard — Repository Organization Pass

Clean up and organize this repository so it looks and feels like a well-maintained production
codebase: a clear structure, no stray clutter at the root, sensible grouping of documentation vs.
source vs. tooling, and nothing that would confuse a new contributor opening it for the first time.

## The one hard rule

**Never delete anything, ever — not a file, not a folder, not a line of content.** If something
looks obsolete, redundant, superseded, or genuinely useless, do not remove it: move it into a new
top-level `archives/` folder instead, preserving its original relative path underneath (e.g. a root
file `FuturePlan.txt` becomes `archives/FuturePlan.txt`, not something flattened or renamed). When
in doubt about whether something is still needed, treat that doubt as a reason to archive it, not
delete it — archiving is reversible, deletion isn't.

## What to actually do

1. **Survey the repo root and every directory** and group things sensibly: documentation together,
   compliance/audit artifacts together, build/CI config left where tooling expects it, source under
   its existing module structure left alone unless it's genuinely disorganized.
2. **Consolidate the accumulated root-level `.md`/`.txt` documents** (audit prompts, progress logs,
   the original spec, the implementation walkthrough, any ad-hoc notes) into a clear `docs/`
   structure with an index, rather than leaving them scattered at the repo root — but move them,
   don't merge/rewrite their content, and don't delete anything superseded (see the hard rule).
3. **Preserve git history on tracked files.** Use `git mv` (or an equivalent that Git recognizes as
   a rename) for anything already tracked, so `git log --follow` still works after the move. For
   untracked files, a normal move is fine.
4. **Do not move or rename anything load-bearing without updating every reference to it.** Before
   moving a file, grep the repo for its filename/path first. In particular, be careful with:
   - `keystore.properties`, `keystore.properties.sample`, and `*.jks` files — `app/build.gradle`
     resolves these via `rootProject.file(...)` at specific relative paths; moving them without
     updating `build.gradle` (and the CI workflow that references the same paths) will silently
     break release signing.
   - `firebase.json`, `.firebaserc`, `firestore.rules`, `firestore.indexes.json` if present — the
     Firebase CLI expects these at fixed locations relative to `firebase.json` unless you also
     update the paths inside it.
   - Anything referenced from `.gitignore`, `settings.gradle`, or `.github/workflows/*.yml`.
   - Generated/tooling directories (e.g. anything under `.agents/`, `.gradle/`, `.idea/`, `build/`)
     — leave these alone; they're not part of "the codebase" to reorganize.
5. **After every batch of moves, verify nothing broke:** run the project's normal build/test
   commands and confirm the app still assembles and existing tests still pass, and confirm any
   moved config (signing, Firebase) still resolves correctly.
6. **Leave source code structure (package layout under `app/src/...`) alone** unless it's genuinely
   disorganized — this pass is about repository hygiene and documentation clarity, not a code
   refactor.

## When you're done

Report exactly what moved where (a simple before → after list), what — if anything — you judged
"useless" enough to archive and why, what you left in place and why, and confirm the build/test
verification you ran after reorganizing actually passed.
