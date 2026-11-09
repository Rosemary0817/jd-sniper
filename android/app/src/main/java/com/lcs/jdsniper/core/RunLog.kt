package com.lcs.jdsniper.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** JSONL 落盘 + CSV 导出。所有写操作串行化，避免多 WebView 并发写坏文件。 */
class RunLog(private val ctx: Context) {

    private val lock = Any()

    private fun dayFile(): File {
        val dir = File(ctx.filesDir, "runs")
        if (!dir.exists()) dir.mkdirs()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return File(dir, "$day.jsonl")
    }

    fun append(e: BridgeEvent) = writeLine(e.toLine())

    fun appendRaw(type: String, detail: String) {
        writeLine(
            BridgeEvent(
                type = type,
                sku = "",
                stage = "",
                ts = System.currentTimeMillis(),
                attempt = 0,
                detail = detail
            ).toLine()
        )
    }

    private fun writeLine(line: String) {
        synchronized(lock) {
            try {
                dayFile().appendText(line + "\n")
            } catch (e: Exception) {
                // 存储不可写时静默，抢购优先
            }
        }
    }

    fun dump(limit: Int = 400): String {
        synchronized(lock) {
            val dir = File(ctx.filesDir, "runs")
            if (!dir.exists()) return "暂无日志"
            val files = dir.listFiles()?.sortedBy { it.name }?.takeLast(3) ?: return "暂无日志"
            val sb = StringBuilder()
            for (f in files) {
                sb.append("== ").append(f.name).append(" ==\n")
                try {
                    val lines = f.readLines()
                    lines.takeLast(limit).forEach { sb.append(it).append('\n') }
                } catch (e: Exception) {
                    sb.append("读取失败：").append(e.message).append('\n')
                }
            }
            return sb.toString()
        }
    }

    fun exportCsv(): File? {
        synchronized(lock) {
            return try {
                val dir = File(ctx.cacheDir, "export")
                if (!dir.exists()) dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val out = File(dir, "jd_runs_$stamp.csv")
                out.writeText("time,iso,type,sku,stage,attempt,detail\n")

                val srcDir = File(ctx.filesDir, "runs")
                val files = srcDir.listFiles()?.sortedBy { it.name } ?: emptyList()
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                for (f in files) {
                    f.forEachLine { line ->
                        if (line.startsWith("{")) {
                            val e = BridgeEvent.parse(line)
                            if (e != null) {
                                val t = iso.format(Date(e.ts))
                                out.appendText(
                                    listOf(
                                        e.ts.toString(),
                                        t,
                                        e.type,
                                        e.sku,
                                        e.stage,
                                        e.attempt.toString(),
                                        e.detail
                                    ).joinToString(",") { esc(it) } + "\n"
                                )
                            }
                        }
                    }
                }
                out
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun esc(s: String): String {
        val v = s.replace("\"", "\"\"")
        return if (v.contains(',') || v.contains('\n') || v.contains('"')) "\"$v\"" else v
    }
}
