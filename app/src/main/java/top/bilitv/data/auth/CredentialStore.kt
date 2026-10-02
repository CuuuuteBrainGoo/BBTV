package top.bilitv.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录凭证存储。
 *
 * 硬规则：SESSDATA / bili_jct / access_key 只存在这里，绝不写日志、绝不进版本库。
 * 优先 EncryptedSharedPreferences；设备不支持时退化为应用私有 SharedPreferences
 * （仍受 Android 应用沙箱保护，其他应用读不到）。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // ponytail: 低端盒子 Keystore 不可用时降级，此处不抛异常以免启动崩溃
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var sessdata: String?
        get() = prefs.getString(KEY_SESSDATA, null)
        set(value) = prefs.edit().putString(KEY_SESSDATA, value).apply()

    var biliJct: String?
        get() = prefs.getString(KEY_JCT, null)
        set(value) = prefs.edit().putString(KEY_JCT, value).apply()

    var dedeUserId: String?
        get() = prefs.getString(KEY_UID, null)
        set(value) = prefs.edit().putString(KEY_UID, value).apply()

    var accessKey: String?
        get() = prefs.getString(KEY_ACCESS_KEY, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_KEY, value).apply()

    var buvid: String?
        get() = prefs.getString(KEY_BUVID, null)
        set(value) = prefs.edit().putString(KEY_BUVID, value).apply()

    val isLoggedIn: Boolean get() = !sessdata.isNullOrBlank() || !accessKey.isNullOrBlank()

    fun cookieHeader(): String = buildString {
        val s = sessdata
        val j = biliJct
        val u = dedeUserId
        if (!s.isNullOrBlank()) append("SESSDATA=$s; ")
        if (!j.isNullOrBlank()) append("bili_jct=$j; ")
        if (!u.isNullOrBlank()) append("DedeUserID=$u; ")
    }.trimEnd(' ', ';')

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val PREFS_NAME = "bilitv_credentials"
        const val KEY_SESSDATA = "SESSDATA"
        const val KEY_JCT = "bili_jct"
        const val KEY_UID = "DedeUserID"
        const val KEY_ACCESS_KEY = "access_key"
        const val KEY_BUVID = "buvid3"
    }
}
