package com.lcs.jdsniper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.lcs.jdsniper.R
import com.lcs.jdsniper.core.AlarmScheduler
import com.lcs.jdsniper.core.Notifier
import com.lcs.jdsniper.core.SaleCalendar
import com.lcs.jdsniper.core.TimeSync
import com.lcs.jdsniper.data.AppConfig
import com.lcs.jdsniper.data.ConfigStore
import com.lcs.jdsniper.data.ItemConfig
import com.lcs.jdsniper.util.Dow
import com.lcs.jdsniper.util.Perms
import com.lcs.jdsniper.util.Skus
import java.time.Instant
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var tvNextTime: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvClockPill: TextView
    private lateinit var tvItemCount: TextView
    private lateinit var tvPermCount: TextView
    private lateinit var tvScheduleCount: TextView
    private lateinit var tvSchedulePreview: TextView
    private lateinit var itemBox: LinearLayout
    private lateinit var permBox: LinearLayout

    private val rows = ArrayList<Row>()
    private val handler = Handler(Looper.getMainLooper())

    private class Row(
        val card: View,
        val etAlias: EditText,
        val etSku: EditText,
        val etUrl: EditText,
        val tvUrl: TextView,
        val tvMore: TextView,
        val tvPriority: TextView,
        val sw: SwitchMaterial
    )

    private class PermDef(
        val nameRes: Int,
        val descRes: Int,
        val granted: Boolean,
        val open: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = ConfigStore(this)
        Notifier.ensureChannel(this)

        tvNextTime = findViewById(R.id.tvNextTime)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvClockPill = findViewById(R.id.tvClockPill)
        tvItemCount = findViewById(R.id.tvItemCount)
        tvPermCount = findViewById(R.id.tvPermCount)
        tvScheduleCount = findViewById(R.id.tvScheduleCount)
        tvSchedulePreview = findViewById(R.id.tvSchedulePreview)
        itemBox = findViewById(R.id.itemBox)
        permBox = findViewById(R.id.permBox)

        bindActions()
        renderItems()
        renderPerms()
        renderHeader()
        handler.postDelayed(ticker, 1000)

        ensureNotificationPermission()
        if (store.offsetMs == 0L) syncClock(silent = true)
    }

    override fun onResume() {
        super.onResume()
        renderPerms()
        renderHeader()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderHeader()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- 动作 ----------

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.btnSave).apply {
            setOnClickListener { saveAll() }
            setOnLongClickListener {
                resetSchedule()
                true
            }
        }
        findViewById<MaterialButton>(R.id.btnLogin).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        findViewById<View>(R.id.cardSchedule).setOnClickListener {
            startActivity(Intent(this, ScheduleActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnSync).setOnClickListener { syncClock(silent = false) }
        findViewById<MaterialButton>(R.id.btnPrewarm).setOnClickListener {
            startActivity(Intent(this, SniperActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnGuide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<TextView>(R.id.tvAddItem).setOnClickListener { addItemRow(null) }
    }

    // ---------- 头卡 ----------

    private fun renderHeader() {
        val cfg = store.load()
        renderScheduleCard(cfg)
        val off = store.offsetMs
        tvClockPill.text = if (off == 0L) {
            getString(R.string.pill_clock_bad)
        } else {
            val sign = if (off > 0) "+" else ""
            "已校准 ${sign}${off}ms"
        }

        val t = SaleCalendar.next(cfg, Instant.now())
        if (t == null) {
            tvNextTime.text = getString(R.string.next_event_none)
            tvCountdown.text = getString(R.string.next_empty_hint)
            return
        }
        val z = t.at.atZone(SaleCalendar.ZONE)
        val dow = "周" + "一二三四五六日"[z.dayOfWeek.value - 1]
        tvNextTime.text = buildString {
            append(dow)
            append(' ')
            append(z.monthValue)
            append('月')
            append(z.dayOfMonth)
            append("日 ")
            append(String.format(Locale.US, "%02d:%02d", z.hour, z.minute))
        }
        val left = t.at.toEpochMilli() - System.currentTimeMillis() - off
        tvCountdown.text = if (left <= 0L) "正在执行…" else "还有 ${human(left)}"
    }

    private fun human(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 86400 -> "${s / 86400} 天 ${(s % 86400) / 3600} 小时"
            s >= 3600 -> "${s / 3600} 小时 ${(s % 3600) / 60} 分"
            else -> "${s / 60} 分 ${s % 60} 秒"
        }
    }

    private fun renderScheduleCard(cfg: AppConfig) {
        val list = cfg.events.sortedWith(compareBy({ it.dow }, { it.time }))
        tvScheduleCount.text = getString(R.string.schedule_count, list.size)
        if (list.isEmpty()) {
            tvSchedulePreview.text = "时间表为空，点击进入可恢复默认"
            return
        }
        val head = list.take(3).joinToString("   ·   ") { e ->
            "${Dow.label(e.dow)} ${e.time.take(5)}"
        }
        tvSchedulePreview.text = if (list.size > 3) "$head   ·   …" else head
    }

    // ---------- 商品 ----------

    private fun renderItems() {
        val cfg = store.load()
        itemBox.removeAllViews()
        rows.clear()
        if (cfg.items.isEmpty()) {
            addItemRow(null)
        } else {
            cfg.items.forEach { addItemRow(it) }
        }
        updateItemCount()
    }

    private fun addItemRow(source: ItemConfig?) {
        if (rows.size >= 4) {
            Toast.makeText(this, "最多同时挂 4 个商品", Toast.LENGTH_SHORT).show()
            return
        }
        val card = LayoutInflater.from(this).inflate(R.layout.item_product, itemBox, false)
        val etAlias = card.findViewById<EditText>(R.id.etAlias)
        val etSku = card.findViewById<EditText>(R.id.etSku)
        val etUrl = card.findViewById<EditText>(R.id.etUrl)
        val tvUrl = card.findViewById<TextView>(R.id.tvUrl)
        val tvMore = card.findViewById<TextView>(R.id.tvMore)
        val tvPriority = card.findViewById<TextView>(R.id.tvPriority)
        val sw = card.findViewById<SwitchMaterial>(R.id.swEnabled)

        etAlias.setText(source?.alias ?: "")
        etSku.setText(source?.sku ?: "")
        val custom = source?.url ?: ""
        val src = source
        if (custom.isNotBlank() && src != null && src.sku.isNotBlank() &&
            !custom.endsWith("${src.sku}.html")
        ) {
            etUrl.setText(custom)
            etUrl.visibility = View.VISIBLE
            tvMore.text = "收起自定义地址"
        }
        sw.isChecked = source?.enabled ?: true
        tvPriority.text = "#${rows.size + 1}"

        val row = Row(card, etAlias, etSku, etUrl, tvUrl, tvMore, tvPriority, sw)
        rows.add(row)
        itemBox.addView(card)
        updateItemCount()

        etSku.doAfterTextChanged { refreshUrlHint(row) }
        etUrl.doAfterTextChanged { refreshUrlHint(row) }
        tvMore.setOnClickListener {
            etUrl.visibility = if (etUrl.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            tvMore.text = if (etUrl.visibility == View.VISIBLE) "收起自定义地址" else getString(R.string.item_url_toggle)
        }
        sw.setOnCheckedChangeListener { _, checked ->
            card.alpha = if (checked) 1f else 0.55f
            updateItemCount()
        }
        card.alpha = if (sw.isChecked) 1f else 0.55f

        refreshUrlHint(row)
    }

    private fun refreshUrlHint(row: Row) {
        val custom = row.etUrl.text.toString().trim()
        if (custom.isNotBlank()) {
            row.tvUrl.text = custom
            return
        }
        val sku = Skus.parseSku(row.etSku.text.toString())
        row.tvUrl.text = if (sku != null) Skus.urlFor(sku) else "填了 SKU 后自动生成打开地址"
    }

    private fun updateItemCount() {
        val on = rows.count { it.sw.isChecked }
        tvItemCount.text = "$on/${rows.size.coerceAtLeast(1)}"
    }

    private fun saveAll() {
        val old = store.load()
        val items = ArrayList<ItemConfig>()
        rows.forEachIndexed { idx, r ->
            val raw = r.etSku.text.toString().trim()
            val sku = Skus.parseSku(raw) ?: raw
            if (sku.isBlank()) return@forEachIndexed
            val custom = r.etUrl.text.toString().trim()
            items.add(
                ItemConfig(
                    alias = r.etAlias.text.toString().trim().ifBlank { "商品${idx + 1}" },
                    sku = sku,
                    url = custom.ifBlank { Skus.urlFor(sku) },
                    expectPrice = "",
                    priority = idx + 1,
                    enabled = r.sw.isChecked,
                    reserve = true
                )
            )
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "至少填一个商品 SKU", Toast.LENGTH_SHORT).show()
            return
        }

        val next: AppConfig = old.copy(items = items)
        store.save(next)
        val t = AlarmScheduler.schedule(this, next)
        val msg = if (t == null) {
            "已保存商品，但时间表为空：长按本按钮恢复默认"
        } else {
            "已布防 ${t.local} · ${next.events.size} 个时段 · ${items.count { it.enabled }} 个商品"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        renderHeader()
    }

    /** 时间表被写坏时的兜底入口：长按「保存并布防」 */
    private fun resetSchedule() {
        val cfg = store.load().copy(events = AppConfig.DEFAULT_EVENTS)
        store.save(cfg)
        val t = AlarmScheduler.schedule(this, cfg)
        val msg = if (t == null) {
            "已写回默认时间表，但排期仍失败，检查系统时间"
        } else {
            "已恢复默认时间表 · 下次 ${t.local}"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        renderHeader()
    }

    // ---------- 权限 ----------

    private fun renderPerms() {
        permBox.removeAllViews()
        val defs = listOf(
            PermDef(R.string.perm_exact, R.string.perm_exact_desc, Perms.exactAlarm(this)) {
                Perms.openExact(this)
            },
            PermDef(R.string.perm_battery, R.string.perm_battery_desc, Perms.battery(this)) {
                Perms.openBattery(this)
            },
            PermDef(R.string.perm_overlay, R.string.perm_overlay_desc, Perms.overlay(this)) {
                Perms.openOverlay(this)
            },
            PermDef(R.string.perm_notify, R.string.perm_notify_desc, Perms.notifications(this)) {
                Perms.openNotif(this)
            }
        )

        var ok = 0
        for (d in defs) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_perm, permBox, false)
            v.findViewById<TextView>(R.id.tvPermName).text = getString(d.nameRes)
            v.findViewById<TextView>(R.id.tvPermDesc).text = getString(d.descRes)
            v.findViewById<View>(R.id.vDot).setBackgroundResource(
                if (d.granted) R.drawable.dot_green else R.drawable.dot_red
            )
            val state = v.findViewById<TextView>(R.id.tvPermState)
            state.text = getString(if (d.granted) R.string.perm_on else R.string.perm_off)
            state.setTextColor(
                ContextCompat.getColor(this, if (d.granted) R.color.ok_green else R.color.brand_red)
            )
            v.setOnClickListener { d.open() }
            permBox.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            if (d.granted) ok++
        }
        tvPermCount.text = "$ok/${defs.size}"
    }

    // ---------- 校时 ----------

    private fun syncClock(silent: Boolean) {
        thread(name = "ntp-sync") {
            val r = TimeSync.sync()
            runOnUiThread {
                if (r == null) {
                    if (!silent) Toast.makeText(this, "校时失败，检查网络", Toast.LENGTH_SHORT).show()
                } else {
                    store.saveOffset(r.offsetMs, r.at)
                    renderHeader()
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
