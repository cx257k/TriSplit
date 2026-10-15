package jp.trisplit

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Builds the intent and options that open an app as a freeform (One UI pop-up view) window. */
object Freeform {
    /** WindowConfiguration.WINDOWING_MODE_FREEFORM (hidden constant). */
    const val WINDOWING_MODE_FREEFORM = 5

    /**
     * [multipleTask] forces a new task even if the app already has one, so the same app can fill
     * two columns. Apps that use singleTask/singleInstance launch modes ignore it.
     */
    fun intentFor(component: ComponentName, multipleTask: Boolean = false): Intent {
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        if (multipleTask) flags = flags or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(flags)
    }

    /** [launchCookie] is attached to the new task's TaskInfo.launchCookies so the task can be found afterwards. */
    fun options(bounds: Bounds, launchCookie: IBinder? = null): ActivityOptions {
        val options = ActivityOptions.makeBasic()
            .setLaunchBounds(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
        // setLaunchWindowingMode and setLaunchCookie are hidden; HiddenApiBypass lifts the restriction.
        HiddenApiBypass.invoke(
            ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM,
        )
        if (launchCookie != null) {
            HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchCookie", launchCookie)
        }
        return options
    }
}
