package goat.thaw.system.monument;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads monument.yml and provides validation/completion utilities.
 */
public class MonumentManager {
    private final JavaPlugin plugin;
    private final Map<String, Monument> monuments = new HashMap<>();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private File dataFile;

    public MonumentManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.getDataFolder().mkdirs();
        dataFile = new File(plugin.getDataFolder(), "monument.yml");
        if (!dataFile.exists()) return; // generated elsewhere
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = cfg.getConfigurationSection("monuments");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            MonumentType type;
            try {
                type = MonumentType.valueOf(id.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Location center = parseLocation(s.getString("center"));
            Location base = parseLocation(s.getString("base"));
            List<int[]> offsets = parseOffsets(s.getList("stationOffsets"));
            Location sign = parseLocation(s.getString("sign"));
            boolean completed = s.getBoolean("completed", false);
            UUID completedBy = null;
            if (s.contains("completedBy")) {
                try { completedBy = UUID.fromString(s.getString("completedBy")); } catch (Exception ignore) {}
            }
            long completedAt = s.getLong("completedAt", 0L);
            Monument m = new Monument(id, type, center, base, offsets, sign, completed, completedBy, completedAt);
            monuments.put(id, m);
            locks.put(id, new ReentrantLock());
        }
    }

    private Location parseLocation(String str) {
        if (str == null) return null;
        String[] parts = str.split(",");
        if (parts.length != 4) return null;
        World w = Bukkit.getWorld(parts[0]);
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);
        return new Location(w, x, y, z);
    }

    private List<int[]> parseOffsets(List<?> raw) {
        if (raw == null) return java.util.Collections.emptyList();
        List<int[]> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof List<?> l && l.size() == 3) {
                int x = ((Number) l.get(0)).intValue();
                int y = ((Number) l.get(1)).intValue();
                int z = ((Number) l.get(2)).intValue();
                out.add(new int[]{x, y, z});
            } else if (o instanceof String s) {
                // Legacy format: "[x,y,z]"
                String[] parts = s.replaceAll("\\[|\\]", "").split(",");
                if (parts.length == 3) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int z = Integer.parseInt(parts[2].trim());
                        out.add(new int[]{x, y, z});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return out;
    }

    public Monument getMonumentAt(Location loc) {
        if (loc == null) return null;
        for (Monument m : monuments.values()) {
            if (!sameWorld(loc, m.getCenter())) continue;
            if (m.getCenter().distance(loc) <= 30) {
                return m;
            }
        }
        return null;
    }

    public Location getNearestBase(Location loc, double maxDist) {
        Monument closest = null;
        double best = maxDist;
        for (Monument m : monuments.values()) {
            if (!sameWorld(loc, m.getBase())) continue;
            double d = m.getBase().distance(loc);
            if (d <= best) { best = d; closest = m; }
        }
        return closest == null ? null : closest.getBase();
    }

    public boolean validateStations(Monument m) {
        if (m == null) return false;
        Location base = m.getBase();
        World w = base.getWorld();
        if (w == null) return false;
        for (int[] off : m.getStationOffsets()) {
            int x = base.getBlockX() + off[0];
            int y = base.getBlockY() + off[1];
            int z = base.getBlockZ() + off[2];
            Block b = w.getBlockAt(x, y, z);
            if (b.getType() != m.getType().getStationMaterial()) return false;
        }
        return true;
    }

    public boolean isCompleted(String id) {
        Monument m = monuments.get(id);
        return m != null && m.isCompleted();
    }

    public boolean markCompleted(String id, UUID player, long timestamp) {
        Monument m = monuments.get(id);
        if (m == null) return false;
        Lock lock = locks.get(id);
        lock.lock();
        try {
            if (m.isCompleted()) return false;
            if (!validateStations(m)) return false;
            m.setCompleted(true);
            m.setCompletedBy(player);
            m.setCompletedAt(timestamp);
            if (!saveAll()) {
                m.setCompleted(false);
                m.setCompletedBy(null);
                m.setCompletedAt(0L);
                return false;
            }
            Bukkit.getPluginManager().callEvent(new CompleteMonumentStandEvent(id, m.getType(), m.getBase(), player, timestamp));
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean saveAll() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Monument m : monuments.values()) {
            String path = "monuments." + m.getId();
            cfg.set(path + ".center", formatLocation(m.getCenter()));
            cfg.set(path + ".base", formatLocation(m.getBase()));
            List<List<Integer>> offs = new ArrayList<>();
            for (int[] o : m.getStationOffsets()) {
                offs.add(Arrays.asList(o[0], o[1], o[2]));
            }
            cfg.set(path + ".stationOffsets", offs);
            if (m.getSign() != null) cfg.set(path + ".sign", formatLocation(m.getSign()));
            cfg.set(path + ".completed", m.isCompleted());
            if (m.getCompletedBy() != null) cfg.set(path + ".completedBy", m.getCompletedBy().toString());
            if (m.getCompletedAt() != 0L) cfg.set(path + ".completedAt", m.getCompletedAt());
        }
        File tmp = new File(dataFile.getParentFile(), dataFile.getName() + ".tmp");
        try {
            cfg.save(tmp);
            if (dataFile.exists()) {
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path backup = new File(dataFile.getParentFile(), dataFile.getName() + ".bak-" + ts).toPath();
                Files.copy(dataFile.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to persist monument.yml: " + e.getMessage());
            return false;
        } finally {
            tmp.delete();
        }
    }

    private String formatLocation(Location loc) {
        return String.format(Locale.US, "%s,%d,%d,%d", loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && b.getWorld() != null && a.getWorld().getName().equals(b.getWorld().getName());
    }

    public Monument get(String id) {
        return monuments.get(id);
    }
}
