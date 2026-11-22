package dev.pranav.applock.shizuku

import android.app.ActivityManager
import android.app.IActivityTaskManager
import android.app.TaskInfo
import android.content.*
import android.content.Context.RECEIVER_EXPORTED
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.IWindowManager
import dev.pranav.applock.core.broadcast.DeviceUnlockReceiver
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.services.isDeviceLocked
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

class ShizukuActivityManager(
    private val context: Context,
    private val appLockRepository: AppLockRepository,
    private val onForegroundAppChanged: (String, String, Long) -> Unit
) {
    private val TAG = "ShizukuActivityManager"
    private var lastForegroundApp = ""
    private var deviceUnlockReceiver: DeviceUnlockReceiver? = null
    private var shouldLockAppsOnReturn = false

    private val handler = Handler(Looper.getMainLooper())
    private val checkForegroundRunnable = object : Runnable {
        override fun run() {
            try {
                checkForegroundApp()
            } catch (e: Exception) {
                e.printStackTrace()
                LogUtils.e(TAG, "Unhandled exception in foreground monitor", e)
            } finally {
                // Schedule itself again after 500ms regardless of failure
                handler.postDelayed(this, 500)
            }
        }
    }

    private val homeButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val currentTop = topActivity
                    if (currentTop != null && lastForegroundApp == currentTop.packageName && currentTop.className == "com.android.launcher3.uioverrides.QuickstepLauncher") {
                        AppLockManager.clearTemporarilyUnlockedApp()
                    }
                }

                Intent.ACTION_SCREEN_OFF -> {
                    AppLockManager.clearTemporarilyUnlockedApp()
                    shouldLockAppsOnReturn = true
                    lastForegroundApp = ""
                }

                Intent.ACTION_USER_PRESENT -> {
                    shouldLockAppsOnReturn = true
                }
            }
        }
    }

    fun start(): Boolean {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "Shizuku is not available")
            return false
        }

        try {
            registerEventReceivers()
            startForegroundAppMonitoring()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun registerEventReceivers() {
        val homeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        context.registerReceiver(homeButtonReceiver, homeFilter, RECEIVER_EXPORTED)

        val unlockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        deviceUnlockReceiver = DeviceUnlockReceiver {
            shouldLockAppsOnReturn = true
        }
        context.registerReceiver(deviceUnlockReceiver, unlockFilter)
    }

    val windowManager: IWindowManager
        get() = SystemServiceHelper.getSystemService("window")
            .let(::ShizukuBinderWrapper)
            .let(IWindowManager.Stub::asInterface)

    private fun startForegroundAppMonitoring() {
        handler.removeCallbacks(checkForegroundRunnable)
        handler.post(checkForegroundRunnable)
        Log.d(TAG, "Foreground app monitoring started")
    }

    private fun checkForegroundApp() {
        if (!appLockRepository.isProtectEnabled()) return
        if (appLockRepository.getBackendImplementation() != BackendImplementation.SHIZUKU) {
            handler.removeCallbacks(checkForegroundRunnable)
            return
        }

        if (!Shizuku.pingBinder()) {
            LogUtils.e(TAG, "Shizuku binder lost during foreground monitoring")
            return
        }

        if (context.isDeviceLocked()) return

        getTasksWrapper().filterVisible().forEach {
            val activity = it.topActivity ?: return@forEach
            val packageName = activity.packageName
            val className = activity.className

            // Skip our own app and known recents classes
            if (packageName == context.packageName) return

            // Skip if app is temporarily unlocked
            if (packageName == lastForegroundApp && AppLockManager.isAppTemporarilyUnlocked(
                    packageName
                )
            ) return

            // If we should lock apps on return (home button pressed, device locked, etc.)
            // then trigger app lock for any new foreground app
            if (shouldLockAppsOnReturn && packageName != lastForegroundApp) {
                LogUtils.d(TAG, "Should lock apps on return - triggering for: $packageName")
                shouldLockAppsOnReturn = false // Reset the flag

                val timeMillis = System.currentTimeMillis()
                lastForegroundApp = packageName
                onForegroundAppChanged(packageName, className, timeMillis)
                return
            }

            // Normal app switching - only trigger if current app has changed
            if (packageName != lastForegroundApp) {
                val triggerExclusions = appLockRepository.getTriggerExcludedApps()

                // Check if previous app was in trigger exclusions
                if (lastForegroundApp in triggerExclusions) {
                    LogUtils.d(
                        TAG,
                        "Previous app $lastForegroundApp is excluded, skipping app lock for $packageName"
                    )
                    lastForegroundApp = packageName
                    return
                }
            }

            val timeMillis = System.currentTimeMillis()
            LogUtils.d(TAG, "Foreground app changed to: $packageName, class: $className")

            lastForegroundApp = packageName
            onForegroundAppChanged(packageName, className, timeMillis)
        }
    }

    fun stop() {
        homeButtonReceiver.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
                Log.d(TAG, "Home button receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering home button receiver", e)
            }
        }

        deviceUnlockReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
                deviceUnlockReceiver = null
                Log.d(TAG, "Device unlock receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering device unlock receiver", e)
            }
        }

        handler.removeCallbacks(checkForegroundRunnable)
        Log.d(TAG, "ShizukuActivityManager stopped")
    }
}

