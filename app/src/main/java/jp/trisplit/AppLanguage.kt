package jp.trisplit

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.icu.util.ULocale
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language choice, stored by the system as the per-app locale (Android 13+), so it also
 * shows in the system's per-app language settings. Changing it recreates running activities.
 */
object AppLanguage {
    enum class Option(val tag: String?) {
        SYSTEM(null), ENGLISH("en"), JAPANESE("ja"), CHINESE_SIMPLIFIED("zh-Hans"), CHINESE_TRADITIONAL("zh-Hant"),
    }

    /** Per-app locales need Android 13; on older versions the app follows the system language. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun current(context: Context): Option {
        if (!supported) return Option.SYSTEM
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return Option.SYSTEM
        val chosen = locales[0]
        return Option.entries.firstOrNull { option ->
            val tag = option.tag ?: return@firstOrNull false
            val locale = Locale.forLanguageTag(tag)
            // Chinese is told apart by script; zh-TW or zh-HK (set elsewhere) count as Traditional.
            locale.language == chosen.language && (locale.script.isEmpty() || locale.script == scriptOf(chosen))
        } ?: Option.SYSTEM
    }

    private fun scriptOf(locale: Locale): String =
        locale.script.ifEmpty { ULocale.addLikelySubtags(ULocale.forLocale(locale)).script }

    fun set(context: Context, option: Option) {
        if (!supported) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            option.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
    }
}
