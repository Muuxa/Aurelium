package com.muuxa.aurelium.compat;

import com.muuxa.aurelium.compat.tnt.AureliumItemsHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Makes every AURELIUM item's dropped entity immune to fire and explosions.
 *
 * <p><b>Two layers, deliberately</b>, because they cover different failure modes:</p>
 * <ol>
 *   <li><b>Damage immunity</b> — {@link EntityInvulnerabilityCheckEvent} fires for every incoming
 *       damage against an entity (in-fire burning, lava, explosion, lightning, cactus, anvil…).
 *       Suppressing the damage is what actually stops the stack from being destroyed.</li>
 *   <li><b>Per-tick repair</b> — some paths damage/ignite an entity without ever routing through
 *       {@code isInvulnerableTo} (and mods may add more). A cheap tick pass re-clears the fire
 *       timer and re-centres nothing else, so even those paths cannot burn the item away.</li>
 * </ol>
 *
 * <p>An item counts as "AURELIUM's" via {@link AureliumItemsHelper}, which resolves from the live
 * registry + class hierarchy. Items added later (new mod versions, KubeJS-created vault /
 * infinite-cell items such as {@code kubejs:starlight_cell}) are therefore covered automatically —
 * nothing needs editing when new items are added.</p>
 */
@EventBusSubscriber(modid = "aurelium")
public final class FireproofItems {
    private FireproofItems() {}

    /** Layer 1: refuse fire / explosion / lightning damage to AURELIUM item entities. */
    @SubscribeEvent
    public static void onInvulnerabilityCheck(EntityInvulnerabilityCheckEvent event) {
        if (event.isInvulnerable()) return;                  // already immune, nothing to do
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;
        var source = event.getSource();
        // Only the *destroying* damage types need suppressing. Restricting to these keeps the
        // change surgical (a void / out-of-world removal stays untouched).
        boolean destroyer = source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.IS_EXPLOSION)
                || source.is(DamageTypeTags.IS_LIGHTNING);
        if (!destroyer) return;
        if (AureliumItemsHelper.isAureliumItem(itemEntity.getItem())) {
            event.setInvulnerable(true);
        }
    }

    /**
     * Layer 2: once a second, clear the fire timer on AURELIUM item entities. Cheap and
     * idempotent, and it covers paths that ignite an entity without routing through
     * {@code isInvulnerableTo}.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < REPAIR_INTERVAL_TICKS) return;
        tickCounter = 0;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // Server-side, type-safe lookup: getEntities(EntityTypeTest, Predicate) -> List.
            // (level.getEntities().getAll() would yield EntityAccess, not Entity.)
            for (Entity entity : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Entity.class),
                    e -> e instanceof ItemEntity item
                            && AureliumItemsHelper.isAureliumItem(item.getItem()))) {
                if (entity instanceof ItemEntity itemEntity && itemEntity.getRemainingFireTicks() > 0) {
                    itemEntity.setRemainingFireTicks(0);
                }
            }
        }
    }

    private static final int REPAIR_INTERVAL_TICKS = 20;
    private static int tickCounter;
}