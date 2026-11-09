package com.openminis.app.a2a

import android.content.Context
import java.io.File

/** 极简诊断日志：内部目录 + `/sdcard/Download/a2a-bubble.log`（外部可读）。 */
object A2aLog {
    fun write(context: Context, line: String) {
        val text = "${System.currentTimeMillis()} $line\n"
        runCatching {
            val file = File(A2aGateway.rootDir(context), "bubble.log")
            file.parentFile?.mkdirs()
            file.appendText(text)
        }
        runCatching { File("/sdcard/Download/a2a-bubble.log").appendText(text) }
    }
}
