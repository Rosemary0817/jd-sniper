// 极简假 DOM：只实现 autopilot.js 用到的那点 API
export function makeElement(opts) {
  const o = opts || {};
  const text = o.text == null ? '' : String(o.text);
  const visible = o.visible !== false;
  const el = {
    tagName: String(o.tag || 'div').toUpperCase(),
    id: o.id || '',
    className: o.className || '',
    innerText: text,
    textContent: text,
    style: {},
    clicks: 0,
    getBoundingClientRect() {
      return { x: 1, y: 1, width: visible ? 120 : 0, height: visible ? 32 : 0, top: 1, left: 1 };
    },
    scrollIntoView() {},
    click() {
      this.clicks += 1;
      if (typeof o.onClick === 'function') o.onClick(this);
    },
    dispatchEvent() {
      return true;
    },
    classList: { add() {}, remove() {}, contains: () => false }
  };
  return el;
}

export function makeDom(opts) {
  const o = opts || {};
  const elements = (o.buttons || []).map(makeElement);
  const extraText = o.text || '';
  const bodyText = () => extraText + '\n' + elements.map((e) => e.innerText).join('\n');

  const matches = (el, sel) => {
    const s = String(sel).trim();
    if (s.startsWith('#')) return el.id === s.slice(1);
    if (s.startsWith('.')) return String(el.className).split(/\s+/).includes(s.slice(1));
    if (s.startsWith('[')) {
      const m = s.match(/\[class\*="([^"]+)"\]/);
      if (m) return String(el.className).includes(m[1]);
      return false;
    }
    // 逗号分隔的组合选择器
    if (s.includes(',')) return s.split(',').some((part) => matches(el, part));
    return el.tagName === s.toUpperCase();
  };

  const doc = {
    title: o.title || '',
    readyState: 'complete',
    body: {
      get innerText() {
        return bodyText();
      },
      get textContent() {
        return bodyText();
      }
    },
    querySelectorAll(sel) {
      const parts = String(sel).split(',').map((x) => x.trim().toUpperCase());
      return elements.filter((el) => parts.includes(el.tagName));
    },
    querySelector(sel) {
      const hit = elements.find((el) => matches(el, sel));
      return hit || null;
    },
    addEventListener() {},
    removeEventListener() {}
  };

  return { doc, elements, bodyText };
}
