package com.rocketglasses.restep.phone

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class LibraryChecks {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun testOfflineDeletionAndStaleImport() {
        val root = File(context.cacheDir, "test-${UUID.randomUUID()}")
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        val path = "photos/$a/${UUID.randomUUID()}.jpg"
        fun session(id: String) = JSONObject().put("id",id).put("name","Demo").put("createdAt",1)
            .put("steps",JSONArray().put(JSONObject().put("id",id).put("photo",path).put("note","Two screws").put("number",1).put("createdAt",1)))
        fun manifest() = JSONObject().put("schemaVersion",1).put("sessions",JSONArray().put(session(a)).put(session(b)))
        try {
            val lib = Library(root)
            lib.storePhoto(path, byteArrayOf(1,2,3)); lib.merge(manifest())
            lib.edit(b) { it.getJSONArray("steps").getJSONObject(0).put("note","Edited note").put("completed",true) }
            lib.delete(a)
            assertTrue(lib.photo(path).exists())
            val restarted = Library(root)
            assertTrue(a in restarted.pending())
            restarted.merge(manifest())
            assertEquals(1,restarted.sessions().size)
            assertEquals("Edited note",restarted.sessions().single().getJSONArray("steps").getJSONObject(0).getString("note"))
            assertTrue(restarted.sessions().single().getJSONArray("steps").getJSONObject(0).getBoolean("completed"))
            restarted.acknowledge(a)
            val again = Library(root)
            assertFalse(a in again.pending()); assertTrue(a in again.deleted())
            again.delete(b); assertFalse(again.photo(path).exists())
            try { again.photo("../../secret.jpg"); fail("Traversal accepted") } catch(_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun testConnectionValidation() {
        val info = JSONObject().put("version",1).put("ssid","DIRECT-RS-ReStep-demo").put("host","192.168.43.1")
            .put("port",8765).put("token",UUID.randomUUID().toString())
        GlassesLink.validate(info)
        for(host in listOf("example.com","127.0.0.1","192.168.1.999","10.0.0.1@evil.com")) {
            try { GlassesLink.validate(info.put("host",host)); fail("Invalid host accepted") } catch(_: IllegalArgumentException) { }
        }
    }
}
