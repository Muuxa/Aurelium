# AURELIUM — 樱粉星愿·万物宝匣

**目标环境：Minecraft 1.21.1 / Java 21 / AE2 NeoForge 19.2.17。**预期编译目标 NeoForge 21.1.248，但模组元数据不锁定 NeoForge 补丁版本。KubeJS 可选；用于编译桥接类的本地 JAR 是 2101.7.2-build.368。代码根包名 `com.muuxa.aurelium`。本工程为**独立重写的源码工程**，不是从 1.20.1 Forge JAR 剪切出来的可执行模组；不依赖 GTL / GTCEu / 原模组。本项目仍须在电脑上编译、启动游戏与验证数据路径，未经游戏实测请先备份存档。

## 构建 / 移至电脑
复制**整个** `Aurelium-NeoForge-1.21.1` 文件夹到电脑，确认安装 JDK 21；在该目录执行 `gradlew.bat build`（Windows），或 `bash gradlew build`（Linux/macOS）；构建成品位于 `build/libs/`。首次构建 Gradle 会下载 NeoForge、Minecraft 及构建依赖；本项目 `libs/` 已附用于**编译**的 AE2、KubeJS JAR，运行时必须安装 AE2（以及 AE2 自身的 GuideME 依赖）；**不使用脚本注册功能时无需安装 KubeJS**。若需要通过 KubeJS 注册元件包，才在游戏中另行安装相应版本的 KubeJS。不要把 `libs/` 内 JAR 再打入成品 JAR。此处源码更新尚未在你的电脑上重新编译/运行；本机 Android 测试构建因 NeoForge 构建依赖下载超时尚未完成。请在电脑上构建并于测试存档验证后再投入正式存档。

## 项目模块
- `registry/AureliumItems.java`：单个 Forge 物品 `aurelium:star_vault`；**不是**为每个包生成不同注册 ID。
- `storage/StarVaultItem.java`、`VaultCellHandler.java`、`VaultInventory.java`、`VaultWorldData.java`、`VaultBytes.java`：AE2 便携存储接口、BigInteger 数量、UUID、世界 SavedData。
- 便携终端插入物品需要 AE 电量：创造栏与 `AureliumCell.create()` 预置 10,000 AE（仅为使新元件可立即使用，并非最大电量）；其他来源的空电元件可通过 AE2 充能器或提供物品 FE 输入的充电设备补电；FE 输入由 NeoForge 能量能力转换到 AE2 有限电池，实际行为仍须测试。普通 AE2 能量卡显式注册（2 张上限）；AE2 基础充电速率现设为 2,147,483,647 AE/t（能源卡可继续提高所报告速率，实际速度还受充能器配置、网络供能与剩余容量影响）；FE 输入取消先前人为的每次 1,000,000 FE 限速，只接受电源请求和剩余 AE 空间所允许的 FE，NeoForge FE 单次接口仍使用 int；最大 AE 电量固定为 2,147,483,647 AE，**电量依然正常消耗与重新充电**。**大型能量卡来源 mod/注册 ID 尚未确定，此版不声称支持；请提供其准确 ID 或 mod JAR 以针对性兼容。**
- `api/AureliumPacks.java`：在 startup_scripts 声明各种内容物模板，不读取尚未初始化的游戏物品注册表；格式 `"64x minecraft:diamond"`，同 ID 合并，数量支持大整数字符串。
- `api/AureliumCellAPI.java`：`create("namespace:pack")` 返回带模板标记的元件栈；服务端实际访问元件时解析物品并向世界持久层灌装内容。复制模板可生成独立物品，但不要用命令硬拷已初始化的 UUID。
- `kubejs/AureliumKubeJSPlugin.java` + `src/main/resources/kubejs.plugins.txt`：仅在安装 KubeJS 时暴露 `AureliumPacks`、`AureliumCell`、`AureliumExport`、`AureliumInfinite`、`AureliumInfiniteCell` 脚本绑定；基础元件/存取/AE2 接入无须 KubeJS。
- `libs/` 现在含四个 **compileOnly** 依赖：AE2、KubeJS，以及用于可选互操作编译的 `neoecoae`、`data_energistics`（见下）。它们只用于**编译**，不打包进成品 JAR，也不是运行前置；运行时可选安装。
- `export/`、`api/AureliumExport.java`：宝匣数据导出（`/aurelium export`），见「导出宝匣数据」章节。
- `storage/InfiniteCell*.java`、`api/AureliumInfinite*.java`、`client/*Infinite*`：无限元件，见「无限元件」章节。
- `client/PinkTooltip.java`：鼠标悬停**文字提示框**粉色渐变边框。若另一个 UI 模组覆盖颜色，请检查事件优先级；不是物品格子自身的描边。
- `client/StarwishTooltip.java`：悬浮介绍按 Shift 逐字显现，默认标题和第一行樱粉逐字流转变色（玩家/KubeJS 自定义标题保持原样）；含从服务端轻量统计字段读取的等效已用字节（B 至 QiB；超过 QiB 显示完整的整数 QiB（小于 1 QiB 的余数仅在显示时省略，实际数量保留），不设容量上限）及物品/流体/其他类型数，空盘隐藏统计行。光环层 `rose_halo.png` 为 12 帧樱粉呼吸光环与环绕星点，作为模型 `layer5` 叠在便携元件贴图上；此为原版资源帧动画方案，光环范围受 16×16 贴图边界限制。
- `assets/aurelium/textures/item/ae_pink_*.png`：基于 AE2 19.2.17 的便携物品存储元件分层贴图重新着色，16×16 原版外形，额外追加 12 帧（3 tick/帧）的原版资源动画流光层 `ae_pink_flow.png` + `.mcmeta`；透明静态预览 `aurelium_icon_preview.png`，保留柔和透明度的动态 APNG `aurelium_icon_animation.png` 和透明 GIF `aurelium_icon_animation.gif` 均在工程根目录；三者只用于预览，不作为游戏贴图打包。AE2 分层贴图的原作者、链接与 LGPL-3.0 许可见 `third_party/ae2/ATTRIBUTION.md`。

