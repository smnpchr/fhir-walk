# fhir-walk

A terminal tool for composing and evaluating FHIRPath against a local pool of FHIR
resources. Kotlin on the JVM, HAPI's engine, JLine prompt.

Nothing leaves the machine. There is no engine endpoint, no package-server fetch and
no terminology lookup — the base StructureDefinitions ship as a JAR dependency.

```
./gradlew installDist && build/install/fhir-walk/bin/fhir-walk samples/basic

r4:pool(6)> :focus Observation/obs2
focus Observation/obs2  (observations.ndjson:1)
  Observation  {"resourceType":"Observation","id":"obs2","status":"final","code":{"coding":[{"system…
  … shortened to fit — :more

r4:Observation/obs2> Observation.subject.resolve().name.family
  [0] string  Mustermann
```

That `resolve()` crossed files: `obs2` came from an NDJSON file, the Patient it
references from a bundle.

## What it does that other FHIRPath tools don't

**`resolve()` works across the whole pool.** Tools built on a single submitted
resource can only resolve what is inside it. Here the pool *is* the implementation of
`resolve()` (`PoolEvaluationContext`), so a reference in an NDJSON file resolves a
resource that came from a bundle in another directory.

**Completion is fed by your data, not just the schema.** Two independent sources:

| source | answers | from |
|---|---|---|
| schema | what is *legal* here | HAPI runtime model (`ModelNavigator`) |
| corpus | what is *present*, with counts | the import-time census (`Census`) |

Extension URLs can only come from the second, and scoping them to the element they
were observed on is what keeps the list short:

```
r4:pool(6)> Patient.name.extension('
  observed on Patient.name
  http://fhir.de/StructureDefinition/humanname-namenszusatz   1 ×  string
```

`Patient.extension('` offers a *different*, non-overlapping set — `patient-birthPlace`
and `patient-nationality`, which sit on the resource root rather than on the name.

You do not have to get that far by hand: at a member position the whole call is
offered, parens and quotes included, so one TAB lands a valid expression.

```
r4:pool(6)> Patient.ext⇥
  extension() — observed here
  extension('http://hl7.org/fhir/StructureDefinition/patient-birthPlace')    1 ×  Address
  extension('http://hl7.org/fhir/StructureDefinition/patient-nationality')   1 ×
```

The census also backs `ofType()` after `resolve()` (types actually referenced), code
systems, profiles and frequency ranking of elements — populated elements are grouped
apart from merely legal ones, which matters in a schema as large as FHIR's.

**`:why` tells you where an expression went empty**, and — because the pool is there —
whether that is a gap in your data or the wrong level of the hierarchy:

```
r4:Patient/pat1> :why Patient.contact.name.given
  Patient                                          1
  .contact                                         0      <-- empty here
  .name                                            —
  .given                                           —
  Patient.contact is populated in 1 of 2 resource(s) of that type
```

That last line is the point. One hit in two says the element is real and this resource
simply lacks it; zero in two would say the expression is aimed at the wrong level, or
the element name is wrong. Only the pool can tell those apart.

## Sample pool

`samples/basic` is a small fixture: two Patients, three Observations and an Encounter,
spread deliberately across all three import paths so one pool is assembled from a
bundle (with `fullUrl`s), a directory tree and an NDJSON file. It carries extensions at
three distinct element paths plus one nested extension, and its coverage is uneven on
purpose — one Patient has `contact`, the other does not — which is what makes `:why`
and the census worth reading.

```
r4:pool(6)> :ext
  Patient            1 ×  http://hl7.org/fhir/StructureDefinition/patient-birthPlace    Address
  Patient            1 ×  http://hl7.org/fhir/StructureDefinition/patient-nationality
  Patient.extension('…/patient-nationality')
                     1 ×  code                                                 CodeableConcept
  Patient.name       1 ×  http://fhir.de/StructureDefinition/humanname-namenszusatz      string
  Encounter          1 ×  http://example.org/StructureDefinition/encounter-ward          string
```

Point `:load` at your own data for anything beyond that. Some tests additionally use a
deeper, domain-specific fixture that is not published; they skip when it is absent.

## Commands

Type a FHIRPath expression, or:

