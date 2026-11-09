package com.lcs.jdsniper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lcs.jdsniper.R
import com.lcs.jdsniper.core.AlarmScheduler
import com.lcs.jdsniper.core.Notifier
import com.lcs.jdsniper.core.SaleCalendar
import com.lcs.jdsniper.core.TimeSync
import com.lcs.jdsniper.data.AppConfig
import com.lcs.jdsniper.data.ConfigStore
import com.lcs.jdsniper.data.ItemConfig
import com.lcs.jdsniper.util.Perms
import com.lcs.jdsniper.util.Skus
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var tvNext: TextView
    private lateinit var tvOffset: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var itemBox: LinearLayout

    private val rows = ArrayList<Row>()
    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private class Row(
        val cb: CheckBox,
        val alias: EditText,
        val sku: EditText,
        val url: EditText
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = ConfigStore(this)
        Notifier.ensureChannel(this)

        tvNext = findViewById(R.id.tvNext)
        tvOffset = findViewById(R.id.tvOffset)
        permBox = findViewById(R.id.permBox)
        itemBox = findViewById(R.id.itemBox)

        bindItems()
        bindPerms()
        bindActions()
        ensureNotificationPermission()
        refresh()

        if (store.offsetMs == 0L) {
            // 首次进来顺手校一次时
            syncClock(silent = true)
        }
    }

    override fun onResume() {
        super.onResume()
        bindPerms()
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun bindActions() {
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }
        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.btnSync).setOnClickListener { syncClock(silent = false) }
        findViewById<Button>(R.id.btnPrewarm).setOnClickListener {
            startActivity(Intent(this, SniperActivity::class.java))
        }
    }

    private fun bindPerms() {
        permBox.removeAllViews()
        val items: List<Triple<Int, Boolean, () -> Unit>> = listOf(
            Triple(R.string.perm_exact, Perms.exactAlarm(this)) { Perms.openExact(this) },
            Triple(R.string.perm_battery, Perms.battery(this)) { Perms.openBattery(this) },
            Triple(R.string.perm_overlay, Perms.overlay(this)) { Perms.openOverlay(this) },
            Triple(R.string.perm_notify, Perms.notifications(this)) { Perms.openNotif(this) }
        )
        for ((labelRes, ok, act) in items) {
            val b = Button(this).apply {
                text = (if (ok) "✓ " else "✗ 点击开启：") + getString(labelRes)
                textSize = 13f
                setTextColor(if (ok) 0xFF12B76A.toInt() else 0xFFE1251B.toInt())
                setOnClickListener { act() }
            }
            permBox.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun bindItems() {
        val cfg = store.load()
        itemBox.removeAllViews()
        rows.clear()

        val inflater = LayoutInflater.from(this)
        cfg.items.forEachIndexed { idx, item ->
            val v = inflater.inflate(R.layout.item_edit, itemBox, false)
            val cb = v.findViewById<CheckBox>(R.id.cbEnabled)
            val etAlias = v.findViewById<EditText>(R.id.etAlias)
            val etSku = v.findViewById<EditText>(R.id.etSku)
            val etUrl = v.findViewById<EditText>(R.id.etUrl)
            val tvPriority = v.findViewById<TextView>(R.id.tvPriority)

            cb.isChecked = item.enabled
            etAlias.setText(item.alias)
            etSku.setText(item.sku)
            etUrl.setText(item.url)
            tvPriority.text = "#${idx + 1}"

            etSku.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val parsed = Skus.parseSku(etSku.text.toString())
                    if (parsed != null && etSku.text.toString() != parsed) etSku.setText(parsed)
                    if (parsed != null && etUrl.text.isBlank()) etUrl.setText(Skus.urlFor(parsed))
                }
            }

            itemBox.addView(v)
            rows.add(Row(cb, etAlias, etSku, etUrl))
        }
    }

    private fun saveAll() {
        val old = store.load()
        val items = rows.mapIndexed { idx, r ->
            val raw = r.sku.text.toString().trim()
            val sku = Skus.parseSku(raw) ?: raw
            val url = r.url.text.toString().trim()
                .ifBlank { if (sku.isNotBlank()) Skus.urlFor(sku) else "" }
            ItemConfig(
                alias = r.alias.text.toString().trim().ifBlank { "商品${idx + 1}" },
                sku = sku,
                url = url,
                expectPrice = "",
                priority = idx + 1,
                enabled = r.cb.isChecked,
                reserve = true
            )
        }
        val next: AppConfig = old.copy(items = items)
        store.save(next)

        val t = AlarmScheduler.schedule(this, next)
        Toast.makeText(
            this,
            if (t == null) "已保存，但没有可排期的事件" else "已保存，下次 ${t.local}",
            Toast.LENGTH_LONG
        ).show()
        refresh()
    }

    private fun refresh() {
        val cfg = store.load()
        val t = SaleCalendar.next(cfg, Instant.now())
        tvNext.text = if (t == null) {
            getString(R.string.next_event_none)
        } else {
            "下一次：${t.local}\n" +
                "已布防：${t.key}\n" +
                "预热 ${SaleCalendar.formatLocal(t.prewarmAt)} · 开火 ${SaleCalendar.formatLocal(t.fireAt)}"
        }

        val off = store.offsetMs
        tvOffset.text = if (off == 0L) {
            "时钟偏差：未校准（开抢前点“立即校时”）"
        } else {
            "时钟偏差：${off} ms · 校于 ${clockFmt.format(Date(store.offsetAt))}"
        }

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ refresh() }, 1000)
    }

    private fun syncClock(silent: Boolean) {
        thread(name = "ntp-sync") {
            val r = TimeSync.sync()
            runOnUiThread {
                if (r == null) {
                    if (!silent) Toast.makeText(this, "校时失败，检查网络", Toast.LENGTH_SHORT).show()
                } else {
                    store.saveOffset(r.offsetMs, r.at)
                    refresh()
                    if (!silent) {
                        Toast.makeText(this, "偏差 ${r.offsetMs}ms（${r.source}）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                42
            )
        }
    }
}
