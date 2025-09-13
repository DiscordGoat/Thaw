package goat.thaw.system.monument;

import org.bukkit.*;
import org.bukkit.block.Block;
import goat.thaw.system.ctm.GenerateCTMEvent;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.ChatColor;
import org.bukkit.Tag;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Handles monument persistence and detection.
 */
public class MonumentManager implements Listener {

    private final Plugin plugin;
    private final File file;
    private FileConfiguration config;
    private Location center;
    private final EnumMap<MonumentType, Boolean> completion = new EnumMap<>(MonumentType.class);

    public MonumentManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "monument.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            config = new YamlConfiguration();
            for (MonumentType type : MonumentType.values()) {
                completion.put(type, false);
            }
            save();
            return;
        }
        config = YamlConfiguration.loadConfiguration(file);
        if (config.contains("center")) {
            String world = config.getString("center.world");
            double x = config.getDouble("center.x");
            double y = config.getDouble("center.y");
            double z = config.getDouble("center.z");
            World w = world != null ? Bukkit.getWorld(world) : null;
            if (w != null) {
                center = new Location(w, x, y, z);
            }
        }
        for (MonumentType type : MonumentType.values()) {
            completion.put(type, config.getBoolean("monuments." + type.name(), false));
        }
    }

    private void save() {
        if (config == null) config = new YamlConfiguration();
        if (center != null) {
            config.set("center.world", center.getWorld().getName());
            config.set("center.x", center.getX());
            config.set("center.y", center.getY());
            config.set("center.z", center.getZ());
        }
        for (Map.Entry<MonumentType, Boolean> e : completion.entrySet()) {
            config.set("monuments." + e.getKey().name(), e.getValue());
        }
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isCompleted(MonumentType type) {
        return completion.getOrDefault(type, false);
    }

    private void markCompleted(MonumentType type) {
        completion.put(type, true);
        save();
    }

    @EventHandler
    public void onGenerate(GenerateCTMEvent event) {
        center = event.getLocation();
        for (MonumentType type : MonumentType.values()) {
            completion.put(type, false);
        }
        save();
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (center == null) return;
        if (!isNearCenter(event.getBlockPlaced().getLocation(), 30)) return;

        Block placed = event.getBlockPlaced();
        Block base = findBase(placed.getLocation());
        if (base == null) return;

        Location baseLoc = base.getLocation();
        Location placedLoc = placed.getLocation();
        boolean station = false;
        for (int dy = 1; dy <= 3; dy++) {
            if (placedLoc.getBlockX() == baseLoc.getBlockX() && placedLoc.getBlockY() == baseLoc.getBlockY() + dy && placedLoc.getBlockZ() == baseLoc.getBlockZ()) {
                station = true;
                break;
            }
        }
        if (!station) return;

        Sign sign = findSign(base.getLocation());
        if (sign == null) return;

        String line = ChatColor.stripColor(sign.getLine(1)).trim();
        MonumentType type = MonumentType.fromId(line);
        if (type == null) return;

        if (!checkFilled(base.getLocation(), type)) return;

        if (isCompleted(type)) return;

        Player player = event.getPlayer();
        markCompleted(type);
        replaceWithGlass(base.getLocation(), type);
        rewardPlayer(player, base.getLocation(), type);
        playCelebration(base.getLocation(), type);
        Bukkit.getPluginManager().callEvent(new CompleteMonumentEvent(line, type, base.getLocation(), player.getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler
    public void onSignInteract(PlayerInteractEvent event) {
        if (center == null) return;
        if (!event.hasBlock()) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (!Tag.SIGNS.isTagged(block.getType())) return;
        if (!isNearCenter(block.getLocation(), 30)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onSignBreak(BlockBreakEvent event) {
        if (center == null) return;
        Block block = event.getBlock();
        if (!Tag.SIGNS.isTagged(block.getType())) return;
        if (!isNearCenter(block.getLocation(), 30)) return;
        event.setCancelled(true);
    }

    private boolean isNearCenter(Location loc, double radius) {
        if (center == null || loc.getWorld() == null) return false;
        if (!center.getWorld().equals(loc.getWorld())) return false;
        return loc.distanceSquared(center) <= radius * radius;
    }

    @Nullable
    private Block findBase(Location placed) {
        World world = placed.getWorld();
        if (world == null) return null;
        Block below = world.getBlockAt(placed.getBlockX(), placed.getBlockY() - 1, placed.getBlockZ());
        if (below.getType() == Material.BEDROCK) return below;
        int radius = 2;
        Block nearest = null;
        double best = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(placed.getBlockX() + dx, placed.getBlockY() + dy, placed.getBlockZ() + dz);
                    if (b.getType() == Material.BEDROCK) {
                        double dist = b.getLocation().distanceSquared(placed);
                        if (dist < best) {
                            best = dist;
                            nearest = b;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    @Nullable
    private Sign findSign(Location base) {
        World world = base.getWorld();
        if (world == null) return null;
        int radius = 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(base.getBlockX() + dx, base.getBlockY() + dy, base.getBlockZ() + dz);
                    if (Tag.SIGNS.isTagged(b.getType())) {
                        BlockState state = b.getState();
                        if (state instanceof Sign sign) {
                            return sign;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean checkFilled(Location base, MonumentType type) {
        World world = base.getWorld();
        if (world == null) return false;
        for (int dy = 1; dy <= 3; dy++) {
            Block b = world.getBlockAt(base.getBlockX(), base.getBlockY() + dy, base.getBlockZ());
            if (b.getType() != type.getRequiredMaterial()) {
                return false;
            }
        }
        return true;
    }

    private void replaceWithGlass(Location base, MonumentType type) {
        World world = base.getWorld();
        if (world == null) return;
        for (int dy = 1; dy <= 3; dy++) {
            Block b = world.getBlockAt(base.getBlockX(), base.getBlockY() + dy, base.getBlockZ());
            b.setType(type.getGlassMaterial(), false);
        }
    }

    private void rewardPlayer(Player player, Location base, MonumentType type) {
        World world = base.getWorld();
        if (world == null) return;
        ItemStack reward = new ItemStack(type.getRewardMaterial());
        Location dropLoc = base.clone().add(0.5, 1.2, 0.5);
        Item item = world.dropItem(dropLoc, reward);
        Vector vec = player.getLocation().toVector().subtract(dropLoc.toVector()).normalize().multiply(0.5);
        item.setVelocity(vec);
    }

    private void playCelebration(Location base, MonumentType type) {
        World world = base.getWorld();
        if (world == null) return;
        world.playSound(base, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ > 600) { cancel(); return; }
                world.spawnParticle(Particle.REDSTONE, base.clone().add(0.5,1,0.5), 8, 0.3, 0.5, 0.3, 0, new Particle.DustOptions(type.getColor(), 1));
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }
}
