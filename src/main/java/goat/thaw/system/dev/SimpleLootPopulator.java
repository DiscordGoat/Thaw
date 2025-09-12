package goat.thaw.system.dev;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SimpleLootPopulator {

    private static class LootEntry {
        final Material material;
        final int min;
        final int max;
        final int weight;

        LootEntry(Material material, int min, int max, int weight) {
            this.material = material;
            this.min = min;
            this.max = max;
            this.weight = weight;
        }

        ItemStack roll() {
            int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
            return new ItemStack(material, amount);
        }
    }

    private final List<LootEntry> entries = new ArrayList<>();
    private int totalWeight = 0;

    public void add(Material material, int min, int max, int weight) {
        entries.add(new LootEntry(material, min, max, weight));
        totalWeight += weight;
    }

    public void populate(Inventory inv, int rolls) {
        //inv.clear();
        Random rng = ThreadLocalRandom.current();

        for (int i = 0; i < rolls; i++) {
            LootEntry entry = pick(rng);
            if (entry == null) continue;

            ItemStack item = entry.roll();

// pick a random empty slot
            int attempts = 0;
            int slot;
            ItemStack existing;
            do {
                slot = rng.nextInt(inv.getSize());
                existing = inv.getItem(slot);
                attempts++;
            } while (existing != null && existing.getType() != Material.AIR && attempts < 10);

            if (attempts < 10) {
                inv.setItem(slot, item);
            }

        }
    }

    private LootEntry pick(Random rng) {
        if (entries.isEmpty()) return null;

        int roll = rng.nextInt(totalWeight);
        int cumulative = 0;
        for (LootEntry e : entries) {
            cumulative += e.weight;
            if (roll < cumulative) return e;
        }
        return null; // should never happen
    }
}