## 元件内容悬停预览（新增，待电脑构建与游戏验证）

- 已在本工程增加 `network/VaultPreviewRequest.java`、`VaultPreviewPage.java`，普通悬停显示数量降序前 **5** 项；按住 Shift 每页展示 **10** 项，滚轮一次翻一整页；第二页只显示本页条目。悬停期间当前页明细与由服务端返回的等效字节/分类统计约每 **1 秒**刷新；物品 CustomData 统计作为未取得服务端响应时的兜底。物品、流体及第三方 AEKey 均按类型列举；页码及剩余项数有中英文文案。
- 服务端仅向持有或正在通过菜单访问该 UUID 元件的玩家返回一页数据（先校验玩家背包/副手/当前打开菜单的槽位），每页最多 10 条；重排结果在世界数据内按 UUID 缓存，存取后失效。客户端不直接扫描世界 SavedData，也不把完整清单写进物品 NBT。单页网络显示对异常超长数量会标记 `> 2^4096`，底层 BigInteger 数据不受影响。大量不同种类首次排序与高频变更仍有成本；箱子等已打开菜单里的槽位可请求明细；仅是 JEI 等纯展示样本则不会请求。
- 当前项目缺少完整 NeoForge/Minecraft 构建依赖，本机 `compileJava --offline` 仍卡在 `:createMinecraftArtifacts` 下载 `neoform-runtime:2.0.31`；**本预览功能尚未通过 Java 编译或目标游戏交互实测**。先在电脑 `gradlew.bat clean build`，有第一条编译报错时按报错修复。备份测试世界后检查滚轮与仓室/随身状态。

