// Pure helpers for "Manage groups" (no DOM, no state): search folding, display ordering, bulk show/hide,
// moving a group in a custom order, and the "show newly added groups" policy. Shared by the app and tests/groups.test.js.

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/** Lower-case, accent-free, separators ("|", "·", "-", "/") folded to spaces: "UK| Sky Sports" ~ "uk sky sports". */
export const foldName = s => String(s ?? '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase()
  .replace(/[|·/\\►▶_\-–—]+/g, ' ').replace(/\s+/g, ' ').trim();

/** Every word of the query must appear in the shown title or the raw provider name. Empty query matches all. */
export function matchesQuery(title, raw, query) {
  const q = foldName(query);
  if (!q) return true;
  const hay = `${foldName(title)} ${foldName(raw)}`;
  return q.split(' ').every(w => hay.includes(w));
}

/** First letter for the jump rail: A-Z, or '#' for digits / emoji / symbols. */
export function letterOf(title) {
  const c = String(title ?? '').normalize('NFKD').replace(/^[^\p{L}\p{N}]+/u, '')[0] || '';
  return /[a-z]/i.test(c) ? c.toUpperCase() : '#';
}

export const SORTS = ['provider', 'az', 'count', 'custom'];

/**
 * Display order of groups ([{ name, count }]): "provider" = as given, "az" = by shown title, "count" = biggest first,
 * "custom" = the saved `order` (names missing from it keep provider order after it). Never mutates the input.
 */
export function orderGroups(groups, { sort = 'provider', order = [] } = {}, title = g => g.name) {
  const list = [...groups];
  if (sort === 'az') return list.sort((a, b) => collator.compare(title(a), title(b)));
  if (sort === 'count') return list.map((g, i) => [g, i]).sort((a, b) => (b[0].count || 0) - (a[0].count || 0) || a[1] - b[1]).map(x => x[0]);
  if (sort === 'custom' && order?.length) {
    const rank = new Map(order.map((n, i) => [n, i]));
    return list.map((g, i) => [g, i]).sort((a, b) => (rank.has(a[0].name) ? rank.get(a[0].name) : 1e9) - (rank.has(b[0].name) ? rank.get(b[0].name) : 1e9) || a[1] - b[1]).map(x => x[0]);
  }
  return list;
}

/**
 * Bulk visibility on ONE tab. `hidden` is the playlist's whole hidden set (it also holds the other tabs' names, which are
 * never touched); `all` = this tab's group names; `targets` = the filtered subset the action applies to.
 * ops: showAll, hideAll, show, hide, only. "only" with no targets does nothing (it would hide every group).
 * Returns a new Set.
 */
export function bulkHidden(hidden, all, targets, op) {
  const out = new Set(hidden);
  switch (op) {
    case 'showAll': all.forEach(n => out.delete(n)); break;
    case 'hideAll': all.forEach(n => out.add(n)); break;
    case 'show': targets.forEach(n => out.delete(n)); break;
    case 'hide': targets.forEach(n => out.add(n)); break;
    case 'only': if (targets.length) { const t = new Set(targets); all.forEach(n => (t.has(n) ? out.delete(n) : out.add(n))); } break;
  }
  return out;
}

/** The names whose hidden state differs between two sets, restricted to `names` (for "Hid 120 groups" / undo text). */
export const changedCount = (before, after, names) => names.reduce((n, g) => n + (before.has(g) !== after.has(g)), 0);

/** Move `name` inside the full ordered list of names. where: 'top' | 'bottom' | -1 | 1 (one step). Returns a new array. */
export function moveName(names, name, where) {
  const list = [...names], i = list.indexOf(name);
  if (i < 0) return list;
  list.splice(i, 1);
  const to = where === 'top' ? 0 : where === 'bottom' ? list.length : Math.max(0, Math.min(list.length, i + where));
  list.splice(to, 0, name);
  return list;
}

/**
 * "Show newly added groups" off: groups that were not in the last known list get hidden. `known` undefined = first time
 * (nothing is hidden; today's groups become the baseline). Returns { hide: [names], known: [names] }.
 */
export function newGroupsToHide(known, current, mode) {
  const cur = [...new Set(current)];
  if (!Array.isArray(known)) return { hide: [], known: cur };
  const have = new Set(known);
  const fresh = cur.filter(n => !have.has(n));
  return { hide: mode === 'hide' ? fresh : [], known: [...new Set([...known, ...cur])] };
}
