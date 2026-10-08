/* ============================================================================
 *  AURELIUM - 无限元件 (infinite cell)  KubeJS 完整示例
 *  放到  kubejs/startup_scripts/  下，重启游戏生效。
 * ============================================================================
 *
 *  一个「无限元件」= 一种「哪些东西无限」的设定。
 *
 *  ---------------------------------------------------------------------------
 *  kindId（种类 id） 和  元件物品 id（item）  有什么区别？
 *  ---------------------------------------------------------------------------
 *    kindId        是一个逻辑名字(纯字符串)，代表"这套无限设定"。
 *                  它只存在于 AURELIUM 的种类表里，玩家永远拿不到它。
 *                  例: 'demo:starlight'
 *    元件物品 id   是一个真实注册的物品(Item)，玩家/驱动能拿到它。
 *                  例: 'demo:starlight_cell'
 *
 *    关系：元件物品创建时会把 kindId 写进自己的 NBT。
 *          玩家拿到物品 -> 游戏读它身上的 kindId -> 查表得知「哪些东西无限、叫什么、什么模型」。
 *
 *    所以：kindId 和元件物品 一一对应；两个 kindId 要用两个不同物品。
 *    类比：kindId = 配方 id，元件物品 = 这道菜。
 *
 *  ---------------------------------------------------------------------------
 *  标准三步
 *  ---------------------------------------------------------------------------
 *    ① 声明 kind         AureliumInfinite.register({ id, item, infinite, ... })
 *    ② 创建元件物品      StartupEvents.registry('item', e => e.create(itemId, 'aurelium:infinite_cell').kind(id))
 *    ③ (可选) 发放/配方  写在 server_scripts 里
 *
 *  三种写法效果一样，随你挑：
 *    A. 具名键对象  AureliumInfinite.register({ ... })          <- 推荐，一眼看清
 *    B. 流式构建器  AureliumInfinite.builder('id')....register()
 *    C. 一行式最简  AureliumInfinite.simple('id', 'item', ['a', 'b'])
 * ==========================================================================*/


/* ============================================================================
 * ① 声明 kind
 * ==========================================================================*/

// -- 例 1: 最完整的一套 -------------------------------------------------------
AureliumInfinite.register({
  id:       'demo:starlight',            // [必填] kindId：这套设定的逻辑名字
  item:     'demo:starlight_cell',       // [必填] 元件物品 id：承载它的那件物品(见②)
  title:    '星愿无限匣',                 // 可选：自定义名字(省略 = 用物品默认名)
  icon:     'minecraft:nether_star',     // 可选：tooltip 标题小图标(省略 = 第一个无限条目)
  infinite: [                            // [必填] 哪些东西无限
    'minecraft:diamond',
    'minecraft:emerald',
    'minecraft:gold_ingot'
  ],
  model:    'demo:item/starlight_cell',  // 可选：自定义模型 id(资源包需提供该 json)
  // texture: null,                      // 可选：自定义贴图 id(给了 model 就优先用 model)
  describe: true,                        // 可选：显示粉色介绍块(默认 true)
  shimmer:  true                         // 可选：名字炫彩(默认 true)
});

// -- 例 2: 最少字段(只有必填项) ----------------------------------------------
AureliumInfinite.register({
  id:       'demo:iron',
  item:     'demo:iron_cell',
  infinite: ['minecraft:iron_ingot', 'minecraft:iron_block', 'minecraft:iron_nugget']
});

// -- 例 3: 流体也无限(流体必须写 fluid: 前缀) --------------------------------
AureliumInfinite.register({
  id:       'demo:fluids',
  item:     'demo:fluid_cell',
  title:    '万流之匣',
  icon:     'minecraft:water_bucket',
  infinite: ['fluid:minecraft:water', 'fluid:minecraft:lava']
});

// -- 例 4: 隐藏介绍 + 不炫彩 --------------------------------------------------
AureliumInfinite.register({
  id:       'demo:silent',
  item:     'demo:silent_cell',
  title:    '无名之匣',
  icon:     'minecraft:gold_ingot',
  infinite: ['minecraft:gold_ingot'],
  describe: false,                       // 不显示粉色介绍块
  shimmer:  false                        // 名字不炫彩
});

// -- 例 5: 数量前缀会被忽略(纯粹为了可读) ------------------------------------
AureliumInfinite.register({
  id:       'demo:ore',
  item:     'demo:ore_cell',
  title:    '矿脉之匣',
  infinite: ['64x minecraft:coal', '64x minecraft:raw_iron', '64x minecraft:raw_gold']
});

// -- 例 6(可选写法 B): 流式构建器 -------------------------------------------
AureliumInfinite.builder('demo:gem')
  .item('demo:gem_cell')
  .title('宝石之匣')
  .icon('minecraft:diamond')
  .infinite('minecraft:diamond', 'minecraft:emerald')
  .register();