| command | effect |
|---|---|
| `:load <path>` | import a file, bundle, NDJSON, or directory tree |
| `:pool [name]` | switch pool; alone, what is in the current one |
| `:pools` | the open pools, their sizes and paths |
| `:open <name> [path]` | add a pool, switch to it, and load `path` into it |
| `:close <name>` | drop a pool |
| `:log [n]` | a git pool's line, newest first, with what each commit changed |
| `:at <rev>` | move a git pool to a revision: a branch, tag, id or `branch~2` |
| `:step [-n\|+n]` | move a git pool along its line; alone, one commit back |
| `:delta` | what this revision added, modified and deleted |
| `:scope [delta\|all]` | run pool-wide over the delta only, or over everything |
| `:focus [label\|#index\|expr\|[n]\|..]` | evaluate against one resource or element (blank clears) |
| `:all <expr>` | evaluate across the whole pool |
| `:why <expr>` | find the step where the expression goes empty |
| `:ext [path]` | extension URLs observed in the pool |
| `:history [filter]` | recorded pool-wide expressions, newest first |
| `:view #n\|name\|@view …` | a table: one row per resource, a column per recorded expression |
| `:name [name [#n]]` | name the expression just run, or `#n`, for `:view`; alone, list the names |
| `:unname <name>` | forget a name |
| `:save [view]` | save the last `:view`, to rerun as `:view @view`; alone, list the saved views |
| `:unsave <view>` | forget a saved view |
| `:workspace [name]` | switch workspace; alone, show it and list the others |
| `:more` | reprint the last listing in full, nothing shortened |
| `:limit [rows\|off]` | rows per listing before the rest waits for `:more` |
| `:fhir [version]` | switch FHIR version (resets the pool) |

With a focus set, a bare expression runs against that resource; with no focus it runs
across the pool.

### Focusing on an element

`:focus` also takes an expression. It runs against the current focus (or across the
pool, with none) and its single result becomes the focus — usually an element, not a
resource. Expressions and completion are then relative to it:

```
r4:Observation/obs2> :focus code
focus Observation/obs2.code  (CodeableConcept in observations.ndjson:1)
  CodeableConcept  {"coding":[{"system":"http://loinc.org","code":"8302-2","display":"Body height"}]}

r4:Observation/obs2.code> coding.code
  [0] code  8302-2
```

Completion keeps both axes: the element's path is spelled the way the census
records it, so `coding.code = '` still offers the codes observed there. Each focus
remembers the one it was reached from; `:focus ..` steps back up, and a blank
`:focus` clears the lot.

The announcement is itself a listing, so `:more` right after `:focus` prints the
focused value whole — the quickest way to see what a long path landed on. Later,
`$this` shows it again.

A focus needs exactly one value. When the expression yields several, they are listed
with numbers, and `:focus [n]` picks one:

```
r4:pool(6)> :focus Observation.code.coding
3 values from 3 resources; focus needs one — pick it with  :focus [n]
  [0] Observation/obs1             Coding  {"system":"http://loinc.org","code":"29463-7","display":"…
  [1] Observation/obs2             Coding  {"system":"http://loinc.org","code":"8302-2","display":"B…
  [2] Observation/obs3             Coding  {"system":"http://loinc.org","code":"8480-6","display":"S…
  … shortened to fit — :more

r4:pool(6)> :focus [1]
focus Observation/obs2.code.coding  (Coding in observations.ndjson:1)
  Coding  {"system":"http://loinc.org","code":"8302-2","display":"Body height"}
```

An indexer inside the expression would not do this across the pool: it applies within
each resource, and every Observation here has exactly one coding. `[n]` picks a row of
the last result: this listing, or an expression evaluated under a focus. Any other
result (`:all`, an empty one, a `:focus` that did not pick) leaves nothing to pick,
so `[n]` never reaches past what is on screen; command output such as `:pool` or
`:more` leaves the rows alone. Picking keeps them, so `:focus [0]`, `:focus ..`,
`:focus [1]` tries one row after another. When the row is one of several from its resource, the label spells
it as the indexer that selects it (`Patient/pat1.name[1]`), so it still names exactly
one element.

