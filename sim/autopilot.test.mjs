import test from 'node:test';
import assert from 'node:assert/strict';
import { boot } from './lib/harness.mjs';

const T0 = 1763000000000; // 任意基准时刻
const ITEM_URL = 'https://item.m.jd.com/product/100012043978.html';

test('商品页：先预约再抢购，事件与点击都落地', () => {
  const h = boot({
    nowMs: T0 - 5000,
    url: ITEM_URL,
    buttons: [
      { text: '预约', tag: 'button' },
      { text: '立即抢购', tag: 'button' }
    ]
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(6000);

  const types = h.types();
  assert.ok(types.includes('armed'), 'must emit armed');
  assert.ok(types.includes('reserved'), 'must reserve first');
  assert.ok(types.includes('grabTapped'), 'must tap grab');
  assert.ok(h.taps() >= 2);

  const reserveOrder = types.indexOf('reserved');
  const grabOrder = types.indexOf('grabTapped');
  assert.ok(reserveOrder < grabOrder, '预约必须在抢购之前');
});

test('T0 之前一次都不点', () => {
  const h = boot({
    nowMs: T0 - 60000,
    url: ITEM_URL,
    buttons: [{ text: '立即抢购', tag: 'button' }]
  });
  h.NS.init(null);
  h.NS.arm(T0 + 60000, 0);
  h.clock.advance(5000);
  assert.equal(h.taps(), 0);
  assert.equal(h.types().includes('armed'), true);
});

test('风控页：立即暂停，等人接管，不盲点', () => {
  const h = boot({
    nowMs: T0,
    url: ITEM_URL,
    buttons: [{ text: '安全验证', tag: 'div' }],
    text: '请拖动滑块完成验证'
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(3000);
  const types = h.types();
  assert.ok(types.includes('risk'));
  assert.ok(types.includes('paused'));
  assert.equal(h.taps(), 0);
});

test('已抢完：立刻停止，不空转', () => {
  const h = boot({
    nowMs: T0,
    url: ITEM_URL,
    buttons: [{ text: '已抢完', tag: 'span' }]
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(3000);
  const done = h.events.find((e) => e.type === 'done');
  assert.ok(done, 'must finish');
  assert.equal(done.reason, 'soldOut');
  assert.ok(h.types().includes('soldOut'));
});

test('结算页：自动提交订单并终止', () => {
  const h = boot({
    nowMs: T0,
    url: 'https://trade.m.jd.com/order/...',
    buttons: [
      { text: '提交订单', tag: 'button' },
      { text: '收货地址', tag: 'div' }
    ]
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(3000);
  const done = h.events.find((e) => e.type === 'done');
  assert.equal(done.reason, 'orderOk');
  assert.ok(h.types().includes('orderOk'));
});

test('autoSubmit=false 时停在结算页等人工', () => {
  const h = boot({
    nowMs: T0,
    url: 'https://trade.m.jd.com/order/...',
    buttons: [{ text: '提交订单', tag: 'button' }]
  });
  h.NS.init({ autoSubmit: false });
  h.NS.arm(T0, 0);
  h.clock.advance(3000);
  const done = h.events.find((e) => e.type === 'done');
  assert.equal(done.reason, 'manualSubmit');
  assert.ok(h.types().includes('settleReady'));
});

test('购物车页：点去结算', () => {
  const h = boot({
    nowMs: T0,
    url: 'https://cart.m.jd.com/...',
    buttons: [
      { text: '去结算', tag: 'a' },
      { text: '全选', tag: 'div' }
    ]
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(2000);
  assert.ok(h.types().includes('settleTapped'));
});

test('登录页：报 authExpired 并暂停', () => {
  const h = boot({
    nowMs: T0,
    url: 'https://plogin.m.jd.com/login/login',
    buttons: [{ text: '手机号登录', tag: 'button' }]
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(2000);
  assert.ok(h.types().includes('authExpired'));
  assert.ok(h.types().includes('paused'));
});

test('无按钮页面：到重试窗口结束报 timeout', () => {
  const h = boot({
    nowMs: T0,
    url: ITEM_URL,
    buttons: [{ text: '商品详情', tag: 'div' }]
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(100000);
  const done = h.events.find((e) => e.type === 'done');
  assert.equal(done.reason, 'timeout');
});

test('点击节奏受 tapIntervalMs 闸门约束（不无脑连点）', () => {
  const h = boot({
    nowMs: T0,
    url: ITEM_URL,
    buttons: [{ text: '立即抢购', tag: 'button' }]
  });
  h.NS.init({ jitterMinMs: 200, jitterMaxMs: 200, tapIntervalMs: 600, mode: 'GRAB_ONLY' });
  h.NS.arm(T0, 0);
  h.clock.advance(2000);
  const taps = h.taps();
  assert.ok(taps >= 2 && taps <= 5, `unexpected tap rate: ${taps}`);
});

test('probe 返回可解析诊断 JSON', () => {
  const h = boot({
    nowMs: T0,
    url: ITEM_URL,
    buttons: [{ text: '立即抢购', tag: 'button' }]
  });
  h.NS.init(null);
  const p = JSON.parse(h.NS.probe());
  assert.equal(p.sku, '100012043978');
  assert.equal(p.stage, 'ITEM');
  assert.ok(Array.isArray(p.buttons) && p.buttons.length >= 1);
  assert.ok(p.buttons.some((b) => b.text === '立即抢购'));
});

test('ready 事件带出 SKU 与 URL', () => {
  const h = boot({ nowMs: T0, url: ITEM_URL, buttons: [] });
  h.NS.init(null);
  const ready = h.events.find((e) => e.type === 'ready');
  assert.equal(ready.sku, '100012043978');
  assert.equal(ready.url, ITEM_URL);
});

test('stop 后不再点击', () => {
  const h = boot({
    nowMs: T0,
    url: ITEM_URL,
    buttons: [{ text: '立即抢购', tag: 'button' }]
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(500);
  h.NS.stop('user');
  const before = h.taps();
  h.clock.advance(5000);
  assert.equal(h.taps(), before);
  assert.ok(h.types().includes('stopped'));
});

test('手动开火忽略 T0，立刻执行', () => {
  const h = boot({
    nowMs: T0,
    url: ITEM_URL,
    buttons: [{ text: '立即抢购', tag: 'button' }]
  });
  h.NS.init(null);
  h.NS.fire();
  h.clock.advance(300);
  assert.ok(h.taps() >= 1);
});

test('不可见元素不点', () => {
  const h = boot({
    nowMs: T0,
    url: ITEM_URL,
    buttons: [{ text: '立即抢购', tag: 'button', visible: false }],
    text: ''
  });
  h.NS.init(null);
  h.NS.arm(T0, 0);
  h.clock.advance(1000);
  assert.equal(h.taps(), 0);
});