## KubeJS 示例
见 `examples/kubejs/startup_scripts/aurelium_demo.js`。将示例文件复制进整合包的 `kubejs/startup_scripts/` 后，在合适的 **server_scripts 服务器上下文**里调用 `AureliumCell.create('demo:celestial_supply')`，例如把返回 ItemStack 用作任务奖励或自定义指令的物品。模板登记阶段不读取世界或注册表；实际生成 ItemStack 应在物品注册完成后。

## 无限元件（本轮新增）

新增物品 `aurelium:infinite_cell`（樱粉星愿·无限匣），用 KubeJS 声明“哪些物品无限”。

**行为**（实现在 `storage/InfiniteCellInventory`）：
- **无限取出**：对已标记物品，`extract` 永远返回请求量，永不减少；`getAvailableStacks` 上报 `Long.MAX_VALUE`。
- **输入销毁**：标记物品 `insert` 返回“已接受”但不保存，送入即销毁（不增长计数）。
- **未标记物品**：既不存储也不提供。
- **无容量、无耗电**（`getIdleDrain()==0`）、`canFitInsideCell()==false`。

**终端显示**（`client/mixin/MEStorageScreenInfinityMixin`）：
- 格子数量：当该键上报量为 `Long.MAX_VALUE` 且被声明为无限 → 显示 `∞`；否则显示 AE2 常规数字（长上限缩写）。
- 悬停 tooltip 的数量行同样替换为粉色 `∞`。
- AE2 格子本就绘制“物品图标 + 数量”，所以显示为 ∞ 时即为“图标 + ∞、无名字”。

**KubeJS（`AureliumInfinite` + `AureliumInfiniteCell`）**：
```js
// 无限元件只有一个物品 id：aurelium:infinite_cell。每种“无限内容”是它上面的一份登记，
// 靠 NBT 标签区分，决定“哪些物品无限 + 显示什么名字”。

// 名字可选：传了就用自定义名，不传/传 null 就用物品默认名。
AureliumInfinite.register('demo:starlight', '星愿无限匣', 'minecraft:nether_star',
  ['minecraft:diamond', 'minecraft:emerald'])          // 自定义名字

AureliumInfinite.register('demo:plain', 'minecraft:diamond',
  ['minecraft:diamond'])                               // 不传名字 → 用默认名

// 第 5 个参数（可选）控制是否显示粉色介绍：省略/true = 显示；false = 只显示名字。
AureliumInfinite.register('demo:silent', 'minecraft:emerald',
  ['minecraft:emerald'], false)

// server_scripts 里发放：
// player.give(AureliumInfiniteCell.create('demo:starlight'))
```
Java 侧对应重载：`register(id, title, icon, entries[, describe])` 与 `register(id, icon, entries[, describe])`（不传名字）。

**内置预设（无需 KubeJS）**：mod 自带一份「无限混凝土」登记 `aurelium:concrete`，含**全部 16 色** `minecraft:*_concrete`，带自定义名 **「无限混凝土」**（`AureliumInfinite.CONCRETE_TITLE`）。它在 `Aurelium` 构造时经 `AureliumInfinite.registerDefaults()` 注册；模组专属创造栏（`aurelium:main`，见 `registry/AureliumTabs.java`）会直接给一份现成的该元件。Java 侧 `AureliumInfiniteCellAPI.create(AureliumInfinite.CONCRETE_ID)` 亦可获取。若 KubeJS 用同 id 重新注册，则覆盖为你的定义。

**外观**：`models/item/infinite_cell.json` 四层贴图（粉底盘 + LED + 亮粉边框 + 滚动流光 `infinite_pink_flow.png` 动画）。物品介绍为粉意文案 + 炫彩 `∞` 行（`client/InfiniteCellTooltip` + `client/InfiniteTooltip`）。

**物品介绍里的“图标 + ∞”**：悬停无限元件时，tooltip 会以**一排物品图标**展示被标记的物品，每个图标的**右下角叠加 `∞`**（像堆叠数量那样），而不是写物品名。实现为自定义 tooltip 组件 `InfiniteIconsTooltip`（数据）+ `ClientInfiniteIconsTooltip`（渲染，`renderItem` + `renderItemDecorations(font, icon, x, y, "∞")`），在 `RenderTooltipEvent.GatherComponents` 阶段注入；组件渲染工厂在 `AureliumClient` 注册到 mod 事件总线。

