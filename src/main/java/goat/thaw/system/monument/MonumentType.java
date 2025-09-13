package goat.thaw.system.monument;

import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;

import java.util.Locale;

/**
 * Types of CTM monuments and their required materials.
 */
public enum MonumentType {
    WHITE(DyeColor.WHITE),
    ORANGE(DyeColor.ORANGE),
    MAGENTA(DyeColor.MAGENTA),
    LIGHT_BLUE(DyeColor.LIGHT_BLUE),
    YELLOW(DyeColor.YELLOW),
    LIME(DyeColor.LIME),
    PINK(DyeColor.PINK),
    GRAY(DyeColor.GRAY),
    LIGHT_GRAY(DyeColor.LIGHT_GRAY),
    CYAN(DyeColor.CYAN),
    PURPLE(DyeColor.PURPLE),
    BLUE(DyeColor.BLUE),
    BROWN(DyeColor.BROWN),
    GREEN(DyeColor.GREEN),
    RED(DyeColor.RED),
    BLACK(DyeColor.BLACK),
    IRON(Material.IRON_BLOCK, Material.GLASS, Material.IRON_INGOT, Color.fromRGB(0xD8D8D8)),
    GOLD(Material.GOLD_BLOCK, Material.GLASS, Material.GOLD_INGOT, Color.fromRGB(0xF9FF4E)),
    EMERALD(Material.EMERALD_BLOCK, Material.GLASS, Material.EMERALD, Color.fromRGB(0x17DD62)),
    DIAMOND(Material.DIAMOND_BLOCK, Material.GLASS, Material.DIAMOND, Color.AQUA);

    private final Material requiredMaterial;
    private final Material glassMaterial;
    private final Material rewardMaterial;
    private final Color color;

    MonumentType(DyeColor dye) {
        String name = dye.name();
        this.requiredMaterial = Material.valueOf(name + "_TERRACOTTA");
        this.glassMaterial = Material.valueOf(name + "_STAINED_GLASS");
        this.rewardMaterial = Material.valueOf(name + "_DYE");
        this.color = dye.getColor();
    }

    MonumentType(Material required, Material glass, Material reward, Color color) {
        this.requiredMaterial = required;
        this.glassMaterial = glass;
        this.rewardMaterial = reward;
        this.color = color;
    }

    public Material getRequiredMaterial() {
        return requiredMaterial;
    }

    public Material getGlassMaterial() {
        return glassMaterial;
    }

    public Material getRewardMaterial() {
        return rewardMaterial;
    }

    public Color getColor() {
        return color;
    }

    public static MonumentType fromId(String id) {
        try {
            return MonumentType.valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
