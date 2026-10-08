package com.muuxa.aurelium.api;

import java.util.Collections;
import java.util.List;

/** Immutable KubeJS startup declaration for one infinite cell kind. */
public final class InfiniteKindSpec {
    private final String id;
    /** The item id that backs this kind; required (each kind names its own element item). */
    private final String itemId;
    private final String title; // may be null: means "use the item's default translatable name"
    private final String iconId;
    private final List<String> items;
    private final boolean describe;
    private final boolean shimmer; // colour-cycle the item name in the tooltip
    private final String model;   // optional: a full model id "ns:item/foo" to render instead
    private final String texture; // optional: a texture id "ns:item/foo" rendered as a flat icon

    InfiniteKindSpec(String id, String itemId, String title, String iconId, List<String> items,
                     boolean describe, boolean shimmer, String model, String texture) {
        this.id = id;
        this.itemId = itemId;
        this.title = title;
        this.iconId = iconId;
        this.items = Collections.unmodifiableList(List.copyOf(items));
        this.describe = describe;
        this.shimmer = shimmer;
        this.model = (model == null || model.isBlank()) ? null : model.trim();
        this.texture = (texture == null || texture.isBlank()) ? null : texture.trim();
    }

    public String id() { return id; }

    /** The item id that backs this kind (always present). */
    public String itemId() { return itemId; }

    /** Custom display name, or {@code null} when the default item name should be used. */
    public String title() { return title; }

    /** True when a custom name was supplied at registration time. */
    public boolean hasCustomTitle() { return title != null && !title.isBlank(); }

    public String iconId() { return iconId; }
    public List<String> items() { return items; }
    public int typeCount() { return items.size(); }

    /**
     * Whether the tooltip flavour/description block is shown. Declared per kind at
     * registration time; {@code false} hides all the pink lore and the "icon + ∞" row,
     * leaving only the item name.
     */
    public boolean describe() { return describe; }

    /** Whether the item name should be colour-cycled (shimmer) in the tooltip. */
    public boolean shimmer() { return shimmer; }

    /** Optional custom model id (client render) or {@code null} to use the built-in look. */
    public String model() { return model; }

    /** Optional custom texture id (client render) or {@code null} to use the built-in look. */
    public String texture() { return texture; }

    /** True when this kind asks the client to render something other than the default. */
    public boolean hasCustomAppearance() { return model != null || texture != null; }
}