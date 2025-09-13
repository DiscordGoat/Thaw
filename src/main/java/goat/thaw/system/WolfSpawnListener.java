package goat.thaw.system;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

/**
 * Prevents wolves from spawning on water and ensures squid spawn in oceans
 * as frequently as wolves appear on land.
 */
public class WolfSpawnListener implements Listener {

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.WOLF) return;
        if (event.getSpawnReason() != SpawnReason.NATURAL) return;

        Block block = event.getLocation().getBlock();
        if (isLiquid(block) || isLiquid(block.getRelative(BlockFace.DOWN))) {
            event.setCancelled(true);
            return;
        }

        spawnNearbySquid(block);
    }

    private void spawnNearbySquid(Block origin) {
        World world = origin.getWorld();
        Location loc = origin.getLocation();
        int radius = 16;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Block top = world.getHighestBlockAt(loc.getBlockX() + dx, loc.getBlockZ() + dz);
                if (top.getType() == Material.WATER) {
                    Block below = top.getRelative(BlockFace.DOWN);
                    if (below.getType() == Material.WATER) {
                        world.spawnEntity(below.getLocation().add(0.5, 0.5, 0.5), EntityType.SQUID);
                        return;
                    }
                }
            }
        }
    }

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        return type == Material.WATER || block.isLiquid();
    }
}
