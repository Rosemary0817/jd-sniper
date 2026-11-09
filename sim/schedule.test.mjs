import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  nextTriggerMs,
  upcoming,
  buildTrigger,
  toLocalParts,
  TZ_OFFSET_MS
} from './lib/schedule.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CFG = JSON.parse(
  fs.readFileSync(path.resolve(HERE, '../config/app-config.json'), 'utf8')
);

const bj = (y, mo, d, h, mi, s) => Date.UTC(y, mo - 1, d, h - 8, mi, s || 0);
const ev = (id) => CFG.schedule.events.find((e) => e.id === id);

test('配置契约基本自洽', () => {
  assert.equal(CFG.tz, 'Asia/Shanghai');
  assert.equal(CFG.schedule.events.length, 5);
  const ids = new Set(CFG.schedule.events.map((e) => e.id));
  assert.equal(ids.size, 5, 'event id 必须唯一');
  for (const e of CFG.schedule.events) {
    assert.match(e.time, /^\d{2}:\d{2}:\d{2}$/);
    assert.ok(e.dow >= 1 && e.dow <= 7);
    assert.ok(['GRAB', 'RESERVE_AND_GRAB'].includes(e.mode));
  }
  const skus = new Set(CFG.items.map((i) => i.sku));
  assert.equal(skus.size, CFG.items.length, 'SKU 必须唯一');
});

test('周一 15:56 的下一个事件是本周四 20:00', () => {
  const now = bj(2026, 11, 9, 15, 56, 26);
  const p = toLocalParts(now);
  assert.equal(p.dow, 1);
  assert.equal(nextTriggerMs(now, ev('thu_reserve')), bj(2026, 11, 12, 20, 0));
});

test('周四 19:59:59 命中共当晚 20:00', () => {
  const now = bj(2026, 11, 12, 19, 59, 59);
  assert.equal(nextTriggerMs(now, ev('thu_reserve')), bj(2026, 11, 12, 20, 0));
});

test('恰好等于目标时刻时排到下一周（严格大于 now）', () => {
  const now = bj(2026, 11, 12, 20, 0, 0);
  assert.equal(nextTriggerMs(now, ev('thu_reserve')), bj(2026, 11, 19, 20, 0));
});

test('周四 20:00:01 之后的下一个事件是周五 10:00', () => {
  const now = bj(2026, 11, 12, 20, 0, 1);
  const next = upcoming(CFG, now)[0];
  assert.equal(next.eventId, 'fri_1000');
  assert.equal(next.at, bj(2026, 11, 13, 10, 0));
});

test('周五四个整点按顺序触发', () => {
  const now = bj(2026, 11, 13, 9, 0, 0);
  const list = upcoming(CFG, now).slice(0, 5);
  assert.deepEqual(
    list.map((t) => t.eventId),
    ['fri_1000', 'fri_1600', 'fri_2000', 'fri_2200', 'thu_reserve']
  );
  assert.equal(list[4].at, bj(2026, 11, 19, 20, 0));
});

test('周五 22:00:01 之后跳到下周四', () => {
  const now = bj(2026, 11, 13, 22, 0, 1);
  const next = upcoming(CFG, now)[0];
  assert.equal(next.eventId, 'thu_reserve');
  assert.equal(next.at, bj(2026, 11, 19, 20, 0));
});

test('周六任意时刻仍是下周四', () => {
  const now = bj(2026, 11, 14, 3, 0, 0);
  assert.equal(upcoming(CFG, now)[0].at, bj(2026, 11, 19, 20, 0));
});

test('预热/开火提前量按下发配置计算', () => {
  const now = bj(2026, 11, 9, 15, 56, 26);
  const t = buildTrigger(CFG, ev('fri_1000'), now);
  assert.equal(t.at, bj(2026, 11, 13, 10, 0));
  assert.equal(t.prewarmAt, t.at - CFG.schedule.preloadLeadSec * 1000);
  assert.equal(t.fireAt, t.at - 250);
  assert.equal(t.localTime, '2026-11-13T10:00');
  assert.equal(t.key, 'fri_1000@2026-11-13T10:00');
});

test('周四事件使用更早的 leadMs（400ms）', () => {
  const now = bj(2026, 11, 9, 15, 56, 26);
  const t = buildTrigger(CFG, ev('thu_reserve'), now);
  assert.equal(t.fireAt, t.at - 400);
  assert.equal(t.mode, 'RESERVE_AND_GRAB');
});

test('5 个事件 key 互不重复且都在未来', () => {
  const now = bj(2026, 11, 9, 15, 56, 26);
  const list = upcoming(CFG, now);
  assert.equal(new Set(list.map((x) => x.key)).size, 5);
  for (const t of list) assert.ok(t.at > now, `${t.key} must be future`);
});

test('跨年边界：2026-12-31 周四 21:00 -> 2027-01-01 周五 10:00', () => {
  const now = bj(2026, 12, 31, 21, 0, 0);
  const p = toLocalParts(now);
  assert.equal(p.dow, 4);
  const next = upcoming(CFG, now)[0];
  assert.equal(next.eventId, 'fri_1000');
  assert.equal(next.at, bj(2027, 1, 1, 10, 0));
});

test('时区常量固定 +08:00', () => {
  assert.equal(TZ_OFFSET_MS, 28800000);
});
