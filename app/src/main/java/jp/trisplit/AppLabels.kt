package jp.trisplit

import android.app.LocaleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageItemInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build

/**
 * App names as the rest of the phone shows them. TriSplit's own language (chosen in its settings)
 * applies to TriSplit's screens only; read through TriSplit's context, other apps' names would
 * follow it too (e.g. rednote would show its Chinese name), so they are read in the system's language.
 */
object AppLabels {

    /** The name of [item] (an app or one of its activities) in the system's language. */
    fun of(context: Context, item: PackageItemInfo): String {
        item.nonLocalizedLabel?.let { return it.toString() }
        val app = (item as? ComponentInfo)?.applicationInfo ?: item as? ApplicationInfo
        val labelRes = item.labelRes.takeIf { it != 0 } ?: app?.labelRes ?: 0
        if (app != null && labelRes != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                return context.packageManager
                    .getResourcesForApplication(app, systemConfiguration(context))
                    .getString(labelRes)
            }
        }
        return item.loadLabel(context.packageManager).toString()
    }

    /**
     * The configuration in the phone's own language. Inside TriSplit's process even the system
     * resources follow TriSplit's chosen language, so the system's languages are asked for.
     */
    private fun systemConfiguration(context: Context): Configuration {
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            config.setLocales(context.getSystemService(LocaleManager::class.java).systemLocales)
        } else {
            config.setLocales(Resources.getSystem().configuration.locales)
        }
        return config
    }

    /** The name of the app [packageName], or the package name if it is not installed. */
    fun ofPackage(context: Context, packageName: String): String =
        runCatching { of(context, context.packageManager.getApplicationInfo(packageName, 0)) }.getOrDefault(packageName)
}
