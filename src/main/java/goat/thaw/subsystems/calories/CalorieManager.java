package goat.thaw.subsystems.calories;

import goat.thaw.system.stats.StatInstance;
import goat.thaw.system.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps player hunger in sync with Calories and enforces zero-calorie death.
 */
public class CalorieManager implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager stats;
    private BukkitTask hungerTask;
    private BukkitTask deathTask;
    private BukkitTask healingTask;

    // Healing config
    private static final int HEALTHY_INTERVAL_TICKS = 20;   // 1s
    private static final int CAUTION_INTERVAL_TICKS = 80;   // 4s
    private static final int STARVED_INTERVAL_TICKS = 200;  // 10s
    private static final double HEALTHY_CAP = 20.0;         // up to 20 health
    private static final double CAUTION_CAP = 18.0;         // up to 18 health
    private static final double STARVED_CAP = 6.0;          // up to 6 health
    private static final double HEAL_AMOUNT = 1.0;          // heals 1.0 (half-heart)
    private static final double HEAL_COST_CALORIES = 75.0; // cost per 1.0 healed

    public CalorieManager(JavaPlugin plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Initial sync for currently online players
        for (Player p : Bukkit.getOnlinePlayers()) syncPlayerHunger(p);

        // Hunger bar updates every 0.5 seconds (10 ticks)
        hungerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) syncPlayerHunger(p);
        }, 10L, 10L);

        // Check for zero calories frequently to enforce instant death
        deathTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) enforceZeroCalorieDeath(p);
        }, 10L, 10L); // every 0.5 seconds

        // Custom healing tick - replaces natural regen
        healingTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) processHealing(p);
        }, 1L, 1L); // every tick
    }

    public void stop() {
        if (hungerTask != null) { hungerTask.cancel(); hungerTask = null; }
        if (deathTask != null) { deathTask.cancel(); deathTask = null; }
        if (healingTask != null) { healingTask.cancel(); healingTask = null; }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Ensure hunger reflects current Calories upon join
        syncPlayerHunger(e.getPlayer());
        enforceZeroCalorieDeath(e.getPlayer());
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        StatInstance calInst = stats.get(p.getUniqueId(), "Calories");
        if (calInst == null) return;
        double calories = calInst.get();
        double max = calInst.getDefinition().getMax();
        if (calories >= max) {
            // Block eating at max calories
            e.setCancelled(true);
            p.sendMessage(ChatColor.YELLOW + "You are fully satiated. You can't eat more right now.");
            return;
        }

        // Apply caloric gain for consumed item
        Material m = e.getItem() != null ? e.getItem().getType() : null;
        if (m != null) {
            double gain = caloriesFor(m);
            if (gain > 0) {
                calInst.add(gain);
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        StatInstance calInst = stats.get(p.getUniqueId(), "Calories");
        if (calInst == null) return;
        // Set to 2000 to avoid chain-death immediately after respawn
        calInst.set(1000.0);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        // After respawn, ensure hunger reflects the updated calorie value
        Bukkit.getScheduler().runTask(plugin, () -> syncPlayerHunger(e.getPlayer()));
    }

    @EventHandler
    public void onRegain(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        EntityRegainHealthEvent.RegainReason r = e.getRegainReason();
        // Cancel natural hunger-based regen; allow other reasons (e.g., potions)
        if (r == EntityRegainHealthEvent.RegainReason.REGEN || r == EntityRegainHealthEvent.RegainReason.SATIATED) {
            e.setCancelled(true);
        }
    }

    private void syncPlayerHunger(Player p) {
        StatInstance calInst = stats.get(p.getUniqueId(), "Calories");
        if (calInst == null) return;
        double calories = calInst.get();

        // Map Calories to hunger: 150 Calories => 1 hunger point; 0..20 range
        int hunger = (int) Math.floor(calories / 150.0);
        if (hunger < 0) hunger = 0;
        if (hunger > 20) hunger = 20;

        // Apply to player food level
        if (p.getFoodLevel() != hunger) {
            p.setFoodLevel(hunger);
        }
    }

    private void enforceZeroCalorieDeath(Player p) {
        StatInstance calInst = stats.get(p.getUniqueId(), "Calories");
        if (calInst == null) return;
        if (calInst.get() <= 0.0) {
            // Kill immediately if not already dead
            if (p.isDead()) return;
            try {
                p.setHealth(0.0);
            } catch (Exception ignore) {
                // In case the player is in a state where health can't be set, do nothing
            }
        }
    }

    // Healing state per-player is time-driven; track elapsed ticks in scoreboard metadata-like structure
    private final java.util.Map<java.util.UUID, Integer> healElapsed = new java.util.HashMap<>();

    private void processHealing(Player p) {
        if (p.isDead()) { healElapsed.remove(p.getUniqueId()); return; }

        StatInstance calInst = stats.get(p.getUniqueId(), "Calories");
        if (calInst == null) return;
        double calories = calInst.get();

        // Determine threshold settings
        int interval;
        double cap;
        if (calories >= 2000.0) {
            interval = HEALTHY_INTERVAL_TICKS;
            cap = HEALTHY_CAP;
        } else if (calories >= 1000.0) {
            interval = CAUTION_INTERVAL_TICKS;
            cap = CAUTION_CAP;
        } else {
            interval = STARVED_INTERVAL_TICKS;
            cap = STARVED_CAP;
        }

        // Advance timer
        int t = healElapsed.getOrDefault(p.getUniqueId(), 0) + 1;
        if (t < interval) { healElapsed.put(p.getUniqueId(), t); return; }

        // Time to attempt a heal tick
        healElapsed.put(p.getUniqueId(), 0);

        double health = p.getHealth();
        if (health >= cap) return; // do not exceed cap for this threshold

        // Require enough calories to pay cost
        if (calories < HEAL_COST_CALORIES) return;

        // Spend calories and heal
        calInst.subtract(HEAL_COST_CALORIES);
        double newHealth = Math.min(cap, health + HEAL_AMOUNT);
        try {
            p.setHealth(newHealth);
        } catch (IllegalArgumentException ignored) {
            // If for any reason cannot set, ignore this tick
        }
    }

    private double caloriesFor(Material m) {
        switch (m) {
            // Meats
            case PORKCHOP: return 200.0; // Raw Porkchop
            case COOKED_PORKCHOP: return 400.0;
            case BEEF: return 200.0; // Raw Beef
            case COOKED_BEEF: return 500.0; // Steak
            case MUTTON: return 200.0; // Raw Mutton
            case COOKED_MUTTON: return 400.0;
            case CHICKEN: return 150.0; // Raw Chicken
            case COOKED_CHICKEN: return 500.0; // Buffed hearty food
            case RABBIT: return 150.0; // Raw Rabbit
            case COOKED_RABBIT: return 350.0;
            case RABBIT_STEW: return 600.0; // Hearty stew bonus
            case COD: return 120.0; // Raw Cod
            case COOKED_COD: return 250.0;
            case SALMON: return 120.0; // Raw Salmon
            case COOKED_SALMON: return 250.0;
            case TROPICAL_FISH: return 100.0;
            case PUFFERFISH: return 50.0; // Dangerous but calories

            // Farm Foods
            case BREAD: return 250.0;
            case COOKIE: return 80.0;
            case CARROT: return 80.0;
            case GOLDEN_CARROT: return 500.0; // Buffed
            case POTATO: return 100.0;
            case BAKED_POTATO: return 250.0;
            case POISONOUS_POTATO: return 40.0;
            case BEETROOT: return 50.0;
            case BEETROOT_SOUP: return 180.0;
            case PUMPKIN_PIE: return 320.0;
            case MELON_SLICE: return 60.0;

            // Fruits & Special
            case APPLE: return 150.0;
            case GOLDEN_APPLE: return 800.0; // Buffed
            case ENCHANTED_GOLDEN_APPLE: return 1500.0; // Super food
            case GLOW_BERRIES: return 80.0;
            case SWEET_BERRIES: return 100.0;
            case CHORUS_FRUIT: return 120.0;
            case HONEY_BOTTLE: return 150.0;

            // Mushrooms & Stews
            case MUSHROOM_STEW: return 220.0;
            case SUSPICIOUS_STEW: return 200.0; // average

            // Hostile Foods
            case ROTTEN_FLESH: return 50.0;
            case SPIDER_EYE: return 30.0;
            case DRIED_KELP: return 30.0;

            default: return 0.0;
        }
    }
}
