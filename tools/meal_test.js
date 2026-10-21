// 員工餐 App 核心邏輯測試:node --test tools/meal_test.js
const test = require('node:test');
const assert = require('node:assert');
const ML = require('../meal/src/main/assets/logic.js');
const SEED = require('../meal/src/main/assets/seed.js');

// 固定亂數,每次結果一樣
function rng(seed) {
  let s = seed || 1;
  return () => { s = (s * 1103515245 + 12345) % 2147483648; return s / 2147483648; };
}
const fresh = () => ML.initState(SEED);
const TODAY = '2026-10-21';   // 星期三

test('初始資料:每道菜的食材都存在、類別正確', () => {
  const s = fresh();
  const kinds = ['pork', 'chicken', 'fish', 'veg', 'egg', 'nonrice'];
  s.dishes.forEach(d => {
    assert.ok(kinds.includes(d.kind), d.name);
    assert.ok(ML.STATUS.includes(d.status), d.name);
    assert.ok(d.ings.length > 0, d.name);
  });
  const names = s.items.map(i => i.name);
  assert.strictEqual(new Set(names).size, names.length, '品項名稱重複');
  ['牛番茄', '皮蛋', '鹹蛋', '紅K', '白K', '油麵', '雞蛋'].forEach(n => assert.ok(names.includes(n), n));
});

test('日期:從今天往後 7 天、星期顯示', () => {
  const w = ML.range(TODAY, 7);
  assert.deepStrictEqual(w, ['2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25', '2026-10-26', '2026-10-27']);
  assert.strictEqual(ML.fmt(TODAY), '10/21(三)');
  assert.strictEqual(ML.addDays('2026-12-31', 1), '2027-01-01');
});

test('菜商批次:一叫二三、三叫四五六、六叫日一(星期一不送)', () => {
  const s = fresh();
  const b = d => ML.vegBatch(s, d);
  assert.strictEqual(b('2026-10-20').orderDate, '2026-10-19');   // 二 ← 一
  assert.strictEqual(b('2026-10-21').orderDate, '2026-10-19');   // 三 ← 一
  assert.strictEqual(b('2026-10-22').orderDate, '2026-10-21');   // 四 ← 三
  assert.strictEqual(b('2026-10-24').orderDate, '2026-10-21');   // 六 ← 三
  assert.deepStrictEqual(b('2026-10-24').coverDates, ['2026-10-22', '2026-10-23', '2026-10-24']);
  assert.strictEqual(b('2026-10-24').dayNo, 2);                  // 送貨後第 3 天
  assert.strictEqual(b('2026-10-25').orderDate, '2026-10-24');   // 日 ← 六
  assert.strictEqual(b('2026-10-26').orderDate, '2026-10-24');   // 一 ← 六
  assert.strictEqual(b('2026-10-25').deliverDate, '2026-10-25');
});

test('抽一天:主菜三選二 + 2 蔬菜 + 1 蛋,符合硬規則', () => {
  for (let k = 1; k <= 30; k++) {
    const s = fresh();
    const day = ML.drawDay(s, TODAY, rng(k));
    const m = ML.maps(s);
    const ds = day.dishes.map(x => m.dish[x.id]);
    assert.ok(ds.every(Boolean), '有空格');
    assert.deepStrictEqual(day.dishes.map(x => x.slot), ['main', 'main', 'veg', 'veg', 'egg']);
    assert.ok(ds.slice(0, 2).every(d => ['pork', 'chicken', 'fish'].includes(d.kind)));
    assert.notStrictEqual(ds[0].kind, ds[1].kind, '兩道主菜要不同種類');
    assert.ok(ds.slice(2, 4).every(d => d.kind === 'veg'));
    assert.strictEqual(ds[4].kind, 'egg');
    assert.ok(ds.filter(d => d.kind === 'fish').length <= 1, '魚超過 1 道');
    assert.ok(ds.filter(d => d.method === '炸').length <= 1, '炸物超過 1 道');
    assert.strictEqual(new Set(ds.map(d => d.main)).size, 5, '主體食材重複');
    assert.ok(ds.every(d => d.status === '常用' || d.status === '輪替'), '抽到候選或停用');
  }
});

