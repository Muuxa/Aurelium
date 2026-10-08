package com.muuxa.aurelium.api;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative registry for AURELIUM infinite cells, filled from KubeJS startup scripts.
 *
 * <p>Each declaration maps a kind id (e.g. {@code "aurelium:celestial"}) to <b>the item id
 * that backs it</b>, a title, an icon, and the set of item ids that should be infinite on that
 * cell. The backing item id is <b>required</b>: every kind names its own element item (created
 * in {@code StartupEvents.registry('item', ...)} with type {@code aurelium:infinite_cell}).
 * The kind is stored on the cell item as a NBT tag, so the mapping stays data-driven.</p>
 *
 * <p>Declarations are cheap and registry-free: they only hold strings. Actual item resolution
 * happens lazily when a cell is opened/used.</p>
 */
public final class AureliumInfinite {
    private static final Logger LOGGER = LoggerFactory.getLogger("Aurelium/Infinite");

    private static final Map<String, InfiniteKindSpec> SPECS = new LinkedHashMap<>();

    private AureliumInfinite() {}

    /**
     * Declare an infinite kind.
     *
     * @param id       kind id, e.g. {@code "aurelium:celestial"}
     * @param itemId   the backing item id (required), e.g. {@code "mypack:celestial_cell"}
     * @param title    custom display name; pass {@code null}/blank to keep the item's default name
     * @param icon     a {@code namespace:item} id used as the cell's headline icon
     * @param entries  item/fluid/key tokens (or {@code "Nx ..."}) that become infinite
     */
    public static synchronized InfiniteKindSpec register(String id, String itemId, String title,
                                                         String icon, String[] entries) {
        return register(id, itemId, title, icon, entries, true, true, null, null);
    }

    /** As {@link #register(String, String, String, String, String[])} but choosing the description. */
    public static synchronized InfiniteKindSpec register(String id, String itemId, String title,
                                                         String icon, String[] entries, boolean describe) {
        return register(id, itemId, title, icon, entries, describe, true, null, null);
    }

    /** As above, also choosing whether the item name colour-cycles (shimmers). */
    public static synchronized InfiniteKindSpec register(String id, String itemId, String title,
                                                         String icon, String[] entries,
                                                         boolean describe, boolean shimmer) {
        return register(id, itemId, title, icon, entries, describe, shimmer, null, null);
    }

