package goat.thaw;

import goat.thaw.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class PopulationManager implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager stats;
    private final DailyAnnouncementManager announcements;
    private final goat.thaw.hunting.TrailManager trails;

    private double monsterPopulation = 50.0; // initial
    private long lastDayObserved = Long.MIN_VALUE;
    private BukkitTask dayTask;
    private File stateFile;

    public PopulationManager(JavaPlugin plugin, StatsManager stats, DailyAnnouncementManager announcements, goat.thaw.hunting.TrailManager trails) {
        this.plugin = plugin;
        this.stats = stats;
        this.announcements = announcements;
        this.trails = trails;
    }

    public void start() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        stateFile = new File(plugin.getDataFolder(), "state.yml");
        loadState();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Check day change every 100 ticks (~5s) to be safe
        dayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkDayChange, 0L, 100L);
    }

    public void stop() {
        if (dayTask != null) { dayTask.cancel(); dayTask = null; }
        saveState();
    }

    public double getMonsterPopulation() {
        return monsterPopulation;
    }

    private void setMonsterPopulation(double v) {
        monsterPopulation = Math.max(0.0, v);
        saveState();
    }

    private void checkDayChange() {
        World overworld = Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(Bukkit.getWorlds().get(0));
        long fullTime = overworld.getFullTime();
        long day = fullTime / 24000L;
        if (day != lastDayObserved) {
            lastDayObserved = day;
            // New day: increase population by 1 and announce
            setMonsterPopulation(monsterPopulation + 1.0);
            announcements.announceNewDay(day, monsterPopulation);
            // Spawn a footprint within 30 blocks of each player at daybreak
            Bukkit.getOnlinePlayers().forEach(p -> {
                try {
                    World w = p.getWorld();
                    double ang = Math.random() * Math.PI * 2.0;
                    double dist = 8.0 + Math.random() * 22.0; // 8..30
                    double tx = p.getLocation().getX() + Math.cos(ang) * dist;
                    double tz = p.getLocation().getZ() + Math.sin(ang) * dist;
                    int bx = (int) Math.floor(tx);
                    int bz = (int) Math.floor(tz);
                    if (w == null) return;
                    if (w.getBlockAt(bx, w.getHighestBlockYAt(bx, bz), bz).isLiquid()) return;
                    trails.spawnTrailStart(new org.bukkit.Location(w, bx, w.getHighestBlockYAt(bx, bz), bz));
                } catch (Throwable ignore) {}
            });
            // Increment DayCount for online players
            Bukkit.getOnlinePlayers().forEach(p -> stats.add(p.getUniqueId(), "DayCount", 1.0));
        }
    }

    @EventHandler
    public void onMonsterDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Monster) {
            setMonsterPopulation(monsterPopulation - 0.1);
        }
    }

    @EventHandler
    public void onMonsterSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Monster)) return;
        int current = currentMonsterCount();
        int cap = (int) Math.floor(monsterPopulation);
        if (current >= cap) {
            e.setCancelled(true);
        }
    }

    private int currentMonsterCount() {
        int total = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity ent : w.getEntitiesByClass(Monster.class)) total++;
        }
        return total;
    }

    private void loadState() {
        if (!stateFile.exists()) { saveState(); return; }
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
            this.monsterPopulation = y.getDouble("monsterPopulation", 50.0);
            this.lastDayObserved = y.getLong("lastDayObserved", Long.MIN_VALUE);
        } catch (Exception ignore) {}
    }

    private void saveState() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("monsterPopulation", monsterPopulation);
            y.set("lastDayObserved", lastDayObserved);
            y.save(stateFile);
        } catch (IOException ignore) {}
    }
}
