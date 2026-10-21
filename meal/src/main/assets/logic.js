/*
 * 員工餐:核心邏輯(抽卡、搭配檢查、叫貨批次、用量計算、文字輸出)。
 * 沒有畫面相關的程式,App 和 Node 測試(tools/meal_test.js)共用。
 * 日期一律用 'YYYY-MM-DD' 字串(手機當地時間)。
 */
(function (root) {
'use strict';

const WEEK = ['日', '一', '二', '三', '四', '五', '六'];
const SLOT_NAME = { main: '主菜', veg: '蔬菜', egg: '蛋', nonrice: '餐點' };
const KIND_NAME = { pork: '豬肉', chicken: '雞肉', fish: '魚', veg: '蔬菜', egg: '蛋', nonrice: '非飯菜日' };
const STATUS = ['常用', '輪替', '候選', '停用'];
const AUTO_STATUS = { 常用: 1, 輪替: 0.4 };   // 自動抽卡只抽這兩種,數字是基本權重
const SUPPLIERS = ['veg', 'pork', 'chicken'];  // 會產生叫貨單的供應商;'stock' = 庫存品

// ---------- 日期 ----------
function pad(n) { return (n < 10 ? '0' : '') + n; }
function ymd(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
function parse(s) { const p = s.split('-'); return new Date(+p[0], +p[1] - 1, +p[2]); }
function addDays(s, n) { const d = parse(s); d.setDate(d.getDate() + n); return ymd(d); }
function dow(s) { return parse(s).getDay(); }
function diffDays(a, b) { return Math.round((parse(b) - parse(a)) / 86400000); }   // b - a
function fmt(s) { const d = parse(s); return (d.getMonth() + 1) + '/' + d.getDate() + '(' + WEEK[d.getDay()] + ')'; }
function range(from, n) { const r = []; for (let i = 0; i < n; i++) r.push(addDays(from, i)); return r; }

// ---------- 資料 ----------
let uid = 0;
function newId(prefix) { return prefix + Date.now().toString(36) + (uid++).toString(36); }

/** 從初始資料建立 App 狀態(品項、料理用名稱撰寫,這裡換成 id)。 */
function initState(seed) {
  const items = seed.items.map((it, i) => Object.assign({ id: 'i' + i }, it));
  const byName = {};
  items.forEach(it => { byName[it.name] = it.id; });
  const dishes = seed.dishes.map((d, i) => {
    const ings = d.ings.map(([name, q]) => {
      if (!byName[name]) throw new Error('料理「' + d.name + '」用到不存在的品項:' + name);
      return { i: byName[name], q };
    });
    const main = d.main ? byName[d.main] : ings[0].i;
    if (!main) throw new Error('料理「' + d.name + '」的主體食材不存在:' + d.main);
    return Object.assign({ id: 'd' + i, risks: [], note: '', flavor: '', sauce: '淺', method: '炒' }, d, { ings, main });
  });
  return {
    version: 1,
    settings: JSON.parse(JSON.stringify(seed.settings)),
    items, dishes,
    days: {},     // 日期 → { type: 'rice' | 'nonrice', dishes: [{ slot, id, locked }] }
    orders: {},   // 批次 key → 叫貨單
  };
}

function index(list) {
  const m = {};
  list.forEach(x => { m[x.id] = x; });
  return m;
}
function maps(state) {
  // 每次取用都重建很便宜(幾百筆),省掉快取失效的麻煩
  return { item: index(state.items), dish: index(state.dishes) };
}

// ---------- 飯菜日的格子 ----------
function slotsFor(state, type) {
  if (type === 'nonrice') {
    const n = Math.max(1, state.settings.rules.nonriceCount || 1);
    return Array(n).fill('nonrice');
  }
  return ['main', 'main', 'main', 'veg', 'veg', 'egg'];
}
function accepts(slot, dish) {
  if (slot === 'main') return dish.kind === 'pork' || dish.kind === 'chicken' || dish.kind === 'fish';
  return dish.kind === slot;
}
function slotLabel(slot, dish) {
  if (slot === 'main' && dish) return KIND_NAME[dish.kind];
  return SLOT_NAME[slot];
}

// ---------- 規則 ----------
function cooldownOf(state, dish) {
  const c = state.settings.rules.cooldown;
  if (dish.kind === 'nonrice') return c.nonrice;
  return c[dish.status] != null ? c[dish.status] : c['輪替'];
}

/** 這道菜距離其他日期出現的最近天數(前後都算;沒出現過 = Infinity)。 */
function nearestUse(state, dishId, date) {
  let best = Infinity;
  for (const d in state.days) {
    if (d === date) continue;
    if (state.days[d].dishes.some(x => x.id === dishId)) best = Math.min(best, Math.abs(diffDays(d, date)));
  }
  return best;
}

/**
 * 把 dish 放進 dishes[idx] 是否符合硬規則。
 * level 0:全部規則;1:不管冷卻期;2:也不管魚 / 炸物 / 同肉種上限(卡池不夠時才放寬)。
 * 回傳 null = 可以,否則是原因文字。
 */
function hardCheck(state, m, date, dishes, idx, dish, level, manual) {
  const r = state.settings.rules;
  if (dish.status === '停用') return '已停用';
  if (!manual && !AUTO_STATUS[dish.status]) return '候選菜不自動抽';
  if (!accepts(dishes[idx].slot, dish)) return '類別不符';
  const others = dishes.filter((x, i) => i !== idx && x.id).map(x => m.dish[x.id]).filter(Boolean);
  if (others.some(o => o.id === dish.id)) return '同一天已經有這道菜';
  if (others.some(o => o.main === dish.main)) return '和同一天的菜用同一種主體食材(' + m.item[dish.main].name + ')';
  if (level < 1) {
    const near = nearestUse(state, dish.id, date);
    if (near < cooldownOf(state, dish)) return near + ' 天內已排過';
  }
  if (level < 2) {
    const all = others.concat([dish]);
    if (all.filter(o => o.kind === 'fish').length > r.maxFish) return '魚料理超過 ' + r.maxFish + ' 道';
    if (all.filter(o => o.method === '炸').length > r.maxFried) return '炸物超過 ' + r.maxFried + ' 道';
    for (const k of ['pork', 'chicken']) {
      if (all.filter(o => o.kind === k).length > r.maxSameMeat) return KIND_NAME[k] + '超過 ' + r.maxSameMeat + ' 道';
    }
  }
  return null;
}

// ---------- 菜商批次(依叫貨排程) ----------
/** 這一天的菜商批次:哪天叫、哪天送、涵蓋哪幾天,以及這天是送貨後第幾天(0 = 送貨當天)。 */
function vegBatch(state, date) {
  const sched = state.settings.vegSchedule;
  const w = dow(date);
  const e = sched.find(s => s.covers.indexOf(w) >= 0);
  if (!e) return null;
  let orderDate = null;
  for (let k = 1; k <= 7; k++) {
    if (dow(addDays(date, -k)) === e.order) { orderDate = addDays(date, -k); break; }
  }
  const deliverDate = addDays(orderDate, 1);
  const coverDates = [];
  for (let j = 0; j < 7 && coverDates.length < e.covers.length; j++) {
    const d = addDays(deliverDate, j);
    if (e.covers.indexOf(dow(d)) >= 0) coverDates.push(d);
  }
  return { key: 'veg|' + orderDate, supplier: 'veg', orderDate, deliverDate, coverDates, dayNo: diffDays(deliverDate, date) };
}

// ---------- 搭配檢查 ----------
/** 一天的搭配警示(軟規則):回傳 [{ level: 'warn' | 'info', text }]。 */
function dayWarnings(state, date) {
  const day = state.days[date];
  if (!day) return [];
  const m = maps(state);
  const r = state.settings.rules;
  const out = [];
  const list = day.dishes.map(x => x.id && m.dish[x.id]).filter(Boolean);
  if (day.dishes.some(x => !x.id)) out.push({ level: 'warn', text: '有空格:卡池裡沒有符合條件的菜,請手動選擇' });
  if (day.type !== 'rice') return out;

  const dark = list.filter(d => d.sauce === '深');
  if (dark.length > r.maxDark) out.push({ level: 'warn', text: '深色醬汁的菜有 ' + dark.length + ' 道,整桌顏色偏深' });
  const stew = list.filter(d => d.method === '滷燒燉');
  if (stew.length > 2) out.push({ level: 'warn', text: '滷、燒、燉的菜有 ' + stew.length + ' 道' });
  const flavors = {};
  list.forEach(d => { if (d.flavor) flavors[d.flavor] = (flavors[d.flavor] || 0) + 1; });
  for (const f in flavors) if (flavors[f] > 1) out.push({ level: 'warn', text: '口味重複:' + f + ' ×' + flavors[f] });
  const fried = list.filter(d => d.method === '炸');
  if (fried.length > r.maxFried) out.push({ level: 'warn', text: '炸物有 ' + fried.length + ' 道' });
  const vegs = day.dishes.filter(x => x.slot === 'veg' && x.id).map(x => m.dish[x.id]).filter(Boolean);
  if (r.needGreen && vegs.length && !vegs.some(d => m.item[d.main] && m.item[d.main].green)) {
    out.push({ level: 'warn', text: '蔬菜沒有綠色青菜' });
  }
  const b = vegBatch(state, date);
  if (b && b.dayNo > 0) {
    list.forEach(d => {
      const it = m.item[d.main];
      if (it && it.keep === '低') out.push({ level: 'warn', text: d.name + ':' + it.name + '是送貨後第 ' + (b.dayNo + 1) + ' 天,葉菜可能不新鮮' });
    });
  }
  list.forEach(d => {
    if (d.status === '候選') out.push({ level: 'info', text: d.name + ' 是候選菜(還沒實際做過)' });
  });
  return out;
}

/** 抽卡時用的分數(越小越好),和 dayWarnings 的規則一致。 */
function penalty(state, m, date, dishes) {
  const r = state.settings.rules;
  const list = dishes.map(x => x.id && m.dish[x.id]).filter(Boolean);
  let p = 0;
  const dark = list.filter(d => d.sauce === '深').length;
  if (dark > r.maxDark) p += 3 * (dark - r.maxDark);
  const stew = list.filter(d => d.method === '滷燒燉').length;
  if (stew > 2) p += 3 * (stew - 2);
  const flavors = {};
  list.forEach(d => { if (d.flavor) flavors[d.flavor] = (flavors[d.flavor] || 0) + 1; });
  for (const f in flavors) if (flavors[f] > 1) p += 2 * (flavors[f] - 1);
  const methods = {};
  list.forEach(d => { methods[d.method] = (methods[d.method] || 0) + 1; });
  for (const k in methods) if (methods[k] > 3) p += methods[k] - 3;
  const vegs = dishes.filter(x => x.slot === 'veg' && x.id).map(x => m.dish[x.id]).filter(Boolean);
  if (r.needGreen && vegs.length && !vegs.some(d => m.item[d.main] && m.item[d.main].green)) p += 4;
  const b = vegBatch(state, date);
  if (b && b.dayNo > 0) list.forEach(d => { if (m.item[d.main] && m.item[d.main].keep === '低') p += 2 * b.dayNo; });
  return p;
}

// ---------- 抽卡 ----------
function weightOf(state, dish, date) {
  const base = AUTO_STATUS[dish.status] || 0.2;
  const near = nearestUse(state, dish.id, date);
  const fresh = near === Infinity ? 1.2 : Math.min(1.2, 0.3 + near / 20);   // 越久沒排越容易抽到
  return base * fresh;
}
function pick(list, weights, rng) {
  let sum = 0;
  weights.forEach(w => { sum += w; });
  let x = rng() * sum;
  for (let i = 0; i < list.length; i++) {
    x -= weights[i];
    if (x <= 0) return list[i];
  }
  return list[list.length - 1];
}

/** 填滿 dishes 裡沒鎖定的格子;嘗試多次,留下搭配分數最好的一組。 */
function fill(state, date, dishes, rng, tries) {
  const m = maps(state);
  const open = dishes.map((x, i) => (x.locked && x.id ? -1 : i)).filter(i => i >= 0);
  let best = null, bestP = Infinity;
  for (let t = 0; t < (tries || 60); t++) {
    const cur = dishes.map(x => (x.locked && x.id ? Object.assign({}, x) : { slot: x.slot, id: null, locked: false }));
    // 選擇少的格子先抽(魚、蛋比較少),比較不會卡住
    const order = open.slice().sort((a, b) => (cur[a].slot === 'main' ? 1 : 0) - (cur[b].slot === 'main' ? 1 : 0));
    for (const i of order) {
      for (let level = 0; level <= 2; level++) {
        const cands = state.dishes.filter(d => !hardCheck(state, m, date, cur, i, d, level, false));
        if (cands.length) {
          cur[i].id = pick(cands, cands.map(d => weightOf(state, d, date)), rng).id;
          break;
        }
      }
    }
    const p = penalty(state, m, date, cur) + cur.filter(x => !x.id).length * 100;
    if (p < bestP) { best = cur; bestP = p; }
    if (p === 0) break;
  }
  return best;
}

function emptyDay(state, type) {
  return { type, dishes: slotsFor(state, type).map(slot => ({ slot, id: null, locked: false })) };
}

/** 抽一天(保留鎖定的菜)。type 不給就沿用原本的(沒有就是飯菜日)。 */
function drawDay(state, date, rng, type) {
  const old = state.days[date];
  const t = type || (old && old.type) || 'rice';
  let day = old && old.type === t ? old : emptyDay(state, t);
  if (day.dishes.length !== slotsFor(state, t).length) day = emptyDay(state, t);
  state.days[date] = { type: t, dishes: fill(state, date, day.dishes, rng) };
  return state.days[date];
}

/** 局部重抽:只換第 idx 道,其他不動。 */
function redrawSlot(state, date, idx, rng) {
  const day = state.days[date];
  const m = maps(state);
  const cur = day.dishes.map(x => Object.assign({}, x));
  const oldId = cur[idx].id;
  cur[idx].id = null;
  for (let level = 0; level <= 2; level++) {
    let cands = state.dishes.filter(d => !hardCheck(state, m, date, cur, idx, d, level, false));
    if (cands.length > 1) cands = cands.filter(d => d.id !== oldId);   // 換一道不一樣的
    if (cands.length) {
      cur[idx].id = pick(cands, cands.map(d => weightOf(state, d, date)), rng).id;
      break;
    }
  }
  if (!cur[idx].id) cur[idx].id = oldId;
  cur[idx].locked = false;
  day.dishes = cur;
  return cur[idx].id;
}

/** 抽一週:每天重抽沒鎖定的菜(依日期順序,冷卻期會考慮前面已經抽好的天)。 */
function drawWeek(state, dates, rng) {
  dates.forEach(d => {
    const old = state.days[d];
    if (old) old.dishes.forEach(x => { if (!x.locked) x.id = null; });
  });
  dates.forEach(d => drawDay(state, d, rng));
}

/** 飯菜日 ⇄ 非飯菜日:那天整天換成另一種卡池重抽。 */
function setDayType(state, date, type, rng) {
  state.days[date] = emptyDay(state, type);
  return drawDay(state, date, rng, type);
}

/** 手動選菜用的清單:每道菜可不可以放、不可以的原因。onlyOrdered = 只列已叫貨食材做得出來的。 */
function candidates(state, date, idx, onlyOrdered) {
  const m = maps(state);
  const day = state.days[date];
  let have = null;
  if (onlyOrdered) have = orderedItems(state, date);
  return state.dishes
    .filter(d => accepts(day.dishes[idx].slot, d) && d.status !== '停用')
    .map(d => {
      let reason = hardCheck(state, m, date, day.dishes, idx, d, 0, true);
      if (have) {
        const miss = d.ings.filter(g => m.item[g.i] && m.item[g.i].supplier !== 'stock' && !have[g.i]);
        if (miss.length) reason = reason || ('缺 ' + miss.map(g => m.item[g.i].name).join('、'));
        if (miss.length) return null;
      }
      return { dish: d, reason };
    })
    .filter(Boolean)
    .sort((a, b) => (a.reason ? 1 : 0) - (b.reason ? 1 : 0) || STATUS.indexOf(a.dish.status) - STATUS.indexOf(b.dish.status) || a.dish.name.localeCompare(b.dish.name, 'zh-Hant'));
}

/** 涵蓋這一天、已經叫貨的批次裡有哪些品項。 */
function orderedItems(state, date) {
  const have = {};
  for (const k in state.orders) {
    const o = state.orders[k];
    if (o.status === 'draft' || o.coverDates.indexOf(date) < 0) continue;
    for (const id in o.sentFinal) if (o.sentFinal[id] > 0) have[id] = true;
  }
  return have;
}

function setDish(state, date, idx, dishId) {
  const x = state.days[date].dishes[idx];
  x.id = dishId;
}

// ---------- 用量 ----------
function scale(state) { return state.settings.people / state.settings.basePeople; }

/** 指定日期、指定供應商的食材需求(未進位):{ 品項id: { q, src: [{ date, dish, q }] } } */
function needs(state, dates, supplier) {
  const m = maps(state);
  const k = scale(state);
  const out = {};
  dates.forEach(date => {
    const day = state.days[date];
    if (!day) return;
    day.dishes.forEach(x => {
      const d = x.id && m.dish[x.id];
      if (!d) return;
      d.ings.forEach(g => {
        const it = m.item[g.i];
        if (!it || it.supplier !== supplier) return;
        const q = g.q * k;
        const o = out[g.i] || (out[g.i] = { q: 0, src: [] });
        o.q += q;
        o.src.push({ date, dish: d.name, q });
      });
    });
  });
  return out;
}

/** 依品項的叫貨級距無條件進位(例如 0.5 斤、1 盒)。 */
function roundUp(item, q) {
  if (q <= 0) return 0;
  const step = item.step || 0.5;
  return Math.round(Math.ceil(q / step - 1e-9) * step * 100) / 100;
}
function qtyText(item, q) {
  const n = Math.round(q * 100) / 100;
  return n + (item.unit || '斤');
}

// ---------- 叫貨單 ----------
/** 叫貨單的資料(存在 state.orders);菜商批次沒動過時不會存,直接即時計算。 */
function orderOf(state, b) {
  return state.orders[b.key] || { key: b.key, supplier: b.supplier, orderDate: b.orderDate, coverDates: b.coverDates,
    status: 'draft', overrides: {}, sentComputed: {}, sentFinal: {}, short: {}, extra: {} };
}
function saveOrder(state, o) { state.orders[o.key] = o; return o; }

/** 這些日期會用到的菜商批次(依叫貨日排序)。 */
function vegBatches(state, dates) {
  const seen = {}, out = [];
  dates.forEach(d => {
    const b = vegBatch(state, d);
    if (b && !seen[b.key]) { seen[b.key] = 1; out.push(b); }
  });
  return out.sort((a, b) => (a.orderDate < b.orderDate ? -1 : 1));
}

/**
 * 叫貨單畫面需要的全部資訊(會自動和目前菜單比對)。
 * lines 每列:item、computed(系統估算,已進位)、final(要叫的量)、override(手動值)、
 * stale(手動改過之後菜單又變了)、add / extra(已叫貨後菜單變動:需追加 / 用不到)、short(缺貨)。
 */
function orderView(state, o) {
  const m = maps(state);
  const need = needs(state, o.coverDates, o.supplier);
  const ids = {};
  Object.keys(need).forEach(id => { ids[id] = 1; });
  Object.keys(o.overrides || {}).forEach(id => { ids[id] = 1; });
  Object.keys(o.sentFinal || {}).forEach(id => { ids[id] = 1; });
  Object.keys(o.extra || {}).forEach(id => { ids[id] = 1; });
  const lines = [];
  let adds = 0, extras = 0, stale = 0;
  Object.keys(ids).forEach(id => {
    const item = m.item[id];
    if (!item) return;
    const computed = need[id] ? roundUp(item, need[id].q) : 0;
    const ov = o.overrides && o.overrides[id];
    const manual = o.extra && o.extra[id] != null;
    const L = { item, computed, src: need[id] ? need[id].src : [], override: ov ? ov.q : null, manual,
      short: !!(o.short && o.short[id]) };
    if (manual) L.final = o.extra[id];
    else L.final = ov ? ov.q : computed;
    L.stale = !!(ov && ov.base !== computed);
    if (L.stale) stale++;
    if (o.status !== 'draft') {
      const sent = o.sentFinal[id] || 0;
      const sentComp = o.sentComputed[id] || 0;
      L.sent = sent;
      L.add = computed > sent ? roundUp(item, computed - sent) : 0;
      L.extra = computed < sentComp && sent > computed ? Math.round((sent - computed) * 100) / 100 : 0;
      if (L.add) adds++;
      if (L.extra) extras++;
    }
    if (!L.computed && !L.final && !L.sent) return;
    lines.push(L);
  });
  lines.sort((a, b) => state.items.indexOf(a.item) - state.items.indexOf(b.item));
  const missing = o.coverDates.filter(d => !state.days[d]);
  return { order: o, lines, adds, extras, stale, missing };
}

function setOverride(state, o, itemId, q) {
  const v = orderView(state, o);
  const L = v.lines.find(x => x.item.id === itemId);
  o = saveOrder(state, o);
  if (o.extra && o.extra[itemId] != null) { o.extra[itemId] = q; return; }
  if (q == null) delete o.overrides[itemId];
  else o.overrides[itemId] = { q, base: L ? L.computed : 0 };
}
/** 手動加一個菜單沒用到的品項。 */
function addExtra(state, o, itemId, q) {
  o = saveOrder(state, o);
  o.extra = o.extra || {};
  o.extra[itemId] = q;
}
function removeLine(state, o, itemId) {
  o = saveOrder(state, o);
  if (o.extra && o.extra[itemId] != null) delete o.extra[itemId];
  else o.overrides[itemId] = { q: 0, base: (orderView(state, o).lines.find(x => x.item.id === itemId) || {}).computed || 0 };
}
/** 採用系統估算(清掉手動值)。 */
function acceptComputed(state, o, itemId) {
  o = saveOrder(state, o);
  delete o.overrides[itemId];
}

/** 標記已叫貨:把目前的量存下來,之後菜單再變就和這份比對。 */
function markSent(state, o, now) {
  const v = orderView(state, o);
  o = saveOrder(state, o);
  o.status = 'sent';
  o.sentAt = now || ymd(new Date());
  o.sentComputed = {};
  o.sentFinal = {};
  v.lines.forEach(L => {
    o.sentComputed[L.item.id] = L.computed;
    if (L.final > 0) o.sentFinal[L.item.id] = L.final;
  });
  return o;
}
/** 追加叫貨送出後:把追加的量算進已叫貨。 */
function markAddSent(state, o) {
  const v = orderView(state, o);
  v.lines.forEach(L => {
    if (L.add) o.sentFinal[L.item.id] = (o.sentFinal[L.item.id] || 0) + L.add;
    o.sentComputed[L.item.id] = L.computed;
  });
}
/** 菜單變少、用不到的量:確認知道了(不再提示)。 */
function ackExtra(state, o) {
  const v = orderView(state, o);
  v.lines.forEach(L => { if (L.extra) o.sentComputed[L.item.id] = L.computed; });
}
function unsend(state, o) {
  o.status = 'draft';
  o.sentComputed = {};
  o.sentFinal = {};
  o.short = {};
}

// ---------- 肉類叫貨(自己選日期區間) ----------
function meatOrders(state, supplier) {
  return Object.keys(state.orders).map(k => state.orders[k]).filter(o => o.supplier === supplier)
    .sort((a, b) => (a.coverDates[0] < b.coverDates[0] ? -1 : 1));
}
/** 這些日期中,有用到這家肉商的肉、但還沒被任何叫貨單涵蓋的日期。 */
function meatUncovered(state, supplier, dates) {
  const covered = {};
  meatOrders(state, supplier).forEach(o => o.coverDates.forEach(d => { covered[d] = 1; }));
  return dates.filter(d => !covered[d] && Object.keys(needs(state, [d], supplier)).length > 0);
}
function createMeatOrder(state, supplier, from, to) {
  const n = diffDays(from, to) + 1;
  const coverDates = range(from, Math.max(1, n));
  // 一天只能屬於一張叫貨單:已經被涵蓋的日期拿掉
  const covered = {};
  meatOrders(state, supplier).forEach(o => o.coverDates.forEach(d => { covered[d] = 1; }));
  const free = coverDates.filter(d => !covered[d]);
  if (!free.length) return null;
  const key = supplier + '|' + free[0];
  return saveOrder(state, { key, supplier, orderDate: null, coverDates: free, status: 'draft',
    overrides: {}, sentComputed: {}, sentFinal: {}, short: {}, extra: {} });
}

// ---------- 輸出文字 ----------
function supplierName(state, s) { return state.settings.suppliers[s] || s; }
function coverText(dates) {
  if (dates.length === 1) return fmt(dates[0]);
  return fmt(dates[0]) + '～' + fmt(dates[dates.length - 1]);
}

/** 複製到 LINE 的叫貨文字。mode = 'full'(整張)或 'add'(只有追加)。 */
function orderText(state, v, mode) {
  const o = v.order;
  const head = o.supplier === 'veg'
    ? '【' + fmt(addDays(o.orderDate, 1)) + ' 送貨】'
    : '【' + coverText(o.coverDates) + ' 用】';
  const lines = mode === 'add'
    ? v.lines.filter(L => L.add).map(L => L.item.name + ' ' + qtyText(L.item, L.add))
    : v.lines.map(L => [L, o.status === 'draft' ? L.final : (L.sent || 0)]).filter(x => x[1] > 0)
      .map(([L, q]) => L.item.name + ' ' + qtyText(L.item, q));
  return (mode === 'add' ? '【追加】' : '') + head + supplierName(state, o.supplier) + '\n' + lines.join('\n');
}

/** 每週菜單報告(只列菜名,給員工看)。 */
function weekReport(state, dates) {
  const m = maps(state);
  const used = dates.filter(d => state.days[d]);
  if (!used.length) return '';
  const out = ['【本週員工餐】' + coverText([used[0], used[used.length - 1]])];
  used.forEach(d => {
    const day = state.days[d];
    const names = day.dishes.map(x => x.id && m.dish[x.id] && m.dish[x.id].name).filter(Boolean);
    out.push('');
    out.push(fmt(d) + (day.type === 'nonrice' ? '' : ''));
    if (day.type === 'nonrice') out.push(names.join('、'));
    else {
      out.push(names.slice(0, 3).join('、'));
      out.push(names.slice(3).join('、'));
    }
  });
  return out.join('\n');
}

/** 庫存品(雞蛋、冷凍魚…)在這些日期的用量。 */
function stockUsage(state, dates) {
  const m = maps(state);
  const n = needs(state, dates, 'stock');
  return Object.keys(n).map(id => ({ item: m.item[id], q: roundUp(m.item[id], n[id].q), src: n[id].src }))
    .sort((a, b) => state.items.indexOf(a.item) - state.items.indexOf(b.item));
}

/** 排過菜單或叫過貨的料理 / 品項不能真的刪掉(只能停用),歷史紀錄才不會出現空白。 */
function dishUsed(state, id) {
  for (const d in state.days) if (state.days[d].dishes.some(x => x.id === id)) return true;
  return false;
}
function itemUsed(state, id) {
  if (state.dishes.some(d => d.ings.some(g => g.i === id))) return true;
  for (const k in state.orders) {
    const o = state.orders[k];
    if ((o.sentFinal && o.sentFinal[id]) || (o.extra && o.extra[id] != null)) return true;
  }
  return false;
}

const api = {
  WEEK, SLOT_NAME, KIND_NAME, STATUS, SUPPLIERS,
  ymd, parse, addDays, dow, diffDays, fmt, range, newId,
  initState, maps, slotsFor, slotLabel, accepts, hardCheck, nearestUse,
  vegBatch, vegBatches, dayWarnings,
  drawDay, redrawSlot, drawWeek, setDayType, candidates, setDish, orderedItems,
  needs, roundUp, qtyText, orderOf, saveOrder, orderView, setOverride, addExtra, removeLine, acceptComputed,
  markSent, markAddSent, ackExtra, unsend, meatOrders, meatUncovered, createMeatOrder,
  supplierName, coverText, orderText, weekReport, stockUsage, dishUsed, itemUsed,
};
if (typeof module !== 'undefined' && module.exports) module.exports = api;
else root.ML = api;
})(this);
