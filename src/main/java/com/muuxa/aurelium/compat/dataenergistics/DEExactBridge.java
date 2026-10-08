package com.muuxa.aurelium.compat.dataenergistics;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.muuxa.aurelium.storage.InfiniteCellInventory;
import com.muuxa.aurelium.storage.VaultInventory;
import java.math.BigInteger;

/**
 * Read-side forwarding for AURELIUM's Data_Energistics interop.
 *
 * <p>Data_Energistics discovers exact amounts through {@code ExactExtractableStorage}
 * on mounted inventories (see {@code com.fish_dan_.data_energistics.ae2.grid}). Unlike
 * NeoEcoAE it has no BigInteger insert interface — its own oversized values live in an
 * internal {@code Map<AEKey, BigInteger>} ledger behind a long insert — so this bridge is
 * deliberately read-only: it lets DE's exact accounting see the vault's real BigInteger
 * instead of a {@code long}-saturated figure.</p>
 *
 * <p><b>Why the fallback matters.</b> The interface is added to AE2's generic wrapper
 * base class, so it applies to every mounted inventory. DE treats any
 * {@code ExactExtractableStorage} as authoritative and skips its own
 * {@code getAvailableStacks} + {@code extract(SIMULATE)} fallback for it. For a non-vault
 * wrapper we reproduce DE's own fallback — but on the <b>wrapper itself</b> (which applies
 * the wrapper's partition/filter rules), not on the unwrapped delegate — so network-wide
 * totals stay identical for everything except the vault.</p>
 *
 * <p>Only loaded when Data_Energistics is present; the mixin config is gated accordingly.</p>
 */
public final class DEExactBridge {
    private DEExactBridge() {
    }

    /**
     * Exact available amount for one key, given the mounted wrapper.
     *
     * <ul>
     *   <li>If the wrapper's delegate is a vault, this is the vault's real BigInteger
     *       (the vault has no capacity limit, so the value is reported as-is).</li>
     *   <li>Otherwise this mirrors DE's own fallback on the <b>wrapper</b>: read what the
     *       wrapper reports, confirm it via the wrapper's simulated extraction.</li>
     * </ul>
     */
    public static BigInteger exactAvailable(MEStorage wrapper, MEStorage delegate, AEKey key, IActionSource source) {
        if (delegate instanceof VaultInventory vault) {
            BigInteger amount = vault.exactSnapshot().get(key);
            return amount == null ? BigInteger.ZERO : amount;
        }
        // Mirrors Data_Energistics' exactAvailability on the mounted member: reported long
        // amount first, then a simulated extraction through the same filtering wrapper.
        KeyCounter counter = new KeyCounter();
        wrapper.getAvailableStacks(counter);
        long reported = counter.get(key);
        if (reported <= 0L) return BigInteger.ZERO;
        long simulated = wrapper.extract(key, reported, Actionable.SIMULATE, source);
        if (simulated < 0L || simulated > reported) return BigInteger.ZERO;
        return BigInteger.valueOf(simulated);
    }

    /**
     * Whether a mounted wrapper should be treated by Data_Energistics as an <b>infinite source</b>
     * for {@code key}, so its finite-transfer planner skips it (exactly as it skips creative
     * cells). True only when the wrapper's delegate is an infinite cell that serves the key.
     */
    public static boolean supportsUnlimitedExtraction(MEStorage delegate, AEKey key, IActionSource source) {
        return delegate instanceof InfiniteCellInventory infinite && infinite.servesInfinitely(key);
    }
}