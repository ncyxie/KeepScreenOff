package com.ncyxie.keepscreenoff

import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager.LayoutParams
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.lang.reflect.Executable

/**
 * Clears FLAG_KEEP_SCREEN_ON and downgrades screen wakelocks, so hooked apps
 * cannot hold the display awake past the system screen-timeout.
 */
class KeepScreenOff : XposedModule() {

    private companion object {
        const val TAG = "KeepScreenOff"
        const val VERBOSE = false

        /** A wakelock's level lives in the low 16 bits of levelAndFlags. */
        const val WAKE_LOCK_LEVEL_MASK = 0x0000FFFF

        val frameworkClassLoader: ClassLoader? = View::class.java.classLoader
    }

    /** Window.setFlags(flags, mask) */
    private val clearFlagArg = Hooker { chain ->
        val args = chain.args.toTypedArray()
        args[0] = (args[0] as Int) and LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        chain.proceed(args)
    }

    /** View.setKeepScreenOn(boolean) */
    private val forceKeepScreenOff = Hooker { chain ->
        chain.proceed(arrayOf<Any>(false))
    }

    /**
     * addView / updateViewLayout. Mutates the caller's LayoutParams rather than
     * substituting a copy, so the app's later changes still reach the window.
     */
    private val clearFlagInParams = Hooker { chain ->
        (chain.getArg(1) as? LayoutParams)?.let {
            it.flags = it.flags and LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        chain.proceed()
    }

    /**
     * PowerManager.newWakeLock(levelAndFlags, tag).
     *
     * Levels are distinct values, not independent bits (PARTIAL 1, SCREEN_DIM 6,
     * SCREEN_BRIGHT 10, FULL 26), so screen levels are replaced with PARTIAL
     * rather than masked out — a lock with no level is invalid. ACQUIRE_CAUSES_WAKEUP
     * is dropped because it turns the display on by itself.
     */
    @Suppress("DEPRECATION")
    private val downgradeScreenWakeLock = Hooker { chain ->
        val args = chain.args.toTypedArray()
        val levelAndFlags = args[0] as Int

        when (levelAndFlags and WAKE_LOCK_LEVEL_MASK) {
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            PowerManager.SCREEN_DIM_WAKE_LOCK,
            PowerManager.FULL_WAKE_LOCK -> {
                val flags = levelAndFlags and
                    WAKE_LOCK_LEVEL_MASK.inv() and
                    PowerManager.ACQUIRE_CAUSES_WAKEUP.inv()
                args[0] = PowerManager.PARTIAL_WAKE_LOCK or flags
                if (VERBOSE) log(Log.DEBUG, TAG, "downgraded wakelock '${args.getOrNull(1)}'")
            }
        }

        chain.proceed(args)
    }

    // Every target is a bootclasspath class, so hooking once per process is enough.
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (VERBOSE) log(Log.DEBUG, TAG, "loaded into ${param.processName}")

        val windowManagerGlobal = findClass("android.view.WindowManagerGlobal")

        install("Window.setFlags", clearFlagArg) {
            Window::class.java.getDeclaredMethod(
                "setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
        }

        install("View.setKeepScreenOn", forceKeepScreenOff) {
            View::class.java.getDeclaredMethod(
                "setKeepScreenOn", Boolean::class.javaPrimitiveType
            )
        }

        install("PowerManager.newWakeLock", downgradeScreenWakeLock) {
            PowerManager::class.java.getDeclaredMethod(
                "newWakeLock", Int::class.javaPrimitiveType, String::class.java
            )
        }

        // addView gained a trailing userId parameter in later versions; only one
        // of these two exists on a given device.
        install("WindowManagerGlobal.addView", clearFlagInParams, optional = true) {
            windowManagerGlobal?.getDeclaredMethod(
                "addView",
                View::class.java,
                ViewGroup.LayoutParams::class.java,
                Display::class.java,
                Window::class.java
            )
        }

        install("WindowManagerGlobal.addView(userId)", clearFlagInParams, optional = true) {
            windowManagerGlobal?.getDeclaredMethod(
                "addView",
                View::class.java,
                ViewGroup.LayoutParams::class.java,
                Display::class.java,
                Window::class.java,
                Int::class.javaPrimitiveType
            )
        }

        install("WindowManagerGlobal.updateViewLayout", clearFlagInParams) {
            windowManagerGlobal?.getDeclaredMethod(
                "updateViewLayout",
                View::class.java,
                ViewGroup.LayoutParams::class.java
            )
        }
    }

    private fun findClass(name: String): Class<*>? = try {
        Class.forName(name, false, frameworkClassLoader)
    } catch (error: Throwable) {
        log(Log.WARN, TAG, "$name not found; its hooks will be skipped", error)
        null
    }

    /**
     * Installs one hook. Missing targets are survivable but always reported, since
     * a module that silently hooks nothing looks identical to one that works.
     */
    private inline fun install(
        name: String,
        hooker: Hooker,
        optional: Boolean = false,
        resolve: () -> Executable?
    ) {
        val target = try {
            resolve()
        } catch (error: Throwable) {
            if (!optional) log(Log.WARN, TAG, "could not resolve $name", error)
            null
        }

        if (target == null) {
            if (!optional) log(Log.WARN, TAG, "$name not found on this Android version")
            return
        }

        try {
            hook(target).setId(name).intercept(hooker)
            if (VERBOSE) log(Log.DEBUG, TAG, "hooked $name")
        } catch (error: Throwable) {
            log(Log.WARN, TAG, "failed to hook $name", error)
        }
    }
}
