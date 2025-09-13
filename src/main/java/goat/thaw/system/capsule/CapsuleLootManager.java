package goat.thaw.system.capsule;

import goat.thaw.Thaw;
import goat.thaw.system.dev.SimpleLootPopulator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles loot population for time capsules.
 * Each capsule type has its own loot table backed by {@link SimpleLootPopulator}.
 */
public class CapsuleLootManager {

    private final Map<String, SimpleLootPopulator> lootPopulators = new HashMap<>();

    public CapsuleLootManager() {
        initializeLootPopulators();
    }

    private void initializeLootPopulators() {
        // LILAC capsule
        SimpleLootPopulator lilac = new SimpleLootPopulator();
        lilac.add(Material.AMETHYST_SHARD, 2, 8, 5);
        lilac.add(Material.PURPLE_DYE, 1, 4, 3);
        lilac.add(Material.CHORUS_FRUIT, 1, 2, 1);
        lootPopulators.put("LILAC", lilac);
    }

    /**
     * Searches for nearby chests around the capsule location and populates them
     * using the capsule's loot table.
     *
     * @param structureLocation location where the capsule was placed
     * @param type               capsule type (schematic name)
     */
    public void populateLoot(Location structureLocation, String type) {
        if (structureLocation.getWorld() == null) return;

        int chestsFound = 0;

        // Search in a 10-block radius around the structure location
        for (int x = -10; x <= 10; x++) {
            for (int y = -10; y <= 10; y++) {
                for (int z = -10; z <= 10; z++) {
                    Block block = structureLocation.clone().add(x, y, z).getBlock();

                    if ((block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) && chestsFound < 1) {
                        Location chestLoc = block.getLocation();

                        Bukkit.getScheduler().runTaskLater(Thaw.getInstance(), () -> {
                            Block liveBlock = chestLoc.getBlock();
                            if (!(liveBlock.getState() instanceof Chest)) return;

                            Chest liveChest = (Chest) liveBlock.getState();
                            Inventory inv = liveChest.getInventory();
                            populateChest(inv, type);
                        }, 20L);

                        chestsFound++;
                    }

                    if (chestsFound >= 1) {
                        return;
                    }
                }
            }
        }
    }

    private void populateChest(Inventory inventory, String type) {
        SimpleLootPopulator populator = lootPopulators.get(type.toUpperCase());
        if (populator != null) {
            int rolls = Math.min(5 + inventory.getSize() / 9, 27);
            populator.populate(inventory, rolls);
        }
    }
}

