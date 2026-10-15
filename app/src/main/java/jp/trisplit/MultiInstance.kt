package jp.trisplit

import android.content.ComponentName
import android.content.pm.PackageManager

/**
 * Whether an app can show two windows of itself at once. Apps declare it with the
 * PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI property (on the activity or the whole app); One UI's
 * own list adds Chrome. Others either reuse their one window, so a column stays empty, or behave
 * unpredictably when forced.
 */
object MultiInstance {
    private const val PROPERTY = "android.window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI"
    private val ALLOWED = setOf("com.android.chrome")

    fun supports(pm: PackageManager, component: ComponentName): Boolean {
        if (component.packageName in ALLOWED) return true
        val onActivity = runCatching { valueOf(pm, component.packageName, pm.getProperty(PROPERTY, component)) }.getOrNull()
        if (onActivity != null) return onActivity
        return runCatching { valueOf(pm, component.packageName, pm.getProperty(PROPERTY, component.packageName)) }.getOrDefault(false)
    }

    /** A boolean written directly, or one in a resource (android:resource="@bool/..."). */
    private fun valueOf(pm: PackageManager, packageName: String, property: PackageManager.Property): Boolean =
        if (property.isResourceId) pm.getResourcesForApplication(packageName).getBoolean(property.resourceId)
        else property.boolean
}
