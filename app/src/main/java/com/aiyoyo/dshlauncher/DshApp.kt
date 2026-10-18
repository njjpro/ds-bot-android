package com.aiyoyo.dshlauncher

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/** 记录未捕获异常，下次启动时展示——自签侧载没有 logcat，只能靠这个闭环。 */
class DshApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                getSharedPreferences("dsh_launcher", MODE_PRIVATE).edit()
                    .putString(KEY_CRASH, writer.toString().take(8000))
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val KEY_CRASH = "last_crash"
    }
}
