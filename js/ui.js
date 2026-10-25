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
  search2: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
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
  sheetEl.innerHTML = `<div class="sheet-grip"></div>${html}`;
  sheetEl.hidden = backdrop.hidden = false;
  sheetEl.scrollTop = y;
  onSheetClose = onClose || null;
  onMount?.(sheetEl);
  return sheetEl;
}

export function closeSheet() {
  sheetEl.hidden = backdrop.hidden = true;
  sheetEl.innerHTML = '';
  const fn = onSheetClose;
  onSheetClose = null;
  fn?.();
}

backdrop.addEventListener('click', closeSheet);
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !sheetEl.hidden) closeSheet(); });

// items: [{ label, fn, danger }], falsy entries are skipped. fn runs inside the tap,
// so it may call APIs that need a user gesture (share, clipboard).
export function menu(title, items) {
  const list = items.filter(Boolean);
  sheet(`${title ? `<div class="sheet-title">${esc(title)}</div>` : ''}
    <div class="menu">
      ${list.map((it, i) => `<button class="menu-item ${it.danger ? 'danger' : ''}" data-i="${i}">${esc(it.label)}</button>`).join('')}
      <button class="menu-item cancel">Cancel</button>
    </div>`, {
    onMount: el => {
      el.querySelector('.menu').onclick = e => {
        const b = e.target.closest('button');
        if (!b) return;
        closeSheet();
        if (b.dataset.i !== undefined) list[+b.dataset.i].fn();
      };
    },
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
export function formSheet({ title, fields, submit = 'Save' }) {
  return new Promise(resolve => {
    let done = false;
    const finish = v => { if (!done) { done = true; resolve(v); } };
    sheet(`<div class="sheet-title">${esc(title)}</div>
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
