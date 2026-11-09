package com.lcs.jdsniper.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lcs.jdsniper.R
import com.lcs.jdsniper.data.ItemConfig
import org.json.JSONObject

/**
 * 每个 SKU 一张深色状态卡，卡里嵌一个常驻 WebView。
 * T-5min 就加载好页面，T0 只做点击，避免临场冷启动 2-4 秒直接错过。
 */
class WebViewPool(
    private val ctx: Context,
    private val container: LinearLayout,
    private val bridge: JsBridge,
    private val onLog: (String) -> Unit
) {

    class Entry(
        val item: ItemConfig,
        val web: WebView,
        val card: View,
        val slot: FrameLayout,
        val dot: View,
        val stage: TextView,
        val detail: TextView
    ) {
        var expanded: Boolean = true
        var state: String = "准备中"
    }

    private val entries = ArrayList<Entry>()
    private var initScript: String = ""

    fun build(items: List<ItemConfig>, jsCfg: JSONObject) {
        destroy()
        buildInitScript(jsCfg)

        val picked = items.take(MAX_VIEWS)
        if (picked.isEmpty()) {
            onLog("没有启用的商品，先回主界面勾选并保存")
            return
        }

        for (item in picked) {
            val card = LayoutInflater.from(ctx)
                .inflate(R.layout.item_sniper_card, container, false)

            val slot = card.findViewById<FrameLayout>(R.id.webSlot)
            val dot = card.findViewById<View>(R.id.vDot)
            val alias = card.findViewById<TextView>(R.id.tvAlias)
            val stage = card.findViewById<TextView>(R.id.tvStage)
            val detail = card.findViewById<TextView>(R.id.tvDetail)
            val head = card.findViewById<LinearLayout>(R.id.cardHead)

            alias.text = item.alias.ifBlank { item.sku }
            stage.text = "准备中"
            detail.text = item.sku

            val web = createWebView(item)
            slot.addView(
                web,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(196)
                )
            )
            container.addView(card)

            val entry = Entry(item, web, card, slot, dot, stage, detail)
            head.setOnClickListener {
                entry.expanded = !entry.expanded
                slot.visibility = if (entry.expanded) View.VISIBLE else View.GONE
            }

            entries.add(entry)
            web.loadUrl(item.url.ifBlank { "https://item.m.jd.com/product/${item.sku}.html" })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(item: ItemConfig): WebView {
        val web = WebView(ctx)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        web.setBackgroundColor(0xFF0F1114.toInt())
        web.addJavascriptInterface(bridge, "__LCS_NATIVE")

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                inject(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                inject(view)
                try {
                    CookieManager.getInstance().flush()
                } catch (e: Exception) {
                    // ignore
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (u.startsWith("http://") || u.startsWith("https://")) return false
                onLog("拦截外部跳转：$u")
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val req = request ?: return
                if (req.isForMainFrame) {
                    onLog("加载失败：${req.url} code=${error?.errorCode}")
                }
            }
        }
        return web
    }

    private fun buildInitScript(jsCfg: JSONObject) {
        val src = JsAssets.autopilot(ctx)
        initScript = ";(function(){try{$src" +
            ";window.__LCS.init(${jsCfg});" +
            "}catch(e){try{window.__LCS_NATIVE.post(JSON.stringify({type:'error',detail:String(e)}))}catch(_){}}})();"
    }

    private fun inject(view: WebView?) {
        view ?: return
        if (initScript.isEmpty()) return
        try {
            view.evaluateJavascript(initScript, null)
        } catch (e: Exception) {
            onLog("注入失败：${e.message}")
        }
    }

    fun evalAll(js: String) {
        for (e in entries) {
            try {
                e.web.evaluateJavascript(js, null)
            } catch (ex: Exception) {
                onLog("${e.item.alias} 执行失败：${ex.message}")
            }
        }
    }

    fun eval(sku: String, js: String) {
        entries.firstOrNull { it.item.sku == sku }?.let {
            try {
                it.web.evaluateJavascript(js, null)
            } catch (ex: Exception) {
                onLog("执行失败：${ex.message}")
            }
        }
    }

    /** 按商品错开执行（staggerPerItemMs），避免 4 个页面同一毫秒压同一条链路 */
    fun evalEach(jsFor: (Int, ItemConfig) -> String) {
        entries.forEachIndexed { index, e ->
            try {
                e.web.evaluateJavascript(jsFor(index, e.item), null)
            } catch (ex: Exception) {
                onLog("${e.item.alias} 执行失败：${ex.message}")
            }
        }
    }

    fun reloadAll() {
        for (e in entries) {
            try {
                e.web.reload()
            } catch (ex: Exception) {
                // ignore
            }
        }
    }

    fun markState(sku: String, state: String, dotRes: Int = R.drawable.dot_gray) {
        entries.firstOrNull { it.item.sku == sku }?.let {
            it.state = state
            it.stage.text = state
            it.dot.setBackgroundResource(dotRes)
        }
    }

    fun markDetail(sku: String, text: String) {
        entries.firstOrNull { it.item.sku == sku }?.let {
            if (text.isNotBlank()) it.detail.text = text
        }
    }

    fun markAll(state: String, dotRes: Int = R.drawable.dot_gray) {
        for (e in entries) {
            e.state = state
            e.stage.text = state
            e.dot.setBackgroundResource(dotRes)
        }
    }

    /** 需要人工操作时把页面展开，其它情况保持用户当前的折叠状态 */
    fun expandAll() {
        for (e in entries) {
            e.expanded = true
            e.slot.visibility = View.VISIBLE
        }
    }

    fun dumpCurrent(): String? {
        var out: String? = null
        for (e in entries) {
            try {
                e.web.evaluateJavascript(
                    "(window.__LCS?__LCS.probe():'{\"stage\":\"NO_JS\"}')"
                ) { value ->
                    if (out == null) out = value
                }
            } catch (ex: Exception) {
                // ignore
            }
        }
        return out
    }

    fun destroy() {
        for (e in entries) {
            try {
                (e.web.parent as? ViewGroup)?.removeView(e.web)
                e.web.stopLoading()
                e.web.loadUrl("about:blank")
                e.web.destroy()
            } catch (ex: Exception) {
                // ignore
            }
        }
        entries.clear()
        container.removeAllViews()
    }

    fun count(): Int = entries.size

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        const val MAX_VIEWS = 4
    }
}

object JsAssets {
    @Volatile
    private var cached: String? = null

    fun autopilot(ctx: Context): String {
        cached?.let { return it }
        val s = try {
            ctx.assets.open("autopilot.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        cached = s
        return s
    }
}
