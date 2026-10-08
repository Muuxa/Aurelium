# AE2 派生贴图：来源与许可证

本目录涉及 AURELIUM 模组中**基于 AE2 原版美术资源的派生贴图**。AURELIUM 的 Java 代码为独立
编写，不含 AE2 源码。

## 上游项目

- **Applied Energistics 2**（Team Applied Energistics）
- 仓库：https://github.com/AppliedEnergistics/Applied-Energistics-2
- 参考版本：`neoforge/v19.2.17`（`appliedenergistics2-19.2.17.jar`）
- 上游许可：**GNU LGPL-3.0**，https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/neoforge/v19.2.17/LICENSE

## 派生资产

以下贴图以上游 `assets/ae2/textures/item/` 中的便携元件分层贴图为像素轮廓基础，
重新着色为柔和粉紫色：

- `src/main/resources/assets/aurelium/textures/item/ae_pink_base.png`
  （派生自 `portable_cell_item_housing.png`）
- `src/main/resources/assets/aurelium/textures/item/ae_pink_led.png`
  （派生自 `portable_cell_led.png`）
- `src/main/resources/assets/aurelium/textures/item/ae_pink_screen.png`
  （派生自 `portable_cell_screen.png`）
- `src/main/resources/assets/aurelium/textures/item/ae_pink_side.png`
  （派生自 `portable_cell_side_256k.png`）

组合模型：`src/main/resources/assets/aurelium/models/item/star_vault.json`
（原版 256k 便携物品元件分层模型见上游 `assets/ae2/models/item/portable_item_cell_256k.json`）。

## AURELIUM 原创资产（非 AE2 派生）

以下为 AURELIUM 自行绘制，不适用 LGPL-3.0 的派生条款：

- `ae_pink_flow.png` + `.mcmeta`：新绘的樱粉流光高光动画层（12 帧 × 3 tick）。
- `rose_halo.png` + `.mcmeta`：新绘的呼吸光环与环绕星点层。
- `infinite_*`、`pattern_tool_*_led.png` 等其余 AURELIUM 贴图。

## 许可义务

AE2 派生贴图（`ae_pink_{base,led,screen,side}.png`）按 **LGPL-3.0** 分发。本目录保留：

- `LICENSE`（LGPL-3.0 全文）
- `GPL-3.txt`（LGPL-3.0 引用的 GPL-3.0 全文）
- 本说明文件

上述文件随 AURELIUM 的 jar 一同分发（`META-INF/aurelium/third_party/ae2/`）。
工程根目录的 MIT 许可**仅适用于自行编写的代码与原创资产**，不覆盖 AE2 派生贴图。

## 修改记录

- 将外壳、侧边、LED、屏幕四层重新着色为粉紫色系。
- 新增樱粉流光动画层与光环层。

## 运行前置

玩家需要自行安装 Applied Energistics 2。本工程 `libs/` 下的 AE2 JAR 仅用于编译，
不随成品内置。
