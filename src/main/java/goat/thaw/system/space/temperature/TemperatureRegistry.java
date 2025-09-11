package goat.thaw.system.space.temperature;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Snow;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple registry mapping block materials to heat influence values.
 * Positive warms the space, negative cools it. Units are arbitrary
 * influence units per touching neighbor face.
 */
public final class TemperatureRegistry {
    private static final Map<Material, Double> INFLUENCE = new HashMap<>();
    private static final double MULTIPLIER = 4; // triple influence of blocks

    static {
        // Heat sources
        put(Material.TORCH, 2);
        put(Material.WALL_TORCH, 2.0);
        put(Material.SOUL_TORCH, 1.0);
        put(Material.CAMPFIRE, 8.0);
        put(Material.SOUL_CAMPFIRE, 6.0);
        put(Material.LAVA, 15.0);
        put(Material.FIRE, 12.0);
        put(Material.FURNACE, 3.0);
        put(Material.BLAST_FURNACE, 4.0);
        put(Material.SMOKER, 3.0);
        put(Material.MAGMA_BLOCK, 4.0);

        // Cool sources
        put(Material.ICE, -2);
        put(Material.PACKED_ICE, -4);
        put(Material.BLUE_ICE, -10.0);
        put(Material.SNOW_BLOCK, -1);
        put(Material.SNOW, -1);
        put(Material.POWDER_SNOW, -1);

        // Liquids
        put(Material.WATER, -3.0); // stronger cooling baseline

        // Terrain subtle biases (small values; multiplied by MULTIPLIER)
    }

    private static void put(Material m, double v) { INFLUENCE.put(m, v); }

    public static double influence(Material m) {
        return MULTIPLIER * INFLUENCE.getOrDefault(m, 0.0);
    }

    public static double influence(Block block) {
        Material m = block.getType();
        if (m == Material.SNOW) {
            // Layered snow: scale by number of layers (1..8)
            BlockData data = block.getBlockData();
            if (data instanceof Snow) {
                int layers = ((Snow) data).getLayers();
                double base = -1.0; // cooling per face at 8 layers
                double scaled = base * (Math.max(1, layers) / 8.0);
                return MULTIPLIER * scaled;
            }
        }
        return influence(m) * MULTIPLIER;
    }
}