test('抽一週:7 天內同一道菜不重複', () => {
  const s = fresh();
  const dates = ML.range(TODAY, 7);
  ML.drawWeek(s, dates, rng(7));
  const seen = {};
  dates.forEach(d => s.days[d].dishes.forEach(x => {
    assert.ok(x.id, d + ' 有空格');
    assert.ok(!seen[x.id], '重複:' + x.id);
    seen[x.id] = 1;
  }));
});

test('停用的菜不會被抽到,候選只能手動選', () => {
  const s = fresh();
  s.dishes.forEach(d => { if (d.name === '蒜炒高麗菜') d.status = '停用'; });
  const stop = s.dishes.find(d => d.name === '蒜炒高麗菜').id;
  for (let k = 1; k <= 20; k++) {
    ML.drawWeek(s, ML.range(TODAY, 7), rng(k));
    Object.values(s.days).forEach(day => assert.ok(!day.dishes.some(x => x.id === stop)));
  }
  ML.drawDay(s, TODAY, rng(1));
  const veg = s.days[TODAY].dishes.findIndex(x => x.slot === 'veg');
  const c = ML.candidates(s, TODAY, veg);
  assert.ok(!c.some(x => x.dish.id === stop), '停用的菜出現在手動清單');
  assert.ok(c.some(x => x.dish.status === '候選'), '手動清單應該有候選菜');
});

test('局部重抽不動其他菜,鎖定的菜整天重抽也不變', () => {
  const s = fresh();
  ML.drawDay(s, TODAY, rng(3));
  const before = s.days[TODAY].dishes.map(x => x.id);
  ML.redrawSlot(s, TODAY, 1, rng(4));
  const after = s.days[TODAY].dishes.map(x => x.id);
  [0, 2, 3, 4].forEach(i => assert.strictEqual(after[i], before[i]));
  assert.notStrictEqual(after[1], before[1]);

  s.days[TODAY].dishes[0].locked = true;
  s.days[TODAY].dishes[4].locked = true;
  for (let k = 1; k <= 10; k++) {
    ML.drawDay(s, TODAY, rng(k + 10));
    assert.strictEqual(s.days[TODAY].dishes[0].id, after[0]);
    assert.strictEqual(s.days[TODAY].dishes[4].id, after[4]);
  }
  ML.drawWeek(s, ML.range(TODAY, 7), rng(99));
  assert.strictEqual(s.days[TODAY].dishes[0].id, after[0]);
});

test('改成非飯菜日:換成非飯菜日卡池', () => {
  const s = fresh();
  ML.drawDay(s, TODAY, rng(1));
  ML.setDayType(s, TODAY, 'nonrice', rng(2));
  const m = ML.maps(s);
  const day = s.days[TODAY];
  assert.strictEqual(day.type, 'nonrice');
  assert.strictEqual(day.dishes.length, 1);
  assert.strictEqual(m.dish[day.dishes[0].id].kind, 'nonrice');
  ML.setDayType(s, TODAY, 'rice', rng(3));
  assert.strictEqual(s.days[TODAY].dishes.length, 5);
});

test('用量:同一食材彙總、依級距進位、庫存品不列入', () => {
  const s = fresh();
  const id = n => s.dishes.find(d => d.name === n).id;
  s.days['2026-10-22'] = { type: 'rice', dishes: [
    { slot: 'veg', id: id('蒜炒高麗菜') }, { slot: 'veg', id: id('木耳炒高麗菜') }, { slot: 'egg', id: id('番茄炒蛋') } ] };
  s.days['2026-10-23'] = { type: 'rice', dishes: [{ slot: 'veg', id: id('家常豆腐') }] };
  const n = ML.needs(s, ['2026-10-22', '2026-10-23'], 'veg');
  const item = name => s.items.find(i => i.name === name);
  assert.strictEqual(n[item('高麗菜').id].q, 6);
  assert.strictEqual(n[item('高麗菜').id].src.length, 2);
  assert.ok(!n[item('雞蛋').id], '雞蛋是庫存品,不應叫貨');
  assert.strictEqual(ML.roundUp(item('蒜末'), 0.2), 0.5);
  assert.strictEqual(ML.roundUp(item('板豆腐'), 7.2), 8);
  const st = ML.stockUsage(s, ['2026-10-22']);
  assert.strictEqual(st[0].item.name, '雞蛋');
  assert.strictEqual(st[0].q, 25);
});

