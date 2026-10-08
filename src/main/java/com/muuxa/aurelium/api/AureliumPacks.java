package com.muuxa.aurelium.api;

import com.muuxa.aurelium.storage.KeyCodec;
import net.minecraft.resources.ResourceLocation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public, server-authoritative registration API; creates cell TEMPLATES, not Forge item IDs.
 *
 * <p>Entries may name any AE2 key type, not only items:</p>
 * <ul>
 *   <li>item — {@code "64x minecraft:diamond"}</li>
 *   <li>fluid — {@code "1000x fluid:minecraft:water"}</li>
 *   <li>any other registered key — {@code "1x key:{...SNBT...}"}</li>
 * </ul>
 * Bare item ids stay unchanged, so existing packs keep working. Resolution to a live key
 * happens later (at cell seeding), because startup scripts run before registries are ready.
 */
public final class AureliumPacks {
    // "<optional count>x <token>"; the token is validated later by KeyCodec.
    private static final Pattern ENTRY = Pattern.compile("^(?:([0-9]+)\\s*x\\s*)?(.+)$");
    private static final Map<String, PackSpec> SPECS = new LinkedHashMap<>();

    private AureliumPacks() {}

    /** e.g. register("my_pack:basics", "星穹补给", ["64x minecraft:diamond"]). */
    public static synchronized PackSpec register(String id, String name, String[] entries) {
        return register(id, name, entries, 10_000.0);
    }

    /**
     * As {@link #register(String, String, String[])}, with an explicit initial AE charge. The
     * charge travels with the pack so that a vault materialised from a nested {@code vault:}
     * reference inherits the same power the exported item had.
     */
    public static synchronized PackSpec register(String id, String name, String[] entries,
                                                 double power) {
        if (id == null || ResourceLocation.tryParse(id) == null) {
            throw new IllegalArgumentException("Invalid pack id: " + id);
        }
        // Re-running KubeJS startup scripts during a reload replaces the declaration;
        // already materialized cells keep their saved world data unchanged.
        if (entries == null) throw new IllegalArgumentException("entries is null");
        Map<String, BigInteger> amounts = new LinkedHashMap<>();
        for (String raw : entries) {
            if (raw == null) throw new IllegalArgumentException("Null entry in " + id);
            Matcher m = ENTRY.matcher(raw.trim());
            if (!m.matches() || !KeyCodec.isValidToken(m.group(2))) {
                throw new IllegalArgumentException(
                        "Expected '64x namespace:item', 'fluid:namespace:fluid' or 'key:{...}', got: " + raw);
            }
            BigInteger amount = new BigInteger(m.group(1) == null ? "1" : m.group(1));
            if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive: " + raw);
            amounts.merge(m.group(2).trim(), amount, BigInteger::add);
        }
        // An empty pack is legitimate: a sub-vault whose contents were all exported separately
        // (or a genuinely empty nested vault) must still be declarable, otherwise the whole
        // generated script fails to load. Seeding such a vault simply leaves it empty.
        PackSpec spec = new PackSpec(id, name == null || name.isBlank() ? id : name, amounts, power);
        SPECS.put(id, spec);
        return spec;
    }

    public static synchronized PackSpec get(String id) { return SPECS.get(id); }
    public static synchronized Map<String, PackSpec> all() { return Collections.unmodifiableMap(new LinkedHashMap<>(SPECS)); }

    // --- Named-key (object) declaration — same style as AureliumInfinite.register({...}) ---

    /**
     * 用「具名键对象」声明一个内容包（和 {@code AureliumInfinite.register({...})} 同一风格）。
     * 由 {@code /aurelium export} 生成的数据脚本用这种写法。
     *
     * <pre>{@code
     * AureliumPacks.register({
     *   id:    'aurelium:export_start',     // 【必填】包 id
     *   name:  '启动物品',                   // 可选：显示名（省略 = 用 id）
     *   items: [                             // 【必填】包内容
     *     { itemId: 'minecraft:paper', count: 64 },
     *     { itemId: 'fluid:minecraft:water', count: 1000 },
     *     { itemId: 'key:{"#t":"appflux:flux",type:"FE"}', count: 1 }
     *   ]
     * });
     * }</pre>
     *
     * <p>每一项的键：<b>itemId</b>（必填；也接受 {@code item} / {@code item_id} / {@code id} / {@code token} 作别名）
     * 与 <b>count</b>（可选；也接受 {@code amount} / {@code quantity} 作别名，缺省 1）。</p>
     *
     * <p>嵌套宝匣用 {@code { packId: 'aurelium:child' }} 或 {@code { itemId: 'vault:aurelium:child' }} 表达。</p>
     */
    public static synchronized PackSpec register(Object options) {
        if (options == null) {
            throw new IllegalArgumentException("内容包选项对象不能为 null");
        }
        String id = optString(options, "id", null);
        if (id == null || ResourceLocation.tryParse(id) == null) {
            throw new IllegalArgumentException("内容包缺少合法的 id: " + id);
        }
        String name = optString(options, "name", null);
        if (name == null) name = optString(options, "title", null);

        List<String> entries = new ArrayList<>();
        Object rawItems = firstMember(options, "items", "entries", "infinite");
        // An empty list is accepted on purpose: an empty nested vault is valid data, and refusing
        // it would abort the entire exported script.
        List<Object> itemList = optObjectList(rawItems);
        for (Object element : itemList) {
            entries.add(toEntryLine(id, element));
        }
        // The pack carries its vault's display name and AE charge so a nested vault materialised
        // from this pack keeps both (a bare { packId: ... } reference has nowhere else to put them).
        double power = 10_000.0;
        Object rawPower = firstMember(options, "power", "vaultPower", "ae");
        if (rawPower != null) {
            try {
                power = Double.parseDouble(rawPower.toString().trim());
            } catch (NumberFormatException malformed) {
                power = 10_000.0;
            }
        }
        return register(id, name, entries.toArray(new String[0]), power);
    }

