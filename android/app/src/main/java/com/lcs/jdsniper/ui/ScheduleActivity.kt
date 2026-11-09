package com.lcs.jdsniper.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.lcs.jdsniper.R
import com.lcs.jdsniper.core.AlarmScheduler
import com.lcs.jdsniper.data.AppConfig
import com.lcs.jdsniper.data.ConfigStore
import com.lcs.jdsniper.data.SaleEvent
import com.lcs.jdsniper.util.Dow
import java.util.Locale

/**
 * 时间表编辑：周几、时刻、动作三样都能直接点着改，改完立刻重排闹钟。
 * 只用原生 TimePickerDialog，不引额外依赖。
 */
class ScheduleActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var eventBox: LinearLayout
    private val events = ArrayList<SaleEvent>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        store = ConfigStore(this)
        eventBox = findViewById(R.id.eventBox)
        events.addAll(store.load().events)

        render()

        findViewById<MaterialButton>(R.id.btnAddEvent).setOnClickListener { addEvent() }
        findViewById<MaterialButton>(R.id.btnResetEvents).setOnClickListener { resetToDefault() }
        findViewById<MaterialButton>(R.id.btnSaveSchedule).setOnClickListener { save() }
    }

    private fun render() {
        eventBox.removeAllViews()
        events.forEachIndexed { index, e ->
            eventBox.addView(buildRow(index, e))
        }
    }

    private fun buildRow(index: Int, e: SaleEvent): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_event, eventBox, false)
        val tvDow = row.findViewById<TextView>(R.id.tvDow)
        val tvTime = row.findViewById<TextView>(R.id.tvTime)
        val tvMode = row.findViewById<TextView>(R.id.tvMode)
        val tvDel = row.findViewById<TextView>(R.id.tvDel)

        tvDow.text = Dow.label(e.dow)
        tvTime.text = e.time
        tvMode.text = if (e.mode == MODE_RESERVE) {
            getString(R.string.event_mode_reserve)
        } else {
            getString(R.string.event_mode_grab)
        }

        tvDow.setOnClickListener {
            events[index] = e.copy(dow = Dow.next(e.dow))
            render()
        }

        tvTime.setOnClickListener {
            val parts = e.time.split(":")
            val hh = parts.getOrNull(0)?.toIntOrNull() ?: 10
            val mm = parts.getOrNull(1)?.toIntOrNull() ?: 0
            TimePickerDialog(
                this,
                { _, h, m ->
                    events[index] = e.copy(
                        time = String.format(Locale.US, "%02d:%02d:00", h, m)
                    )
                    render()
                },
                hh, mm, true
            ).show()
        }

        tvMode.setOnClickListener {
            val next = if (e.mode == MODE_RESERVE) "GRAB" else MODE_RESERVE
            events[index] = e.copy(mode = next)
            render()
        }

        tvDel.setOnClickListener {
            if (events.size <= 1) {
                toast(getString(R.string.toast_keep_one))
                return@setOnClickListener
            }
            events.removeAt(index)
            render()
        }

        return row
    }

    private fun addEvent() {
        if (events.size >= MAX_EVENTS) {
            toast(getString(R.string.toast_max_events))
            return
        }
        events.add(
            SaleEvent(
                id = "custom_${System.currentTimeMillis()}",
                dow = 5,
                time = "10:00:00",
                mode = "GRAB",
                leadMs = 250L
            )
        )
        render()
    }

    private fun resetToDefault() {
        events.clear()
        events.addAll(AppConfig.DEFAULT_EVENTS)
        render()
        toast("已恢复内置 5 个时段，记得点保存")
    }

    private fun save() {
        if (events.isEmpty()) {
            resetToDefault()
            return
        }
        // leadMs 按动作补齐，避免用户复制出 0 提前量
        val list = events.map { e ->
            e.copy(
                time = normalizeTime(e.time),
                leadMs = if (e.mode == MODE_RESERVE) 400L else 250L
            )
        }
        val cfg = store.load().copy(events = list)
        store.save(cfg)
        val t = AlarmScheduler.schedule(this, cfg)
        val msg = if (t == null) {
            "已保存，但排期失败：检查系统时间"
        } else {
            getString(R.string.toast_schedule_saved, list.size) + " · 下次 ${t.local}"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun normalizeTime(raw: String): String {
        val parts = raw.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 10
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val s = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MODE_RESERVE = "RESERVE_AND_GRAB"
        private const val MAX_EVENTS = 12
    }
}
