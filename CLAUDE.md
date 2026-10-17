# Working on fhir-walk

A terminal tool for composing and evaluating FHIRPath against a local pool of FHIR
resources. See README.md for what it does; this file is about how to change it.

## Constraints that shaped the design

These came from the project's requirements, not from taste. Check with the user before
relaxing any of them.

- **Nothing leaves the machine.** No engine endpoint, no package-server fetch, no
  terminology lookup. The base StructureDefinitions are a JAR dependency
  (`hapi-fhir-validation-resources-*`), not a download. Before adding a dependency or
  a feature, ask what it talks to.
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
  `samples/references` is a second published one for `resolve()` typing: urn-linked
  references, a multi-target path, and a target that only arrives in a later load.
  `BiobankPoolTest` and `RenderTest` use a deeper, domain-specific fixture under
  `samples/biobank/` that is **gitignored and deliberately not published** — it is not
  reproducible from a clone. Those tests call `assumeTrue` on its presence, so they
  skip rather than fail when it is absent; keep that guard on anything new that uses
  it. Tests that must run everywhere belong in `PoolTest` or `CompleterTest` against
  `samples/basic`.
- **`samples/` is not a declared input of the `test` task**, so editing, adding or
  removing sample data does not invalidate it. Gradle reports `BUILD SUCCESSFUL` in
  well under a second and the result XML is left over from the previous run — which
  looks exactly like a passing test of the change you just made. Use
  `./gradlew test --rerun-tasks` whenever an outcome depends on sample data (new
  fixtures, or checking the `assumeTrue` skip guard by hiding one). A real run of this
  suite takes about ten seconds; a sub-second success means nothing executed.
- Listings go through `Repl.emit`, which keeps whatever does not fit for `:more`.
  Never truncate output without an expansion path.
- Candidate `value` is what gets inserted (including any closing `')`); `displ` is what
  the menu shows. Keep them distinct, and assert on the right one in tests.
- Keep candidate lists under 100 entries — past that JLine asks "see all N
  possibilities?" instead of helping.
