package com.muuxa.aurelium.compat.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import com.muuxa.aurelium.storage.InfiniteCellInventory;
import com.muuxa.aurelium.storage.VaultInventory;
import java.math.BigInteger;
import java.util.function.BiConsumer;

/**
 * Shared forwarding logic for AURELIUM's NeoEcoAE interop.
 *
 * <p>This whole package is only ever loaded when NeoEcoAE is present (the mixin config
 * plugin gates every class here). Nothing in the core {@code storage} package imports
 * NeoEcoAE, so AURELIUM keeps working standalone.</p>
 *
 * <p>Why a bridge instead of implementing the interface on {@link VaultInventory} itself:
 * NeoEcoAE discovers exact-BigInteger inventories with {@code instanceof ECOBigIntegerStorage}
 * on the objects that are actually mounted in the network. AE2 wraps a mounted cell in
 * {@code DriveWatcher}/{@code MEInventoryHandler} (drives, chests) or {@code SupplierStorage}
 * (portable terminal), so the wrapper — not the raw cell — is what the network sees. We
 * therefore teach the wrappers to forward to the vault underneath, exactly like NeoEcoAE's
 * own {@code NetworkStorageMixin} forwards to its raw, unwrapped cells.</p>
 */
public final class VaultExactBridge {
    private VaultExactBridge() {
    }

    /** Unwrap {@code storage} to a vault, or {@code null} when it is not one. */
    public static VaultInventory vaultOf(MEStorage storage) {
        return storage instanceof VaultInventory vault ? vault : null;
    }

    /** Unwrap {@code storage} to an infinite cell, or {@code null} when it is not one. */
    public static InfiniteCellInventory infiniteOf(MEStorage storage) {
        return storage instanceof InfiniteCellInventory infinite ? infinite : null;
    }

    /**
     * Report an infinite cell's served keys as {@link ExactAmount#unbounded()} so NeoEcoAE's
     * creative-extraction filter recognises the cell as an <b>infinite source</b> and skips it
     * (its {@code ECOCreativeExtractionFilter} treats an unchanged {@code infinite()} amount as
     * a creative source and voids the extraction). Without this the infinite cell would look
     * like an ordinary finite stock and get drained.
     */
    public static void visitInfiniteAmounts(InfiniteCellInventory infinite, BiConsumer<AEKey, ExactAmount> visitor) {
        for (AEKey key : infinite.infiniteKeys()) {
            visitor.accept(key, ExactAmount.unbounded());
        }
    }

    /**
     * Exact BigInteger insert, mirroring {@code ECOBigIntegerStorage.insertBigInteger}:
     * amounts up to {@code Long.MAX_VALUE} go through AE2's ordinary long path; larger
     * amounts are handed to the vault's BigInteger state machine intact — never split or
     * scaled — and the vault's own return value is authoritative.
     */
    public static BigInteger insertBigInteger(VaultInventory vault, AEKey key, BigInteger amount,
                                              Actionable mode, IActionSource source) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        if (!vault.acceptsForBridge(key)) return BigInteger.ZERO;
        if (amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
            long accepted = vault.insert(key, amount.longValueExact(), mode, source);
            return BigInteger.valueOf(accepted);
        }
        if (mode == Actionable.SIMULATE) {
            // The vault has no fixed capacity, so the exact amount is always accepted.
            return amount;
        }
        return vault.creditExact(key, amount);
    }

    /**
     * Exact BigInteger insert for an infinite cell: a served key's input is accepted and voided
     * (the cell never grows), matching its long-path behaviour; anything else is rejected.
     */
    public static BigInteger insertInfinite(InfiniteCellInventory infinite, AEKey key, BigInteger amount,
                                            Actionable mode, IActionSource source) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        if (!infinite.servesInfinitely(key)) return BigInteger.ZERO;
        return amount;
    }

    /**
     * Borrow the vault's real BigInteger contents for the display layer, so a stack that
     * exceeds {@code Long.MAX_VALUE} is shown exactly instead of saturating. Only finite,
     * non-zero amounts are reported; NeoEcoAE's collector treats these as exact totals.
     */
    public static void visitExactAmounts(VaultInventory vault, BiConsumer<AEKey, ExactAmount> visitor) {
        for (var entry : vault.exactSnapshot().entrySet()) {
            BigInteger amount = entry.getValue();
            if (amount != null && amount.signum() > 0) {
                visitor.accept(entry.getKey(), ExactAmount.finite(amount));
            }
        }
    }
}
