package goat.thaw.system.stats;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class StatsManager implements Listener {

    private final JavaPlugin plugin;
    private final Map<String, StatDefinition> definitions = new HashMap<>(); // key: lower-case name
    private final Map<UUID, Map<String, StatInstance>> playerStats = new HashMap<>();
    private BukkitTask saveTask;
    private File statsDir;

    public StatsManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // Lifecycle
    public void start() {
        // Prepare dirs
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        statsDir = new File(plugin.getDataFolder(), "stats");
        if (!statsDir.exists()) statsDir.mkdirs();

        // Register default stats
        registerDefinition(new StatDefinition("Temperature", 72.0, 200.0, -200.0));
        registerDefinition(new StatDefinition("ExternalTemperature", 65.0, 200.0, -200.0));
        registerDefinition(new StatDefinition("Calories", 3000.0, 3400.0, 0.0));
        registerDefinition(new StatDefinition("Oxygen", 500.0, 1000.0, 0.0));
        registerDefinition(new StatDefinition("DayCount", 0.0, 1000000.0, 0.0));

        // Load all existing stats from disk
        loadAllFromDisk();

        // Ensure currently online players have entries
        for (Player p : Bukkit.getOnlinePlayers()) ensurePlayerLoaded(p.getUniqueId());

        // Listen for joins to initialize missing files
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Save every 10 seconds (200 ticks); refresh from disk after saving
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            saveAllToDisk();
            reloadAllFromDisk();
        }, 200L, 200L);
    }

    public void stop() {
        if (saveTask != null) { saveTask.cancel(); saveTask = null; }
        saveAllToDisk();
    }

    // Definitions
    public void registerDefinition(StatDefinition def) {
        definitions.put(def.getName().toLowerCase(Locale.ROOT), def);
    }

    public StatDefinition getDefinition(String name) {
        if (name == null) return null;
        return definitions.get(name.toLowerCase(Locale.ROOT));
    }

    public Set<String> getDefinedNames() { return Collections.unmodifiableSet(definitions.keySet()); }

    // Player access
    public Map<String, StatInstance> getAll(UUID uuid) {
        return playerStats.computeIfAbsent(uuid, id -> new HashMap<>());
    }

    public StatInstance get(UUID uuid, String statName) {
        StatDefinition def = getDefinition(statName);
        if (def == null) return null;
        Map<String, StatInstance> map = getAll(uuid);
        return map.computeIfAbsent(def.getName().toLowerCase(Locale.ROOT), k -> new StatInstance(def, def.getInitial()));
    }

    public boolean set(UUID uuid, String statName, double value) {
        StatInstance inst = get(uuid, statName);
        if (inst == null) return false;
        inst.set(value);
        return true;
    }

    public boolean add(UUID uuid, String statName, double delta) {
        StatInstance inst = get(uuid, statName);
        if (inst == null) return false;
        inst.add(delta);
        return true;
    }

    public boolean subtract(UUID uuid, String statName, double delta) {
        StatInstance inst = get(uuid, statName);
        if (inst == null) return false;
        inst.subtract(delta);
        return true;
    }

    // Events
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ensurePlayerLoaded(e.getPlayer().getUniqueId());
        // If no file exists, create with defaults immediately
        File f = fileFor(e.getPlayer().getUniqueId());
        if (!f.exists()) {
            initDefaults(e.getPlayer().getUniqueId());
            saveToDisk(e.getPlayer().getUniqueId());
        }
    }

    // Persistence
    private File fileFor(UUID uuid) { return new File(statsDir, uuid.toString() + ".yml"); }

    private void loadAllFromDisk() {
        File[] files = statsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            try {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                String uuidStr = f.getName().substring(0, f.getName().length() - 4);
                UUID uuid = UUID.fromString(uuidStr);
                Map<String, StatInstance> map = getAll(uuid);
                for (String key : definitions.keySet()) {
                    StatDefinition def = definitions.get(key);
                    double val = yml.getDouble("stats." + def.getName(), def.getInitial());
                    map.put(def.getName().toLowerCase(Locale.ROOT), new StatInstance(def, val));
                }
            } catch (Exception ignore) {}
        }
    }

    private void reloadAllFromDisk() {
        // Reload only for players we have in memory
        for (UUID uuid : new ArrayList<>(playerStats.keySet())) {
            loadFromDisk(uuid);
        }
    }

    private void loadFromDisk(UUID uuid) {
        File f = fileFor(uuid);
        if (!f.exists()) return;
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            Map<String, StatInstance> map = getAll(uuid);
            for (String key : definitions.keySet()) {
                StatDefinition def = definitions.get(key);
                double val = yml.getDouble("stats." + def.getName(), def.getInitial());
                map.put(def.getName().toLowerCase(Locale.ROOT), new StatInstance(def, val));
            }
        } catch (Exception ignore) {}
    }

    private void saveAllToDisk() {
        // Save stats for known players (both online and loaded offline)
        for (UUID uuid : new ArrayList<>(playerStats.keySet())) {
            saveToDisk(uuid);
        }
    }

    private void saveToDisk(UUID uuid) {
        File f = fileFor(uuid);
        YamlConfiguration yml = new YamlConfiguration();
        Map<String, StatInstance> map = playerStats.get(uuid);
        if (map == null) return;
        for (Map.Entry<String, StatInstance> e : map.entrySet()) {
            String properName = e.getValue().getDefinition().getName();
            yml.set("stats." + properName, e.getValue().get());
        }
        try {
            yml.save(f);
        } catch (IOException ignore) {}
    }

    private void ensurePlayerLoaded(UUID uuid) {
        Map<String, StatInstance> map = getAll(uuid);
        File f = fileFor(uuid);
        if (f.exists()) {
            loadFromDisk(uuid);
        } else {
            initDefaults(uuid);
        }
    }

    private void initDefaults(UUID uuid) {
        Map<String, StatInstance> map = getAll(uuid);
        for (StatDefinition def : definitions.values()) {
            map.put(def.getName().toLowerCase(Locale.ROOT), new StatInstance(def, def.getInitial()));
        }
    }
}
