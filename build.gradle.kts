import java.nio.file.Files

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

/**
 * Where one build's tests keep their temporary files (#94): a fresh directory under
 * the system's temp, made the first time a test JVM starts and removed when the build
 * ends, whether the tests passed or failed. Its own name for every build, so two
 * builds of one checkout never share it, and outside the project, since some tests
 * need a directory that is in no git repository. Ones left by a build that never got
 * to end (a killed daemon) are swept once a day old.
 */
abstract class TestTemp : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private var made: File? = null

    @get:Synchronized
    val dir: File
        get() = made ?: run {
            sweep()
            Files.createTempDirectory(PREFIX).toFile().also {
                File(it, "home").mkdirs()
                made = it
            }
        }

    private fun sweep() {
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        File(System.getProperty("java.io.tmpdir")).listFiles { file ->
            file.isDirectory && file.name.startsWith(PREFIX) && file.lastModified() < dayAgo
        }?.forEach { it.deleteRecursively() }
    }

    override fun close() {
        made?.deleteRecursively()
    }

    private companion object {
        const val PREFIX = "fhir-walk-tests-"
    }
}

/**
 * Hands [TestTemp] to the test JVM as its temp directory and its home, so tests write
 * neither into the system's temp nor into the user's home (`~/.fhir-walk`, JGit's
 * `~/.config/jgit`) and read no `~/.gitconfig`. Internal: the path is new each build,
 * and must not make every build's tests look changed to the build cache.
 */
class TestTempArguments(@get:Internal val temp: Provider<TestTemp>) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = temp.get().dir.let {
        listOf("-Djava.io.tmpdir=$it", "-Duser.home=${File(it, "home")}")
    }
}

val testTemp = gradle.sharedServices.registerIfAbsent("fhirWalkTestTemp", TestTemp::class) {}

tasks.test {
    useJUnitPlatform()
    usesService(testTemp)
    jvmArgumentProviders += TestTempArguments(testTemp)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