**内容只能通过 API/KubeJS 声明，不能在工作台标记**：`InfiniteCellItem` **有意不实现** `ICellWorkbenchItem` / `IBasicCellItem` / `getConfigInventory`，因此 AE2「元件工作台」会把它显示为不可编辑——玩家无法在工作台里手动“标记”哪些物品无限。无限内容**只能**通过内部/外部 API 或 KubeJS 声明：
```java
AureliumInfinite.register(id, title, icon, String[] items);        // 声明种类
AureliumInfiniteCellAPI.create(kindId);                            // 生成该种类的元件
AureliumInfiniteCellAPI.setKind(stack, kindId);                    // （等效，直接写 NBT）
```
（KubeJS 绑定名：`AureliumInfinite.register(...)` 与 `AureliumInfiniteCell.create(...)`。）

**在驱动器里的外观**：AE2 驱动器渲染元件用的是**专用的“元件机箱”模型**（`StorageCellModels`，按物品查表），不是物品自身模型。AURELIUM 通过 `client/AureliumCellModels`（`ModelEvent.RegisterAdditional`）为宝匣与无限元件各自注册了粉色系的机箱模型与贴图（`block/drive/cells/vault_cell`、`block/drive/cells/infinite_cell`）。未注册时会退化为 AE2 默认外观。注意 `StorageCellModels.registerModel` 对同一物品二次注册会抛异常，故用一次性守卫防止资源重载崩溃。

**数值安全**：多个无限元件会把同一物品的量在 AE2 的 `KeyCounter` 里**裸 long 相加（无饱和）**。因此无限元件上报 `Long.MAX_VALUE/64` 而非 `Long.MAX_VALUE`，避免叠加溢出为负数；客户端以“≥ 该值且该 id 被声明”判定为 ∞。

**注意（未实测）**：∞ 的判定用“上报量 ≥ `REPORTED_AMOUNT` + 该 id 被 KJS 声明”双条件。此系列改动**尚未编译、未游戏实测**，Mixin 注入点与模型注册在装 AE2 的环境需验证。

## 导出宝匣数据（供 KubeJS 注册）

命令 **`/aurelium export [名字]`**（仅玩家可执行，读取副手宝匣，副手没有则读主手）会把该宝匣的世界存储内容导出为三个文件，写到 **`<存档>/data/Aurelium/export/`**：

- `vault_<名字或UUID前8位>_<时间>.txt`：人类可读清单（UUID、导出时间、名字/电量、套娃层级、类型/数量合计；物品行可直接粘贴）。
- `vault_<...>.js`：**可直接放进 `kubejs/startup_scripts/` 的注册脚本**（套娃会展开为多个包）：
  ```js
  AureliumPacks.register(
    "aurelium:exported_xxxxxxxx",
    "导出的宝匣 xxxxxxxx",
    [
      "12345678901234567890x minecraft:diamond"
    ]
  );
  ```
- `vault_<...>.recipe.js`：配套的合成配方，需放进 `kubejs/server_scripts/`（放错到 startup 会「既不执行也不报错」）。
- 数量以**纯十进制字符串**写出，超过 `long` 也能精确回灌（`AureliumPacks.register` 内部用 `new BigInteger(字符串)`）。
- **所有 AE2 key 类型都支持**：物品写成裸 id，流体写成 `fluid:命名空间:id`，其他类型写成 `key:{...SNBT...}`（见 `storage/KeyCodec.java`）。清单与 `.js` 都会包含这些行。

KubeJS 也可直接调用：`AureliumExport.held(player, '文件名')`（server_scripts，传 `ServerPlayer`）返回 `[注册脚本路径, 配方路径, 清单路径]`；`AureliumExport.directory()` 返回导出目录。示例见 `examples/kubejs/server_scripts/aurelium_export_demo.js`。

