// Small DOM helpers: escaping, icons, bottom sheets, menus, forms and toasts.

export const $ = (sel, root = document) => root.querySelector(sel);

export const esc = s => String(s ?? '').replace(/[&<>"']/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

const PATHS = {
  plus: '<path d="M12 5v14M5 12h14"/>',
  back: '<path d="M15 18l-6-6 6-6"/>',
  more: '<circle class="dot" cx="5" cy="12" r="1.7"/><circle class="dot" cx="12" cy="12" r="1.7"/><circle class="dot" cx="19" cy="12" r="1.7"/>',
  star: '<path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  prev: '<path d="M18 6v12l-8.5-6zM6 6v12"/>',
  next: '<path d="M6 6v12l8.5-6zM18 6v12"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7"/>',
  select: '<rect x="4" y="4" width="16" height="16" rx="4"/><path d="M8.5 12l2.5 2.5 4.5-5"/>',
  sliders: '<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  list: '<path d="M9 6h11M9 12h11M9 18h11"/><circle class="dot" cx="4.5" cy="6" r="1.2"/><circle class="dot" cx="4.5" cy="12" r="1.2"/><circle class="dot" cx="4.5" cy="18" r="1.2"/>',
  tv: '<rect x="3" y="5" width="18" height="12" rx="2.5"/><path d="M8 21h8M12 17v4"/>',
  share: '<path d="M12 3v12M8 7l4-4 4 4"/><path d="M6 11v8a2 2 0 002 2h8a2 2 0 002-2v-8"/>',
  guide: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M3 9h18M9 9v11M14 13h4M14 16.5h3"/>',
  grid: '<rect x="3.5" y="3.5" width="7.5" height="7.5" rx="1.5"/><rect x="13" y="3.5" width="7.5" height="7.5" rx="1.5"/><rect x="3.5" y="13" width="7.5" height="7.5" rx="1.5"/><rect x="13" y="13" width="7.5" height="7.5" rx="1.5"/>',
  refresh: '<path d="M20 11a8 8 0 10-2.3 5.7"/><path d="M20 4v7h-7"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 018 0v3"/>',
  eye: '<path d="M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
  grip: '<circle cx="9" cy="6" r="1.4"/><circle cx="15" cy="6" r="1.4"/><circle cx="9" cy="12" r="1.4"/><circle cx="15" cy="12" r="1.4"/><circle cx="9" cy="18" r="1.4"/><circle cx="15" cy="18" r="1.4"/>',
  'eye-off': '<path d="M3 3l18 18"/><path d="M10.6 5.9A9.8 9.8 0 0112 5.5c6 0 9.5 6.5 9.5 6.5a17 17 0 01-3.3 4.1M6.6 6.6C4 8.4 2.5 12 2.5 12s3.5 6.5 9.5 6.5a9.6 9.6 0 004.1-.9"/><path d="M9.9 9.9a3 3 0 004.2 4.2"/>',
  film: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4"/>',
  play: '<path class="dot" d="M7 4.5v15l13-7.5z"/>',
  pause: '<rect class="dot" x="6" y="4.5" width="4.5" height="15" rx="1"/><rect class="dot" x="13.5" y="4.5" width="4.5" height="15" rx="1"/>',
  back30: '<path d="M4 11a8 8 0 102.3-5.7"/><path d="M4 4v7h7"/><text x="8.2" y="16" font-size="7.5" font-weight="700" fill="currentColor" stroke="none">30</text>',
  volume: '<path d="M4 9.5v5h3.5L12 18V6L7.5 9.5z"/><path d="M15.5 9a4 4 0 010 6M18 6.5a7.5 7.5 0 010 11"/>',
  'volume-off': '<path d="M4 9.5v5h3.5L12 18V6L7.5 9.5z"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>',
  pip: '<rect x="3" y="5" width="18" height="14" rx="2.5"/><rect class="dot" x="12" y="11" width="7" height="5" rx="1"/>',
  airplay: '<path d="M5 16.5A2.5 2.5 0 013 14V7a2.5 2.5 0 012.5-2.5h13A2.5 2.5 0 0121 7v7a2.5 2.5 0 01-2 2.5"/><path d="M12 14l5 6H7z"/>',
  expand: '<path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"/>',
  download: '<path d="M12 4v11M7 10.5l5 5 5-5M5 20h14"/>',
  search2: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
  trophy: '<path d="M7.5 4h9v5.5a4.5 4.5 0 01-9 0z"/><path d="M7.5 6H4.5a3 3 0 003.2 4M16.5 6h3a3 3 0 01-3.2 4M12 14v3.5M8.5 20.5h7M9.5 17.5h5"/>',
  bell: '<path d="M6 10.5a6 6 0 0112 0c0 4.2 1.6 5.8 2 6.3H4c.4-.5 2-2.1 2-6.3z"/><path d="M10 20a2.2 2.2 0 004 0"/>',
  'bell-on': '<path class="dot" d="M5.2 10.5a6.8 6.8 0 0113.6 0c0 3.9 1.4 5.3 2 5.9.5.5.2 1.4-.6 1.4H3.8c-.8 0-1.1-.9-.6-1.4.6-.6 2-2 2-5.9z"/><path d="M10 20.2a2.2 2.2 0 004 0"/>',
};

export const icon = (name, cls = '') => `<svg class="ico ${cls}" viewBox="0 0 24 24" aria-hidden="true">${PATHS[name]}</svg>`;

// ---------- bottom sheet ----------
const backdrop = $('#sheet-backdrop');
const sheetEl = $('#sheet');
let onSheetClose = null;

// keepScroll: re-render an open sheet in place (season chips, Save) without jumping to the top.
export function sheet(html, { onMount, onClose, keepScroll = false } = {}) {
  const prev = onSheetClose;
  onSheetClose = null;
  prev?.();
  const y = keepScroll && !sheetEl.hidden ? sheetEl.scrollTop : 0;
  if (!keepScroll) settle();
  // #sheet is reused by every sheet: drop the last one's click handler (a series sheet's would toggle a
  // film's plot a second time, so tapping it did nothing). Each onMount sets its own again.
  sheetEl.onclick = null;
  sheetEl.innerHTML = `<div class="sheet-grip"></div>${html}`;
  sheetEl.hidden = backdrop.hidden = false;
  sheetEl.scrollTop = y;
  onSheetClose = onClose || null;
  onMount?.(sheetEl);
  return sheetEl;
}

export function closeSheet() {
  settle();
  sheetEl.hidden = backdrop.hidden = true;
  sheetEl.innerHTML = '';
  const fn = onSheetClose;
  onSheetClose = null;
  fn?.();
}

backdrop.addEventListener('click', closeSheet);

// Swipe down to close, like any iPhone sheet: drag it down from the top (anywhere on it while it's scrolled to the top);
// let go past a third of the way, or with a flick, and it slides away; otherwise it springs back.
function settle() { sheetEl.style.transition = sheetEl.style.transform = backdrop.style.transition = backdrop.style.opacity = ''; }
let drag = null;
// Can the touch's target scroll by itself in this direction (an inner list, a text box)? Then leave it alone.
function innerScrolls(el, up) {
  for (; el && el !== sheetEl; el = el.parentElement) {
    if (el.scrollHeight > el.clientHeight + 1 && /auto|scroll/.test(getComputedStyle(el).overflowY)) {
      if (up ? el.scrollTop + el.clientHeight < el.scrollHeight - 1 : el.scrollTop > 0) return true;
    }
  }
  return false;
}
sheetEl.addEventListener('touchstart', e => {
  const docked = sheetEl.getBoundingClientRect().bottom >= innerHeight - 2;   // not the centred desktop dialog
  drag = e.touches.length === 1 && docked ? { x: e.touches[0].clientX, y: e.touches[0].clientY, ly: e.touches[0].clientY, top: sheetEl.scrollTop <= 0, on: false, dy: 0, t: 0, v: 0 } : null;
}, { passive: true });
sheetEl.addEventListener('touchmove', e => {
  if (!drag) return;
  const t = e.touches[0], dx = t.clientX - drag.x, dy = t.clientY - drag.y;
  if (!drag.on) {
    const step = t.clientY - drag.ly; drag.ly = t.clientY;
    if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
    if (Math.abs(dx) > Math.abs(dy)) { drag = null; return; }   // sideways: a row of cast / chips
    // Vertical but not a dismiss: scroll the sheet, never the page behind it once the sheet hits an end.
    if (dy < 0 || !drag.top || sheetEl.scrollTop > 0 || innerScrolls(e.target, false)) {
      const atEnd = step < 0 ? sheetEl.scrollTop + sheetEl.clientHeight >= sheetEl.scrollHeight - 1 : sheetEl.scrollTop <= 0;
      if (atEnd && e.cancelable && !innerScrolls(e.target, step < 0)) e.preventDefault();
      return;
    }
    drag.on = true; drag.y = t.clientY; drag.t = e.timeStamp;
    sheetEl.style.transition = backdrop.style.transition = 'none';
  }
  e.preventDefault();   // the sheet moves instead of the page bouncing
  const d = Math.max(0, t.clientY - drag.y);
  drag.v = (d - drag.dy) / Math.max(1, e.timeStamp - drag.t); drag.dy = d; drag.t = e.timeStamp;
  sheetEl.style.transform = `translateY(${d}px)`;
  backdrop.style.opacity = String(Math.max(0, 1 - d / sheetEl.offsetHeight));
}, { passive: false });
// Dragging on the dimmed page behind an open sheet doesn't scroll it.
backdrop.addEventListener('touchmove', e => { if (e.cancelable) e.preventDefault(); }, { passive: false });
function release() {
  const d = drag; drag = null;
  if (!d?.on) return;
  const ease = 'cubic-bezier(.2, .8, .2, 1)';
  if (d.dy > sheetEl.offsetHeight / 3 || (d.v > 0.5 && d.dy > 24)) {
    sheetEl.style.transition = `transform .22s ${ease}`; backdrop.style.transition = 'opacity .22s';
    sheetEl.style.transform = 'translateY(100%)'; backdrop.style.opacity = '0';
    setTimeout(closeSheet, 220);
  } else {
    sheetEl.style.transition = `transform .3s ${ease}`; backdrop.style.transition = 'opacity .3s';
    sheetEl.style.transform = ''; backdrop.style.opacity = '';
  }
}
sheetEl.addEventListener('touchend', release);
sheetEl.addEventListener('touchcancel', release);
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !sheetEl.hidden) closeSheet(); });

