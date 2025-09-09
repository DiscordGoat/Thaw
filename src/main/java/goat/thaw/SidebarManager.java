package goat.thaw;

import goat.thaw.stats.StatInstance;
import goat.thaw.stats.StatsManager;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class SidebarManager implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager stats;
    private BukkitTask task;

    private static class PlayerSidebar {
        Scoreboard board;
        Objective objective;
        String lastTemp;
        String lastCal;
        String lastOxy;
        CalorieAnimation calAnim;
    }

    private static class CalorieAnimation {
        int displayed;     // what we currently show
        int target;        // where we want to get to
        int remaining;     // ticks left in animation
        int step;          // per-tick step size (>=1)
        int direction;     // -1 or +1
    }

    private final Map<UUID, PlayerSidebar> sidebars = new HashMap<>();

    public SidebarManager(JavaPlugin plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public void start() {
        // Create per-player boards for online players
        for (Player p : Bukkit.getOnlinePlayers()) ensureBoard(p);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Update every tick to support smooth animations
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        sidebars.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { ensureBoard(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { sidebars.remove(e.getPlayer().getUniqueId()); }

    private void ensureBoard(Player p) {
        sidebars.computeIfAbsent(p.getUniqueId(), id -> {
            PlayerSidebar ps = new PlayerSidebar();
            ps.board = Bukkit.getScoreboardManager().getNewScoreboard();
            ps.objective = ps.board.registerNewObjective("environment", "dummy", ChatColor.AQUA + "Environment");
            ps.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            p.setScoreboard(ps.board);
            return ps;
        });
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ensureBoard(p);
            PlayerSidebar ps = sidebars.get(p.getUniqueId());

            // Get player's Temperature stat (fallback 72.0 if missing)
            double temp = 72.0;
            StatInstance inst = stats.get(p.getUniqueId(), "Temperature");
            if (inst != null) temp = inst.get();

            ChatColor tempColor;
            if (temp >= 65.0 && temp <= 85.0) tempColor = ChatColor.GREEN; // comfortable
            else if ((temp >= 35.0 && temp < 65.0) || (temp > 85.0 && temp <= 110.0)) tempColor = ChatColor.YELLOW; // caution
            else tempColor = ChatColor.RED; // dangerous

            String text = ChatColor.RED + "Temperature: "
                    + tempColor + String.format(Locale.US, "%.1f", temp)
                    + ChatColor.RED + " F" + ChatColor.RESET;

            if (ps.lastTemp != null && !ps.lastTemp.equals(text)) {
                ps.board.resetScores(ps.lastTemp);
            }
            Score sTemp = ps.objective.getScore(text);
            sTemp.setScore(3);
            ps.lastTemp = text;

            // Calories with animation
            int actualCalInt = 0;
            StatInstance calInst = stats.get(p.getUniqueId(), "Calories");
            if (calInst != null) actualCalInt = (int)Math.round(calInst.get());
            ps.calAnim = updateCalorieAnimation(ps.calAnim, actualCalInt);
            int displayCal = ps.calAnim.displayed;
            ChatColor calNumColor;
            if (displayCal >= 2000 && displayCal <= 3000) calNumColor = ChatColor.GREEN;
            else if (displayCal >= 1000 && displayCal < 2000) calNumColor = ChatColor.YELLOW;
            else calNumColor = ChatColor.RED;
            String calText = ChatColor.YELLOW + "Calories: " + calNumColor + String.format(Locale.US, "%.0f", (double)displayCal) + ChatColor.RESET;
            if (ps.lastCal != null && !ps.lastCal.equals(calText)) {
                ps.board.resetScores(ps.lastCal);
            }
            Score sCal = ps.objective.getScore(calText);
            sCal.setScore(2);
            ps.lastCal = calText;
        
            // Oxygen
            double oxy = 0.0;
            double oxyMax = 1000.0;
            StatInstance oxyInst = stats.get(p.getUniqueId(), "Oxygen");
            if (oxyInst != null) { oxy = oxyInst.get(); oxyMax = oxyInst.getDefinition().getMax(); }
            double pct = oxyMax > 0 ? (oxy / oxyMax) : 0.0;
            ChatColor oxyNumColor = (pct >= 0.5) ? ChatColor.GREEN : (pct >= 0.2 ? ChatColor.YELLOW : ChatColor.RED);
            String oxyText = ChatColor.AQUA + "Oxygen: " + oxyNumColor + String.format(Locale.US, "%.0f", oxy) + ChatColor.RESET;
            if (ps.lastOxy != null && !ps.lastOxy.equals(oxyText)) {
                ps.board.resetScores(ps.lastOxy);
            }
            Score sOxy = ps.objective.getScore(oxyText);
            sOxy.setScore(1);
            ps.lastOxy = oxyText;
        }
    }

    private CalorieAnimation updateCalorieAnimation(CalorieAnimation anim, int newTarget) {
        final int LIFESPAN_TICKS = 40; // 2 seconds at 20 TPS
        if (anim == null) {
            anim = new CalorieAnimation();
            anim.displayed = newTarget;
            anim.target = newTarget;
            anim.remaining = 0;
            anim.step = 0;
            anim.direction = 0;
            return anim;
        }

        // If a new change arrives while animating, adjust step over remaining lifespan
        if (newTarget != anim.target) {
            if (anim.remaining <= 0) {
                anim.remaining = LIFESPAN_TICKS;
            }
            anim.target = newTarget;
            int diff = Math.abs(anim.target - anim.displayed);
            anim.direction = (anim.target >= anim.displayed) ? 1 : -1;
            anim.step = Math.max(1, (int) Math.ceil(diff / (double) Math.max(1, anim.remaining)));
        }

        // If idle and no animation, start a new one if needed
        if (anim.displayed == anim.target) {
            anim.remaining = 0;
            anim.step = 0;
            anim.direction = 0;
            return anim;
        }

        // Progress one tick
        int next = anim.displayed + anim.direction * anim.step;
        if ((anim.direction > 0 && next > anim.target) || (anim.direction < 0 && next < anim.target)) {
            next = anim.target;
        }
        anim.displayed = next;
        anim.remaining = Math.max(0, anim.remaining - 1);

        // If reached target but still have lifespan (due to rounding), hold target
        if (anim.displayed == anim.target) {
            anim.step = 0;
            anim.direction = 0;
        }
        return anim;
    }
}
