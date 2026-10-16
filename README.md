# fhir-walk

A terminal tool for composing and evaluating FHIRPath against a local pool of FHIR
resources. Kotlin on the JVM, HAPI's engine, JLine prompt.

Nothing leaves the machine. There is no engine endpoint, no package-server fetch and
no terminology lookup — the base StructureDefinitions ship as a JAR dependency.

```
./gradlew installDist && build/install/fhir-walk/bin/fhir-walk samples/biobank

r4:pool(22)> :focus Specimen/al-pa-001-1
focus Specimen/al-pa-001-1  (pathogen.ndjson:1)

r4:Specimen/al-pa-001-1> Specimen.parent.resolve().parent.resolve().parent.resolve().parent.resolve().identifier.value
  [0] string  ST-2026-0001
```

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
r4:pool(22)> Specimen.container.extension('
  observed on Specimen.container
  https://biobank.example.org/StructureDefinition/container-lot-number   9 ×  string
```

`Specimen.extension('` offers a *different*, non-overlapping set (freezing time,
time-to-freezing, storage, aliquot-group purpose). The census also
backs `ofType()` after `resolve()` (types actually referenced), code systems, profiles
and frequency ranking of elements — populated elements are grouped apart from merely
legal ones, which matters in a schema as large as FHIR's.

**`:why` tells you where an expression went empty**, and — because the pool is there —
whether that is a gap in your data or the wrong level of the hierarchy:

```
r4:Specimen/swab-002> :why Specimen.collection.collectedDateTime
  Specimen                                         1
  .collection                                      0      <-- empty here
  .collectedDateTime                               —
  Specimen.collection is populated in 1 of 20 resource(s) of that type
```

One hit in twenty says "wrong level" — only the master sample carries `collection`.
Twelve in twenty (as for the freezing-time extension) says the expression is right and
the data is incomplete. That distinction is the whole value of having the pool.

## Sample pools

`samples/biobank` is a real-world biobanking hierarchy: a native stool sample
aliquoted through **aliquot groups** into direct aliquots and swabs, each swab
aliquoted again into pathogen- and genetics-analysis sets. Aliquots never reference
their primary directly — the group sits in between, so walking the lineage means
chaining `resolve()` (four hops from a terminal aliquot to the master sample).

```
spec-master-stool                       ST-2026-0001   (bundle)
├── ag-stool-direct                     aliquot group  (one file per group)
│   └── al-stool-001..003                              (NDJSON)
└── ag-stool-swab                       aliquot group
    └── swab-001, swab-002
        ├── ag-sw00N-patho              aliquot group
        │   └── al-pa-00N-1, al-pa-00N-2
        └── ag-sw00N-gen                aliquot group
            └── al-ge-00N-1, al-ge-00N-2
```

20 Specimens, a donor and one XML Observation, deliberately spread across all three
import paths so one pool is assembled from a bundle, a directory tree and NDJSON. It
carries custom extensions at four distinct paths — `specimen-freezing-time` and
`specimen-time-to-freezing` on the Specimen root, `container-lot-number` on
`Specimen.container`, `collection-ambient-temperature` on `Specimen.collection` — plus
a nested `specimen-storage` extension with `temperature`/`freezer`/`position`
sub-extensions. Coverage is deliberately partial: some specimens have no freezing time
recorded, which is what makes `:why` and the census worth reading.

`generate.py` regenerates it. `samples/basic` is the small fixture set the
non-biobank tests use.

## Commands

Type a FHIRPath expression, or:

| command | effect |
|---|---|
| `:load <path>` | import a file, bundle, NDJSON, or directory tree |
| `:pool` | what is in the pool |
| `:focus [label\|#index]` | evaluate against one resource (blank clears) |
| `:all <expr>` | evaluate across the whole pool |
| `:why <expr>` | find the step where the expression goes empty |
| `:ext [path]` | extension URLs observed in the pool |
| `:hits` | expand the last pool-wide result |
| `:fhir [version]` | switch FHIR version (resets the pool) |

With a focus set, a bare expression runs against that resource; with no focus it runs
across the pool. TAB completes.

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
./gradlew test                              # 43 tests
./gradlew installDist                       # then run the start script:
build/install/fhir-walk/bin/fhir-walk samples/biobank
```

Requires JDK 21.

**Use the start script, not `./gradlew run`.** Gradle does not hand the process a
TTY, so JLine falls back to a dumb terminal and TAB completion is silently disabled
(it warns about this on startup). `gradle run` is still useful for piping a script of
commands in; interactive work wants `installDist`.
