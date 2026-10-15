package jp.trisplit

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Connection to [SplitService] through Shizuku. Call from the main thread. */
object ShizukuBridge {
    enum class Status { NOT_RUNNING, NO_PERMISSION, READY }

    /** Shizuku is not running or has not granted permission; callers show their own localized message. */
    class UnavailableException : IllegalStateException("Shizuku is not running or permission is not granted")

    const val PERMISSION_REQUEST_CODE = 1001
    private const val BIND_TIMEOUT_MS = 5_000L

    private var service: ISplitService? = null
    private var pending: CompletableDeferred<ISplitService>? = null

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, SplitService::class.java.name),
    )
        // Keep the service process alive between uses: starting it costs about a second, which
        // showed as lag when TriSplit is picked for a split screen.
        .daemon(true)
        .processNameSuffix("split")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val connected = ISplitService.Stub.asInterface(binder)
            service = connected
            pending?.complete(connected)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            pending = null
        }
    }

    fun status(): Status = when {
        !Shizuku.pingBinder() || Shizuku.isPreV11() -> Status.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
        else -> Status.READY
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST_CODE)

    suspend fun service(): ISplitService {
        // isBinderAlive is a local check; pingBinder would be a binder call on every use, often from
        // the main thread (the handles poll several times a second).
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (status() != Status.READY) throw UnavailableException()
        val deferred = pending?.takeIf { !it.isCompleted }
            ?: CompletableDeferred<ISplitService>().also {
                pending = it
                try {
                    Shizuku.bindUserService(serviceArgs, connection)
                } catch (e: RuntimeException) {
                    // Shizuku went away meanwhile: bind afresh next time instead of waiting on this one.
                    pending = null
                    throw UnavailableException()
                }
            }
        return try {
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Bind again next time rather than waiting on a bind that never completes (e.g. Shizuku
            // restarted while it was in flight).
            if (pending === deferred) pending = null
            throw UnavailableException()
        }
    }
}
