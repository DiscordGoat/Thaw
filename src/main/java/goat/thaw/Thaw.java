package goat.thaw;

import goat.thaw.subsystems.calories.ActivityEnergyManager;
import goat.thaw.subsystems.combat.PopulationManager;
import goat.thaw.subsystems.temperature.ThermalRegulator;
import goat.thaw.system.FirstJoinListener;
import goat.thaw.system.dev.*;
import goat.thaw.system.space.SpaceManager;
import goat.thaw.system.space.SpaceEventListener;
import goat.thaw.system.space.SpacePresenceListener;
import goat.thaw.system.space.SpaceBlockListener;
import goat.thaw.system.stats.StatsCommand;
import goat.thaw.system.stats.StatsManager;
import goat.thaw.subsystems.hunting.TrailManager;
import goat.thaw.subsystems.hunting.TrailRenderMode;
import goat.thaw.subsystems.hunting.SpawnTrailStartCommand;
import goat.thaw.subsystems.calories.CalorieManager;
import goat.thaw.system.DailyAnnouncementManager;
import goat.thaw.system.SidebarManager;
import goat.thaw.system.TablistManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;
import goat.thaw.subsystems.temperature.DiceManager;
import goat.thaw.system.logging.DiceLogger;
import goat.thaw.system.effects.EffectManager;
import goat.thaw.subsystems.oxygen.OxygenManager;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

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
    private SpaceManager spaceManager;
    private DiceManager diceManager;
    private DiceLogger diceLogger;
    private SledManager sledManager;
    private EffectManager effectManager;
    private OxygenManager oxygenManager;
    private SchemManager schematicManager;
    private static final List<String> BUNGALOW_SCHEMATICS = Arrays.asList(
            "fire",
            "scholar",
            "mourner",
            "burglar",
            "farmer",
            "pacifist"
            // add more when you create them
    );

    @Override
    public void onEnable() {

        // getServer().getPluginManager().registerEvents(new ResourcePackListener(), this);

        // Spaces: load and listeners
        spaceManager = new SpaceManager(this);
        spaceManager.load();
        getServer().getPluginManager().registerEvents(new SpacePresenceListener(spaceManager), this);
        getServer().getPluginManager().registerEvents(new SpaceEventListener(spaceManager, this), this);
        getServer().getPluginManager().registerEvents(new SpaceBlockListener(spaceManager), this);
        getServer().getPluginManager().registerEvents(new FirstJoinListener(this), this);
        getCommand("locateBungalows").setExecutor(new LocateBungalowsCommand());

        // Schematic system
        schematicManager = new SchemManager(this);
        if (getCommand("testschem") != null) {
            getCommand("testschem").setExecutor(new TestSchemCommand(schematicManager));
        }

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
        if (getCommand("floodfillalgorithm") != null) {
            getCommand("floodfillalgorithm").setExecutor(new FloodFillAlgorithmCommand(this, spaceManager));
        }
        if (getCommand("sled") != null) {
            sledManager = new SledManager(this);
            getCommand("sled").setExecutor(new SledCommand(sledManager));
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
        trailManager.setRenderMode(TrailRenderMode.PARTICLE);
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

        // DICE: external climate and body drift
        diceLogger = new DiceLogger(this);
        diceManager = new DiceManager(this, statsManager, spaceManager, diceLogger);
        diceManager.start();

        // Effects: circumstantial and timed (e.g., Hypoxia)
        effectManager = new EffectManager(this, statsManager);
        effectManager.start();

        // Sidebar: live environment HUD with Temperature
        sidebarManager = new SidebarManager(this, statsManager);
        sidebarManager.start();

        // Oxygen: deep-underground detection, depletion and regeneration
        oxygenManager = new OxygenManager(this, statsManager);
        oxygenManager.start();

        // Tablist: condition overview segments + world pop
        tablistManager = new TablistManager(this, statsManager, populationManager, effectManager, oxygenManager);
        tablistManager.start();

        if (getCommand("externaltempdebug") != null) {
            getCommand("externaltempdebug").setExecutor(new ExternalTempDebugCommand(diceManager));
        }
        if (getCommand("dicelog") != null) {
            getCommand("dicelog").setExecutor(new DiceLogCommand(diceLogger));
        }

        Bukkit.getScheduler().runTask(this, () -> {
            World arctic = Bukkit.getWorld("Arctic");
            if (arctic == null) {
                Bukkit.getLogger().info("[Thaw] Creating new Arctic world...");
                WorldCreator wc = new WorldCreator("Arctic").generator(new ArcticChunkGenerator());
                arctic = wc.createWorld();

                if (arctic != null) {
                    //pregenerateArctic(arctic);
                }
            } else {
                Bukkit.getLogger().info("[Thaw] Reloading existing Arctic world...");
                new WorldCreator("Arctic").generator(new ArcticChunkGenerator()).createWorld();
            }

            // Now safe to schedule bungalow placement too
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                ArcticChunkGenerator gen = ArcticChunkGenerator.getInstance();
                if (gen == null) return;

                World world = Bukkit.getWorld("Arctic");
                if (world == null) return;

                List<Location> toPlace;
                synchronized (gen.bungalowQueue) {
                    toPlace = new ArrayList<>(gen.bungalowQueue);
                    gen.bungalowQueue.clear();
                }

                if (!toPlace.isEmpty()) {
                    Bukkit.getLogger().info("[Thaw] Placing " + toPlace.size() + " bungalows...");
                }


                for (Location loc : toPlace) {
                    Random rng = new Random();
                    String chosen = BUNGALOW_SCHEMATICS.get(rng.nextInt(BUNGALOW_SCHEMATICS.size()));

                    schematicManager.placeStructure(chosen, loc);
                    gen.registerBungalow(loc);

                }
            }, 200L, 200L);
        });
    }

    @Override
    public void onDisable() {
        if (spaceManager != null) spaceManager.save();
        if (sidebarManager != null) sidebarManager.stop();
        if (statsManager != null) statsManager.stop();
        if (tablistManager != null) tablistManager.stop();
        if (populationManager != null) populationManager.stop();
        if (trailManager != null) trailManager.stop();
        if (calorieManager != null) calorieManager.stop();
        if (activityEnergyManager != null) activityEnergyManager.stop();
        if (thermalRegulator != null) thermalRegulator.stop();
        if (diceManager != null) diceManager.stop();
        if (sledManager != null) sledManager.stopAll();
        if (effectManager != null) effectManager.stop();
        if (oxygenManager != null) oxygenManager.stop();
    }

    private static class ChunkPos {
        final int x, z;
        ChunkPos(int x, int z) { this.x = x; this.z = z; }
    }



}
