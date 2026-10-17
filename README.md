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
| `:pool` | what is in the pool |
| `:focus [label\|#index\|expr\|[n]\|..]` | evaluate against one resource or element (blank clears) |
| `:all <expr>` | evaluate across the whole pool |
| `:why <expr>` | find the step where the expression goes empty |
| `:ext [path]` | extension URLs observed in the pool |
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
the last numbered listing — this one, or the result of an expression evaluated under a
focus — as long as the focus has not changed since, so `:focus [0]`, `:focus ..`,
`:focus [1]` works. When the row is one of several from its resource, the label spells
it as the indexer that selects it (`Patient/pat1.name[1]`), so it still names exactly
one element.

A result that is a resource in the pool, such as `:focus subject.resolve()`, becomes an
ordinary resource focus. Labels are matched before expressions: `Observation/obs2` is a
label, even though it also parses as a division.

With an element in focus HAPI has no root resource, so `%resource` is unavailable.

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
           Importer                         bundle | dirtree | ndjson loaders
           Census, CensusWalker             corpus axis of completion
eval/      PoolEvaluationContext            pool-backed resolve()
           Evaluator                        single + pool-wide fan-out
           FhirPathTokenizer                prefix split, cursor context
           PromptLine                       where the expression starts in a line
           PrefixWalker                     powers :why
complete/  FhirPathCompleter                merges both axes into JLine candidates
repl/      Repl, Session, FhirPathParser, Render
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

## Build

```
./gradlew test                              # 59 tests
./gradlew installDist                       # then run the start script:
build/install/fhir-walk/bin/fhir-walk samples/basic
```

Requires JDK 21.

**Use the start script, not `./gradlew run`.** Gradle does not hand the process a
TTY, so JLine falls back to a dumb terminal and TAB completion is silently disabled
(it warns about this on startup). `gradle run` is still useful for piping a script of
commands in; interactive work wants `installDist`.