// items: [{ label, fn, danger }], falsy entries are skipped. fn runs inside the tap,
// so it may call APIs that need a user gesture (share, clipboard).
// onCancel: runs when the menu is dismissed without a choice (Cancel, swipe down, tapping outside),
// e.g. to go back to the sheet it was opened from.
export function menu(title, items, { onCancel } = {}) {
  const list = items.filter(Boolean);
  let chosen = false;
  sheet(`${title ? `<div class="sheet-title">${esc(title)}</div>` : ''}
    <div class="menu">
      ${list.map((it, i) => `<button class="menu-item ${it.danger ? 'danger' : ''}" data-i="${i}">${esc(it.label)}</button>`).join('')}
      <button class="menu-item cancel">Cancel</button>
    </div>`, {
    onMount: el => {
      el.querySelector('.menu').onclick = e => {
        const b = e.target.closest('button');
        if (!b) return;
        chosen = b.dataset.i !== undefined;
        closeSheet();
        if (chosen) list[+b.dataset.i].fn();
      };
    },
    onClose: () => { if (!chosen) onCancel?.(); },
  });
}

function field(f) {
  const common = `name="${f.name}" placeholder="${esc(f.placeholder || '')}" ${f.required ? 'required' : ''} ${f.inputmode ? `inputmode="${f.inputmode}"` : ''} autocorrect="off" spellcheck="false"`;
  const input = f.type === 'select'
    ? `<select name="${f.name}">${f.options.map(o => `<option value="${esc(o.value)}" ${o.value === f.value ? 'selected' : ''}>${esc(o.label)}</option>`).join('')}</select>`
    : f.type === 'textarea'
    ? `<textarea ${common} rows="5">${esc(f.value)}</textarea>`
    : `<input ${common} type="${f.type || 'text'}" value="${esc(f.value ?? '')}" ${f.type === 'url' ? 'autocapitalize="off" inputmode="url"' : ''} ${f.list ? `list="dl-${f.name}"` : ''}>`;
  const datalist = f.list ? `<datalist id="dl-${f.name}">${f.list.map(o => `<option value="${esc(o)}">`).join('')}</datalist>` : '';
  return `<label>${esc(f.label)}${input}${datalist}</label>`;
}

