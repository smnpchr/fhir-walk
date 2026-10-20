# Working on fhir-walk

A terminal tool for composing and evaluating FHIRPath against a local pool of FHIR
resources. See README.md for what it does; this file is about how to change it.

## Constraints that shaped the design

These came from the project's requirements, not from taste. Check with the user before
relaxing any of them.

- **Nothing leaves the machine.** No engine endpoint, no package-server fetch, no
  terminology lookup. The base StructureDefinitions are a JAR dependency
  (`hapi-fhir-validation-resources-*`), not a download. Before adding a dependency or
  a feature, ask what it talks to. JGit is the one that could talk to a network: it
  ships transport code. `GitSource` only opens a local repository and reads trees,
  blobs and diffs. Never fetch, push, check out or commit from it; keeping a
  registry up to date stays with whatever writes it.
- **No npm.** This ruled out self-hosting fhirpath-lab (a Nuxt app) and any web UI
  whose assets come through npm. It is why the surface is a TUI rather than a browser.
- **Terminal only, and deliberately narrow.** General-purpose extensibility was traded
  away for a tool that does one job well. Resist adding surfaces (HTTP, LSP, notebook
  kernels); they were all considered and dropped on purpose.

## The one architectural invariant

`model/FhirVersion.kt` is the only version-aware file. Everything else takes a
`FhirContext` and is version-agnostic by construction.

**Never import `org.hl7.fhir.r4.*` or `org.hl7.fhir.r5.*` in main code.** Use the
version-independent APIs: `ca.uhn.fhir.context.*` for the model,
`ca.uhn.fhir.fhirpath.IFhirPath` for evaluation, `org.hl7.fhir.instance.model.api.*`
(`IBase`, `IBaseResource`, `IBaseReference`, …) for instances, `ca.uhn.fhir.util.*`
for helpers like `BundleUtil`.

This is why the project does **not** use `FHIRPathEngine` / `TypeDetails` /
`IHostApplicationServices`, which are per-version and would need an adapter for every
feature. Adding a FHIR version should be a dependency line plus an enum entry.

## The central idea: two completion axes

Completion merges two independent sources, and most of the value lives in the second:

- **schema** — what is *legal* here, from HAPI's runtime model (`ModelNavigator`)
- **corpus** — what is actually *present*, with counts, from the import-time census
  (`Census`, built by `CensusWalker`)

Extension URLs can only come from the corpus, and they are scoped to the element they
were observed on. The corpus also types `resolve()` (from observed reference targets)
and choice elements (from observed concrete types) where the static model cannot.

Census paths are recorded in the shape the user types, including `extension('url')`
steps, so a cursor context matches a census key with no rewriting. Keep that property
when extending the walker.

## HAPI behaviours that cost time to discover

- **Choice elements navigate by base name.** `Observation.value`, never
  `Observation.valueQuantity` — the wire spelling evaluates to empty. `elementName` is
  the base name; `validChildNames` are the wire names.
- **Detect a choice with `RuntimeChildChoiceDefinition`**, not by counting
  `validChildNames`. A reference child carries a second synthetic name
  (`parent` / `parentResource`) without being a choice. `RuntimeChildAny`
  (`Extension.value`) is a subclass, so it is covered.
- **Three "optional" dependencies are mandatory**: `org.fhir:ucum` and
  `hapi-fhir-caching-caffeine` (without it `newFhirPath()` throws HAPI-2200), and
  `hapi-fhir-validation-resources-*` (without it `ofType()`/`as()` fail with
  HAPI-0255 — and choice navigation goes through `ofType()`).
- **R5's base definitions are NPM packages, and HAPI's reader for them is not a
  dependency.** Without `PackageDefinitions`, `newFhirPath()` on R5 fails with
  HAPI-2330 (`DefaultProfileValidationSupportNpmStrategy`, in `hapi-fhir-validation`:
  29 modules, including an HTTP client). R4's definitions are loose files that
  `hapi-fhir-base` reads itself. `PackageDefinitions` is lazy on purpose: parsing all
  307 R5 definitions up front takes 5 s and ~200 MB, more than Gradle's 512 MB test
  heap can spare; the engine only asks for a handful by URL. It asks in several
  spellings — canonical, `StructureDefinition/X`, bare `X`, and `String` meaning
  the primitive `string` — and an exact-URL lookup turns `ofType(String)` from
  R4's empty answer into an R5 error.
- **`setPrettyPrint(true)` is not honoured by `encodeToString`** for datatype elements,
  only by `encodeResourceToString`. Most values here are datatypes, hence the
  hand-written indenter in `Render`.
- **`IFhirPath.evaluate` takes any `IBase`**, which is what makes an element focus
  possible — but there is no way to pass the containing resource, so `%resource`
  fails against an element. A leading datatype name (`CodeableConcept.coding`) works
  against an element just as `Observation.code` does against a resource.
- **`IFhirPath.parse()` returns an opaque handle**, so no AST is available. Nothing
  needs one: `FhirPathTokenizer` splits expressions on the raw string, which also
  means a half-typed expression still completes.
- **The Kotlin plugin has a version floor.** HAPI pulls kotlin-stdlib transitively via
  okhttp; an older compiler cannot read those modules.

## Pools and git revisions

- **The delta scope narrows only pool-wide evaluation**: `ResourcePool.inScope()`,
  used by `Evaluator.evaluateAll` and `ViewBuilder`. Lookup, `resolve()`, `:why` and
  the census see the whole snapshot, because a changed resource usually references
  an unchanged one. New pool-wide features should iterate `inScope()`, not `all()`.
