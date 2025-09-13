package goat.thaw.subsystems.temperature;

import goat.thaw.system.stats.StatInstance;
import goat.thaw.system.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Adjusts existing fire ticks on players based on their body temperature.
 * Does not ignite players; only modifies ongoing fire duration.
 */
public class FireTickManager {
    private final JavaPlugin plugin;
    private final StatsManager stats;
    private BukkitTask task;

    public FireTickManager(JavaPlugin plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L); // every second
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            int fire = p.getFireTicks();
            if (fire <= 0) continue;

            StatInstance temp = stats.get(p.getUniqueId(), "Temperature");
            double t = temp != null ? temp.get() : 72.0;

            if (t < 50.0) {
                // Too cold to maintain fire
                p.setFireTicks(0);
            } else if (t < 65.0) {
                // Halve remaining duration
                p.setFireTicks(Math.max(0, fire - 20));
            } else if (t >= 190.0) {
                // Overheated: maintain high ticks for permanent burn
                p.setFireTicks(Math.max(fire, 160));
            } else if (t > 100.0) {
                // Hotter: extend burn time by ~50%
                p.setFireTicks(fire + 7);
            }
        }
    }
}

