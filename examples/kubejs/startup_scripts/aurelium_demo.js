// NeoForge 1.21.1 + KubeJS 2101.7.2-build.368 example.
// Startup phase: these are templates for aurelium:star_vault; NOT extra Forge item IDs.

// ── 写法 A：具名键对象（推荐；/aurelium export 生成的就是这种）──────────────
AureliumPacks.register({
  id:    'demo:celestial_supply',            // 【必填】包 id
  name:  '§d樱粉星愿·万物宝匣',               // 可选：显示名
  items: [                                   // 【必填】内容
    { itemId: 'minecraft:diamond',        count: 64 },
    { itemId: 'minecraft:iron_ingot',     count: 8192 },
    { itemId: 'minecraft:stone',          count: '250000000000000000000' },  // 超 long 也行
    // 流体必须带 fluid: 前缀；数量键也可写 amount / quantity
    { itemId: 'fluid:minecraft:water',    count: 1000 },
    { itemId: 'fluid:minecraft:lava',     count: 16000 },
    // 嵌套宝匣：用 packId 指向另一个包
    // { packId: 'demo:inner_pack', count: 1 }
  ]
});

// ── 写法 B：字符串数组（旧写法，仍然完全支持）───────────────────────────────
AureliumPacks.register('demo:celestial_supply_old', '§d樱粉星愿·万物宝匣', [
  '64x minecraft:diamond',
  '8192x minecraft:iron_ingot',
  '250000000000000000000x minecraft:stone',
  // Fluids and other AE2 key types are supported too (item = bare id):
  '1000x fluid:minecraft:water',
  '16000x fluid:minecraft:lava'
]);

// Infinite cells can also declare fluids (a "creative water/lava" cell):
AureliumInfinite.register('demo:fluids', 'demo:fluids_cell', '无限流体', 'minecraft:water_bucket',
  ['fluid:minecraft:water', 'fluid:minecraft:lava']);

// Optional custom appearance (both optional; omit for the built-in pink look).
// 'model' / 'texture' are model ids that must exist in a resource pack on the client.
// Full overload: register(id, itemId, title, icon, entries, describe, shimmer, model, texture)
AureliumInfinite.register('demo:pretty', 'demo:pretty_cell', '梦幻匣', 'minecraft:nether_star',
  ['minecraft:diamond'],            // entries
  true,                             // describe
  true,                             // shimmer (colour-cycle the name)
  'mypack:item/fancy_cell',         // model  (may be null)
  null);                            // texture (may be null; used only if model is null)

// Call AureliumCell.create('demo:celestial_supply') ONLY after item registries exist
// (e.g. in server script during a server/player event). Do not call it while
// evaluating startup_scripts: this phase registers declarations before items exist.

// ─────────────────────────────────────────────────────────────────────────────
// 自定义 ID 的无限元件（每个元件一个独立物品 ID，itemId 必填）。
// 用 KubeJS 官方的 StartupEvents.registry('item', ...)：
//   event.create('<元件物品ID>', 'aurelium:infinite_cell')  ← 类型由 AURELIUM 提供
//        .infiniteKind('<kindId>')                          ← 反绑到 kind
// 然后在 AureliumInfinite.register 里把同一个 itemId 填进去（kind ↔ 物品一一对应）。
// 物品本身只带一个 kind 标签；是否炫彩由 register 的 shimmer 参数决定。
// ─────────────────────────────────────────────────────────────────────────────

// 1) 声明 kind：register(kindId, itemId, title, icon, entries[, describe][, shimmer])
AureliumInfinite.register('mypack:celestial', 'mypack:celestial_cell', '星愿匣',
  'minecraft:nether_star', ['minecraft:diamond', 'minecraft:emerald']);

// 只想要描述、不要炫彩：传 describe=true, shimmer=false
AureliumInfinite.register('demo:plain', 'demo:plain_cell', '素匣',
  'minecraft:diamond', ['minecraft:diamond'], true, false);

// 2) 为每个 kind 创建它自己的元件物品（独立 ID）
StartupEvents.registry('item', event => {
  event.create('mypack:celestial_cell', 'aurelium:infinite_cell')
       .infiniteKind('mypack:celestial');

  event.create('demo:plain_cell', 'aurelium:infinite_cell')
       .infiniteKind('demo:plain');
});