test('叫貨單:菜單變動自動更新;已叫貨後顯示追加 / 用不到', () => {
  const s = fresh();
  const id = n => s.dishes.find(d => d.name === n).id;
  const item = name => s.items.find(i => i.name === name);
  s.days['2026-10-22'] = { type: 'rice', dishes: [{ slot: 'veg', id: id('蒜炒高麗菜') }, { slot: 'veg', id: id('蒜炒四季豆') }] };
  const b = ML.vegBatch(s, '2026-10-22');
  let v = ML.orderView(s, ML.orderOf(s, b));
  assert.strictEqual(v.lines.find(L => L.item.name === '高麗菜').final, 3);
  assert.deepStrictEqual(v.missing, ['2026-10-23', '2026-10-24']);

  // 手動改量 → 菜單又變 → 提示 stale
  ML.setOverride(s, ML.orderOf(s, b), item('高麗菜').id, 4);
  s.days['2026-10-23'] = { type: 'rice', dishes: [{ slot: 'veg', id: id('木耳炒高麗菜') }] };
  v = ML.orderView(s, ML.orderOf(s, b));
  const cab = v.lines.find(L => L.item.name === '高麗菜');
  assert.strictEqual(cab.final, 4);
  assert.strictEqual(cab.computed, 6);
  assert.ok(cab.stale);
  ML.acceptComputed(s, ML.orderOf(s, b), item('高麗菜').id);
  assert.strictEqual(ML.orderView(s, ML.orderOf(s, b)).lines.find(L => L.item.name === '高麗菜').final, 6);

  // 已叫貨 → 換菜 → 追加 / 用不到
  ML.markSent(s, ML.orderOf(s, b), TODAY);
  const text = ML.orderText(s, ML.orderView(s, ML.orderOf(s, b)), 'full');
  assert.ok(text.startsWith('【10/22(四) 送貨】菜商\n'), text);
  assert.ok(text.includes('高麗菜 6斤'), text);
  s.days['2026-10-22'].dishes[1].id = id('蒜炒青江菜');   // 四季豆 → 青江菜
  v = ML.orderView(s, ML.orderOf(s, b));
  assert.strictEqual(v.lines.find(L => L.item.name === '青江菜').add, 3);
  assert.strictEqual(v.lines.find(L => L.item.name === '四季豆').extra, 3);
  assert.ok(ML.orderText(s, v, 'add').includes('青江菜 3斤'));
  ML.markAddSent(s, ML.orderOf(s, b));
  ML.ackExtra(s, ML.orderOf(s, b));
  v = ML.orderView(s, ML.orderOf(s, b));
  assert.strictEqual(v.adds, 0);
  assert.strictEqual(v.extras, 0);
  // 用現有食材換菜:青江菜已叫貨,蒜炒青江菜做得出來
  const c = ML.candidates(s, '2026-10-22', 1, true);
  assert.ok(c.some(x => x.dish.name === '蒜炒青江菜'));
  assert.ok(!c.some(x => x.dish.name === '蒜炒地瓜葉'));
});

test('肉類叫貨:自選區間、未涵蓋日期提醒', () => {
  const s = fresh();
  const dates = ML.range(TODAY, 7);
  ML.drawWeek(s, dates, rng(5));
  const un = ML.meatUncovered(s, 'pork', dates);
  assert.ok(un.length > 0);
  const o = ML.createMeatOrder(s, 'pork', TODAY, ML.addDays(TODAY, 3));
  assert.deepStrictEqual(o.coverDates, ML.range(TODAY, 4));
  const left = ML.meatUncovered(s, 'pork', dates);
  assert.ok(left.every(d => d > ML.addDays(TODAY, 3)));
  // 重疊的日期不會重複叫
  const o2 = ML.createMeatOrder(s, 'pork', ML.addDays(TODAY, 2), ML.addDays(TODAY, 6));
  assert.strictEqual(o2.coverDates[0], ML.addDays(TODAY, 4));
  const v = ML.orderView(s, o);
  assert.ok(ML.orderText(s, v, 'full').startsWith('【10/21(三)～10/24(六) 用】豬肉商'));
});