实现：`export/VaultExporter.java`（核心，服务端主线程读 `VaultWorldData`）、`export/AureliumCommands.java`（命令）、`api/AureliumExport.java`（KJS 门面）。

> **注意**：导出目录取自 `VaultWorldData.cellDirectory(server)` 下的 `export/` 子目录，即 `<存档>/data/Aurelium/export/`（不再使用 FMLPaths.GAMEDIR）。该实现已在电脑上通过 `./gradlew build` 编译；游戏内行为仍未实测。

## AEKey 数量与 I/O 边界

当前工程已用 `AEKey` 作为资源身份、`BigInteger` 作为持久化数量；AE2 19.2.17 的 `MEStorage.insert/extract(AEKey, long, ...)` 单次请求和返回仍是 `long`。`Long.MAX_VALUE × 1024` 超出该类型，不能直接乘在读写请求/返回值上，否则会溢出或导致凭空复制/丢失资源。一次调用最多准确处理请求的数量；多次调用的累计数量可以超过 `long`。实际每 tick 吞吐取决于上游 AE2 接口、供能/设备行为和服务端性能，不能宣称使用 AEKey 即可跳过传输接口限制。

### 数值规模与 QiB 之后（本轮新增）

宝匣内部无容量上限（`canFitInsideCell()==false`、世界数据用 BigInteger 记录），所以**存到 QiB 以上不会丢数据**。但显示与传输链路上原本有两个隐患，本轮已修：

1. **统计行超长（已修，最危险）**：`VaultBytes.format` 原来的单位表止于 QiB（2^100 B），超过后不再换单位，整数部分会无限变长。服务端原本把 `page.usedBytes().toString()` 直接塞进 `writeUtf(..., 1235)` 的字段——一旦十进制超过 1235 字符就会**抛异常，可能把查看者踢下线**。现在：
   - `VaultBytes.format` 在超出最大单位且整数超过 24 位时改用**有界科学计数**（如 `1.044e+1233 B`），任意规模输出都 ≤ 35 字符；
   - 服务端只发送**已格式化的有界字符串**，客户端直接显示，不再二次解析原始十进制。
2. **单行数量阈值（已修）**：宝匣预览里每行的数量若达到 **10^26（比 `Long.MAX_VALUE` 的 19 位多 8 位，即 27 位十进制）及以上**，服务端发送 `infinity` 令牌，客户端显示为粉色符号 **`∞`**；低于该阈值则完整显示数字。
   - **注意**：这只是**显示简写**，表示“数量过大、用 ∞ 代替显示”，**并不代表宝匣是真的无限盘**；超过 10^26 的数量仍以 BigInteger 精确记录。真正的无限来源是 `aurelium:infinite_cell`（无限元件）。
   - 旧令牌 `large` 仍作兼容。

因此：“超过 QiB”的实际表现是——**能存、能累加、能存取**；统计行显示为科学计数或 QiB 整数值；单个堆叠达到 10^26 及以上时该行显示为 `∞`。底层 BigInteger 与写盘数据不被改动。

### 与 NeoEcoAE / Data_Energistics 的超 long 对接（新增）

参考两个可达“单次超过 `long`”的 AE2 附属实现（详见 `SUPERLONG_TRANSPORT_NOTES.md`）：

- **NeoEcoAE** 提供写侧 BigInteger 契约 `ECOBigIntegerStorage.insertBigInteger(AEKey, BigInteger, …)` 与读侧 `ExactAmountSource`，并 Mixin AE2 `NetworkStorage` 做网格级聚合；它自己的磁盘是**裸挂载 cell**。
- **Data_Energistics** 提供读侧 `ExactExtractableStorage.exactAvailable(AEKey, IActionSource)` 与带回滚的 `transferFinite`；但它的**写路径在 AE2 接口层仍是 long**，超 long 数值只存在于其内部 `Map<AEKey, BigInteger>` 台账。

