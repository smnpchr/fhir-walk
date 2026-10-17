package dev.fhirwalk

import dev.fhirwalk.repl.WatchedFile
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchedFileTest {

    private val dir = Files.createTempDirectory("fhir-walk-watched")
    private val file = dir.resolve("names")

    @Test
    fun `a replace leaves no temp file behind`() {
        val watched = WatchedFile(file)
        assertTrue(watched.replace("a\n"))
        assertTrue(watched.replace("b\n"))
        assertEquals(listOf(file), dir.listDirectoryEntries())
        assertEquals(listOf("b"), Files.readAllLines(file))
    }

    @Test
    fun `a symlinked file is written through, not replaced`() {
        val real = Files.createDirectories(dir.resolve("dotfiles")).resolve("names")
        Files.writeString(real, "old\n")
        Files.createSymbolicLink(file, real)
        assertTrue(WatchedFile(file).replace("new\n"))
        assertTrue(Files.isSymbolicLink(file))
        assertEquals(listOf("new"), Files.readAllLines(real))
    }

    @Test
    fun `an unchanged-looking file is re-read when forced`() {
        Files.writeString(file, "aaa\n")
        val watched = WatchedFile(file)
        assertEquals(listOf("aaa"), watched.readIfChanged())
        // Same size, same modification time: what a coarse clock shows another
        // session's write as.
        val time = Files.getLastModifiedTime(file)
        Files.writeString(file, "bbb\n")
        Files.setLastModifiedTime(file, time)
        assertNull(watched.readIfChanged())
        assertEquals(listOf("bbb"), watched.readIfChanged(force = true))
    }
}
