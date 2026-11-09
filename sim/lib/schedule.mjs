// 与 Kotlin SaleCalendar 同契约的参考实现（测试基准）
// 京东活动固定北京时间，中国无夏令时，用固定 +08:00 平移计算，避免宿主时区干扰。
export const TZ_OFFSET_MS = 8 * 3600 * 1000;
export const WEEK_MS = 7 * 24 * 3600 * 1000;

export function toLocalParts(ms) {
  const d = new Date(ms + TZ_OFFSET_MS);
  return {
    year: d.getUTCFullYear(),
    month: d.getUTCMonth(),
    day: d.getUTCDate(),
    dow: d.getUTCDay() === 0 ? 7 : d.getUTCDay(), // 1=Mon..7=Sun
    hour: d.getUTCHours(),
    minute: d.getUTCMinutes(),
    second: d.getUTCSeconds(),
    ms: d.getUTCMilliseconds()
  };
}

export function parseTime(hhmmss) {
  const parts = String(hhmmss).split(':').map(Number);
  const h = parts[0] || 0;
  const m = parts[1] || 0;
  const s = parts[2] || 0;
  if (h > 23 || m > 59 || s > 59) throw new Error('bad time: ' + hhmmss);
  return { h, m, s };
}

/** 严格大于 now 的下一次触发（毫秒 epoch）。now 恰好等于目标时刻时排到下一周。 */
export function nextTriggerMs(nowMs, event) {
  const { h, m, s } = parseTime(event.time);
  const p = toLocalParts(nowMs);
  const targetDow = ((Number(event.dow) - 1) % 7 + 7) % 7 + 1;
  let delta = (targetDow - p.dow + 7) % 7;
  let cand =
    Date.UTC(p.year, p.month, p.day + delta, h, m, s) - TZ_OFFSET_MS;
  if (cand <= nowMs) cand += WEEK_MS;
  return cand;
}

export function localKey(ms) {
  const p = toLocalParts(ms);
  const pad = (n, w = 2) => String(n).padStart(w, '0');
  return `${p.year}-${pad(p.month + 1)}-${pad(p.day)}T${pad(p.hour)}:${pad(p.minute)}`;
}

export function buildTrigger(cfg, event, nowMs) {
  const at = nextTriggerMs(nowMs, event);
  const lead = Number(event.leadMs != null ? event.leadMs : cfg.schedule.fireLeadMs);
  return {
    eventId: event.id,
    mode: event.mode,
    at,
    fireAt: at - lead,
    prewarmAt: at - Number(cfg.schedule.preloadLeadSec) * 1000,
    key: `${event.id}@${localKey(at)}`,
    localTime: localKey(at)
  };
}

export function upcoming(cfg, nowMs, limit) {
  const list = cfg.schedule.events.map((e) => buildTrigger(cfg, e, nowMs));
  list.sort((a, b) => a.at - b.at);
  return limit ? list.slice(0, limit) : list;
}

export function nextAny(cfg, nowMs) {
  const list = upcoming(cfg, nowMs);
  return list.length ? list[0] : null;
}