    /**
     * Full declaration.
     *
     * @param describe  {@code true} shows the pink flavour text + "icon + ∞" row in the tooltip
     * @param shimmer   {@code true} colour-cycles the item name in the tooltip (like the vault)
     * @param model     optional full model id (e.g. {@code "mypack:item/my_cell"}) or {@code null}
     * @param texture   optional texture id rendered as a flat icon, or {@code null}
     */
    public static synchronized InfiniteKindSpec register(String id, String itemId, String title,
                                                         String icon, String[] entries,
                                                         boolean describe, boolean shimmer,
                                                         String model, String texture) {
        if (id == null || ResourceLocation.tryParse(id) == null) {
            throw new IllegalArgumentException("Invalid infinite kind id: " + id);
        }
        if (itemId == null || ResourceLocation.tryParse(itemId) == null) {
            throw new IllegalArgumentException(
                    "Infinite kind '" + id + "' must declare a valid backing item id (got: " + itemId + ")");
        }
        if (entries == null || entries.length == 0) {
            throw new IllegalArgumentException(
                    "Infinite kind '" + id + "' has no entries. Pass 'infinite: [...]' (a JS array) — "
                            + "each element is an item id ('minecraft:diamond'), 'fluid:<id>', or 'key:<SNBT>'.");
        }
        if (model != null && !model.isBlank() && ResourceLocation.tryParse(model) == null) {
            throw new IllegalArgumentException("Invalid model id: " + model);
        }
        if (texture != null && !texture.isBlank() && ResourceLocation.tryParse(texture) == null) {
            throw new IllegalArgumentException("Invalid texture id: " + texture);
        }
        List<String> items = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null) throw new IllegalArgumentException("Null entry in " + id);
            String token = stripCount(raw.trim());
            if (!com.muuxa.aurelium.storage.KeyCodec.isValidToken(token)) {
                throw new IllegalArgumentException(
                        "Bad infinite entry in '" + id + "': " + raw
                                + "  ->  valid forms are an item id ('minecraft:diamond'), a fluid"
                                + " ('fluid:minecraft:water'), or a generic key ('key:<SNBT>')."
                                + " (If you passed an array and see the whole array here, the array"
                                + " itself was not recognised.)");
            }
            if (!items.contains(token)) items.add(token);
        }
        String iconId = (icon == null || ResourceLocation.tryParse(icon) == null)
                ? stripCount(items.get(0)) : icon;
        // title == null/blank is preserved so the item falls back to its default name.
        String resolvedTitle = (title == null || title.isBlank()) ? null : title;
        InfiniteKindSpec spec = new InfiniteKindSpec(id, itemId, resolvedTitle, iconId, items,
                describe, shimmer, model, texture);
        SPECS.put(id, spec);
        LOGGER.info("已登记无限元件种类 '{}'（承载物品 '{}'，{} 项：{}）",
                id, itemId, items.size(), items);
        return spec;
    }

    /** Accepts both {@code "minecraft:diamond"} and {@code "64x minecraft:diamond"}. */
    private static String stripCount(String raw) {
        int x = raw.indexOf('x');
        if (x > 0) {
            String head = raw.substring(0, x).trim();
            if (!head.isEmpty() && head.chars().allMatch(Character::isDigit)) {
                return raw.substring(x + 1).trim();
            }
        }
        return raw;
    }

    public static synchronized InfiniteKindSpec get(String id) { return SPECS.get(id); }

    // --- Fluent entry points (preferred from KubeJS) ---

    /**
     * 开始用流式构建器声明一个无限元件 kind（推荐写法）。链式设置完调用 {@code .register()}。
     *
     * <pre>{@code
     * AureliumInfinite.builder('demo:starlight')
     *     .item('demo:starlight_cell')
     *     .title('星愿无限匣')
     *     .icon('minecraft:nether_star')
     *     .infinite('minecraft:diamond', 'minecraft:emerald')
     *     .register();
     * }</pre>
     */
    public static InfiniteKindBuilder builder(String id) {
        return new InfiniteKindBuilder(id);
    }

    /**
     * 一行式最简声明：给 kind id、元件物品 id 和一组无限条目，其余用默认值
     * （名字用物品默认名、显示粉色介绍、名字炫彩、无自定义模型）。
     *
     * <pre>{@code
     * AureliumInfinite.simple('demo:iron', 'demo:iron_cell',
     *     ['minecraft:iron_ingot', 'minecraft:iron_block']);
     * }</pre>
     *
     * <p>想自定义名字/模型等请改用 {@link #builder(String)}。</p>
     */
    public static synchronized InfiniteKindSpec simple(String id, String itemId, String[] entries) {
        return register(id, itemId, null, null, entries);
    }

    /** {@link #simple} 的 Java 便捷重载（List 版）。 */
    public static synchronized InfiniteKindSpec simple(String id, String itemId, java.util.List<String> entries) {
        return register(id, itemId, null, null, entries == null ? new String[0] : entries.toArray(new String[0]));
    }

    // --- Named-key (object) declaration — the "high-version item component" style ---

    /**
     * 用「具名键对象」声明一个无限元件（类似高版本物品组件的写法，一眼看清哪项是哪项）。
     *
     * <pre>{@code
     * // kubejs/startup_scripts/xxx.js
     * AureliumInfinite.register({
     *   id:      'demo:starlight',            // 【必填】kind 唯一 id
     *   item:    'demo:starlight_cell',       // 【必填】承载它的元件物品 id
     *   title:   '星愿无限匣',                 // 可选：名字（省略 = 物品默认名）
     *   icon:    'minecraft:nether_star',     // 可选：tooltip 图标（省略 = 第一个无限条目）
     *   infinite: [                           // 【必填】哪些东西无限
     *     'minecraft:diamond',
     *     'minecraft:emerald',
     *     'fluid:minecraft:water'             // 流体记得写 fluid: 前缀
     *   ],
     *   model:   'demo:item/starlight_cell',  // 可选：自定义模型 id
     *   texture: null,                        // 可选：自定义贴图 id（model 优先）
     *   describe: true,                       // 可选：是否显示粉色介绍（默认 true）
     *   shimmer:  true                        // 可选：名字是否炫彩（默认 true）
     * });
     * }</pre>
     *
     * <p>只支持这些键；未知键会被忽略（不报错）。必填：{@code id}、{@code item}、{@code infinite}。</p>
     */
    public static synchronized InfiniteKindSpec register(Object options) {
        if (options == null) {
            throw new IllegalArgumentException("无限元件选项对象不能为 null");
        }
        String id = optString(options, "id", null);
        String item = optString(options, "item", null);
        String title = optString(options, "title", null);
        String icon = optString(options, "icon", null);
        String model = optString(options, "model", null);
        String texture = optString(options, "texture", null);
        boolean describe = optBool(options, "describe", true);
        boolean shimmer = optBool(options, "shimmer", true);

        List<String> infinite = optStringList(member(options, "infinite"));
        if (infinite.isEmpty()) infinite = optStringList(member(options, "entries")); // 兼容别名
        if (infinite.isEmpty()) {
            throw new IllegalArgumentException("无限元件 '" + id + "' 缺少 infinite: [...]（哪些东西无限）"
                    + "  | 诊断: " + debug(options));
        }

        return register(id, item, title, icon, infinite.toArray(new String[0]),
                describe, shimmer, model, texture);
    }

    private static String optString(Object options, String key, String fallback) {
        Object v = member(options, key);
        if (v == null) return fallback;
        String s = v.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static boolean optBool(Object options, String key, boolean fallback) {
        Object v = member(options, key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    /**
     * Read one named member from an options value. Works for a Java {@link Map} (the normal case)
     * <b>and</b> for a raw KubeJS/Rhino JS object, which is not a {@code Map} at all: it is a
     * {@code Scriptable}, whose members are read through {@code get(String,Scriptable)} /
     * {@code getMember(String)} / {@code get(String)} / {@code getProperty(String)}. Everything is
     * done reflectively so this class never has to reference Rhino at compile time.
     */
    private static Object member(Object options, String key) {
        if (options == null) return null;
        if (options instanceof Map<?, ?> m) {
            return m.get(key);
        }
        // Rhino Scriptable: get(String, Scriptable)  (start = the object itself)
        Object viaTwoArg = invokeStringMethod(options, key, "get");
        if (viaTwoArg != null) return viaTwoArg;
        // Single-arg accessors used by various script object wrappers.
        for (String name : new String[] { "getMember", "get", "getProperty", "getValue" }) {
            Object v = invokeStringMethod(options, key, name);
            if (v != null) return v;
        }
        // Plain bean getter (getId() / getItem() ...) as a last resort.
        if (key.length() > 0) {
            String getter = "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            try {
                var m = options.getClass().getMethod(getter);
                return m.invoke(options);
            } catch (ReflectiveOperationException ignored) {
                // not a bean either
            }
        }
        return null;
    }

    /** True when the options value actually declares {@code key} (Map or Scriptable). */
    private static boolean hasMember(Object options, String key) {
        if (options == null) return false;
        if (options instanceof Map<?, ?> m) return m.containsKey(key);
        for (String name : new String[] { "has", "hasMember", "containsKey", "contains" }) {
            try {
                var mm = options.getClass().getMethod(name, String.class);
                Object r = mm.invoke(options, key);
                if (r instanceof Boolean b) return b;
            } catch (ReflectiveOperationException ignored) {
                // try the next name
            }
        }
        return member(options, key) != null;
    }

    /** Call a {@code name(String)} or {@code name(String, X)} method reflectively; null if absent. */
    private static Object invokeStringMethod(Object options, String key, String methodName) {
        for (var m : options.getClass().getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 1 && p[0] == String.class) {
                    return m.invoke(options, key);
                }
                if (p.length == 2 && p[0] == String.class) {
                    Object arg2 = p[1].isInstance(options) ? options : null;
                    if (arg2 == null) continue;
                    return m.invoke(options, key, arg2);
                }
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // try the next overload
            }
        }
        return null;
    }

    /**
     * Diagnostic helper, callable from KubeJS:
     * {@code console.info(AureliumInfinite.debug({ id:'a:b', infinite:['minecraft:diamond'] }))}.
     * Prints the object's Java class and the members this code can actually see, so a
     * "挂不上" problem becomes obvious (e.g. {@code id=null} means members are unreadable).
     */
    public static String debug(Object options) {
        if (options == null) return "options=null";
        StringBuilder sb = new StringBuilder();
        sb.append("class=").append(options.getClass().getName());
        if (options instanceof Map<?, ?> m) {
            sb.append(" mapKeys=").append(m.keySet());
        }
        for (String k : new String[] { "id", "item", "infinite", "entries" }) {
            sb.append(" ").append(k).append("=");
            Object v = member(options, k);
            sb.append(v == null ? "<null>" : (v.getClass().getSimpleName() + ":" + v));
        }
        sb.append(" methods=[");
        for (var m : options.getClass().getMethods()) {
            String n = m.getName();
            if (n.startsWith("get") || n.startsWith("has") || n.equals("size") || n.equals("length")) {
                sb.append(n).append('(').append(m.getParameterCount()).append(") ");
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /** 接受 KubeJS 数组 / Java List / 单个字符串 / 带 {@code getValue()} 的包装对象。 */
    private static List<String> optStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof Iterable<?> it) {
            for (Object e : it) {
                if (e != null) out.add(e.toString());
            }
            return out;
        }
        if (raw instanceof Object[] arr) {
            for (Object e : arr) {
                if (e != null) out.add(e.toString());
            }
            return out;
        }
        if (raw instanceof Map<?, ?> m) {
            for (Object e : m.values()) {
                if (e != null) out.add(e.toString());
            }
            return out;
        }
        // Rhino / KubeJS JS arrays (NativeArray) may expose length + indexed get rather than
        // implementing java.util.List. Read them reflectively so the array is never mistaken
        // for one long string.
        List<String> viaIndex = readScriptableArray(raw);
        if (viaIndex != null && !viaIndex.isEmpty()) return viaIndex;
        // KubeJS/JS 常见：带 getValue() 的数组包装（如 JavaArray / 属性包装）
        try {
            var getValue = raw.getClass().getMethod("getValue");
            Object v = getValue.invoke(raw);
            if (v != null && v != raw) return optStringList(v);
        } catch (ReflectiveOperationException ignored) {
            // not a wrapper — fall through
        }
        out.add(raw.toString());
        return out;
    }

    /** Try to read a JS/Rhino array via its {@code length}/{@code get} methods. Null if not one. */
    private static List<String> readScriptableArray(Object raw) {
        Integer len = lengthOf(raw);
        if (len == null || len < 0) return null;
        List<String> out = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            Object v = elementAt(raw, i);
            if (v == null) return null; // not a real indexed array after all
            out.add(v.toString());
        }
        return out;
    }

    private static Integer lengthOf(Object raw) {
        for (String name : new String[] { "getLength", "size", "length" }) {
            try {
                Object r = raw.getClass().getMethod(name).invoke(raw);
                if (r instanceof Number n) return n.intValue();
            } catch (ReflectiveOperationException ignored) {
                // try the next name
            }
        }
        return null;
    }

    private static Object elementAt(Object raw, int index) {
        // get(int)
        try {
            return raw.getClass().getMethod("get", int.class).invoke(raw, index);
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        // get(int, Scriptable/Object): pass the array itself as the "start" scope
        for (var m : raw.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!m.getName().equals("get") || p.length != 2 || p[0] != int.class) continue;
            Object arg2 = p[1].isInstance(raw) ? raw : null;
            if (arg2 == null && p[1].isPrimitive()) continue;
            try {
                return m.invoke(raw, index, arg2);
            } catch (ReflectiveOperationException ignored) {
                // try the next overload
            }
        }
        return null;
    }

    /** The kind whose backing item id matches {@code itemId}, or {@code null} if none. */
    public static synchronized InfiniteKindSpec byItemId(String itemId) {
        if (itemId == null) return null;
        for (InfiniteKindSpec spec : SPECS.values()) {
            if (itemId.equals(spec.itemId())) return spec;
        }
        return null;
    }

    public static synchronized Map<String, InfiniteKindSpec> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(SPECS));
    }

    // --- Built-in presets (available even without KubeJS) ---

    /** Preset id for the built-in "all concrete" infinite cell. */
    public static final String CONCRETE_ID = "aurelium:concrete";

    /** Backing item id of the bundled concrete preset (its own dedicated item). */
    public static final String CONCRETE_ITEM = "aurelium:infinite_concrete";

    /** Default custom title for the bundled concrete preset. */
    public static final String CONCRETE_TITLE = "无限混凝土";

    /** The 16 vanilla concrete colours, in the standard dye order. */
    private static final String[] CONCRETE_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black"
    };


    /**
     * Registers the bundled presets exactly once. Safe to call from the mod constructor;
     * a KubeJS script re-registering the same id simply replaces the definition.
     */
    public static synchronized void registerDefaults() {
        if (!SPECS.containsKey(CONCRETE_ID)) {
            String[] concrete = new String[CONCRETE_COLORS.length];
            for (int i = 0; i < CONCRETE_COLORS.length; i++) {
                concrete[i] = "minecraft:" + CONCRETE_COLORS[i] + "_concrete";
            }
            // The concrete preset has its own dedicated item id and a shimmering pink name.
            register(CONCRETE_ID, CONCRETE_ITEM, CONCRETE_TITLE, "minecraft:white_concrete", concrete,
                    true, true, null, null);
        }

    }
}
