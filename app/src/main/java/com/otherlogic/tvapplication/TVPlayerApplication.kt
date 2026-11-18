package com.otherlogic.tvapplication

import androidx.multidex.MultiDexApplication
import android.os.Process
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class TVPlayerApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->

            try {

                val sw = StringWriter()
                val pw = PrintWriter(sw)

                throwable.printStackTrace(pw)

                val crashText = """
                    ===== CRASH REPORT =====
                    
                    Message:
                    ${throwable.message}
                    
                    Stacktrace:
                    ${sw}
                    
                """.trimIndent()

                val crashDir = File(
                    getExternalFilesDir(null),
                    "logs"
                )

                if (!crashDir.exists()) {
                    crashDir.mkdirs()
                }

                val crashFile = File(
                    crashDir,
                    "crash.txt"
                )

                crashFile.writeText(crashText)

            } catch (e: Exception) {
                Log.e("CrashHandler", "Failed to save crash", e)
            }
            Toast.makeText(
                applicationContext,
                "Application crashed. Crash log saved.",
                android.widget.Toast.LENGTH_LONG
            ).show()

            Thread.sleep(2000)
            Process.killProcess(Process.myPid())
            System.exit(1)
        }
    }
}