val topActivity: ComponentName?
    get() = getTasksWrapper().firstOrNull()?.topActivity

private val activityTaskManager: IActivityTaskManager by lazy {
    SystemServiceHelper.getSystemService("activity_task")
        .let(::ShizukuBinderWrapper)
        .let(IActivityTaskManager.Stub::asInterface)
}

private fun getTasksWrapper(): List<ActivityManager.RunningTaskInfo> = when {
    Build.VERSION.SDK_INT < 31 -> runCatching { activityTaskManager.getTasks(8) }.getOrNull()
        .orEmpty()

    else -> runCatching { activityTaskManager.getTasks(8, false, false, Display.INVALID_DISPLAY) }
        .getOrNull()
        .orEmpty()
}

private val taskFieldCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Field?>()

/**
 * Reads a boolean field off a RunningTaskInfo without compiling a direct call to
 * `isVisible()` / `isRunning`. Those don't exist on every Android version
 * (isVisible() throws NoSuchMethodError on Android 10 and below).
 */
private fun ActivityManager.RunningTaskInfo.readBoolean(name: String, default: Boolean): Boolean {
    val field = taskFieldCache.getOrPut(name) {
        var cls: Class<*>? = ActivityManager.RunningTaskInfo::class.java
        var found: java.lang.reflect.Field? = null
        while (cls != null && cls != Any::class.java && found == null) {
            found = runCatching {
                HiddenApiBypass.getInstanceFields(cls!!)
                    .firstOrNull { it.name == name && it.type == Boolean::class.javaPrimitiveType }
            }.getOrNull()
            cls = cls.superclass
        }
        found?.apply { isAccessible = true }
    } ?: return default
    return runCatching { field.getBoolean(this) }.getOrDefault(default)
}

private fun List<ActivityManager.RunningTaskInfo>.filterVisible(): List<ActivityManager.RunningTaskInfo> {
    val usable = filter {
        it.topActivity != null &&
                it.readBoolean("isRunning", true) &&
                it.readBoolean("isVisible", true)
    }
    // Before Android 11 there is no per-task visibility flag; the list is ordered
    // most-recent-first, so only the first usable task is the foreground one.
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) usable.take(1) else usable
}

fun TaskInfo.isFreeform(): Boolean {
    try {
        return HiddenApiBypass.invoke(TaskInfo::class.java, this, "isFreeform") as Boolean
    } catch (e: Throwable) {
        e.printStackTrace()
        return false
    }
}

fun TaskInfo.isFocused(): Boolean {
    try {
        return HiddenApiBypass.getInstanceFields(TaskInfo::class.java)
            .firstOrNull { it.name == "isFocused" }!!
            .getBoolean(this)
    } catch (e: Throwable) {
        e.printStackTrace()
        return false
    }
}
