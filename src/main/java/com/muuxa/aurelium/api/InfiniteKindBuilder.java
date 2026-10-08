package com.muuxa.aurelium.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 流式（fluent）构建器：用来声明一个「无限元件种类（kind）」。
 *
 * <p>相比 {@link AureliumInfinite#register(String, String, String, String, String[], boolean, boolean, String, String)}
 * 那串位置参数，这个构建器把每一项都写成命名方法，脚本里读起来一目了然：</p>
 *
 * <pre>{@code
 * // kubejs/startup_scripts/xxx.js
 * AureliumInfinite.builder('demo:starlight')   // kind 唯一 id（命名空间:名字）
 *     .item('demo:starlight_cell')             // 【必填】承载它的元件物品 id
 *     .title('星愿无限匣')                      // 自定义名字（不填 = 用物品默认名）
 *     .icon('minecraft:nether_star')           // tooltip 标题图标
 *     .infinite('minecraft:diamond',           // ↓ 这些 id 都是「无限」的
 *               'minecraft:emerald',
 *               'minecraft:gold_ingot')
 *     .model('demo:item/starlight_cell')       // 【可选】自定义模型（见下）
 *     .describe(true)                          // 【可选】是否显示粉色介绍（默认 true）
 *     .shimmer(true)                           // 【可选】名字是否炫彩（默认 true）
 *     .register();                             // 最后一步：登记
 * }</pre>
 *
 * <h2>自定义模型 / 贴图</h2>
 * <ul>
 *   <li>{@link #model(String)}：填一个「模型 id」，形如 {@code "命名空间:item/文件名"}。
 *       资源包需提供 {@code assets/<命名空间>/models/item/<文件名>.json}。</li>
 *   <li>{@link #texture(String)}：填一个「贴图 id」，形如 {@code "命名空间:item/文件名"}，
 *       同样按 baked model id 解析（需要一个引用该贴图的模型 JSON）。</li>
 *   <li>两者都给时 {@code model} 优先；都不给时用内置的粉色元件外观。</li>
 * </ul>
 *
 * <h2>无限内容（entries）写法</h2>
 * <ul>
 *   <li>物品：{@code "minecraft:diamond"}</li>
 *   <li>流体：{@code "fluid:minecraft:water"}（流体必须带 {@code fluid:} 前缀）</li>
 *   <li>可带数量前缀（会被忽略，仅作可读性）：{@code "64x minecraft:diamond"}</li>
 *   <li>同一个 kind 里重复的 id 会自动去重。</li>
 * </ul>
 *
 * @see AureliumInfinite#builder(String)
 * @see InfiniteKindSpec
 */
public final class InfiniteKindBuilder {

    private final String id;
    private String itemId;
    private String title;
    private String icon;
    private final List<String> entries = new ArrayList<>();
    private boolean describe = true;
    private boolean shimmer = true;
    private String model;
    private String texture;

    InfiniteKindBuilder(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("无限元件 kind id 不能为空");
        }
        this.id = id.trim();
    }

    /**
     * 【必填】声明「哪个物品是这个 kind 的元件」。
     *
     * <p>这个物品必须是在 {@code StartupEvents.registry('item', ...)} 里用类型
     * {@code aurelium:infinite_cell} 创建出来的（即 {@code InfiniteCellItem}）。
     * 一个 kind 对应一个独立物品 id，这样才能各自有自己的名字/贴图/模型。</p>
     */
    public InfiniteKindBuilder item(String itemId) {
        this.itemId = itemId == null ? null : itemId.trim();
        return this;
    }

    /** 自定义显示名；不调用（或传 {@code null}/空白）则使用元件物品的默认名字。 */
    public InfiniteKindBuilder title(String title) {
        this.title = title;
        return this;
    }

    /** tooltip 标题里显示的小图标（物品 id）；不填则用第一个无限条目的图标。 */
    public InfiniteKindBuilder icon(String iconId) {
        this.icon = iconId == null ? null : iconId.trim();
        return this;
    }

    /**
     * 追加一个或多个「无限」条目（物品/流体 id）。可多次调用，会累加。
     *
     * <pre>{@code
     * .infinite('minecraft:diamond')
     * .infinite('minecraft:emerald', 'minecraft:gold_ingot')
     * }</pre>
     */
    public InfiniteKindBuilder infinite(String... tokens) {
        if (tokens != null) {
            for (String t : tokens) {
                if (t != null && !t.isBlank()) entries.add(t.trim());
            }
        }
        return this;
    }

    /** 批量追加：{@code .infiniteAll(['minecraft:diamond', 'minecraft:emerald'])}。 */
    public InfiniteKindBuilder infiniteAll(Collection<String> tokens) {
        if (tokens != null) {
            for (String t : tokens) {
                if (t != null && !t.isBlank()) entries.add(t.trim());
            }
        }
        return this;
    }

    /** 是否显示粉色介绍块（默认 {@code true}）。 */
    public InfiniteKindBuilder describe(boolean describe) {
        this.describe = describe;
        return this;
    }

    /** 名字是否做粉色炫彩（默认 {@code true}）。 */
    public InfiniteKindBuilder shimmer(boolean shimmer) {
        this.shimmer = shimmer;
        return this;
    }

    /**
     * 【可选】自定义模型 id，形如 {@code "demo:item/my_cell"}。
     * 资源包需提供 {@code assets/demo/models/item/my_cell.json}。给定时优先于 {@link #texture(String)}。
     */
    public InfiniteKindBuilder model(String modelId) {
        this.model = modelId;
        return this;
    }

    /**
     * 【可选】自定义贴图 id，形如 {@code "demo:item/my_cell"}。
     * 按 baked model id 解析（需要一个引用该贴图的模型 JSON）；未给 {@link #model(String)} 时生效。
     */
    public InfiniteKindBuilder texture(String textureId) {
        this.texture = textureId;
        return this;
    }

    /**
     * 执行登记，返回不可变的 {@link InfiniteKindSpec}。
     *
     * @throws IllegalArgumentException 缺 itemId、没有无限条目、或 id 格式非法时抛出
     */
    public InfiniteKindSpec register() {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException(
                    "无限元件 kind '" + id + "' 必须调用 .item('你的元件物品id')");
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "无限元件 kind '" + id + "' 至少要有一个 .infinite(...) 条目");
        }
        return AureliumInfinite.register(id, itemId, title, icon,
                entries.toArray(new String[0]), describe, shimmer, model, texture);
    }
}