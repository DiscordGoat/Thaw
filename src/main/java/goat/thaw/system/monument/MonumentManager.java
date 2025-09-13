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

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class MonumentManager implements Listener {

    private final Plugin plugin;
    private final File file;
    private FileConfiguration config;
    private Location center;
    private final EnumMap<MonumentType, Boolean> completion = new EnumMap<>(MonumentType.class);

    // Simple toggle to silence noisy debug logs if desired
    private final boolean debug = true;

    public MonumentManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "monument.yml");
        logInfo("Constructing MonumentManager");
        load();
        logInfo("Constructed MonumentManager (center=" + center + ")");
    }

    /* -------------------------
       Public debug / inspection
       ------------------------- */

    /** Dump an internal snapshot to the logger */
    public void debugDump() {
        logInfo("=== MonumentManager debugDump ===");
        logInfo("file.exists=" + file.exists() + " path=" + file.getAbsolutePath());
        logInfo("center=" + center);
        logInfo("config=" + (config == null ? "null" : "loaded"));
        for (MonumentType t : MonumentType.values()) {
            logInfo("monument." + t.name() + " = " + completion.getOrDefault(t, false));
        }
        logInfo("=== end debugDump ===");
    }

    /** Run a quick self-check and log results */
    public void runSelfCheck() {
        logInfo("Running runSelfCheck()");
        try {
            logInfo("Checking file...");
            logInfo("file.exists=" + file.exists());
            if (file.exists() && config == null) {
                logWarning("Config file exists but config is null. Reloading config...");
                config = YamlConfiguration.loadConfiguration(file);
            }
            logInfo("Checking center...");
            if (center == null) {
                logWarning("center == null (no CTM generated or failed to load).");
            } else {
                logInfo("center is " + center);
            }
            logInfo("Checking monument map size: " + completion.size());
            for (MonumentType t : MonumentType.values()) {
                logInfo(" - " + t.name() + ": " + completion.getOrDefault(t, false));
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("runSelfCheck failed:");
            ex.printStackTrace();
        }
    }

    /** Return a copy snapshot of completion statuses (thread-safe-ish) */
    public Map<MonumentType, Boolean> getCompletionSnapshot() {
        synchronized (completion) {
            return new EnumMap<>(completion);
        }
    }

    /** Return center (may be null) */
    @Nullable
    public Location getCenter() {
        return center;
    }

    /** Expose file for sanity checks */
    public File getFile() {
        return file;
    }

    /* -------------------------
       Core load/save + helpers
       ------------------------- */

    private void load() {
        logInfo("load() start");
        try {
            if (!file.exists()) {
                logInfo("monument.yml does not exist -> creating default config");
                plugin.getDataFolder().mkdirs();
                config = new YamlConfiguration();
                for (MonumentType type : MonumentType.values()) {
                    completion.put(type, false);
                }
                save();
                logInfo("Created default monument.yml");
                return;
            }
            config = YamlConfiguration.loadConfiguration(file);
            logInfo("Loaded config from file");
            if (config.contains("center")) {
                String world = config.getString("center.world");
                double x = config.getDouble("center.x");
                double y = config.getDouble("center.y");
                double z = config.getDouble("center.z");
                World w = world != null ? Bukkit.getWorld(world) : null;
                if (w != null) {
                    center = new Location(w, x, y, z);
                    logInfo("Loaded center from config: " + center);
                } else {
                    logWarning("World for center not found: " + world);
                }
            } else {
                logInfo("No center in config");
            }
            for (MonumentType type : MonumentType.values()) {
                boolean val = config.getBoolean("monuments." + type.name(), false);
                completion.put(type, val);
                if (debug) logInfo("Loaded completion: " + type.name() + "=" + val);
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Exception in load():");
            ex.printStackTrace();
        } finally {
            logInfo("load() end");
        }
    }

    private void save() {
        logInfo("save() start");
        try {
            if (config == null) config = new YamlConfiguration();
            if (center != null) {
                try {
                    config.set("center.world", center.getWorld().getName());
                    config.set("center.x", center.getX());
                    config.set("center.y", center.getY());
                    config.set("center.z", center.getZ());
                } catch (Exception ex) {
                    logWarning("Failed to write center to config: " + ex.getMessage());
                }
            }
            for (Map.Entry<MonumentType, Boolean> e : completion.entrySet()) {
                config.set("monuments." + e.getKey().name(), e.getValue());
            }
            config.save(file);
            logInfo("Saved config to " + file.getAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().severe("IOException saving monument.yml:");
            e.printStackTrace();
        } catch (Exception e) {
            plugin.getLogger().severe("Unexpected exception saving monument.yml:");
            e.printStackTrace();
        } finally {
            logInfo("save() end");
        }
    }

    public boolean isCompleted(MonumentType type) {
        boolean val = completion.getOrDefault(type, false);
        if (debug) logInfo("isCompleted(" + type + ") -> " + val);
        return val;
    }

    private void markCompleted(MonumentType type) {
        logInfo("markCompleted(" + type + ") start");
        synchronized (completion) {
            completion.put(type, true);
            // persist immediately so that concurrent check sees it
            save();
        }
        logInfo("markCompleted(" + type + ") end");
    }

    /* -------------------------
       Events
       ------------------------- */

    @EventHandler
    public void onGenerate(GenerateCTMEvent event) {
        logInfo("onGenerate() called with location=" + event.getLocation());
        try {
            center = event.getLocation();
            for (MonumentType type : MonumentType.values()) {
                completion.put(type, false);
            }
            save();
            logInfo("onGenerate(): reset monuments and saved config");
        } catch (Exception ex) {
            plugin.getLogger().severe("Exception in onGenerate():");
            ex.printStackTrace();
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        logInfo("onBlockPlace() called by player=" + safeName(event.getPlayer()) + " block=" + event.getBlockPlaced().getType()
                + " at " + event.getBlockPlaced().getLocation());
        try {
            if (center == null) {
                logInfo("onBlockPlace: center==null -> ignoring");
                return;
            }
            if (!isNearCenter(event.getBlockPlaced().getLocation(), 30)) {
                logInfo("onBlockPlace: not near center -> ignoring");
                return;
            }

            Block placed = event.getBlockPlaced();
            Block base = findBase(placed.getLocation());
            if (base == null) {
                logInfo("onBlockPlace: findBase returned null -> ignoring");
                return;
            }
            logInfo("onBlockPlace: found base at " + base.getLocation());

            Location baseLoc = base.getLocation();
            Location placedLoc = placed.getLocation();
            boolean station = false;
            for (int dy = 1; dy <= 3; dy++) {
                if (placedLoc.getBlockX() == baseLoc.getBlockX() && placedLoc.getBlockY() == baseLoc.getBlockY() + dy && placedLoc.getBlockZ() == baseLoc.getBlockZ()) {
                    station = true;
                    break;
                }
            }
            if (!station) {
                logInfo("onBlockPlace: not a station placement -> ignoring");
                return;
            }

            Sign sign = findSign(base.getLocation());
            if (sign == null) {
                logInfo("onBlockPlace: no sign found near base -> ignoring");
                return;
            }

            // log sign lines & data for debugging
            try {
                for (int i = 0; i < 4; i++) {
                    String ln = sign.getLine(i);
                    logInfo("Sign line[" + i + "] = '" + ChatColor.stripColor(ln) + "'");
                }
            } catch (Exception e) {
                logWarning("Failed to read sign lines: " + e.getMessage());
            }

            String rawLine;
            try {
                rawLine = ChatColor.stripColor(sign.getLine(1)).trim();
            } catch (Exception e) {
                logWarning("Reading sign.getLine(1) failed, falling back to sign.toString()");
                rawLine = sign.toString();
            }
            logInfo("onBlockPlace: rawLine='" + rawLine + "'");
            MonumentType type = MonumentType.fromId(rawLine);
            logInfo("onBlockPlace: resolved MonumentType -> " + type);

            if (type == null) {
                logInfo("onBlockPlace: MonumentType==null -> ignoring");
                return;
            }

            if (!checkFilled(base.getLocation(), type)) {
                logInfo("onBlockPlace: checkFilled returned false -> ignoring");
                return;
            }

            synchronized (completion) {
                if (isCompleted(type)) {
                    logInfo("onBlockPlace: already completed -> ignoring");
                    return;
                }
                // mark completed here inside lock
                completion.put(type, true);
                save();
            }

            Player player = event.getPlayer();
            logInfo("onBlockPlace: completing monument for type=" + type + " by player=" + safeName(player));
            replaceWithGlass(base.getLocation(), type);
            rewardPlayer(player, base.getLocation(), type);
            playCelebration(base.getLocation(), type);
            Bukkit.getPluginManager().callEvent(new CompleteMonumentEvent(rawLine, type, base.getLocation(), player.getUniqueId(), System.currentTimeMillis()));
            logInfo("onBlockPlace: CompleteMonumentEvent fired");
        } catch (Throwable t) {
            plugin.getLogger().severe("Unhandled exception in onBlockPlace:");
            t.printStackTrace();
        } finally {
            logInfo("onBlockPlace() end");
        }
    }

    @EventHandler
    public void onSignInteract(PlayerInteractEvent event) {
        if (center == null) {
            if (debug) logInfo("onSignInteract: center==null -> ignoring");
            return;
        }
        if (!event.hasBlock()) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (!Tag.SIGNS.isTagged(block.getType())) return;
        if (!isNearCenter(block.getLocation(), 45)) return;
        logInfo("onSignInteract: cancelling interaction at " + block.getLocation());
        event.setCancelled(true);
    }

    @EventHandler
    public void onSignBreak(BlockBreakEvent event) {
        if (center == null) return;
        Block block = event.getBlock();
        if (!Tag.SIGNS.isTagged(block.getType())) return;
        if (!isNearCenter(block.getLocation(), 30)) return;
        logInfo("onSignBreak: cancelling sign break at " + block.getLocation() + " by " + safeName(event.getPlayer()));
        event.setCancelled(true);
    }

    /* -------------------------
       Helper routines
       ------------------------- */

    private boolean isNearCenter(Location loc, double radius) {
        if (center == null || loc.getWorld() == null) {
            if (debug) logInfo("isNearCenter: center==null or loc.world==null -> false");
            return false;
        }
        if (!center.getWorld().equals(loc.getWorld())) {
            if (debug) logInfo("isNearCenter: different world -> false");
            return false;
        }
        double dsq = loc.distanceSquared(center);
        boolean res = dsq <= radius * radius;
        if (debug) logInfo("isNearCenter: loc=" + loc + " center=" + center + " dsq=" + dsq + " radius=" + radius + " -> " + res);
        return res;
    }

    @Nullable
    private Block findBase(Location placed) {
        logInfo("findBase() start for placed=" + placed);
        World world = placed.getWorld();
        if (world == null) {
            logWarning("findBase: placed.getWorld() == null");
            return null;
        }

        int px = placed.getBlockX();
        int py = placed.getBlockY();
        int pz = placed.getBlockZ();

        // 1) Fast common-case: bedrock directly below (placed at base+1)
        Block below = world.getBlockAt(px, py - 1, pz);
        if (below.getType() == Material.BEDROCK) {
            logInfo("findBase: direct bedrock below -> " + below.getLocation());
            return below;
        }

        // 2) Downward scan: cover the case where the placed block is at base+2 or base+3
        //    (i.e., bedrock lies 2..5 blocks below). Adjust maxDepth if your monuments sit deeper.
        int maxDepth = 5; // safe, small number
        for (int d = 2; d <= maxDepth; d++) {
            Block b = world.getBlockAt(px, py - d, pz);
            if (b.getType() == Material.BEDROCK) {
                logInfo("findBase: found bedrock by downward scan at " + b.getLocation() + " (dy=" + d + ")");
                return b;
            }
        }

        // 3) Fallback: local radius search but with a larger vertical range
        int radius = 2;
        Block nearest = null;
        double best = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 1; dy++) { // include deeper offsets
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(px + dx, py + dy, pz + dz);
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
        logInfo("findBase() end -> nearest=" + (nearest == null ? "null" : nearest.getLocation()) + " best=" + best);
        return nearest;
    }


    @Nullable
    private Sign findSign(Location base) {
        logInfo("findSign() start for base=" + base);
        World world = base.getWorld();
        if (world == null) {
            logWarning("findSign: base.getWorld() == null");
            return null;
        }
        int radius = 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(base.getBlockX() + dx, base.getBlockY() + dy, base.getBlockZ() + dz);
                    if (Tag.SIGNS.isTagged(b.getType())) {
                        BlockState state = b.getState();
                        if (state instanceof Sign sign) {
                            logInfo("findSign: found sign at " + b.getLocation());
                            return sign;
                        } else {
                            logInfo("findSign: found sign-block but state not Sign: " + b.getLocation() + " state=" + state);
                        }
                    }
                }
            }
        }
        logInfo("findSign() end -> null");
        return null;
    }

    private boolean checkFilled(Location base, MonumentType type) {
        logInfo("checkFilled() start for base=" + base + " type=" + type);
        World world = base.getWorld();
        if (world == null) {
            logWarning("checkFilled: base.getWorld() == null");
            return false;
        }
        for (int dy = 1; dy <= 3; dy++) {
            Block b = world.getBlockAt(base.getBlockX(), base.getBlockY() + dy, base.getBlockZ());
            Material m = b.getType();
            if (m != type.getRequiredMaterial()) {
                logInfo("checkFilled: mismatch at y=" + (base.getBlockY() + dy) + " found=" + m + " expected=" + type.getRequiredMaterial());
                return false;
            }
        }
        logInfo("checkFilled: all matched for type=" + type);
        return true;
    }

    private void replaceWithGlass(Location base, MonumentType type) {
        logInfo("replaceWithGlass() start for base=" + base + " type=" + type);
        World world = base.getWorld();
        if (world == null) {
            logWarning("replaceWithGlass: world==null");
            return;
        }
        for (int dy = 1; dy <= 3; dy++) {
            Block b = world.getBlockAt(base.getBlockX(), base.getBlockY() + dy, base.getBlockZ());
            try {
                b.setType(type.getGlassMaterial(), false);
                logInfo("replaceWithGlass: set " + b.getLocation() + " -> " + type.getGlassMaterial());
            } catch (Exception e) {
                logWarning("replaceWithGlass: failed to set block at " + b.getLocation() + " : " + e.getMessage());
            }
        }
        logInfo("replaceWithGlass() end");
    }

    private void rewardPlayer(Player player, Location base, MonumentType type) {
        logInfo("rewardPlayer() start for player=" + safeName(player) + " base=" + base + " type=" + type);
        World world = base.getWorld();
        if (world == null) {
            logWarning("rewardPlayer: world==null");
            return;
        }
        ItemStack reward = new ItemStack(type.getRewardMaterial());
        Location dropLoc = base.clone().add(0.5, 1.2, 0.5);
        Item item = world.dropItem(dropLoc, reward);
        Vector vec = player.getLocation().toVector().subtract(dropLoc.toVector());
        // guard against zero vector
        if (vec.lengthSquared() < 1e-8) {
            logInfo("rewardPlayer: zero-length vector, applying small upward nudge");
            vec = new Vector(0, 0.25, 0);
        } else {
            vec = vec.normalize().multiply(0.5);
        }
        item.setVelocity(vec);
        logInfo("rewardPlayer: dropped item at " + dropLoc + " velocity=" + vec);
    }

    private void playCelebration(Location base, MonumentType type) {
        logInfo("playCelebration() start for base=" + base + " type=" + type);
        World world = base.getWorld();
        if (world == null) {
            logWarning("playCelebration: world==null");
            return;
        }
        try {
            world.playSound(base, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            Bukkit.broadcastMessage(ChatColor.AQUA + "Monument Complete!");
        } catch (Exception e) {
            logWarning("playCelebration: playSound failed: " + e.getMessage());
        }
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                try {
                    if (ticks++ > 600) {
                        cancel();
                        return;
                    }
                    // cheap proximity check: only spawn particles if players are nearby (to reduce noise)
                    boolean someoneNear = world.getPlayers().stream().anyMatch(p -> p.getLocation().distanceSquared(base) < 64 * 64);
                    if (!someoneNear) return;
                    world.spawnParticle(Particle.DUST, base.clone().add(0.5,1,0.5), 8, 0.3, 0.5, 0.3, 0, new Particle.DustOptions(type.getColor(), 1));
                } catch (Throwable t) {
                    plugin.getLogger().severe("Exception in celebration runnable:");
                    t.printStackTrace();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
        logInfo("playCelebration() scheduled particle task");
    }

    /* -------------------------
       Small util logging helpers
       ------------------------- */

    private void logInfo(String s) {
        if (debug) plugin.getLogger().info("[MonumentManager] " + s);
    }

    private void logWarning(String s) {
        plugin.getLogger().warning("[MonumentManager] " + s);
    }

    private String safeName(Player p) {
        return p == null ? "null" : p.getName() + "(" + p.getUniqueId() + ")";
    }
    private static final Map<String, MonumentType> MONUMENT_SYNONYMS = new HashMap<>();
    static {
        // common compat entries (uppercased, no underscores)
        MONUMENT_SYNONYMS.put("LIGHT_GRAY", MonumentType.LIGHT_GRAY);
        MONUMENT_SYNONYMS.put("LIGHT_BLUE", MonumentType.LIGHT_BLUE); // common typo
        // add more historical/misspelled names here if you find them
    }
}
