package goat.thaw.system;

import goat.thaw.subsystems.combat.PopulationManager;
import goat.thaw.system.stats.StatInstance;
import goat.thaw.system.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;

public class TablistManager implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager stats;
    private final PopulationManager population;
    private BukkitTask task;

    public TablistManager(JavaPlugin plugin, StatsManager stats, PopulationManager population) {
        this.plugin = plugin;
        this.stats = stats;
        this.population = population;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Update every 10 ticks (0.5s) to start
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 10L);
        // Apply immediately to online players
        tick();
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        // Clear headers/footers on stop
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.setPlayerListHeaderFooter("", ""); } catch (Throwable ignore) {}
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { updatePlayer(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { /* nothing to clear explicitly */ }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) updatePlayer(p);
    }

    private void updatePlayer(Player p) {
        // Calories
        double cal = 0.0, calMax = 3000.0;
        StatInstance calInst = stats.get(p.getUniqueId(), "Calories");
        if (calInst != null) { cal = calInst.get(); calMax = calInst.getDefinition().getMax(); }
        double calPct = calMax > 0 ? Math.min(cal, calMax) / calMax : 0.0;
        ChatColor calNumColor = (cal >= 2000.0 && cal <= 3000.0) ? ChatColor.GREEN
                : (cal >= 1000.0 ? ChatColor.YELLOW : ChatColor.RED);
        String calWord = (calNumColor == ChatColor.GREEN) ? "healthy" : (calNumColor == ChatColor.YELLOW ? "caution" : "danger");
        String calLine = ChatColor.YELLOW + "Calories: " + calNumColor + String.format(Locale.US, "%.0f%%", calPct * 100.0)
                + ChatColor.GRAY + " (" + calWord + ")" + ChatColor.RESET;

        // Oxygen
        double oxy = 0.0, oxyMax = 1000.0;
        StatInstance oxyInst = stats.get(p.getUniqueId(), "Oxygen");
        if (oxyInst != null) { oxy = oxyInst.get(); oxyMax = oxyInst.getDefinition().getMax(); }
        double oxyPct = oxyMax > 0 ? Math.min(oxy, oxyMax) / oxyMax : 0.0;
        ChatColor oxyNumColor = (oxyPct >= 0.5) ? ChatColor.GREEN : (oxyPct >= 0.2 ? ChatColor.YELLOW : ChatColor.RED);
        String oxyWord = (oxyNumColor == ChatColor.GREEN) ? "healthy" : (oxyNumColor == ChatColor.YELLOW ? "caution" : "hypoxic");
        String oxyLine = ChatColor.AQUA + "Oxygen: " + oxyNumColor + String.format(Locale.US, "%.0f%%", oxyPct * 100.0)
                + ChatColor.GRAY + " (" + oxyWord + ")" + ChatColor.RESET;

        // Thermal (Temperature)
        double temp = 72.0;
        StatInstance tInst = stats.get(p.getUniqueId(), "Temperature");
        if (tInst != null) temp = tInst.get();
        ChatColor tempColor;
        if (temp >= 65.0 && temp <= 85.0) tempColor = ChatColor.GREEN;
        else if ((temp >= 35.0 && temp < 65.0) || (temp > 85.0 && temp <= 110.0)) tempColor = ChatColor.YELLOW;
        else tempColor = ChatColor.RED;
        String thermalWord;
        if (tempColor == ChatColor.GREEN) thermalWord = "healthy";
        else if (tempColor == ChatColor.YELLOW) thermalWord = (temp < 65.0 ? "shivering" : "overheated");
        else thermalWord = (temp < 65.0 ? "frostbite" : "heatstroke");

        // Thermal "optimal" percent: 100% in green band, scales within yellow bands, 0% in red bands
        double optimalPct;
        if (temp >= 65.0 && temp <= 85.0) optimalPct = 1.0;
        else if (temp >= 35.0 && temp < 65.0) optimalPct = Math.max(0.0, (temp - 35.0) / 30.0);
        else if (temp > 85.0 && temp <= 110.0) optimalPct = Math.max(0.0, (110.0 - temp) / 25.0);
        else optimalPct = 0.0;
        String thermalLine = ChatColor.RED + "Thermal: " + tempColor + String.format(Locale.US, "%.0f%%", optimalPct * 100.0)
                + ChatColor.GRAY + " (" + thermalWord + ")" + ChatColor.RESET;

        // Effects placeholder
        String effectsLine = ChatColor.WHITE + "Effects: " + ChatColor.GRAY + "(none)" + ChatColor.RESET;

        String worldLine = ChatColor.WHITE + "World: " + ChatColor.RED + "Monster Pop "
                + ChatColor.WHITE + String.format(Locale.US, "%.1f", population.getMonsterPopulation());

        // External temperature from DICE/Spaces
        double ext = 0.0; // bias
        StatInstance extInst = stats.get(p.getUniqueId(), "ExternalTemperature");
        if (extInst != null) ext = extInst.get();
        String extLine = ChatColor.BLUE + "External Temp: " + ChatColor.WHITE + String.format(Locale.US, "%+.1f F", ext);

        String header = ChatColor.AQUA + "=== Condition ===\n" + calLine + "\n" + oxyLine + "\n" + thermalLine + "\n" + effectsLine + "\n" + worldLine + "\n" + extLine;
        String footer = "";
        try {
            p.setPlayerListHeaderFooter(header, footer);
        } catch (Throwable ignore) {
            // Some forks require separate calls
            try { p.setPlayerListHeader(header); } catch (Throwable ignore2) {}
            try { p.setPlayerListFooter(footer); } catch (Throwable ignore3) {}
        }
    }
}