// -- 例 7(可选写法 C): 一行式最简 -------------------------------------------
AureliumInfinite.simple('demo:cobble', 'demo:cobble_cell',
  ['minecraft:cobblestone', 'minecraft:cobbled_deepslate']);


/* ============================================================================
 * ② 为每个 kind 创建它自己的元件物品
 *    - 物品 id 必须和上面 .item / item: 完全一致
 *    - 类型固定写 'aurelium:infinite_cell'
 *    - 用 .kind('kindId') 把它绑到对应的 kind
 * ==========================================================================*/
StartupEvents.registry('item', event => {
  event.create('demo:starlight_cell', 'aurelium:infinite_cell').kind('demo:starlight');
  event.create('demo:iron_cell',      'aurelium:infinite_cell').kind('demo:iron');
  event.create('demo:fluid_cell',     'aurelium:infinite_cell').kind('demo:fluids');
  event.create('demo:silent_cell',    'aurelium:infinite_cell').kind('demo:silent');
  event.create('demo:ore_cell',       'aurelium:infinite_cell').kind('demo:ore');
  event.create('demo:gem_cell',       'aurelium:infinite_cell').kind('demo:gem');
  event.create('demo:cobble_cell',    'aurelium:infinite_cell').kind('demo:cobble');

  // 也可以顺手给物品加原版属性(KubeJS ItemBuilder 通用方法)，例如：
  //   .displayName('显示名')  .rarity('epic')  .glow(true)  .tag('c:my_tag')
});


/* ============================================================================
 * ③ 自定义模型 / 贴图
 * ============================================================================
 *  资源包(放 kubejs/assets/ 下也行)需要：
 *
 *    kubejs/assets/demo/models/item/starlight_cell.json
 *      {
 *        "parent": "minecraft:item/generated",
 *        "textures": { "layer0": "demo:item/starlight_cell" }
 *      }
 *
 *    kubejs/assets/demo/textures/item/starlight_cell.png       <- 16x16 贴图
 *
 *  然后对象里写  model: 'demo:item/starlight_cell'
 *  (= 模型 id；模型 JSON 就在上面那个路径)
 *  texture: 是等价的备用写法，同样需要一个引用它的模型 JSON。
 *  两个都不给时，用内置的粉色元件外观。
 * ==========================================================================*/


/* ============================================================================
 *  常用 API 速查
 * ============================================================================
 *
 *  [声明 kind] 三选一
 *    AureliumInfinite.register({ ... })                 <- 具名键对象(推荐)
 *    AureliumInfinite.builder(kindId)....register()     <- 流式构建器
 *    AureliumInfinite.simple(kindId, itemId, [entries]) <- 一行式
 *
 *  [对象写法的键]
 *    id         [必填] kindId，形如 'demo:starlight'
 *    item       [必填] 元件物品 id
 *    infinite   [必填] 数组，哪些东西无限：
 *                        物品   'minecraft:diamond'
 *                        流体   'fluid:minecraft:water'  (流体必须带 fluid: 前缀)
 *                        带数量 '64x minecraft:diamond'  (数量被忽略，只为可读)
 *    title        可选：自定义名字(省略 = 物品默认名)
 *    icon         可选：tooltip 标题小图标(省略 = 第一个无限条目)
 *    model        可选：自定义模型 id ('ns:item/xxx')
 *    texture      可选：自定义贴图 id ('ns:item/xxx'，model 优先)
 *    describe     可选：是否显示粉色介绍块(默认 true)
 *    shimmer      可选：名字是否炫彩(默认 true)
 *    (未知键会被忽略，不报错。entries 可作为 infinite 的别名。)
 *
 *  [查询 / 使用]  server_scripts 或 startup 都行
 *    AureliumInfinite.all()                            -> 所有已登记的 kind (Map)
 *    AureliumInfinite.get('demo:starlight')            -> 取某个 spec
 *    AureliumInfinite.byItemId('demo:starlight_cell')  -> 反查 kind
 *    AureliumInfiniteCell.describe('demo:starlight')   -> 打印一份摘要文本
 *
 *  [给玩家发放]  写在 server_scripts 里
 *    player.give(AureliumInfiniteCell.create('demo:starlight'))
 *
 *  [在配方 / JEI 里引用]  只需要 NBT 模板字符串时
 *    AureliumInfiniteCell.templateTag('demo:starlight')
 *    -> '{"aurelium_infinite":"demo:starlight"}'
 *
 *  [绑定 / 改派已有元件栈的 kind]
 *    AureliumInfiniteCell.setKind(itemStack, 'demo:starlight')
 * ==========================================================================*/