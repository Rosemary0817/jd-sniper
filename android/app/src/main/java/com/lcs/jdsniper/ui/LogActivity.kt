package com.lcs.jdsniper.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.lcs.jdsniper.R
import com.lcs.jdsniper.core.RunLog

class LogActivity : AppCompatActivity() {

    private lateinit var log: RunLog
    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        log = RunLog(this)
        tv = findViewById(R.id.tvLog)
        tv.text = log.dump()

        findViewById<Button>(R.id.btnExport).setOnClickListener {
            val f = log.exportCsv()
            if (f == null) {
                tv.append("\n导出失败：没有可写目录")
                return@setOnClickListener
            }
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "导出执行日志"))
            } catch (e: Exception) {
                tv.append("\n导出失败：${e.message}")
            }
        }
    }
}
