/*
 * autopilot.js — 京东 H5 页面内状态机（路径 B 的核心）
 *
 * 设计约束（不要改）：
 *   1) 只做 DOM 点击，不构造任何下单接口请求 —— h5st / sign 由京东页面自身生成。
 *   2) 只依赖 window / document / setTimeout / Date，可在 Node 假 DOM 中直接跑测试。
 *   3) 时间基准：服务端毫秒（Date.now() - offset），offset = 服务端时间 - 本机时间。
 *   4) 所有对外事件走 __LCS_NATIVE.post(json)。
 */
;(function (W) {
  'use strict';

  var D = W.document;
  var NS = W.__LCS || (W.__LCS = {});

  var VERSION = '1.0.0';
  var MAX_TIMER = 2147483000;

  var DEFAULTS = {
    jitterMinMs: 60,
    jitterMaxMs: 260,
    tapIntervalMs: 600,
    maxAttemptsPerItem: 3,
    stopOnSoldOut: true,
    retryWindowSec: 90,
    mode: 'GRAB',
    autoSubmit: true
  };

  /* ---------- 按钮词典：文本优先，选择器兜底 ---------- */
  var TXT = {
    reserve: ['预约', '预约Ta', '立即预约', '提前预约', '先预约', '预约提醒'],
    grab: ['立即抢购', '马上抢购', '立即购买', '马上抢', '去抢购', '抢购', '立即下单'],
    cart: ['加入购物车', '加购物车'],
    settle: ['去结算', '去购物车结算', '立即结算', '结算', '去下单'],
    submit: ['提交订单', '确认提交', '确认订单', '立即提交'],
    soldOut: ['已抢完', '已售完', '已售罄', '无货', '库存不足', '很遗憾', '来晚了',
      '预约已结束', '该商品暂时无货', '活动已结束'],
    risk: ['安全验证', '拖动滑块', '请完成验证', '访问过于频繁', '验证码'],
    login: ['登录/注册', '账号登录', '手机号登录', '密码登录']
  };

  var SEL = {
    risk: '#captcha, .risk-modal, .verify-box, [class*="captcha"]',
    submit: '#submitOrder, .btn-submit, [class*="submit-order"]',
    settle: '#settleBtn, .btn-settle, [class*="go-settle"]'
  };

  var S = {
    cfg: null,
    sku: '',
    url: '',
    stage: 'BOOT',
    running: false,
    armed: false,
    manual: false,
    t0: 0,
    offset: 0,
    deadline: 0,
    attempt: 0,
    taps: 0,
    stageTaps: {},
    reserved: false,
    timer: null,
    lastTapAt: 0,
    stalledPosted: false,
    lastPost: '',
    report: []
  };

  /* ---------- 基础工具 ---------- */

  function norm(s) {
    return String(s == null ? '' : s)
      .replace(/[\s\u00a0]+/g, '')
      .replace(/[（(【\[][^）)】\]]*[）)】\]]/g, '');
  }

  function bodyText() {
    try {
      if (D && D.body && typeof D.body.innerText === 'string') return D.body.innerText;
      if (D && D.body && typeof D.body.textContent === 'string') return D.body.textContent;
    } catch (e) { /* noop */ }
    return '';
  }

  function hasAny(list, text) {
    var t = text == null ? norm(bodyText()) : text;
    for (var i = 0; i < list.length; i++) {
      if (t.indexOf(list[i]) >= 0) return true;
    }
    return false;
  }

  function allElements() {
    try {
      if (D && D.querySelectorAll) {
        var n = D.querySelectorAll('button,a,div,span,li,strong,p');
        var out = [];
        for (var i = 0; i < n.length; i++) out.push(n[i]);
        return out;
      }
    } catch (e) { /* noop */ }
    return [];
  }

  function rectOf(el) {
    try {
      if (el && typeof el.getBoundingClientRect === 'function') {
        var r = el.getBoundingClientRect() || {};
        return { w: r.width || 0, h: r.height || 0 };
      }
    } catch (e) { /* noop */ }
    return { w: 1, h: 1 };
  }

  function visible(el) {
    try {
      if (el && el.style && (el.style.display === 'none' || el.style.visibility === 'hidden')) return false;
      var r = rectOf(el);
      return r.w > 0 && r.h > 0;
    } catch (e) {
      return true;
    }
  }

  function tagOf(el) {
    return String((el && el.tagName) || '').toUpperCase();
  }

  /* 在候选集合里挑“最具体”的匹配元素：文本完全相等 > 前缀 > 包含；同级优先 button/a。 */
  function pick(list) {
    var els = allElements();
    var best = null;
    var bestScore = -1;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (!el) continue;
      var t = norm(el.innerText != null ? el.innerText : el.textContent);
      if (!t || t.length > 18) continue;
      if (!visible(el)) continue;
      var score = -1;
      for (var k = 0; k < list.length; k++) {
        var key = list[k];
        if (t === key) { score = Math.max(score, 100); }
        else if (t.indexOf(key) === 0) { score = Math.max(score, 70); }
        else if (t.indexOf(key) >= 0) { score = Math.max(score, 40); }
      }
      if (score < 0) continue;
      if (tagOf(el) === 'BUTTON' || tagOf(el) === 'A') score += 10;
      score += Math.max(0, 12 - t.length);
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function bySelector(sel) {
    try {
      if (D && D.querySelector) return D.querySelector(sel);
    } catch (e) { /* noop */ }
    return null;
  }

  /* 合成触摸序列：比 el.click() 更接近真实手指，降低被行为风控识别成脚本的概率 */
  function synthTouch(el, r) {
    try {
      if (!W.Touch || !W.TouchEvent) return false;
      var x = (r && r.x != null) ? r.x : 1;
      var y = (r && r.y != null) ? r.y : 1;
      var touch = new W.Touch({ identifier: 1, target: el, clientX: x, clientY: y });
      var opts = { bubbles: true, cancelable: true, touches: [touch], targetTouches: [touch], changedTouches: [touch] };
      el.dispatchEvent(new W.TouchEvent('touchstart', opts));
      el.dispatchEvent(new W.TouchEvent('touchend', opts));
      return true;
    } catch (e) {
      return false;
    }
  }

  function tap(el) {
    if (!el) return false;
    try { if (el.scrollIntoView) el.scrollIntoView({ block: 'center' }); } catch (e) { /* noop */ }
    var r = rectOf(el);
    synthTouch(el, r);
    try { if (typeof el.click === 'function') el.click(); } catch (e) { /* noop */ }
    try {
      if (typeof el.dispatchEvent === 'function' && typeof W.MouseEvent === 'function') {
        el.dispatchEvent(new W.MouseEvent('click', { bubbles: true, cancelable: true }));
      }
    } catch (e) { /* noop */ }
    S.taps++;
    S.stageTaps[S.stage] = (S.stageTaps[S.stage] || 0) + 1;
    return true;
  }

  /* ---------- 与原生通信 ---------- */

  function post(type, extra) {
    var evt = {
      type: type,
      sku: S.sku,
      stage: S.stage,
      ts: Date.now(),
      attempt: S.attempt,
      ver: VERSION
    };
    if (extra) {
      for (var k in extra) {
        if (Object.prototype.hasOwnProperty.call(extra, k)) evt[k] = extra[k];
      }
    }
    S.report.push(evt);
    if (S.report.length > 200) S.report.shift();
    try {
      if (W.__LCS_NATIVE && typeof W.__LCS_NATIVE.post === 'function') {
        W.__LCS_NATIVE.post(JSON.stringify(evt));
      }
    } catch (e) { /* 桥不可用时静默 */ }
    return evt;
  }

  /* ---------- 页面识别 ---------- */

  function detectStage() {
    var url = '';
    try { url = String((W.location && W.location.href) || ''); } catch (e) { /* noop */ }

    if (bySelector(SEL.risk)) return 'RISK';
    if (hasAny(TXT.risk)) return 'RISK';

    if (url.indexOf('plogin') >= 0 || url.indexOf('/login') >= 0) return 'LOGIN';
    if (!pick(TXT.grab) && !pick(TXT.reserve) && !pick(TXT.cart) && hasAny(TXT.login)) return 'LOGIN';

    if (pick(TXT.submit) || bySelector(SEL.submit)) return 'SETTLE';
    if (hasAny(['全选']) && (pick(TXT.settle) || bySelector(SEL.settle))) return 'CART';
    if (pick(TXT.grab) || pick(TXT.reserve) || pick(TXT.cart)) return 'ITEM';
    return S.stage === 'BOOT' ? 'ITEM' : S.stage;
  }

  /* ---------- 主循环 ---------- */

  function serverNow() {
    return Date.now() - S.offset;
  }

  function clearTimer() {
    if (S.timer != null) {
      try { W.clearTimeout(S.timer); } catch (e) { /* noop */ }
      try { clearTimeout(S.timer); } catch (e) { /* noop */ }
      S.timer = null;
    }
  }

  function jitter() {
    var lo = S.cfg.jitterMinMs;
    var hi = Math.max(lo, S.cfg.jitterMaxMs);
    return Math.round(lo + Math.random() * (hi - lo));
  }

  function schedule(ms) {
    clearTimer();
    var d = Math.max(0, Math.min(ms, MAX_TIMER));
    S.timer = setTimeout(loop, d);
  }

  /*
   * 统一点击闸门：同一按钮（同一页面）最小间隔 tapIntervalMs。
   * 秒杀场景最怕“点了没反应就疯狂连点”——重复跳转会把已经进入的结算流程打回。
   */
  function tapGate(el, evtName, extra, afterMs) {
    var t = serverNow();
    if (S.lastTapAt > 0) {
      var gap = t - S.lastTapAt;
      if (gap < S.cfg.tapIntervalMs) {
        schedule(S.cfg.tapIntervalMs - gap + 20);
        return false;
      }
    }
    S.lastTapAt = t;
    S.attempt++;
    tap(el);
    post(evtName, extra);
    schedule(afterMs == null ? jitter() : afterMs);
    return true;
  }

  function finish(reason, extra) {
    S.running = false;
    clearTimer();
    post('done', { reason: reason, detail: extra || '' });
  }

  function pause(reason) {
    S.running = false;
    clearTimer();
    post('paused', { reason: reason });
  }

  function loop() {
    if (!S.running) return;

    var t = serverNow();
    if (t < S.t0 - 30) {
      schedule(Math.min(S.t0 - t - 20, 40));
      return;
    }
    if (t > S.deadline) {
      finish('timeout');
      return;
    }

    var stage = detectStage();
    S.stage = stage;

    if (stage === 'RISK') {
      post('risk', { url: pageUrl() });
      pause('risk');
      return;
    }
    if (stage === 'LOGIN') {
      post('authExpired', { url: pageUrl() });
      pause('login');
      return;
    }

    var grabEl = pick(TXT.grab) || bySelector('#btn-grab, .btn-grab');
    var reserveEl = pick(TXT.reserve);
    var target = grabEl || reserveEl;

    /* 无目标按钮 + 页面出现终止词 => 该 SKU 已结束，立即停，不空转 */
    if (!target && S.cfg.stopOnSoldOut && hasAny(TXT.soldOut)) {
      post('soldOut', { snapshot: norm(bodyText()).slice(0, 60) });
      finish('soldOut');
      return;
    }

    if (stage === 'SETTLE') {
      if (!S.cfg.autoSubmit) {
        post('settleReady');
        finish('manualSubmit');
        return;
      }
      var submitEl = pick(TXT.submit) || bySelector(SEL.submit);
      if (submitEl) {
        S.attempt++;
        tap(submitEl);
        post('orderOk', { taps: S.taps });
        finish('orderOk');
        return;
      }
      schedule(jitter());
      return;
    }

    if (stage === 'CART') {
      var settleEl = pick(TXT.settle) || bySelector(SEL.settle);
      if (settleEl) {
        tapGate(settleEl, 'settleTapped', { taps: S.taps + 1 }, 400);
        return;
      }
      schedule(jitter());
      return;
    }

    /* ITEM：预约优先，其次抢购，最后加购 */
    if (reserveEl && !S.reserved && S.cfg.mode !== 'GRAB_ONLY') {
      if (tapGate(reserveEl, 'reserved', { text: norm(reserveEl.innerText || reserveEl.textContent) }, jitter())) {
        S.reserved = true;
      }
      return;
    }

    if (grabEl) {
      var gap = Math.max(80, Math.min(jitter() - 40, S.cfg.tapIntervalMs));
      tapGate(grabEl, 'grabTapped', {
        text: norm(grabEl.innerText || grabEl.textContent),
        taps: S.taps + 1
      }, gap);
      return;
    }

    var cartEl = pick(TXT.cart);
    if (cartEl) {
      tapGate(cartEl, 'cartTapped', { text: norm(cartEl.innerText || cartEl.textContent) }, 350);
      return;
    }

    /* 一屏没按钮：可能是还没刷出来，保持节奏重试，只告警一次 */
    if (S.stageTaps[stage] && S.stageTaps[stage] > S.cfg.maxAttemptsPerItem * 4) {
      if (!S.stalledPosted) {
        S.stalledPosted = true;
        post('stalled', { stage: stage });
      }
      schedule(800);
      return;
    }
    schedule(jitter());
  }

  function pageUrl() {
    try { return String((W.location && W.location.href) || ''); } catch (e) { return ''; }
  }

  function parseSku(url) {
    var m = String(url || '').match(/(?:product\/|item\.m?\.jd\.com\/)(\d+)/);
    return m ? m[1] : '';
  }

  /* ---------- 对外 API ---------- */

  NS.version = VERSION;

  NS.init = function (cfg) {
    var c = DEFAULTS;
    if (cfg) {
      var src = cfg;
      if (typeof cfg === 'string') {
        try { src = JSON.parse(cfg); } catch (e) { src = null; }
      }
      if (src) {
        for (var k in DEFAULTS) {
          if (src[k] != null) c[k] = src[k];
        }
      }
    }
    S.cfg = c;
    S.url = pageUrl();
    S.sku = parseSku(S.url);
    S.stage = 'ITEM';
    post('ready', { url: S.url, sku: S.sku, mode: c.mode });
    return VERSION;
  };

  /*
   * arm(t0ServerMs, offsetMs)
   * 原生在 T-fireLead 调用一次，JS 自己等到点，避免 evaluateJavascript 的往返抖动。
   */
  NS.arm = function (t0, offset) {
    if (!S.cfg) NS.init(null);
    clearTimer();
    S.t0 = Number(t0) || 0;
    S.offset = Number(offset) || 0;
    S.deadline = S.t0 + Number(S.cfg.retryWindowSec) * 1000;
    S.attempt = 0;
    S.taps = 0;
    S.stageTaps = {};
    S.reserved = false;
    S.lastTapAt = 0;
    S.stalledPosted = false;
    S.armed = true;
    S.running = true;
    S.stage = 'ITEM';
    post('armed', { t0: S.t0, offset: S.offset, deadline: S.deadline });

    var wait = S.t0 - serverNow() - 30;
    schedule(wait > 0 ? wait : 0);
    return S.t0;
  };

  /* 手动开火：测试与“补抢”用，立刻执行 loop */
  NS.fire = function () {
    if (!S.cfg) NS.init(null);
    S.t0 = 0;
    S.deadline = serverNow() + Number(S.cfg.retryWindowSec) * 1000;
    S.armed = true;
    S.running = true;
    S.manual = true;
    post('armed', { manual: true, deadline: S.deadline });
    schedule(0);
    return true;
  };

  NS.stop = function (reason) {
    clearTimer();
    S.running = false;
    post('stopped', { reason: reason || 'manual' });
    return true;
  };

  NS.state = function () {
    return {
      ver: VERSION,
      stage: S.stage,
      running: S.running,
      armed: S.armed,
      t0: S.t0,
      offset: S.offset,
      attempt: S.attempt,
      taps: S.taps,
      sku: S.sku,
      url: pageUrl()
    };
  };

  /* 诊断：把当前页所有可点元素的文本吐出来，页面改版时用它重新映射词典 */
  NS.probe = function () {
    var els = allElements();
    var out = [];
    for (var i = 0; i < els.length && out.length < 60; i++) {
      var el = els[i];
      var t = norm(el.innerText != null ? el.innerText : el.textContent);
      if (!t || t.length > 24) continue;
      out.push({ text: t, tag: tagOf(el), vis: visible(el) });
    }
    return JSON.stringify({
      url: pageUrl(),
      title: (D && D.title) || '',
      stage: detectStage(),
      sku: parseSku(pageUrl()),
      buttons: out
    });
  };

  /* 供原生读取最近事件（桥丢事件时的兜底） */
  NS.report = function (n) {
    var k = n || 20;
    return JSON.stringify(S.report.slice(-k));
  };

  NS._reset = function () {
    clearTimer();
    S.report = [];
    S.stage = 'BOOT';
    S.running = false;
    S.armed = false;
    S.attempt = 0;
    S.taps = 0;
    S.stageTaps = {};
    S.reserved = false;
    S.lastTapAt = 0;
    S.stalledPosted = false;
  };

  /* 页面就绪后自动 init，方便原生直接 arm */
  try {
    if (D && D.addEventListener) {
      D.addEventListener('DOMContentLoaded', function () {
        if (!S.cfg) NS.init(null);
      });
    }
  } catch (e) { /* noop */ }

})(typeof window !== 'undefined' ? window : this);