AURELIUM 宝匣内部本就是 `Map<AEKey, BigInteger>`，因此：

- 新增 `compat/neoecoae/`：当 NeoEcoAE 存在时，让 AE2 的库存包装 `DelegatingMEInventory`（磁盘 `DriveWatcher`、箱子/存储总线 `MEInventoryHandler` 的基类）与便携终端的 `SupplierStorage` 实现 `ECOBigIntegerStorage` + `ExactAmountSource`，把宝匣真实数量交给 NeoEcoAE 通道。**写**按 `ECOBigIntegerStorage.insert` 语义：≤ long 走原生接口，> long 整段交给宝匣 `creditExact`，不乘系数、不拆批。
- 新增 `compat/dataenergistics/`：当 DE 存在时，让同样的包装实现 `ExactExtractableStorage`（只读），使 DE 的精确核算看到宝匣真实 BigInteger。
- 两个互操作包都在对应模组缺失时**完全不加载**（`neoforge.mods.toml` 的 `requiredMods` + 各自的 Mixin 插件双重把关），核心 `storage/` 包不 import 任何 NeoEcoAE/DE 类型，脱离它们仍可编译运行。

本轮仍**未**运行构建、**未**游戏实测；互操作桥接是按两套 JAR 的静态接口编写的，需在装有对应模组的环境验证。按用户要求未运行 Gradle。

本轮另将 `VaultInventory` 的 long `insert/extract` 重构为共享的 BigInteger 核心（`creditExact`/`debitExact`），返回值语义不变：`insert` 仍返回接受量、`extract` 仍返回实际取走量，`VaultWorldData.putAmount` 仍设脏世界数据，`publishSummary` 仅在摘要实际变化时通知一次保存提供者。属于等价重构，**不是把转移量放大，也没有测得固定速度提升**。

## 边界与待验证
- 设计上不限种类，服务器存储使用 BigInteger；AE2 读写接口每次操作仍然是 Java `long`，界面展示也会截断到 Long.MAX_VALUE。内存/硬盘/网络仍有限。
- 本轮将便携物品从 AE2 `PortableCellItem` 改成 `AbstractPortableCell`，避免 `IBasicCellItem` 在菜单里强制仅展示物品；后端可接收所有**已经注册到 AE2 的 AEKey 类型**。物品与 AE2 流体键直接支持；FE / Mek 化学品等需要 Applied Flux / Applied Mekanistics 等对应模组先注册键类型并接入 AE2 网络。普通 FE 物品充电输入与“把 FE 当作网络存储资源”是两个不同功能。摘要按 AE2 每种资源 `amountPerByte` 换算**等效字节数**（每类型向上取整），仅作显示，不代表容量限额或磁盘序列化文件实际大小；底层保留 BigInteger 数量。不直接依赖 ExtendedAE-Plus 的实现。
- 后端文件在世界 `data/aurelium_vault_cells.dat`，元件 `minecraft:custom_data` 带 UUID 和服务端维护的物品数量/种类摘要；物品明细仍在世界数据中。跨世界复制单个物品不会复制世界数据。请先在测试存档验证备份/迁移/满容量行为。
- `gradle.properties` 中的 `neo_version` 只决定**编译用的目标版本**；`neoforge.mods.toml` 对 NeoForge 使用合法开放范围 `[0,)`，不锁定补丁版。放宽元数据不等于跨所有版本兼容：若某版本缺少 API，仍可能加载或运行失败，应在目标版本测试。KubeJS 的 `compileOnly` 依赖只用于编译可选桥接，**不是运行前置**。
- **贴图使用并修改了 AE2 原版便携元件资源**。本工程根目录 MIT 仅适用于自行编写的代码与原创内容；AE2 派生贴图维持 LGPL-3.0，按照 `third_party/ae2/ATTRIBUTION.md` 保留声明与许可全文，不能按 MIT 单独声称拥有 AE2 原贴图。KubeJS / NeoForge 仍有各自许可证。
