package dev.fhirwalk

import dev.fhirwalk.pool.GitSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitSourceTest {

    @Test
    fun `a spec splits at its last at sign, except one git writes in a revision`() {
        assertEquals("reg/import" to "branch/out", GitSource.split("reg/import@branch/out"))
        assertEquals("me@host/import" to "master", GitSource.split("me@host/import@master"))
        assertEquals("reg/import" to "branch/out@{1}", GitSource.split("reg/import@branch/out@{1}"))
        assertEquals("reg/import" to "HEAD@{yesterday}", GitSource.split("reg/import@HEAD@{yesterday}"))
        assertEquals("reg/import" to "@{-1}", GitSource.split("reg/import@@{-1}"))
    }

    @Test
    fun `a spec without a revision does not split`() {
        assertNull(GitSource.split("reg/import"))
        assertNull(GitSource.split("reg/import@"))
        assertNull(GitSource.split("@master"))
    }
}
