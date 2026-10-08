# Aurelium · 樱粉星愿

A rose-pink Applied Energistics 2 storage expansion for Minecraft 1.21.1 / NeoForge.

Aurelium 是一个樱粉主题的 **Applied Energistics 2** 扩展，围绕“没有容量上限”和“取之不尽”两个点设计：
万物宝匣用 `BigInteger` 计数、没有上限；无限元件与无限磁盘永不耗尽；样板工具一键搬运整个供应器的样板。

---

## 特性

### 万物宝匣（Star Vault）

物品 `aurelium:star_vault`，一把粉色的便携存储终端。

- 数量用 **BigInteger** 记录，**没有容量上限**，存到 QiB（2^100 字节）以上依然精确，不会溢出、丢数或凭空复制。
- 支持全部 AE2 键类型：物品、流体，以及第三方模组注册的键（FE、化学品等）。
- 悬停即可浏览内容：按 Shift 逐页翻，滚轮翻整页，数量降序；过大的单行数量显示为粉色 `∞`。
- 宝匣可以装宝匣（默认 5 层，服务器配置可调），也能收纳无限元件与无限磁盘。

### 无限元件（Infinite Cell）

物品 `aurelium:infinite_cell`，用 KubeJS 或 API 声明“哪些物品无限”，不需要在工作台手动标记。

- 已标记物品：取出永远成功、永不减少；送入即销毁。
- AE2 终端里显示为 `∞`。
- 悬停时以“一排物品图标 + 右下角 `∞`”展示被标记的内容。
- 自带一个「无限混凝土」预设（`aurelium:infinite_concrete`，16 色混凝土）。

### 无限磁盘（Infinite Disk）

物品 `aurelium:infinite_disk`，装在 AE2 驱动器槽位里的无限元件：同样 BigInteger 数量、永不溢出。

### 样板剪切 / 复制工具（Pattern Cut / Copy Tools）

物品 `aurelium:pattern_cut_tool` / `aurelium:pattern_copy_tool`，两把“内存卡”风格的工具，用于批量搬运样板。

- 潜行 + 右键容器：剪切 / 复制其中所有样板。
- 右键容器：粘贴（剪切工具粘贴后消耗；复制工具永不消耗，可反复贴）。
- 潜行 + 右键空气：把已存样板放回地面，绝不静默丢失。
- 粘贴前会先读目标剩余容量：装得下一次贴完，装不下只贴能填的部分，剩余留在工具里。
- 目标已有相同主产物的样板时默认跳过；可开“替换模式”换出后再贴。
- 数据存在物品自身 NBT（gzip 压缩），可跨存档携带。
- 兼容常见样板容器：AE2 样板供应器（方块 / 线缆部件）、ExtendedAE 装配矩阵、ExtendedAE-Plus 超级装配矩阵、闪电科技（AE2LT）、NeoEcoAE 样板总线、无用之物等。

### 进阶端口（Advanced IO Port）

方块 `aurelium:advance_io_port`，纯 AE2 的 IO 端口（无需 ExtendedAE）。

- 每 tick 工作一次（不像原版端口会降速到 1~5 间隔）。
- 单键每 tick 最多搬运 `256 × Long.MAX_VALUE`，双向都适用；继承 AE2 的 EMPTY / FILL 操作模式语义。

---

## 依赖

| | |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1+ |
| **必需** | Applied Energistics 2 **19.2.17+** |
| 可选 | KubeJS（脚本注册元件包 / 无限元件时） |
| 可选 | NeoEcoAE、Data_Energistics（超 long 精确传输 / 读取） |
| 可选 | ExtendedAE / ExtendedAE-Plus / 闪电科技 / 无用之物等（样板容器兼容） |

需要 Java 21。

---

## 安装

把 `Aurelium-<版本>.jar` 放进 `mods/` 目录，并确保已安装 Applied Energistics 2（以及它自身的 GuideME 依赖）。不包含 AE2 本体。

---

## 构建

需要 JDK 21：

```bash
gradlew.bat build     # Windows
./gradlew build       # Linux / macOS
```

产物在 `build/libs/`。`libs/` 里的 jar 只是**编译期**依赖（`compileOnly`），不会被打进成品 jar，也不是运行前置。

---

## 命令

- `/aurelium export [名字] [replace]`
  - 手持宝匣：导出为 KubeJS 注册脚本（数据 / 配方 / 清单），写到 `<存档>/data/Aurelium/export/`。
  - 手持样板工具：导出为仅注册该工具的脚本。
  - `replace` 为布尔值（默认 `false`）：是否给导出的样板工具打上“替换模式”标记。
- `/aurelium infinite [kind]`
  - 诊断已登记的无限元件种类：物品是否已注册、每个条目能否解析成 AE2 键。

---

## 配置

`config/aurelium-server.toml`：

- `vault.max_vault_nest`：宝匣最大嵌套层数，默认 `5`。
- `pattern_tool.cut_limit`：剪切工具单次最多剪走多少个样板，默认 `10000`。

---

## KubeJS 脚本

在 `kubejs/startup_scripts/` 里声明无限元件：

```js
AureliumInfinite.register({
  id:       'demo:starlight',          // kind id
  item:     'demo:starlight_cell',     // 承载它的物品 id
  title:    '星愿无限匣',              // 可选
  icon:     'minecraft:nether_star',   // 可选
  infinite: ['minecraft:diamond', 'minecraft:emerald'],
  describe: true,                      // 可选：显示粉色介绍
  shimmer:  true                       // 可选：名字炫彩
});

StartupEvents.registry('item', event => {
  event.create('demo:starlight_cell', 'aurelium:infinite_cell').kind('demo:starlight');
});
```

在 `kubejs/startup_scripts/` 里声明“内容包”（一组物品打包成一个宝匣）：

```js
AureliumPacks.register('demo:supply', '星穹补给', ['64x minecraft:diamond']);
```

更多用法见 `examples/kubejs/`。

---

## 许可 / 致谢

- 自写代码与原创内容：MIT（见 `LICENSE`）。
- AE2 派生贴图：LGPL-3.0，原作者、链接与许可全文见 `third_party/ae2/ATTRIBUTION.md`。

---

## 说明

- 宝匣的数量存储无上限，但 AE2 单次传输接口仍是 `long`：单次调用的上限不变，累计数量可以超过 `long`。
- 之前的“2i”彩蛋内容已从本模组移除，独立为 **II Technology**（`iitechnology`）这个单独 mod。
- 使用前请备份存档；边界场景（超大数值、跨模组联动）欢迎反馈崩溃报告与复现步骤。