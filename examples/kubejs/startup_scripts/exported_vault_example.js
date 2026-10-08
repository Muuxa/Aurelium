// ─────────────────────────────────────────────────────────────────────────────
// 这是 /aurelium export 生成的 .js 的【示例样子】（新格式：独立物品 id + 套娃包）。
// 你实际导出的文件名是 vault_xxx.js，请以实际生成为准，不要用这份示例当数据。
// 放到 kubejs/startup_scripts/ 下即可。
// ─────────────────────────────────────────────────────────────────────────────

// ① 注册物品（每个宝匣一个独立物品；根不带数字，子包从 _1 开始）
StartupEvents.registry('item', event => {
  event.create('export_test', 'aurelium:star_vault')
       .vaultPack("aurelium:export_test")
       .vaultPower(10000)
       .displayName("导出的宝匣 test");
  event.create('export_test_1', 'aurelium:star_vault')
       .vaultPack("aurelium:export_test_1")
       .vaultPower(10000)
       .displayName("导出的宝匣 1");
});

// ② 注册内容包（每个宝匣一个；父包用 "vault:<子包id>" 向下套娃）
AureliumPacks.register({
  id:   "aurelium:export_test",
  name: "导出的宝匣 test",
  items: [
    { packId: "aurelium:export_test_1", count: 1 }
  ]
});

AureliumPacks.register({
  id:   "aurelium:export_test_1",
  name: "导出的宝匣 1",
  items: [
    { itemId: "aurelium:infinite_concrete", count: 1 }
  ]
});

// ─────────────────────────────────────────────────────────────────────────────
// 取用：Item.of('export_test')  或  AureliumCell.create('aurelium:export_test')
// 首次被打开/读取时，内容（含套娃）会自动构建并落盘。
// ─────────────────────────────────────────────────────────────────────────────