test('每週報告:只列菜名', () => {
  const s = fresh();
  const dates = ML.range(TODAY, 7);
  ML.drawWeek(s, dates, rng(2));
  ML.setDayType(s, dates[2], 'nonrice', rng(3));
  const r = ML.weekReport(s, dates);
  const lines = r.split('\n');
  assert.strictEqual(lines[0], '【本週員工餐】10/21(三)～10/27(二)');
  assert.ok(!r.includes('斤'));
  assert.ok(r.includes('10/23(五)'));
});

test('自己輸入菜名:不被重抽換掉、不算叫貨、會出現在週報', () => {
  const s = fresh();
  const dates = ML.range(TODAY, 7);
  ML.drawWeek(s, dates, rng(4));
  ML.setCustom(s, TODAY, 0, '市場烤鴨');
  ML.drawWeek(s, dates, rng(5));
  ML.drawDay(s, TODAY, rng(6));
  assert.strictEqual(s.days[TODAY].dishes[0].custom, '市場烤鴨');
  assert.ok(!ML.dayWarnings(s, TODAY).some(w => w.text.indexOf('空格') >= 0));
  assert.ok(ML.weekReport(s, dates).split('\n')[3].startsWith('市場烤鴨、'));
  ML.redrawSlot(s, TODAY, 0, rng(7));
  assert.ok(!s.days[TODAY].dishes[0].custom, '重抽這一格就換回卡池的菜');
  assert.ok(s.days[TODAY].dishes[0].id);
});

test('批次新增料理:解析格式、找不到的食材報錯', () => {
  const s = fresh();
  const r = ML.parseBatch(s, [
    '# 註解',
    '蔬菜 蒜炒油菜:油菜 3.5、蒜末 0.2',
    '豬肉 沙茶豬肉：豬肉片 4,洋蔥 2斤',
    '魚 紅燒吳郭魚:吳郭魚 8',
    '蔬菜 蒜炒高麗菜:高麗菜 3',
    '蔬菜 炒火星菜:火星菜 3',
    '甜點 布丁:雞蛋 30',
    '蛋 煎蛋:雞蛋',
    '',
  ].join('\n'), '輪替');
  assert.deepStrictEqual(r.dishes.map(d => d.name), ['蒜炒油菜', '沙茶豬肉', '紅燒吳郭魚']);
  assert.strictEqual(r.errors.length, 4);
  assert.ok(r.errors[0].msg.indexOf('已經有') >= 0);
  assert.ok(r.errors[1].msg.indexOf('火星菜') >= 0);
  const pork = r.dishes[1];
  assert.strictEqual(pork.kind, 'pork');
  assert.strictEqual(pork.status, '輪替');
  assert.strictEqual(pork.flavor, '沙茶');
  assert.strictEqual(pork.ings[1].q, 2);
  assert.strictEqual(r.dishes[2].method, '滷燒燉');
  assert.strictEqual(r.dishes[2].sauce, '深');
});

test('舊版存檔升級:資料保留,主菜改成三選二', () => {
  const s = fresh();
  ML.drawWeek(s, ML.range(TODAY, 7), rng(1));
  delete s.settings.rules.mainCount;
  s.settings.rules.maxSameMeat = 2;
  const old = JSON.parse(JSON.stringify(s));
  const up = ML.migrate(JSON.parse(JSON.stringify(old)));
  assert.strictEqual(up.settings.rules.mainCount, 2);
  assert.strictEqual(up.settings.rules.maxSameMeat, 1);
  assert.deepStrictEqual(up.days, old.days);
  assert.deepStrictEqual(up.dishes, old.dishes);
  assert.deepStrictEqual(up.items, old.items);
  // 舊的 6 道菜那天重抽後變成 5 道,鎖定的菜和自己輸入的菜保留
  const day = up.days[TODAY];
  day.dishes = [{ slot: 'main', id: null, locked: false }].concat(day.dishes);   // 舊版是 3 主菜
  assert.strictEqual(day.dishes.length, 6);
  ML.setCustom(up, TODAY, 1, '市場烤鴨');
  day.dishes[4].locked = true;
  const lockedVeg = day.dishes[4].id;
  ML.drawDay(up, TODAY, rng(2));
  const ds = up.days[TODAY].dishes;
  assert.strictEqual(ds.length, 5);
  assert.ok(ds.some(x => x.custom === '市場烤鴨'));
  assert.ok(ds.some(x => x.id === lockedVeg && x.locked));
});
