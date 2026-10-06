package top.bilitv

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.auth.CredentialStore

class CredentialReplacementTest {
    @Test fun `Web relogin removes old App identity and TV login replaces all account fields together`() {
        val values = mutableMapOf<String, String>()
        var writes = 0
        val pending = mutableMapOf<String, String?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { self, method, args ->
            when (method.name) {
                "putString" -> { pending[args[0] as String] = args[1] as String?; self }
                "apply" -> { pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; pending.clear(); writes++; null }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> values[args[0] as String] ?: args[1]
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
        val store = CredentialStore(prefs)
        store.buvid = "device-id"
        val before = writes
        store.replaceLogin(mapOf("SESSDATA" to "tv-a", "bili_jct" to "csrf-a", "DedeUserID" to "1"), "app-a")
        assertEquals(before + 1, writes)
        assertEquals("app-a", store.accessKey)
        store.replaceLogin(mapOf("SESSDATA" to "web-b", "DedeUserID" to "2"))
        assertNull(store.accessKey)
        assertNull(store.biliJct)
        assertEquals("2", store.dedeUserId)
        assertEquals("SESSDATA=web-b; DedeUserID=2", store.cookieHeader())
        assertEquals("device-id", store.buvid)
        store.replaceLogin(mapOf("SESSDATA" to "tv-c", "bili_jct" to "csrf-c", "DedeUserID" to "3"), "app-c")
        assertEquals("app-c", store.accessKey)
        assertEquals("3", store.dedeUserId)
        store.replaceLogin(mapOf("SESSDATA" to "web-d"), " ")
        assertNull(store.accessKey)
        assertNull(store.dedeUserId)
    }
}
