package com.lcs.jdsniper.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.lcs.jdsniper.R
import com.lcs.jdsniper.core.BridgeEvent
import com.lcs.jdsniper.core.JsAssets
import com.lcs.jdsniper.core.JsBridge
import com.lcs.jdsniper.core.Notifier
import com.lcs.jdsniper.core.RunLog
import com.lcs.jdsniper.core.SaleCalendar
import com.lcs.jdsniper.core.SniperService
import com.lcs.jdsniper.core.TimeSync
import com.lcs.jdsniper.core.WebViewPool
import com.lcs.jdsniper.data.AppConfig
import com.lcs.jdsniper.data.ConfigStore
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 抢购台：T-5min 就加载好所有 SKU 页面，T0 由页面内 autopilot 精确开火。
 * 界面只做三件事——把剩余时间喊到你脸上、把每个 SKU 的进度点出来、抢到后震醒人。
 */
class SniperActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var runLog: RunLog
    private lateinit var cfg: AppConfig
    private lateinit var pool: WebViewPool

    private lateinit var tvEventKey: TextView
    private lateinit var tvCountdownBig: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvOffset: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvLog: TextView
    private lateinit var cardContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logBuf = StringBuilder()

    private var offsetMs = 0L
    private var targetAt = 0L
    private var fireAt = 0L
    private var fired = false
    private var stopped = false
    private var eventKey = ""

    private val colorOk = 0xFF7ED0A5.toInt()
    private val colorWarn = 0xFFFFC107.toInt()
    private val colorErr = 0xFFFF6B6B.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        val lockFlags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        window.addFlags(lockFlags)

        setContentView(R.layout.activity_sniper)
        tvEventKey = findViewById(R.id.tvEventKey)
        tvCountdownBig = findViewById(R.id.tvCountdownBig)
        tvClock = findViewById(R.id.tvClock)
        tvOffset = findViewById(R.id.tvOffset)
        tvSub = findViewById(R.id.tvSub)
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod.getInstance()
        cardContainer = findViewById(R.id.cardContainer)

        store = ConfigStore(this)
        runLog = RunLog(this)
        cfg = store.load()
        offsetMs = store.offsetMs

        readIntent(intent)
        tvEventKey.text = eventKey

        val bridge = JsBridge { ev -> handleEvent(ev) }
        pool = WebViewPool(this, cardContainer, bridge) { msg -> appendLog(msg) }
        pool.build(cfg.enabledItems(), cfg.jsConfig())

        findViewById<MaterialButton>(R.id.btnFire).setOnClickListener { doArm(true) }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener { doStop() }
        findViewById<MaterialButton>(R.id.btnDump).setOnClickListener {
            appendLog("PROBE: ${pool.dumpCurrent() ?: "null"}")
            android.widget.Toast.makeText(
                this,
                "诊断结果已写入下方日志",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        appendLog("抢购台启动 · SKU ${pool.count()} 个 · offset=${offsetMs}ms")
        if (pool.count() == 0) {
            appendLog("没有启用的商品：回主界面勾选并保存")
        }

        maybeSyncClock()
        handler.post(ticker)
        scheduleAutoArm()
    }

    private fun readIntent(intent: Intent?) {
        eventKey = intent?.getStringExtra(SniperService.EXTRA_EVENT_KEY) ?: ""
        targetAt = intent?.getLongExtra(SniperService.EXTRA_TARGET_AT, 0L) ?: 0L
        fireAt = intent?.getLongExtra(SniperService.EXTRA_FIRE_AT, 0L) ?: 0L

        if (targetAt <= 0L) {
            val t = SaleCalendar.next(cfg, Instant.now())
            if (t != null) {
                targetAt = t.at.toEpochMilli()
                fireAt = t.fireAt.toEpochMilli()
                if (eventKey.isBlank()) eventKey = t.key
            }
        }
        if (fireAt <= 0L && targetAt > 0L) fireAt = targetAt - cfg.fireLeadMs
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        fired = false
        stopped = false
        readIntent(intent)
        tvEventKey.text = eventKey
        appendLog("重新布防：${SaleCalendar.formatLocal(Instant.ofEpochMilli(targetAt))}")
        handler.removeCallbacksAndMessages(null)
        handler.post(ticker)
        scheduleAutoArm()
    }

    // ---------- 时钟 ----------

    private fun maybeSyncClock() {
        val stale = System.currentTimeMillis() - store.offsetAt > 2 * 3600_000L
        if (offsetMs != 0L && !stale) return
        thread(name = "ntp-sync") {
            val r = TimeSync.sync()
            if (r != null) {
                offsetMs = r.offsetMs
                store.saveOffset(r.offsetMs, r.at)
                runOnUiThread {
                    appendLog("校时完成 offset=${r.offsetMs}ms via ${r.source}")
                }
            } else {
                runOnUiThread { appendLog("校时失败，沿用本机时钟（偏差未知）") }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val serverNow = System.currentTimeMillis() + offsetMs
            tvClock.text = clockFmt.format(Date(serverNow))
            val sign = if (offsetMs > 0) "+" else ""
            tvOffset.text = if (offsetMs == 0L) "offset 未校准" else "offset $sign${offsetMs}ms"

            if (targetAt > 0L && !fired) {
                val left = targetAt - serverNow
                tvCountdownBig.text = if (left > 0) fmtLeft(left) else "00:00"
                if (left > 0) {
                    tvSub.text = "已排期，到点自动开火"
                    tvSub.setTextColor(colorOk)
                }
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun fmtLeft(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    // ---------- 布防 / 开火 ----------

    private fun scheduleAutoArm() {
        if (targetAt <= 0L) {
            appendLog("没有排期：可用「手动开火」立即执行")
            return
        }
        val serverNow = System.currentTimeMillis() + offsetMs
        val windowEnd = targetAt + cfg.retryWindowSec * 1000L
        if (serverNow > windowEnd) {
            appendLog("本轮窗口已过：${SaleCalendar.formatLocal(Instant.ofEpochMilli(targetAt))}")
            return
        }
        waitAndArm()
    }

    private fun waitAndArm() {
        if (fired || stopped || isFinishing) return
        val serverNow = System.currentTimeMillis() + offsetMs
        val left = fireAt - serverNow
        if (left <= 0L) {
            doArm(false)
            return
        }
        val step = when {
            left > 10_000L -> 500L
            left > 2_000L -> 50L
            else -> 5L
        }
        handler.postDelayed({ waitAndArm() }, minOf(step, left))
    }

    private fun doArm(manual: Boolean) {
        if (fired && !manual) return
        fired = true

        val now = System.currentTimeMillis() + offsetMs
        // 已过点时不能把过去的 t0 丢给 JS，否则 deadline 已过会立刻 timeout
        val t0 = if (manual) now + 300L else maxOf(targetAt, now + 100L)
        targetAt = t0
        fireAt = if (manual) t0 - 300L else minOf(fireAt, t0)

        pool.markAll("开火", R.drawable.dot_amber)
        appendLog("开火 t0=$t0 offset=$offsetMs manual=$manual views=${pool.count()}")

        tvSub.text = getString(R.string.sniper_firing)
        tvSub.setTextColor(colorWarn)

        val src = JsAssets.autopilot(this)
        val cfgJs = cfg.jsConfig().toString()
        pool.evalEach { index, _ ->
            val t = t0 + index.toLong() * cfg.staggerPerItemMs
            "(function(){try{" +
                "if(!window.__LCS){" + src + "}" +
                "__LCS.init(" + cfgJs + ");" +
                "__LCS.arm(" + t + "," + offsetMs + ");" +
                "}catch(e){try{__LCS_NATIVE.post(JSON.stringify({type:'error',detail:String(e)}))}catch(_){}}})()"
        }
    }

    private fun doStop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        pool.evalAll("(function(){try{window.__LCS&&__LCS.stop('user')}catch(e){}})()")
        appendLog("已手动停止")
        tvSub.text = getString(R.string.sniper_stopped)
        tvSub.setTextColor(colorWarn)
    }

    // ---------- 事件 ----------

    private fun handleEvent(e: BridgeEvent) {
        runLog.append(e)
        val detail = if (e.detail.isBlank()) "" else " ${e.detail}"
        appendLog("[${e.sku}] ${e.type}$detail")

        when (e.type) {
            "ready" -> pool.markState(e.sku, "已就绪", R.drawable.dot_green)
            "armed" -> pool.markState(e.sku, "已布防", R.drawable.dot_green)
            "reserved" -> pool.markState(e.sku, "已预约", R.drawable.dot_green)
            "grabTapped" -> {
                pool.markState(e.sku, "已点抢购", R.drawable.dot_amber)
                pool.markDetail(e.sku, e.detail)
            }
            "cartTapped" -> pool.markState(e.sku, "已加购", R.drawable.dot_amber)
            "settleTapped" -> pool.markState(e.sku, "进结算", R.drawable.dot_amber)
            "orderOk" -> onOrderOk(e)
            "soldOut" -> pool.markState(e.sku, "已抢完", R.drawable.dot_red)
            "risk" -> onRisk()
            "authExpired" -> onAuthExpired()
            "stalled" -> pool.markState(e.sku, "找不到按钮", R.drawable.dot_amber)
            "error" -> appendLog("JS 异常：${e.detail}")
            "paused", "stopped", "done" -> Unit
        }
    }

    private fun onOrderOk(e: BridgeEvent) {
        pool.markState(e.sku, "订单已提交", R.drawable.dot_green)
        tvSub.text = getString(R.string.sniper_order)
        tvSub.setTextColor(colorErr)
        tvCountdownBig.setTextColor(colorErr)
        appendLog("=== 订单已提交：${e.sku}，去收银台 ===")
        Notifier.notifySuccess(this, "${e.sku} 已提交，尽快付款")
        if (cfg.ui.vibrate) Notifier.vibrate(this)
        runLog.appendRaw("payWait", "订单已提交，等待人工支付 sku=${e.sku}")
        store.markFired(eventKey.ifBlank { "manual" })
    }

    private fun onRisk() {
        pool.expandAll()
        tvSub.text = getString(R.string.sniper_risk)
        tvSub.setTextColor(colorWarn)
        appendLog("命中风控/验证，已暂停自动点击（页面已展开）")
    }

    private fun onAuthExpired() {
        tvSub.text = getString(R.string.sniper_auth)
        tvSub.setTextColor(colorWarn)
        appendLog("登录态失效（未取到 pt_key）")
    }

    private fun appendLog(line: String) {
        logBuf.append(clockFmt.format(Date())).append(' ').append(line).append('\n')
        while (logBuf.length > 5000) logBuf.delete(0, 1200)
        tvLog.text = logBuf
        tvLog.post {
            val l = tvLog.layout
            if (l != null) {
                val y = l.height - tvLog.height
                if (y > 0) tvLog.scrollTo(0, y)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::pool.isInitialized) {
            try {
                pool.destroy()
            } catch (e: Exception) {
                // ignore
            }
        }
        super.onDestroy()
    }
}
