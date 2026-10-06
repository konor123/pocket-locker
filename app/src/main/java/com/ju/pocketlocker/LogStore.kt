package com.ju.pocketlocker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 설정 화면 하단에 보여주는 간단한 파일 로그. 최근 200줄까지 유지된다. */
object LogStore {

    private const val FILE_NAME = "pocket_locker.log"
    private const val MAX_LINES = 200

    @Synchronized
    fun append(context: Context, message: String) {
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val lines = readLines(context).toMutableList()
            lines.add("$time  $message")
            while (lines.size > MAX_LINES) lines.removeAt(0)
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use {
                it.write(lines.joinToString("\n").toByteArray())
            }
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String = try {
        readLines(context).joinToString("\n")
    } catch (_: Exception) {
        ""
    }

    fun clear(context: Context) {
        try {
            context.deleteFile(FILE_NAME)
        } catch (_: Exception) {
        }
    }

    private fun readLines(context: Context): List<String> = try {
        context.openFileInput(FILE_NAME).bufferedReader().readLines()
    } catch (_: Exception) {
        emptyList()
    }
}
