package goat.thaw.subsystems.temperature;

import goat.thaw.system.stats.StatInstance;
import goat.thaw.system.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Uses Calories to regulate player Temperature toward 72 F when outside the Healthy band (65-85 F).
 *
 * Calorie tiers:
 *  - 2000..3000: 1 degree / 5s (0.1 deg per 0.5s step), cost 2 cal/deg
 *  - 1000..2000: 1 degree / 10s (0.05 deg per 0.5s step), cost 2 cal/deg
 *  - <1000: no regulation
 *  - Inside 65..85 F: no regulation (save calories)
 */
public class ThermalRegulator implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager stats;
    private BukkitTask task;

    private static final double SETPOINT = 72.0;
    private static final double HEALTHY_MIN = 65.0;
    private static final double HEALTHY_MAX = 85.0;
    private static final double COST_PER_DEGREE = 2.0; // cal/deg

    public ThermalRegulator(JavaPlugin plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L); // every 0.5s
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            StatInstance temp = stats.get(p.getUniqueId(), "Temperature");
            StatInstance cal = stats.get(p.getUniqueId(), "Calories");
            if (temp == null || cal == null) continue;

            double t = temp.get();
            double cals = cal.get();

            // Skip when in healthy band
            if (t >= HEALTHY_MIN && t <= HEALTHY_MAX) continue;

            // Determine tier
            double stepDeg; // degrees per 0.5s
            if (cals >= 2000.0) stepDeg = 0.1; // 1 deg / 5s
            else if (cals >= 1000.0) stepDeg = 0.05; // 1 deg / 10s
            else stepDeg = 0.0;
            if (stepDeg <= 0.0) continue;

            // Move toward setpoint
            double diff = SETPOINT - t;
            double dir = Math.signum(diff);
            double applied = Math.min(Math.abs(diff), stepDeg);
            if (applied <= 0.0) continue;

            double cost = applied * COST_PER_DEGREE;
            if (cals < cost) continue; // not enough fuel; skip this tick

            temp.set(t + dir * applied);
            cal.subtract(cost);
        }
    }
}