- **Parsed resources are shared between revisions**, kept by blob id in
  `RevisionLoader`. Never mutate a resource from the pool; it may belong to the
  revision you step to next.
- **A revision is read completely before the pool is touched.** A missing or
  damaged commit or tree throws from the JGit read; `RevisionLoader.load` does all
  of those first, `GitPool` turns a failure into `Move.Failed`, and `Repl.openGit`
  attaches a `GitPool` only once it has loaded. Keep that order: a half-loaded git
  pool breaks the prompt and the saved state. An unreadable *blob* is softer: it is
  reported as a problem for its file, like a parse error, and the move goes ahead.
  `:log` shows a commit whose changes cannot be read as `Δ?`.
- **A forward step has to be found from the line's tip**: a commit does not know
  its children. Back follows first parents and always works.

## FHIRPath behaviours worth remembering

- String literals are **single-quoted**. A double quote delimits an identifier, so
  `extension("url")` fails with a message that never mentions quoting. Completion
  supplies the quote itself for functions whose first argument is a string.
- A syntax error belongs to the expression, not the data: validate once up front
  rather than once per resource.

## Verifying interactive behaviour

**`./gradlew run` gives the process no TTY.** JLine silently falls back to a dumb
terminal, which means completion and key bindings are *disabled* — four real bugs hid
behind this. Use it only for piping a script of commands:

```
printf ':pool\n:quit\n' | ./gradlew run -q --console=plain --args="samples/basic"
```

For anything involving TAB, arrow keys or the menu, build and run the start script:

```
./gradlew installDist && build/install/fhir-walk/bin/fhir-walk samples/basic
```

To verify keystrokes automatically, drive that binary through a pty. Two traps:

- **Set the window size** (`TIOCSWINSZ`) or JLine sees 0 columns and renders nothing —
  not the prompt, not the menu.
- **Arrow keys have two encodings**: CSI (`ESC [ A`) and SS3 (`ESC O A`). Test both;
  which one arrives depends on whether application-cursor-keys mode took effect. Both
  are bound explicitly in `bindArrowKeys`.

Clear the line (`ctrl-u`) between cases in a scripted pty session, or leftover text
produces confusing results that look like product bugs.

## Conventions

- Tests: `samples/basic` is the published fixture and backs most tests.
  `RegistryFixture` builds a git registry with JGit in a temp directory, because a
  repository cannot be committed inside this one; `GitPoolTest` runs against it.
  `samples/references` is a second published one for `resolve()` typing: urn-linked
  references, a multi-target path, and a target that only arrives in a later load.
  `HdrpPoolTest` and `RenderTest` use a deeper, domain-specific fixture under
  `samples/hdrp/` that is **gitignored and deliberately not published**, and so not
  reproducible from a clone; it is regenerated with its own `generate.py`. Those tests
  call `assumeTrue` on its presence, so they skip rather than fail when it is absent;
  keep that guard on anything new that uses it, and don't name the systems or sites
  it is modelled on in anything published. `RegistryFixture` writes its own neutral
  resources so `GitPoolTest` runs everywhere. Tests that must run everywhere belong
  in `PoolTest` or `CompleterTest` against `samples/basic`.
- **`samples/` is not a declared input of the `test` task**, so editing, adding or
  removing sample data does not invalidate it. Gradle reports `BUILD SUCCESSFUL` in
  well under a second and the result XML is left over from the previous run — which
  looks exactly like a passing test of the change you just made. Use
  `./gradlew test --rerun-tasks` whenever an outcome depends on sample data (new
  fixtures, or checking the `assumeTrue` skip guard by hiding one). A real run of this
  suite takes about ten seconds; a sub-second success means nothing executed.
- `Repl` keeps its expressions, names, views, state and line history in a workspace
  under `~/.fhir-walk` by default. Tests must pass `Workspaces(null)` (all in memory)
  or `Workspaces(<temp dir>)`, or they write into the developer's home. `./gradlew run` does not forward `JAVA_OPTS`, so `-Duser.home=…` does not
  redirect it there either; the installed start script does honour it.
- Commands write into an `Output` and `Printer` prints it: colour, fitting to the
  window, the row limit and `:more` all live there. Write new output with spans
  (`out.line { index(id); +"  "; shrink(subject) }`, `out.listing(…)`), marking the
  part that may give way with `shrink` rather than calling `abbreviate` yourself;
  tests can then check `Repl.outputOf(line)` instead of printed text. A value is
  `shrink(…, Kind.PREVIEW, cutsEnd = true)`; a table is `out.table(…)`, laid out by
  the printer. `out.text` is for plain lines only: no command calls `Style` or
  measures the window (only `run()`'s last-resort crash line writes to the terminal
  itself). Never truncate output without an expansion path.
- Commands live in `repl/command/`: each is a `Command` (`Command.kt`) in one of
  the `*Commands` groups, which share a `ReplState`; `Repl` only reads lines and
  dispatches them. A command's usage, summary, details and manual topic are text
  in `src/main/resources/dev/fhirwalk/repl/command/help.yaml`, not in code. Adding a command
  means an entry there too: `Repl` refuses to start when the two disagree. Quote a
  YAML value holding ` #` — unquoted, the rest of the line is a comment.
- Candidate `value` is what gets inserted (including any closing `')`); `displ` is what
  the menu shows. Keep them distinct, and assert on the right one in tests.
- Keep candidate lists under 100 entries — past that JLine asks "see all N
  possibilities?" instead of helping.