// Resolves to { [name]: trimmed value } or null if dismissed.
export function formSheet({ title, fields, submit = 'Save', intro = '' }) {
  return new Promise(resolve => {
    let done = false;
    const finish = v => { if (!done) { done = true; resolve(v); } };
    sheet(`<div class="sheet-title">${esc(title)}</div>${intro ? `<p class="muted sheet-intro">${esc(intro)}</p>` : ''}
      <form class="form">
        ${fields.map(field).join('')}
        <div class="form-actions"><button type="button" class="btn" data-cancel>Cancel</button><button class="btn primary">${esc(submit)}</button></div>
      </form>`, {
      onMount: el => {
        const form = el.querySelector('form');
        form.querySelector('[data-cancel]').onclick = closeSheet;
        form.onsubmit = e => {
          e.preventDefault();
          const data = new FormData(form);
          finish(Object.fromEntries(fields.map(f => [f.name, String(data.get(f.name) ?? '').trim()])));
          closeSheet();
        };
      },
      onClose: () => finish(null),
    });
  });
}

export function confirmDialog(message, okLabel = 'OK', danger = false) {
  return new Promise(resolve => {
    let done = false;
    const finish = v => { if (!done) { done = true; resolve(v); } };
    sheet(`<p class="confirm-text">${esc(message)}</p>
      <div class="form-actions"><button class="btn" data-no>Cancel</button><button class="btn ${danger ? 'danger-fill' : 'primary'}" data-yes>${esc(okLabel)}</button></div>`, {
      onMount: el => {
        el.querySelector('[data-no]').onclick = closeSheet;
        el.querySelector('[data-yes]').onclick = () => { finish(true); closeSheet(); };
      },
      onClose: () => finish(false),
    });
  });
}

// ---------- toast ----------
let toastTimer;
// busy: a job in progress ("Refreshing…") — shows a spinner and stays up until the next toast replaces it
// (a 60s safety timeout covers a job that never reports back).
export function toast(message, { action, fn, busy = false } = {}) {
  const t = $('#toast');
  t.innerHTML = `${busy ? '<span class="spin" aria-hidden="true"></span>' : ''}<span>${esc(message)}</span>${action ? `<button>${esc(action)}</button>` : ''}`;
  t.classList.toggle('busy', busy);
  t.hidden = false;
  if (action) t.querySelector('button').onclick = () => { t.hidden = true; fn(); };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, busy ? 60000 : action ? 5000 : 2200);
}
