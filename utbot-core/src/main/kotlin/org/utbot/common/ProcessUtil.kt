package org.utbot.common

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import java.util.*
import kotlin.jvm.optionals.getOrNull

/**
 * working pid for jvm 8 and 9+
 */
val Process.getPid: Long
    get() = try {
        if (isJvm9Plus) {
            // because we cannot reference Java9+ API here
            ClassLoader.getSystemClassLoader().loadClass("java.lang.Process").getDeclaredMethod("pid").invoke(this) as Long
        } else {
            when (javaClass.name) {
                "java.lang.UNIXProcess" -> {
                    val fPid = javaClass.getDeclaredField("pid")
                    fPid.withAccessibility { fPid.getLong(this) }

                }

                "java.lang.Win32Process", "java.lang.ProcessImpl" -> {
                    val fHandle = javaClass.getDeclaredField("handle")
                    fHandle.withAccessibility {
                        val handle = fHandle.getLong(this)
                        val winntHandle = WinNT.HANDLE()
                        winntHandle.pointer = Pointer.createConstant(handle)
                        Kernel32.INSTANCE.GetProcessId(winntHandle).toLong()
                    }
                }

                else -> -2
            }
        }
    } catch (e: Exception) {
        -1
    }

private interface CLibrary : Library {
    fun getpid(): Int

    companion object {
        val INSTANCE = Native.load("c", CLibrary::class.java) as CLibrary
    }
}

/**
 * working for jvm 8 and 9+
 */
val currentProcessPid: Long
    get() =
        try {
            if (isJvm9Plus) {
                val handleClass = ClassLoader.getSystemClassLoader().loadClass("java.lang.ProcessHandle")
                val handle = handleClass.getDeclaredMethod("current").invoke(handleClass)
                handleClass.getDeclaredMethod("pid").invoke(handle) as Long
            } else {
                if (isWindows) {
                    Kernel32.INSTANCE.GetCurrentProcessId()
                } else {
                    CLibrary.INSTANCE.getpid()
                }.toLong()
            }
        } catch (e: Throwable) {
            -1
        }


fun isProcessAlive(pid: Int): Boolean {
    if (isJvm9Plus) {
        val handleClass = ClassLoader.getSystemClassLoader().loadClass("java.lang.ProcessHandle")
        val handleOptional = handleClass.getDeclaredMethod("of").invoke(handleClass, pid.toLong()) as Optional<*>
        val handle = handleOptional.getOrNull() ?: return false
        return handleClass.getDeclaredMethod("isAlive").invoke(handle) as Boolean
    }
    else {
        val cmd: String = if (isWindows) {
            "cmd /c tasklist /FI \"PID eq $pid\""
        } else {
            "ps -p $pid"
        }

        val proc = Runtime.getRuntime().exec(cmd)

    }
}