package com.muuxa.aurelium.compat.ae2lt;

import appeng.api.networking.IGridNodeListener;
import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;

/**
 * Optional hook between the OI port and AE2 Lightning Tech's frequency binding.
 *
 * <p>AURELIUM must keep working when AE2LT is absent, so {@code TileAdvanceIOPort} never
 * references an AE2LT type. Instead the gated AE2LT mixin installs a small {@link Binding}
 * adapter here, and the port calls through this class. Without AE2LT nothing is ever
 * registered and every method is a no-op.</p>
 */
public final class AE2LTFrequencyHooks {

    /** The subset of {@code FrequencyBindingAccess} the port lifecycle needs. */
    public interface Binding {
        void tick();

        void onReady();

        void setRemoved();

        void clearRemoved();

        void onChunkUnloaded();

        void onMainNodeStateChanged(IGridNodeListener.State reason);

        void save(CompoundTag tag);

        void load(CompoundTag tag);
    }

    private static final Map<Object, WeakReference<Binding>> BINDINGS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AE2LTFrequencyHooks() {
    }

    /** Called by the AE2LT mixin once, from the port's constructor. */
    public static void attach(Object port, Binding binding) {
        if (port == null || binding == null) return;
        BINDINGS.put(port, new WeakReference<>(binding));
    }

    private static Binding get(Object port) {
        if (port == null) return null;
        WeakReference<Binding> reference = BINDINGS.get(port);
        return reference == null ? null : reference.get();
    }

    public static void tick(Object port) {
        Binding binding = get(port);
        if (binding != null) binding.tick();
    }

    public static void onReady(Object port) {
        Binding binding = get(port);
        if (binding != null) binding.onReady();
    }

    public static void setRemoved(Object port) {
        Binding binding = get(port);
        if (binding != null) binding.setRemoved();
    }

    public static void clearRemoved(Object port) {
        Binding binding = get(port);
        if (binding != null) binding.clearRemoved();
    }

    public static void onChunkUnloaded(Object port) {
        Binding binding = get(port);
        if (binding != null) binding.onChunkUnloaded();
    }

    public static void onMainNodeStateChanged(Object port, IGridNodeListener.State reason) {
        Binding binding = get(port);
        if (binding != null) binding.onMainNodeStateChanged(reason);
    }

    public static void save(Object port, CompoundTag tag) {
        Binding binding = get(port);
        if (binding != null) binding.save(tag);
    }

    public static void load(Object port, CompoundTag tag) {
        Binding binding = get(port);
        if (binding != null) binding.load(tag);
    }
}
