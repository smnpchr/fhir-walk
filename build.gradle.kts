plugins {
    // Must be >= the stdlib HAPI pulls in transitively (via okhttp), or the
    // compiler cannot read those modules' metadata.
    kotlin("jvm") version "2.2.21"
    application
}

group = "dev.fhirwalk"
version = "0.1.0"

repositories {
    mavenCentral()
}

val hapiVersion = "8.12.1"

dependencies {
    // Version-independent HAPI front door: FhirContext, IFhirPath, model introspection.
    implementation("ca.uhn.hapi.fhir:hapi-fhir-base:$hapiVersion")

    // Structures shipped by default. FhirContext.forXxx() fails with a clear message
    // when a version's structures are absent, so these degrade gracefully -- adding a
    // version is a dependency line plus an enum entry, never a refactor.
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:$hapiVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r5:$hapiVersion")
    // implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4b:$hapiVersion")
    // implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-dstu3:$hapiVersion")

    // The base StructureDefinitions, shipped apart from the structures JARs. Without
    // them HAPI's worker context cannot resolve type names, so ofType()/as()/is()
    // fail with HAPI-0255 -- and choice elements are navigated via ofType().
    // These are local files, not a package-server fetch. R4's are loose files HAPI
    // reads itself; R5's are NPM packages, read by PackageDefinitions -- HAPI's own
    // reader for those lives in hapi-fhir-validation, deliberately not a dependency.
    runtimeOnly("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r4:$hapiVersion")
    runtimeOnly("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r5:$hapiVersion")

    // Both are optional in HAPI's POM but required in practice: UCUM is loaded
    // eagerly by the FHIRPath engine (quantity comparison), and the cache SPI has no
    // default provider, so newFhirPath() throws HAPI-2200 without one.
    implementation("org.fhir:ucum:1.0.10")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:$hapiVersion")

    // PackageDefinitions reads an NPM package's .index.json with it. Already brought
    // in by hapi-fhir-base; declared because we now use it directly. No version: the
    // one HAPI's constraints resolve to.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // Reads help.yaml, the text of :help and :manual. A parser (it brings SnakeYAML)
    // that talks to nothing; versioned by the same Jackson BOM as databind.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // Reads a local registry's history: trees, blobs and diffs from the object
    // database. It contains transport code, but none of it runs unless called, and
    // nothing here calls it -- no fetch, push, checkout or commit. Syncing a
    // registry stays with fhir-pipes and your own git.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")

    implementation("org.jline:jline:3.30.17")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.fhirwalk.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
