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
        SimpleLootPopulator brown = new SimpleLootPopulator();
        brown.add(Material.AMETHYST_SHARD, 2, 8, 5);
        brown.add(Material.HAY_BLOCK, 1, 6, 1);
        brown.add(Material.IRON_INGOT, 1, 16, 5);
        brown.add(Material.DIAMOND, 1, 3, 2);
        brown.add(Material.WHEAT_SEEDS, 16, 32, 5);
        brown.add(Material.ENDER_PEARL, 1, 1, 2);
        brown.add(Material.GOLDEN_APPLE, 1, 1, 2);
        lootPopulators.put("BROWN", brown);


        SimpleLootPopulator green = new SimpleLootPopulator();
        green.add(Material.AMETHYST_SHARD, 2, 8, 5);
        green.add(Material.CHORUS_FRUIT, 1, 2, 1);
        green.add(Material.HAY_BLOCK, 1, 6, 1);
        green.add(Material.IRON_INGOT, 1, 16, 5);
        green.add(Material.DIAMOND, 1, 3, 2);
        green.add(Material.WHEAT_SEEDS, 16, 32, 5);
        green.add(Material.ENDER_PEARL, 1, 1, 2);
        green.add(Material.GOLDEN_APPLE, 1, 1, 2);


        lootPopulators.put("GREEN", green);
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

                    if ((block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) && chestsFound < 2) {
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

                    if (chestsFound >= 2) {
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

    // Expose available loot table names for dev tools
    public java.util.Set<String> getAvailableTypes() {
        return java.util.Collections.unmodifiableSet(lootPopulators.keySet());
    }

    // Populate any provided Inventory directly by type; returns true if type exists
    public boolean populateInventory(Inventory inventory, String type) {
        SimpleLootPopulator populator = lootPopulators.get(type.toUpperCase());
        if (populator == null) return false;
        int rolls = Math.min(5 + inventory.getSize() / 9, 27);
        populator.populate(inventory, rolls);
        return true;
    }
}

