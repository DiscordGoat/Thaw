package goat.thaw.system.monument;

import org.bukkit.Color;
import org.bukkit.Material;

/**
 * Types of monuments and their associated station material and reward color.
 */
public enum MonumentType {
    PURPLE(Material.PURPLE_TERRACOTTA, Material.PURPLE_STAINED_GLASS, Color.PURPLE),
    BLUE(Material.BLUE_TERRACOTTA, Material.BLUE_STAINED_GLASS, Color.BLUE),
    IRON(Material.IRON_BLOCK, Material.WHITE_STAINED_GLASS, Color.WHITE),
    BROWN(Material.BROWN_TERRACOTTA, Material.BROWN_STAINED_GLASS, Color.fromRGB(150, 75, 0)),
    DIAMOND(Material.DIAMOND_BLOCK, Material.LIGHT_BLUE_STAINED_GLASS, Color.AQUA);

    private final Material stationMaterial;
    private final Material glassMaterial;
    private final Color color;

    MonumentType(Material stationMaterial, Material glassMaterial, Color color) {
        this.stationMaterial = stationMaterial;
        this.glassMaterial = glassMaterial;
        this.color = color;
    }

    public Material getStationMaterial() {
        return stationMaterial;
    }

    public Material getGlassMaterial() {
        return glassMaterial;
    }

    public Color getColor() {
        return color;
    }
}
