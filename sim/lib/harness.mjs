// 把 autopilot.js 装进 vm 沙箱：假 DOM + 假时钟 + 原生桥
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { makeClock } from './clock.mjs';
import { makeDom } from './dom.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const AUTOPILOT_PATH = path.resolve(
  HERE,
  '../../android/app/src/main/assets/autopilot.js'
);

export function loadAutopilotSource() {
  return fs.readFileSync(AUTOPILOT_PATH, 'utf8');
}

export function boot(opts) {
  const o = opts || {};
  const clock = makeClock(o.nowMs);
  const { doc, elements } = makeDom(o);
  const events = [];

  const win = {
    document: doc,
    location: { href: o.url || 'https://item.m.jd.com/product/SKU_EGG.html' },
    __LCS_NATIVE: {
      post(json) {
        events.push(JSON.parse(json));
      }
    },
    setTimeout: (fn, ms) => clock.setTimeout(fn, ms),
    clearTimeout: (id) => clock.clearTimeout(id)
  };

  const sandbox = {
    window: win,
    document: doc,
    location: win.location,
    __LCS_NATIVE: win.__LCS_NATIVE,
    setTimeout: (fn, ms) => clock.setTimeout(fn, ms),
    clearTimeout: (id) => clock.clearTimeout(id),
    Math: Object.assign(Object.create(Math), { random: () => 0.5 }),
    JSON,
    Date: { now: () => clock.now() },
    console
  };
  sandbox.globalThis = sandbox;

  const ctx = vm.createContext(sandbox);
  vm.runInContext(loadAutopilotSource(), ctx, { filename: 'autopilot.js' });

  return {
    NS: ctx.window.__LCS,
    win,
    doc,
    elements,
    events,
    clock,
    taps() {
      return elements.reduce((n, e) => n + e.clicks, 0);
    },
    types() {
      return events.map((e) => e.type);
    }
  };
}
