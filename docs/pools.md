# Pools: several at once, and stepping through a registry's history

Status: steps 1 and 2 are built (several named pools; git revisions with stepping). Step 3, comparing two arbitrary revisions, is not.

## The use case

fhir-pipes syncs a local LIMS with a central one through a sequence of
sub-syncs, *pipes*. Each pipe has its own git registry:

```
<fhir-pipes-base>/registry/<pipe>/import/<ResourceType>/<one file per resource>
```

- `master` holds the resources as imported.
- `branch/out` holds them after the transformation rules ran: the state that is
  published to the target.

A commit on `branch/out` is a sync run, and **its delta against its parent is what
that run published**. That delta is the main thing to inspect. Re-applying a changed
rule produces an ordinary commit whose delta should contain only what the rule
touches: change the rule for stool samples, and only stool samples should show up.
Nothing records which rule ran; the diff is the only evidence. Sync runs never
delete. A manual fixup can, such as removing dummy patients fetched by mistake.

There are four pipes today: in, specimen, and two observation pipes. Each registry
is context-complete. Whatever a pool's resources reference, the pipe imported too,
so `resolve()` never has to look across pools and commits never need correlating
across registries.

Comparing `master` with `branch/out` at one point, to see what a transformation did,
matters less: `git diff` already covers most of it.

## Staying generic

fhir-walk learns git revisions, deltas and several named pools. It never learns
fhir-pipes. The pipe names, the `import/` subdirectory and the branch name
`branch/out` are configuration in a workspace, so a change to fhir-pipes' layout is
an edit to a config, not to the code. The README names fhir-pipes as the use case
that motivated the feature, and that is the only place it appears.

## Model

### A pool has a name and a source

| source | spelling | contents |
|---|---|---|
| directory | `samples/basic` | what `:load` reads today |
| git revision | `~/fp/registry/specimen/import@branch/out` | the tree at that revision, under that path |

A git source is a path inside a repository, plus a revision, which defaults to
`HEAD`. The repository is found by walking up from the path, so the registry layout
needs no special casing. Files are read from the object database. Nothing is
checked out, and the working tree is never touched.

A session holds several pools. One of them is current: expressions, `:focus`, `:all`,
`:view` and completion work against it. Today's single pool becomes a pool called
`pool`, so nothing changes for anyone who never names one.

### The delta filters the snapshot rather than replacing it

The pool at a revision is always the **full snapshot**. Its census, completion and
`resolve()` see everything, exactly what the transformation saw. A changed Specimen
usually references a primary or a patient that did not change in the same commit,
and a separate delta pool would resolve those references to nothing.

The delta (files added, modified or deleted against the first parent, under the
source path) is a **scope** over the snapshot:

- With the scope on, `:all`, `:view` and pool-wide expressions run over the delta's
  resources only. `resolve()` still reaches the whole snapshot.
- Deleted resources are not in the snapshot. They are listed by key, and `:at
  <rev>~1` evaluates against the parent if their content matters.
- The scope turns on when you step, because stepping is how you ask what a run
  published. `:scope all` turns it off.

### Version-level and pool-level state

`Session` splits in two:

- **Per version (shared):** `FhirContext`, `Render`. `:fhir` still replaces all of
  it and still empties every pool, because pools are homogeneous by construction.
- **Per pool:** `ResourcePool` and its census, `Evaluator` (whose `resolve()` is
  bound to the pool), `ModelNavigator`, `PrefixWalker`, `Importer`, `FocusResolver`,
  `ViewBuilder`, and the focus. Switching pools keeps each pool's focus.

Recorded expressions, names and saved views stay workspace-wide, so the same
`:view @published` runs in any pool and at any revision.

## Commands

| command | effect |
|---|---|
| `:pools` | the pools, their sources and sizes, and which one is current |
| `:pool [name]` | switch to a pool; alone, what is in the current one (as now) |
| `:open <name> [source]` | add a pool and switch to it; with no source, an empty one to `:load` into |
| `:close <name>` | drop a pool |
| `:log [n]` | the current pool's branch, newest first: commit, date, subject, delta size |
| `:at <rev>` | move the current pool to a revision (`a1b2c3`, `branch/out~3`, a tag) |
| `:step [-n\|+n]` | move along first-parent history; alone, one step back |
| `:delta` | added, modified and deleted resources at this revision |
| `:scope [delta\|all]` | what pool-wide evaluation runs over |

`:load` keeps adding to the current pool, and refuses on a git pool, whose contents
are its revision.

The prompt carries the pool and, for a git pool, the revision and the scope:

```
r4:specimen@3f2a1c Δ12> :view @published
```

A session that verifies a run:

