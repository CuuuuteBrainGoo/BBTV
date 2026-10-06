package top.bilitv.data.settings

import java.util.Locale
import android.content.Context
import android.os.Build
import top.bilitv.R

/** Stable preference values; API content (titles, names, comments) is not translated. */
enum class UiLanguage(val tag: String, val labelRes: Int) {
    SYSTEM("system", R.string.language_system),
    CHINESE("zh-CN", R.string.language_chinese),
    ENGLISH("en", R.string.language_english);

    val locale: Locale? get() = if (this == SYSTEM) null else Locale.forLanguageTag(tag)

    companion object {
        fun of(tag: String?): UiLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

val Context.uiLocale: Locale
    get() = if (Build.VERSION.SDK_INT >= 24) resources.configuration.locales[0]
        else @Suppress("DEPRECATION") resources.configuration.locale
