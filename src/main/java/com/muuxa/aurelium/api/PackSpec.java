package com.muuxa.aurelium.api;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable KubeJS startup declaration. No game registries touched during script evaluation. */
public final class PackSpec {
    private final String id;
    private final String title;
    private final Map<String, BigInteger> items;
    /** Initial AE charge for a vault item built from this pack (nested vaults use it too). */
    private final double power;

    PackSpec(String id, String title, Map<String, BigInteger> items) {
        this(id, title, items, 10_000.0);
    }

    PackSpec(String id, String title, Map<String, BigInteger> items, double power) {
        this.id = id;
        this.title = title;
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        this.power = power;
    }

    public String id() { return id; }
    public String title() { return title; }
    public double power() { return power; }
    public int typeCount() { return items.size(); }
    public Map<String, BigInteger> items() { return items; }
}
