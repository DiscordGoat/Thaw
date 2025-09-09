package goat.thaw;

import goat.thaw.stats.StatInstance;
import goat.thaw.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Applies per-minute baseline and per-action calorie costs based on player activity.
 */
public class ActivityEnergyManager implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager stats;
    private BukkitTask sampleTask;

    // Baselines (cal/min)
    private static final double IDLE_RATE = 1.2;
    private static final double WALK_RATE = 3.0;
    private static final double SPRINT_RATE = 12.0;
    private static final double SWIM_RATE = 7.2;
    private static final double SPRINT_SWIM_RATE = 13.2;
    private static final double SNEAK_RATE = 0.6; // add-on when sneaking

    // Per-action costs
    private static final double JUMP_COST = 1.5;
    private static final double BREAK_COST = 2.0;
    private static final double SWING_COST = 2.0;
    private static final double HIT_BONUS_COST = 3.0;
    private static final double BOW_SHOT_COST = 5.0;
    private static final double SHIELD_BLOCK_COST = 2.0;

    private static final long SAMPLE_PERIOD_TICKS = 10L; // 0.5s

    private static class State {
        Location lastLoc;
        boolean lastOnGround;
    }

    private final Map<UUID, State> states = new HashMap<>();

    public ActivityEnergyManager(JavaPlugin plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) ensureState(p);
        sampleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleTick, SAMPLE_PERIOD_TICKS, SAMPLE_PERIOD_TICKS);
    }

    public void stop() {
        if (sampleTask != null) { sampleTask.cancel(); sampleTask = null; }
        states.clear();
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { ensureState(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }

    private void ensureState(Player p) {
        states.computeIfAbsent(p.getUniqueId(), id -> {
            State s = new State();
            s.lastLoc = p.getLocation();
            s.lastOnGround = p.isOnGround();
            return s;
        });
    }

    private void sampleTick() {
        final double windowSeconds = SAMPLE_PERIOD_TICKS / 20.0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            ensureState(p);
            State s = states.get(p.getUniqueId());

            // Movement classification
            boolean swimming = p.isSwimming();
            boolean sprinting = p.isSprinting();
            Location now = p.getLocation();
            double dx = now.getX() - s.lastLoc.getX();
            double dz = now.getZ() - s.lastLoc.getZ();
            double distH = Math.hypot(dx, dz);
            boolean walking = !sprinting && !swimming && distH > 0.05; // ~0.1 blocks/s threshold
            boolean sneaking = p.isSneaking();

            // Base rate (exclusive)
            double rate = IDLE_RATE;
            if (swimming && sprinting) rate = SPRINT_SWIM_RATE;
            else if (swimming) rate = SWIM_RATE;
            else if (sprinting) rate = SPRINT_RATE;
            else if (walking) rate = WALK_RATE;
            // Sneak add-on
            if (sneaking) rate += SNEAK_RATE;

            // Jump detection: ground -> air with positive Y velocity and not swimming
            int jumps = 0;
            boolean onGround = p.isOnGround();
            if (s.lastOnGround && !onGround && !swimming && p.getVelocity().getY() > 0.2) {
                jumps = 1;
            }

            // Compute calorie cost for window
            double baselineCost = rate * (windowSeconds / 60.0);
            double jumpCost = jumps * JUMP_COST;
            double total = baselineCost + jumpCost;

            if (total > 0) spendCalories(p, total);

            s.lastLoc = now;
            s.lastOnGround = onGround;
        }
    }

    private void spendCalories(Player p, double amount) {
        StatInstance cal = stats.get(p.getUniqueId(), "Calories");
        if (cal == null) return;
        cal.subtract(amount);
    }

    // Per-action hooks
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        spendCalories(p, BREAK_COST);
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        // Only charge on successful entity hit: swing + hit costs
        spendCalories(p, SWING_COST + HIT_BONUS_COST);

        // Defender shield block cost (approximate): if target is blocking
        if (e.getEntity() instanceof Player target) {
            if (target.isBlocking()) {
                spendCalories(target, SHIELD_BLOCK_COST);
            }
        }
    }

    @EventHandler
    public void onBow(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        spendCalories(p, BOW_SHOT_COST);
    }
}
