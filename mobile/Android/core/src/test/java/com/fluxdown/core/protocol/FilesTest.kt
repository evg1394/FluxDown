package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesTest {
    @Test
    fun fsListResponseParsesFullShape() {
        val v = Json.parse(
            """{"path":"/srv/dl","parent":"/srv","dirs":[{"name":"a","path":"/srv/dl/a"},{"name":"b","path":"/srv/dl/b"}],"denied":false}""",
        )
        val r = FsListResponse.fromJson(v)!!
        assertEquals("/srv/dl", r.path)
        assertEquals("/srv", r.parent)
        assertEquals(listOf(FsEntry("a", "/srv/dl/a"), FsEntry("b", "/srv/dl/b")), r.dirs)
        assertFalse(r.denied)
    }

    @Test
    fun fsListResponseDefaultsWhenOptionalFieldsMissing() {
        val r = FsListResponse.fromJson(Json.parse("""{"path":"/"}"""))!!
        assertNull(r.parent)
        assertTrue(r.dirs.isEmpty())
        assertFalse(r.denied)
    }

    @Test
    fun fsListResponseKeepsWindowsDriveRootAndDeniedFlag() {
        val r = FsListResponse.fromJson(Json.parse("""{"path":"C:\\","dirs":[],"denied":true}"""))!!
        assertEquals("C:\\", r.path)
        assertNull(r.parent)
        assertTrue(r.denied)
    }

    @Test
    fun fsListResponseWithoutPathIsInvalid() {
        assertNull(FsListResponse.fromJson(Json.parse("""{"dirs":[]}""")))
    }

    @Test
    fun fsListSkipsEntriesWithoutPath() {
        val r = FsListResponse.fromJson(Json.parse("""{"path":"/x","dirs":[{"name":"orphan"},{"name":"ok","path":"/x/ok"}]}"""))!!
        assertEquals(listOf(FsEntry("ok", "/x/ok")), r.dirs)
    }

    @Test
    fun fsListParamsOmitsEmptyPath() {
        assertEquals("{}", FsListParams().toJson().toJson())
        assertEquals("{}", FsListParams("").toJson().toJson())
        assertEquals("""{"path":"/data"}""", FsListParams("/data").toJson().toJson())
    }

    @Test
    fun connPolicySummaryParsesCountAndDefaultsToZero() {
        assertEquals(7L, ConnPolicySummary.fromJson(Json.parse("""{"domainCount":7}""")).domainCount)
        assertEquals(0L, ConnPolicySummary.fromJson(Json.parse("{}")).domainCount)
    }

    @Test
    fun saveDirectoryValidation() {
        assertTrue(SettingsSaveDirectory.isValid(""))
        assertTrue(SettingsSaveDirectory.isValid("   "))
        assertTrue(SettingsSaveDirectory.isValid("/srv/downloads"))
        assertTrue(SettingsSaveDirectory.isValid("C:\\Users\\me"))
        assertTrue(SettingsSaveDirectory.isValid("d:/data"))
        assertTrue(SettingsSaveDirectory.isValid("\\\\nas\\share"))
        assertFalse(SettingsSaveDirectory.isValid("downloads"))
        assertFalse(SettingsSaveDirectory.isValid("~/downloads"))
        assertFalse(SettingsSaveDirectory.isValid("C:"))
        assertFalse(SettingsSaveDirectory.isValid("C:relative"))
    }

    @Test
    fun lastComponentHandlesBothSeparators() {
        assertEquals("b", SettingsSaveDirectory.lastComponent("/a/b/"))
        assertEquals("y", SettingsSaveDirectory.lastComponent("C:\\x\\y"))
        assertEquals("dl", SettingsSaveDirectory.lastComponent("dl"))
        assertEquals("", SettingsSaveDirectory.lastComponent("/"))
    }
}
