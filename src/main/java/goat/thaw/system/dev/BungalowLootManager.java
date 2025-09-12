package goat.thaw.system.dev;

import goat.thaw.Thaw;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BungalowLootManager {
    
    private final Map<String, SimpleLootPopulator> lootPopulators = new HashMap<>();
    
    public BungalowLootManager() {
        // Initialize loot populators for each bungalow type
        initializeLootPopulators();
    }

    private void initializeLootPopulators() {
        // BURGLAR bungalow
        SimpleLootPopulator burglar = new SimpleLootPopulator();
        burglar.add(Material.IRON_SWORD, 1, 1, 5);
        burglar.add(Material.LEATHER_BOOTS, 1, 1, 3);
        burglar.add(Material.GOLD_INGOT, 1, 3, 2);
        lootPopulators.put("BURGLAR", burglar);

        // FARMER bungalow
        SimpleLootPopulator farmer = new SimpleLootPopulator();
        farmer.add(Material.WHEAT, 4, 16, 5);
        farmer.add(Material.HAY_BLOCK, 1, 3, 3);
        farmer.add(Material.CARROT, 2, 10, 4);
        lootPopulators.put("FARMER", farmer);

        // FIRE bungalow
        SimpleLootPopulator fire = new SimpleLootPopulator();
        fire.add(Material.COAL, 4, 12, 5);
        fire.add(Material.FLINT_AND_STEEL, 1, 1, 2);
        fire.add(Material.MAGMA_CREAM, 1, 2, 1);
        lootPopulators.put("FIRE", fire);

        // MOURNER bungalow
        SimpleLootPopulator mourner = new SimpleLootPopulator();
        mourner.add(Material.BLACK_CANDLE, 1, 2, 3);
        mourner.add(Material.WITHER_ROSE, 1, 1, 2);
        mourner.add(Material.BONE, 3, 12, 4);
        lootPopulators.put("MOURNER", mourner);

        // PACIFIST bungalow
        SimpleLootPopulator pacifist = new SimpleLootPopulator();
        pacifist.add(Material.BREAD, 2, 8, 5);
        pacifist.add(Material.PAPER, 1, 5, 3);
        pacifist.add(Material.BOOK, 1, 2, 2);
        lootPopulators.put("PACIFIST", pacifist);

        // SCHOLAR bungalow
        SimpleLootPopulator scholar = new SimpleLootPopulator();
        scholar.add(Material.BOOK, 2, 6, 5);
        scholar.add(Material.ENCHANTED_BOOK, 1, 1, 2);
        scholar.add(Material.INK_SAC, 1, 4, 3);
        lootPopulators.put("SCHOLAR", scholar);
    }


    
    public void populateLoot(Location structureLocation, String type) {
        if (structureLocation.getWorld() == null) return;
        
        int chestsFound = 0;
        int furnacesFound = 0;
        
        // Search in a 10-block radius around the structure location
        for (int x = -10; x <= 10; x++) {
            for (int y = -10; y <= 10; y++) {
                for (int z = -10; z <= 10; z++) {
                    Block block = structureLocation.clone().add(x, y, z).getBlock();
                    
                    // Check for chests
                    if ((block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) && chestsFound < 2) {
                        Location chestLoc = block.getLocation();

                        Bukkit.getScheduler().runTaskLater(Thaw.getInstance(), () -> {
                            Block liveBlock = chestLoc.getBlock();
                            if (!(liveBlock.getState() instanceof Chest)) return;

                            Chest liveChest = (Chest) liveBlock.getState();
                            Inventory inv = liveChest.getInventory(); // guaranteed live container
                            populateChest(inv, type);

                            Bukkit.getLogger().info("[Thaw] Chest before update: " + Arrays.toString(inv.getContents()));

                            Bukkit.getLogger().info("[Thaw] Populated chest at " + chestLoc);
                        }, 20*20); // 1 second delay is safer than 10 ticks

                        chestsFound++;
                    }

                    // Check for furnaces
                    else if (block.getType() == Material.FURNACE && furnacesFound < 1) {
                        Furnace furnace = (Furnace) block.getState();
                        populateFurnace(furnace.getInventory());
                        furnacesFound++;
                    }
                    
                    // Early exit if we've found all containers
                    if (chestsFound >= 2 && furnacesFound >= 1) {
                        return;
                    }
                }
            }
        }
    }

    private void populateChest(Inventory inventory, String type) {
        SimpleLootPopulator populator = lootPopulators.get(type.toUpperCase());
        if (populator != null) {
            // Number of rolls based on container size (adjust as needed)
            int rolls = Math.min(5 + inventory.getSize() / 9, 27);
            populator.populate(inventory, rolls);
        }
    }

    private void populateFurnace(Inventory inventory) {
        // Clear existing contents
        inventory.clear();
        
        // Add 1-16 coal/charcoal (80% chance for coal, 20% for charcoal)
        Random random = ThreadLocalRandom.current();
        int amount = random.nextInt(16) + 1;
        Material fuel = random.nextDouble() < 0.8 ? Material.COAL : Material.CHARCOAL;
        inventory.setItem(1, new ItemStack(fuel, amount));
        
        // 10% chance to add a cooked steak to the result slot
        if (random.nextDouble() < 0.1) {
            inventory.setItem(2, new ItemStack(Material.COOKED_BEEF, 1));
        }
    }
}
