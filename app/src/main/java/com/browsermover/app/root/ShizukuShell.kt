package com.browsermover.app.root

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.browsermover.app.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Optional Shizuku backend.
 *
 * A Shizuku service started by ADB runs as shell UID 2000; it can control
 * packages and launch activities, but Linux permissions still prevent it from
 * reading /data/user/0/<package>. A Shizuku service started with root runs as
 * UID 0 and is equivalent to su for this app. [backendLabel] makes that
 * distinction visible instead of pretending rootless ADB can migrate data.
 */
object ShizukuShell {
    private const val REQUEST_CODE = 1001
    private const val TAG = "browser_migrator_shell"

    private var appContext: Context? = null
    private var service: IShellService? = null
    private val connecting = AtomicBoolean(false)
    private var pendingConnect: ((Boolean) -> Unit)? = null
    @Volatile private var lastError: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IShellService.Stub.asInterface(it) }
            connecting.set(false)
            pendingConnect?.invoke(service != null)
            pendingConnect = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connecting.set(false)
            pendingConnect?.invoke(false)
            pendingConnect = null
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            if (granted) connect(null)
            pendingPermission?.invoke(granted)
            pendingPermission = null
        }

    private var pendingPermission: ((Boolean) -> Unit)? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
    }

    fun release() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    }

    fun installed(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        installed() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun uid(): Int? = try {
        if (hasPermission()) Shizuku.getUid() else null
    } catch (_: Throwable) {
        null
    }

    fun isRootBackend(): Boolean = uid() == 0

    fun backendLabel(): String = when {
        !installed() -> "Shizuku not running"
        !hasPermission() -> "Shizuku found; permission not granted"
        uid() == 0 -> "Shizuku root (UID 0)"
        uid() != null -> "Shizuku ADB (UID ${uid()}) — cannot read app-private data"
        else -> "Shizuku unavailable"
    }

    fun requestPermission(callback: (Boolean) -> Unit) {
        if (!installed()) {
            callback(false)
            return
        }
        if (hasPermission()) {
            connect(callback)
            return
        }
        pendingPermission = callback
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            pendingPermission = null
            lastError = e.message
            callback(false)
        }
    }

    suspend fun connectAndWait(): Boolean = withContext(Dispatchers.IO) {
        if (hasPermission() && service != null) return@withContext true
        if (!hasPermission()) return@withContext false
        suspendCancellableCoroutine { cont ->
            connect { ok -> if (cont.isActive) cont.resume(ok) }
        }
    }

    fun connect(callback: ((Boolean) -> Unit)? = null) {
        if (!hasPermission()) {
            callback?.invoke(false)
            return
        }
        if (service != null) {
            callback?.invoke(true)
            return
        }
        val context = appContext
        if (context == null) {
            callback?.invoke(false)
            return
        }
        if (!connecting.compareAndSet(false, true)) {
            pendingConnect = callback
            return
        }
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shizuku_shell")
                .version(1)
                .tag(TAG)
            Shizuku.bindUserService(args, connection)
            pendingConnect = callback
        } catch (e: Throwable) {
            connecting.set(false)
            lastError = e.message
            callback?.invoke(false)
        }
    }

    suspend fun exec(command: String): CommandResult? = withContext(Dispatchers.IO) {
        if (!connectAndWait()) return@withContext null
        val svc = service ?: return@withContext null
        try {
            val raw = svc.exec(command) ?: return@withContext null
            val firstLine = raw.lineSequence().firstOrNull().orEmpty()
            val exit = firstLine.removePrefix("MIGRATOR_EXIT:").toIntOrNull() ?: -1
            val output = raw.substringAfter('\n', "")
            CommandResult(
                exitCode = exit,
                stdout = output.trim(),
                stderr = if (exit == 0) "" else output.trim(),
                success = exit == 0
            )
        } catch (e: Throwable) {
            lastError = e.message
            null
        }
    }

    fun lastError(): String? = lastError
}
