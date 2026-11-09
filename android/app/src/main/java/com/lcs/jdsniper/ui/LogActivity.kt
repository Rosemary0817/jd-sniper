package com.lcs.jdsniper.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.lcs.jdsniper.R
import com.lcs.jdsniper.core.RunLog

class LogActivity : AppCompatActivity() {

    private lateinit var log: RunLog
    private lateinit var tv: TextView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        log = RunLog(this)
        tv = findViewById(R.id.tvLog)
        tvEmpty = findViewById(R.id.tvEmpty)

        render()

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
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

    private fun render() {
        val text = log.dump()
        val empty = text.isBlank() || text.contains("暂无日志")
        if (empty) {
            tvEmpty.visibility = View.VISIBLE
            tv.visibility = View.GONE
            return
        }
        tvEmpty.visibility = View.GONE
        tv.visibility = View.VISIBLE
        tv.text = text
    }
}
