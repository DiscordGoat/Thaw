package goat.thaw.system;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Prevents wolves from spawning on water.
 */
public class WolfSpawnListener implements Listener {

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.WOLF) return;

        Block block = event.getLocation().getBlock();
        if (isLiquid(block) || isLiquid(block.getRelative(BlockFace.DOWN))) {
            event.setCancelled(true);
        }
    }

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        return type == Material.WATER || block.isLiquid();
    }
}
