package goat.thaw;

import goat.thaw.dev.DecomissionCommand;
import goat.thaw.dev.GenerateArcticCommand;
import goat.thaw.dev.RegenerateArcticCommand;
import goat.thaw.dev.WarpToCommand;
import goat.thaw.dev.TeleportToPeakCommand;
import goat.thaw.resourcepack.ResourcePackListener;
import goat.thaw.stats.StatsCommand;
import goat.thaw.stats.StatsManager;
import goat.thaw.hunting.TrailManager;
import goat.thaw.hunting.SpawnTrailStartCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Thaw extends JavaPlugin {

    private SidebarManager sidebarManager;
    private StatsManager statsManager;
    private TablistManager tablistManager;
    private PopulationManager populationManager;
    private DailyAnnouncementManager dailyAnnouncementManager;
    private TrailManager trailManager;
    private CalorieManager calorieManager;
    private ActivityEnergyManager activityEnergyManager;
    private ThermalRegulator thermalRegulator;

    @Override
    public void onEnable() {
        // getServer().getPluginManager().registerEvents(new ResourcePackListener(), this);

        // Dev commands
        if (getCommand("generatearctic") != null) {
            getCommand("generatearctic").setExecutor(new GenerateArcticCommand());
        }
        if (getCommand("warpto") != null) {
            getCommand("warpto").setExecutor(new WarpToCommand());
        }
        if (getCommand("decomission") != null) {
            getCommand("decomission").setExecutor(new DecomissionCommand());
        }
        if (getCommand("regeneratearctic") != null) {
            getCommand("regeneratearctic").setExecutor(new RegenerateArcticCommand());
        }
        if (getCommand("teleporttopeak") != null) {
            getCommand("teleporttopeak").setExecutor(new TeleportToPeakCommand());
        }

        // Stats system: load definitions and persistent values
        statsManager = new StatsManager(this);
        statsManager.start();
        if (getCommand("statscommand") != null) {
            StatsCommand sc = new StatsCommand(statsManager);
            getCommand("statscommand").setExecutor(sc);
            getCommand("statscommand").setTabCompleter(sc);
        }

        // Population & daily announcements
        dailyAnnouncementManager = new DailyAnnouncementManager();

        // Trails: dev spawning + random/daybreak hooks
        trailManager = new TrailManager(this);
        trailManager.start();
        if (getCommand("spawntrailstart") != null) {
            getCommand("spawntrailstart").setExecutor(new SpawnTrailStartCommand(trailManager));
        }

        populationManager = new PopulationManager(this, statsManager, dailyAnnouncementManager, trailManager);
        populationManager.start();

        // Calories: hunger + health coupling
        calorieManager = new CalorieManager(this, statsManager);
        calorieManager.start();

        // Activity energy drain
        activityEnergyManager = new ActivityEnergyManager(this, statsManager);
        activityEnergyManager.start();

        // Thermal regulation using calories
        thermalRegulator = new ThermalRegulator(this, statsManager);
        thermalRegulator.start();

        // Sidebar: live environment HUD with Temperature
        sidebarManager = new SidebarManager(this, statsManager);
        sidebarManager.start();

        // Tablist: condition overview segments + world pop
        tablistManager = new TablistManager(this, statsManager, populationManager);
        tablistManager.start();

        // (Trail dev command already registered above)
    }

    @Override
    public void onDisable() {
        if (sidebarManager != null) sidebarManager.stop();
        if (statsManager != null) statsManager.stop();
        if (tablistManager != null) tablistManager.stop();
        if (populationManager != null) populationManager.stop();
        if (trailManager != null) trailManager.stop();
        if (calorieManager != null) calorieManager.stop();
        if (activityEnergyManager != null) activityEnergyManager.stop();
        if (thermalRegulator != null) thermalRegulator.stop();
    }
}