```
r4:pool(0)> :workspace fhir-pipes
workspace fhir-pipes, FHIR R4
pool in        registry/in/import@branch/out        412 resource(s)
pool specimen  registry/specimen/import@branch/out  2.3k resource(s)
...
r4:in@9e1d07> :pool specimen
r4:specimen@3f2a1c> :log 3
  3f2a1c  2026-10-17  sync            Δ12
  77b0e4  2026-10-16  sync            Δ48
  c2a9d1  2026-10-16  sync            Δ3
r4:specimen@3f2a1c> :at 77b0e4
r4:specimen@77b0e4 Δ48> Specimen.type.coding.code.distinct()
  [0] code  STOOL
```

The last line is the check from the use case. `77b0e4` re-applied a changed rule.
Its subject says nothing about that, but its delta does: a re-application that
should only have touched stool samples shows nothing else.

## Workspaces

The pools a workspace holds are part of its saved state, so `--workspace fhir-pipes`
opens all four: open them once, and every later start brings them back. Each
`registry/<pipe>` is a repository of its own, so each pool names its own.

Since step 1, the state file has a `pool<TAB>name` line per pool, followed by that
pool's `load` and `focus` lines, and a `current` line. The default pool's lines go
first without a `pool` line, so a session that never opens another pool writes
exactly what it did before. A git pool has a `source` line, the commit it is `at`,
and `scope delta` while its scope is the delta:

```
pool	in
source	/home/me/fp/registry/in/import@branch/out
at	9e1d07c4…
pool	in-imported
source	/home/me/fp/registry/in/import@master
at	51f0a2b9…
pool	specimen
source	/home/me/fp/registry/specimen/import@branch/out
at	3f2a1c88…
scope	delta
```

Nothing is specific to `branch/out`. A pool on `master` steps the same way, and its
delta is what a run imported. That is useful for the in pipe, where fetching from the
central LIMS is the step worth checking. Two pools on one repository are just two
pools.

Its state then records each pool's revision, its scope, its focus, and the current
pool. A pool declared at `branch/out` restores at the revision it was left on, not at
the branch tip. `:at branch/out` brings it up to date.

## Performance

Snapshots hold 1,000 to 10,000 resources, rarely more than 20,000. At that size a
parsed snapshot can plausibly reach a few hundred megabytes (an estimate, to be
measured), so caching whole snapshots per revision does not scale. What does scale
is the blob id: a file whose content did not change has the same id in every
revision.

- **Parsed resources are cached by blob id**, and shared by every snapshot that
  contains them. Stepping to a neighbour parses only the delta's files, and holding
  several revisions costs about one snapshot plus their deltas. Nothing mutates a
  parsed resource, so sharing is safe; each snapshot still has its own indexes.
- **The census is rebuilt per revision.** It has no removal, and walking 20,000
  parsed resources should be quick next to parsing them. If measurements on a real
  registry say otherwise, the next step is a census per blob, merged.
- **The first load of a revision** parses every file once, like `:load` on a
  working tree today. That is the cost to measure on a 20,000-resource registry
  before deciding whether it needs anything more.

Tests use small fixtures and stay well inside the 512 MB test heap.

The delta comes from a tree diff of two commits restricted to the source path,
which costs nothing like a full load. `:log` can afford to show a delta size for
every commit it lists.

## Dependency: JGit

Reading the object database needs `org.eclipse.jgit`. Measured against the "nothing
leaves the machine" constraint: JGit contains transport code, but none of it runs
unless it is called, and fhir-walk only opens a local repository and reads trees and
blobs. No fetch, push, checkout or commit, ever. Syncing a registry stays in
fhir-pipes and your own `git`. CLAUDE.md should record this beside the other
dependency notes.

The alternative is shelling out to the `git` CLI: no new dependency, but `git` has
to be on the PATH, and output has to be parsed. JGit also lets tests build fixture
repositories in a temp directory.

## Delivery

1. **Several named pools, directories only.** The `Session` split, `:pools`,
   `:pool <name>`, `:open`, `:close`, prompt and workspace state. Useful on its own:
   point one pool at each registry's working tree.
2. **Git revisions as a source.** JGit, `:at`, `:step`, `:log`, `:delta`, `:scope`,
   the blob cache. A repository cannot be committed inside this one, so the fixture
   is built by the tests: `RegistryFixture`, a small history on `master` and
   `branch/out`, built with JGit in a temp directory, including a re-application and
   a deletion.
3. **Comparing two revisions** (`master` against `branch/out`, or any two),
   low priority. Generic `:delta <rev> <rev>` with the same scope semantics.

## Settled

- **Each `registry/<pipe>` is its own repository.** A pool's source names it, and the
  repository is found by walking up from the source path.
- **Scale:** 1,000 to 10,000 resources per snapshot, rarely past 20,000. This is what
  rules out caching whole snapshots, in favour of caching parsed resources by blob.
- **Stepping `master`** is wanted, for the in pipe, and needs nothing beyond what is
  described here.
