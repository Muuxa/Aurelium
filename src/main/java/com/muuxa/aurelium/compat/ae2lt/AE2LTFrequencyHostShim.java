package com.muuxa.aurelium.compat.ae2lt;

import appeng.blockentity.grid.AENetworkedBlockEntity;
import com.muuxa.aurelium.storage.TileAdvanceIOPort;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AE2 Lightning Tech's {@code FrequencyBindingHost} requires a
 * {@link AENetworkedBlockEntity}, but AE2's IO port (which the advanced port extends) is an
 * {@code AENetworkedInvBlockEntity} — a sibling class, not a subclass. The API only ever reads
 * {@code getMainNode()}, {@code getLevel()}, {@code getBlockPos()} and {@code getBlockState()}
 * from that value, so this shim adopts the port's own managed node by swapping the private
 * field and forwards everything else to the port.
 *
 * <p>Created once per port, only when AE2LT is installed. Purely internal glue; it never
 * touches world state by itself.</p>
 */
public final class AE2LTFrequencyHostShim extends AENetworkedBlockEntity {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aurelium/AE2LT");
    private final TileAdvanceIOPort port;

    private AE2LTFrequencyHostShim(TileAdvanceIOPort port) {
        super(port.getType(), port.getBlockPos(), port.getBlockState());
        this.port = port;
    }

    /**
     * Builds a shim that reports the port's grid node, or {@code null} when the node field is
     * unreachable (the frequency card then simply does not see the port, instead of crashing).
     */
    public static AE2LTFrequencyHostShim create(TileAdvanceIOPort port) {
        if (port == null) return null;
        try {
            AE2LTFrequencyHostShim shim = new AE2LTFrequencyHostShim(port);
            ((NodeDelegateAccess) (Object) shim).aurelium$setNodeDelegate(port.getMainNode());
            return port.getMainNode() == null ? null : shim;
        } catch (Throwable failure) {
            LOGGER.warn("AURELIUM：无法把 OI 端口接入闪电科技的频率系统（{}）。", failure.toString());
            return null;
        }
    }

    @Override
    public Level getLevel() {
        return port.getLevel();
    }

    @Override
    public void saveChanges() {
        port.saveChanges();
    }

    @Override
    public void markForUpdate() {
        port.markForUpdate();
    }
}