A result that is a resource in the pool, such as `:focus subject.resolve()`, becomes an
ordinary resource focus. Labels are matched before expressions: `Observation/obs2` is a
label, even though it also parses as a division.

With an element in focus HAPI has no root resource, so `%resource` is unavailable.

### Recorded expressions and `:view`

A pool-wide expression — a bare one with no focus, or any `:all` — is recorded when
it parses and matches at least one resource. The log is the workspace's `expressions`
file (see [Workspaces](#workspaces)), one expression per line, append-only and without duplicates, so a number from
`:history` keeps meaning the same expression across sessions. Expressions typed under
a focus are not recorded: `coding.code` means nothing once the focus is gone. A
misspelt element parses fine and only ever comes back empty, which is why a match is
the bar.

The file is yours to edit. Blank and repeated lines are skipped, so `#n` is the n-th
distinct expression: appending a line or editing one in place keeps the numbers, while
deleting a line renumbers everything after it. Sessions running side by side share the
file and agree on the numbers. If the file cannot be read or written, that is reported
once and the session keeps its expressions in memory, leaving the file untouched.

`:view` turns recorded expressions into columns:

```
r4:pool(6)> :history
  #3  Patient.name.given
  #2  Patient.birthDate
  #1  Patient.name.family

r4:pool(6)> :view #1 #2 #3
2 Patient row(s)
                #1 name.family  #2 birthDate  #3 name.given
  Patient/pat1  Mustermann      1964-08-12    Erika, Maria, Rika
  Patient/pat2  Schmidt         1979-02-28    Hans
```

Every column must start from the same resource type, which decides the rows: every
resource of that type, including those where every cell is empty. Columns must yield
primitives — `Patient.name.family`, not `Patient.name` — and several values in one cell
are joined with `, `. Anything else is refused before a row is printed. `resolve()` and
`extension('…')` work in a column as anywhere else. TAB after `:view` offers the
recorded expressions by number.

A column can also be named, and is then asked for and titled by its name. `:name
family` right after a pool-wide expression names that one; `:name family #1` names one
from `:history`:

```
r4:pool(6)> Patient.name.family
2 of 6 resource(s) matched
  Patient/pat1                 Mustermann
  Patient/pat2                 Schmidt

r4:pool(6)> :name family
family  Patient.name.family

r4:pool(6)> :view family #2
2 Patient row(s)
                family      #2 birthDate
  Patient/pat1  Mustermann  1964-08-12
  Patient/pat2  Schmidt     1979-02-28
```

Names are kept in the workspace's `names` file, one `name<TAB>expression` per line, and
handled like the log: re-read when it changes, left untouched once it cannot be read.
A name holds the expression's text rather than its number, so renumbering the log by
hand does not change what `family` means. Only expressions that would be recorded can
be named this way — pool-wide, and matching something — but a name may hold a
structured one such as `Patient.name`; it is `:view` that asks for primitives.
`:history` shows each entry's names beside it, `:name` alone lists them all, and `:name family #3` rebinds a name, saying what it
held before.

A name starts with a letter, so it never reads as a number, and names are
case-sensitive. Typing a command without its colon (`view #1`, `name family #1`) still
gets a hint, unless the word is an element where the expression would run *and* the
line parses as FHIRPath: a bare `name` is `Patient.name`, not `:name`.

A view can be saved and rerun. `:save people` (or `:save @people`) saves the last
`:view`, `:view @people` runs it again, and further columns can follow
(`:view @people #4`); `:save` alone lists the saved views and `:unsave people` forgets
one. A saved view holds its columns' expressions rather than their numbers, and its
titles are renumbered against the log as it stands: a column whose expression has
left the log keeps its place with no number, and gets one back when it returns. A
column asked for by name follows the name, so rebinding `family` changes what the
view computes along with its title; once the name is forgotten, the column keeps the
expression it was saved with.

### Several pools

A session can hold several pools, each with its own census and its own focus.
Completion offers what is in the current pool, and `resolve()` stays inside it:

```
r4:pool(6)> :open tree samples/basic/tree
loaded 3 resource(s) from 2 file(s); pool tree now 3
r4:tree(3)> Observation.subject.resolve()
0 of 3 resource(s) matched
  nothing matched — :why
r4:tree(3)> :pool pool
pool pool: 6 resource(s)
r4:pool(6)> :pools
* pool       6  /home/me/fhir-walk/samples/basic
  tree       3  /home/me/fhir-walk/samples/basic/tree
```

The Patients those Observations reference are in the bundle, which only the first
pool holds. A session starts with one pool called `pool`, and nothing changes for
anyone who never opens another. Once there are several, the prompt names the pool
before a focus: `r4:tree:Observation/obs2>`.

Recorded expressions, names and saved views belong to the workspace, not to a pool,
so `:view @people` runs in any of them. `:fhir` keeps the pools and empties them all.

### Pools from git revisions

A pool can be a directory inside a git repository, at a revision: `dir@rev`. It is
read straight from the object database. Nothing is checked out, the working tree is
never touched, and nothing is fetched or pushed.

- **`dir@` with nothing after it** opens the branch HEAD is on. That branch is saved,
  so the pool keeps following it even after HEAD moves elsewhere.
- **TAB after the `@`** lists the repository's branches and tags.
- **A plain `dir` inside a repository** still loads the working tree from disk,
  uncommitted changes included. `:open` says when the directory has a history to open
  instead.

```
r4:pool(0)> :open in registry/in/import@branch/out
pool in at 4844b16 on branch/out, 2026-10-18 09:06 sync2: 3 resource(s) from 3 file(s)
r4:in@4844b16(3)> :log
branch/out in /home/me/registry/in/import, newest first
* 4844b16  2026-10-18 09:06      Δ1  sync2
  e6d2df0  2026-10-18 09:06      Δ1  sync
  551f93e  2026-10-18 09:06      Δ2  import
r4:in@4844b16(3)> :step
pool in at e6d2df0 on branch/out, 2026-10-18 09:06 sync — Δ1: 1 modified
r4:in@e6d2df0 Δ1> Specimen.type.coding.code
1 of 1 resource(s) matched
  Specimen/s1                  EDTA_PLASMA
```

A commit's **delta** is what it added, modified and deleted against its first parent.
Moving with `:step` or `:at` sets the scope to the delta, so pool-wide expressions,
`:all` and `:view` run over just what that commit changed. `resolve()`, completion
and `:why` still see the whole revision, so a changed Specimen still reaches the
Patient that did not change. `:delta` lists it, deletions included by label, and
`:scope all` goes back to the whole pool.

TAB after `:at` lists the line's commits newest first, by date and short id with
their subject, the current one marked; picking one moves there. Branches and tags follow.
`:step -1` follows the first parent. `:step +1` walks towards the tip of the pool's
line, the branch it was opened on, or the last branch it was moved to with `:at`.
Nothing about this is specific to a branch name: a pool on `master` steps the same
way.

A workspace saves each git pool's source, the commit it was left at and its scope.
The next start restores it at that commit rather than the branch tip, and `:at
branch/out` catches up. A source that has gone is reported and stays saved.
Moving to a neighbouring revision parses only the files whose content differs.

The motivating case, a sync tool's registries, and the design are in
[docs/pools.md](docs/pools.md).

### Workspaces

Everything that outlives a session belongs to a workspace: the expression log, names,
saved views, the up-arrow history, and the state the session was left in — FHIR
version, the pools with their paths and focus, which pool was current, and `:limit`.
Starting in a workspace restores that state:

```
$ fhir-walk --workspace basic samples/basic
workspace basic, FHIR R4
loaded 6 resource(s) from 3 file(s); pool now 6
r4:pool(6)> :focus Patient/pat1
focus Patient/pat1  (bundle.json#entry[0])
  …
r4:Patient/pat1> :quit

$ fhir-walk --workspace basic
workspace basic, FHIR R4
loaded 6 resource(s) from 3 file(s); pool now 6
focus Patient/pat1
r4:Patient/pat1>
```

The default workspace is `~/.fhir-walk` itself; a named one lives in
`~/.fhir-walk/workspaces/<name>/` and is created the first time something is saved
there. Separate workspaces keep datasets apart: the expressions worth keeping for one
pool are noise against another. `:workspace` shows the current one and lists the others, and `:workspace other`
switches mid-session: a pool, focus and history of its own.

The state is a description rather than a replay of commands, so each part is restored,
or reported, on its own. A loaded path that has gone is reported and kept, to load
again once it is back. The focus is saved as the chain of `:focus` steps that reached
it, so `:focus ..` still steps back up; one that no longer resolves ends the focus as
far as it got. `:fhir` empties the pools and forgets their paths.

On the command line, `--fhir` wins over the version the workspace was left on, and
paths load on top of the restored ones. A pool is one version, so a session on another
version than the workspace's restores neither its paths nor its focus, and says so;
they stay saved until something changes, just as `:fhir` would reset them. A
workspace left on a version this build cannot open starts on the default instead. `--fresh` starts with an empty pool and no
focus, and leaves the saved state as it is until something changes: the first
`:load`, `:focus`, `:limit` or `:fhir` replaces it.

Two sessions in one workspace share its log and names, as above. Each writes its own
state, so the one that changes something last is the one the next start restores.

## Output

A listing fills the window — rows beyond it, and values too wide for it, are kept
rather than discarded. `:more` reprints the last listing whole, with every value
pretty-printed and the origin of each hit:

```
r4:pool(6)> :all Observation.code.coding
3 of 6 resource(s) matched
  Observation/obs1             {"system":"http://loinc.org","code":"29463-7","display":"…
  Observation/obs2             {"system":"http://loinc.org","code":"8302-2","display":"B…
  … 1 more of 3 — :more

r4:pool(6)> :more
3 of 6 resource(s) matched
  Observation/obs1  (bundle.json#entry[2])
      Coding
        {
          "system": "http://loinc.org",
          "code": "29463-7",
          "display": "Body weight"
        }
  Observation/obs2  (observations.ndjson:1)
      Coding
        {
          "system": "http://loinc.org",
          "code": "8302-2",
          "display": "Body height"
        }
  …
```

Row and width limits come from the terminal, so they track the window rather than a
fixed guess. `:limit 50` sets the rows explicitly and `:limit off` prints everything
up front, letting the terminal scroll instead.

## Completion

TAB completes, including inside the `:all` and `:why` arguments, which are expressions
like any other. On an empty line it lists the pool's resource types; after a `.` the
elements and functions available there, pool-populated ones first; inside
`extension('…')` the URLs observed on that element.

Types are followed through calls where they can be known. `resolve()` takes the type
its references were observed to point at. `repeat(…)` keeps its input's type when the
projection maps that type to itself, which is how hierarchies are walked:
`Specimen.repeat(parent.resolve()).` completes as a Specimen, and
`Questionnaire.item.repeat(item).` as an item. A `repeat` whose rounds could be
different types, such as `repeat(subject.resolve())`, stays untyped.

The first TAB opens the candidate menu: the grouped list appears and the first
candidate goes into the line. **Up/Down** walk the candidates from there, as do further
TABs, and Enter takes whichever is shown. That is how you pick a long extension URL
without typing it. A candidate in a string position brings its own closing `')`, so
Enter always submits something that parses.

## Import paths

Three loaders, one extension point (`Importer`):

1. **Bundles** — flattened to their entries, recursively, keeping `fullUrl` so urn:
   references resolve. The Bundle itself is not retained, so the census counts each
   real resource once.
2. **Directory trees** — recursive walk picking up `.json`, `.xml`, `.ndjson`, `.jsonl`.
3. **NDJSON** — one resource per line, the bulk-export shape.

## FHIR versions

R4 by default; `--fhir r5` or `:fhir r5` to switch. One pool is always one version —
switching resets it. Mixed-version pools are the one complexity refused outright.

R4 and R5 structures ship by default; R4B and DSTU3 need their dependency lines
uncommented in `build.gradle.kts` (the enum entries are already there, and
`FhirVersion.create()` reports a clear error until then). Since FHIR JSON carries no
version marker, the version is declared rather than sniffed — but a parse failure
retries under the other versions and tells you which one fits.

## Architecture

```
model/     FhirVersion -> FhirContext        <-- the only version-aware code
           ModelNavigator                   schema axis of completion
           FhirPathFunctions                static signature + type-effect table
pool/      ResourcePool, PooledResource     pool + resolve() indexes
           GitSource, RevisionLoader        a directory in git, at a revision
           Importer                         bundle | dirtree | ndjson loaders
           Census, CensusWalker             corpus axis of completion
eval/      PoolEvaluationContext            pool-backed resolve()
           Evaluator                        single + pool-wide fan-out
           FhirPathTokenizer                prefix split, cursor context
           PromptLine                       where the expression starts in a line
           PrefixWalker                     powers :why
           ViewBuilder                      powers :view
complete/  FhirPathCompleter                merges both axes into JLine candidates
repl/      Repl, FhirPathParser, Render
           Session, Pool                    per version; per pool, several
           GitPool                          a pool's revision, line and scope
           Workspace, SavedState            what outlives a session
           ExpressionLog, NameStore,        its files, on WatchedFile /
           ViewStore, KeyedStore            KeyedStore
```

Everything outside `model/` takes a `FhirContext` and is version-agnostic by
construction, so there is nowhere to accidentally hardcode R4. That is deliberate:
the version-independent `IFhirPath` / `ca.uhn.fhir.context` APIs replace the
version-specific `FHIRPathEngine` / `TypeDetails` route, which would have needed a
per-version adapter for every feature.

## Things worth knowing

- **Choice elements navigate by base name.** HAPI follows the spec: `Observation.value`,
  not `Observation.valueQuantity` (which evaluates to empty). The census and the
  completer both use base names for this reason, and the census records the concrete
  types observed so `Observation.value` can still be typed — from the data — well
  enough to complete `.unit`.
- **Arrow keys are bound in both encodings.** JLine asks the terminal for
  application-cursor-keys mode (DECCKM) and then binds only the SS3 form (`ESC O A`)
  that mode implies; a terminal or multiplexer that keeps sending the CSI form
  (`ESC [ A`) was left typing a literal `[A` instead of moving. That is why arrow
  navigation worked in some terminals and not others. Both forms are now bound
  explicitly, in the line editor and in the completion menu.
- **FHIRPath strings are single-quoted.** A double quote delimits an *identifier*, so
  `extension("url")` fails to parse with a message that never mentions quoting.
  Completion therefore opens the quote for you on every function whose first argument
  is a string, and a failed parse containing `"` gets an explicit hint.
- **Nothing is truncated without a way back.** Earlier versions capped several
  listings with no expansion path, which is worse than printing too much; every
  shortened listing now names `:more`.
- **Candidate lists are kept under 100 entries**, because past that JLine asks "do you
  wish to see all N possibilities?" instead of showing them. So a bare TAB offers the
  pool's resource types rather than all 146, and ten common functions rather than the
  whole table; typing one character opens up the full lists. In a type-argument
  position the census answer wins outright — if every reference at that path points at
  a Patient, you get `Patient`, not the schema.
- **Completion order within a group** is JLine's, not frequency. Counts are shown in
  each candidate's description; making frequency drive the order needs a custom
  menu renderer.
- **No business-identifier `resolve()`** yet. It is in HAPI's signature and common in
  real data, but needs a secondary index and an ambiguity policy — worth adding once
  real data shows it is needed.

## Dependency notes

Three HAPI dependencies are optional in its POM but required in practice:
`org.fhir:ucum` and `hapi-fhir-caching-caffeine` (without it `newFhirPath()` throws
HAPI-2200), and `hapi-fhir-validation-resources-*`, without which `ofType()`/`as()`
fail with HAPI-0255. The Kotlin plugin must stay at or above the stdlib HAPI pulls in
transitively via okhttp.

JGit reads git pools. It ships transport code, but fhir-walk only opens local
repositories and reads trees, blobs and diffs from them.

## Build

```
./gradlew test                              # 221 tests; 18 skip without the local fixture
./gradlew installDist                       # then run the start script:
build/install/fhir-walk/bin/fhir-walk samples/basic
```

Requires JDK 21.

**Use the start script, not `./gradlew run`.** Gradle does not hand the process a
TTY, so JLine falls back to a dumb terminal and TAB completion is silently disabled
(it warns about this on startup). `gradle run` is still useful for piping a script of
commands in; interactive work wants `installDist`.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
