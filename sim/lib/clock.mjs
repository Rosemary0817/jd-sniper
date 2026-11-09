// 确定性假时钟：setTimeout 只在 advance() 时按时间顺序触发
export function makeClock(startMs) {
  let now = startMs == null ? 1762700000000 : startMs;
  let seq = 1;
  let timers = [];

  return {
    now: () => now,
    setTimeout(fn, ms) {
      const id = seq++;
      timers.push({ id, at: now + Math.max(0, Number(ms) || 0), fn, seq: id });
      return id;
    },
    clearTimeout(id) {
      timers = timers.filter((t) => t.id !== id);
    },
    setInterval() {
      throw new Error('autopilot must not use setInterval');
    },
    advance(ms) {
      const end = now + Math.max(0, Number(ms) || 0);
      let guard = 0;
      for (;;) {
        timers.sort((a, b) => a.at - b.at || a.seq - b.seq);
        const t = timers[0];
        if (!t || t.at > end) break;
        timers.shift();
        now = t.at;
        t.fn();
        if (++guard > 20000) throw new Error('timer storm');
      }
      now = end;
    },
    pending: () => timers.length
  };
}