    /** Render one object-form item ({itemId, count}) into the canonical {@code "<count>x <token>"} line. */
    private static String toEntryLine(String packId, Object element) {
        if (element == null) throw new IllegalArgumentException("内容包 '" + packId + "' 中有 null 项");
        // A bare string is accepted too ("64x minecraft:paper").
        if (element instanceof CharSequence cs) {
            return cs.toString().trim();
        }
        // A nested vault may be declared as { packId: 'aurelium:child', count: 1 }.
        String token = firstString(element, "itemId", "item_id", "itemid", "item", "id", "token");
        String nestedPack = firstString(element, "packId", "pack_id", "vault");
        if ((token == null || token.isBlank()) && nestedPack != null && !nestedPack.isBlank()) {
            token = KeyCodec.VAULT_PREFIX + nestedPack.trim();
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "内容包 '" + packId + "' 的某一项缺少 itemId（也可用 item/item_id/id/token，"
                            + "嵌套宝匣可用 packId）: " + element);
        }
        BigInteger count = firstNumber(element, "count", "amount", "quantity", "n");
        if (count == null) count = BigInteger.ONE;
        if (count.signum() <= 0) {
            throw new IllegalArgumentException("内容包 '" + packId + "' 的某项数量必须为正: " + element);
        }
        return count + "x " + token.trim();
    }

    // --- reflective member readers (Map / Rhino Scriptable), same approach as AureliumInfinite ---

    private static Object member(Object options, String key) {
        if (options == null) return null;
        if (options instanceof Map<?, ?> m) return m.get(key);
        for (String name : new String[] { "getMember", "get", "getProperty", "getValue" }) {
            Object v = invokeStringMethod(options, key, name);
            if (v != null) return v;
        }
        return null;
    }

    private static Object firstMember(Object options, String... keys) {
        for (String k : keys) {
            Object v = member(options, k);
            if (v != null) return v;
        }
        return null;
    }

    private static String optString(Object options, String key, String fallback) {
        Object v = member(options, key);
        if (v == null) return fallback;
        String s = v.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String firstString(Object options, String... keys) {
        for (String k : keys) {
            Object v = member(options, k);
            if (v == null) continue;
            String s = v.toString().trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    private static BigInteger firstNumber(Object options, String... keys) {
        for (String k : keys) {
            Object v = member(options, k);
            if (v == null) continue;
            if (v instanceof Number n) return toBigInteger(n.toString());
            String s = v.toString().trim();
            if (!s.isEmpty()) return toBigInteger(s);
        }
        return null;
    }

    /** Accepts plain integers and decimal forms like {@code 1.0E9} that JS may produce. */
    private static BigInteger toBigInteger(String raw) {
        try {
            return new BigInteger(raw);
        } catch (NumberFormatException notPlain) {
            try {
                return new BigDecimal(raw).toBigInteger();
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("数量不是有效数字: " + raw);
            }
        }
    }

    private static Object invokeStringMethod(Object options, String key, String methodName) {
        for (var m : options.getClass().getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 1 && p[0] == String.class) return m.invoke(options, key);
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

    /** Read a JS/Java list of objects; falls back to length+index reflection for Rhino arrays. */
    private static List<Object> optObjectList(Object raw) {
        List<Object> out = new ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof Iterable<?> it) {
            for (Object e : it) if (e != null) out.add(e);
            return out;
        }
        if (raw instanceof Object[] arr) {
            for (Object e : arr) if (e != null) out.add(e);
            return out;
        }
        for (String lenName : new String[] { "getLength", "size", "length" }) {
            Integer len = null;
            try {
                Object r = raw.getClass().getMethod(lenName).invoke(raw);
                if (r instanceof Number n) len = n.intValue();
            } catch (ReflectiveOperationException ignored) {
                // try the next accessor
            }
            if (len == null || len < 0) continue;
            for (int i = 0; i < len; i++) {
                Object v = elementAt(raw, i);
                if (v != null) out.add(v);
            }
            return out;
        }
        out.add(raw);
        return out;
    }

    private static Object elementAt(Object raw, int index) {
        try {
            return raw.getClass().getMethod("get", int.class).invoke(raw, index);
        } catch (ReflectiveOperationException ignored) {
            // fall through to the two-arg form
        }
        for (var m : raw.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!m.getName().equals("get") || p.length != 2 || p[0] != int.class) continue;
            Object arg2 = p[1].isInstance(raw) ? raw : null;
            try {
                return m.invoke(raw, index, arg2);
            } catch (ReflectiveOperationException ignored) {
                // try the next overload
            }
        }
        return null;
    }
}
