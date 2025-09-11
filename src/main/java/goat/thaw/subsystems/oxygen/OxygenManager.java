package goat.thaw.subsystems.oxygen;

import goat.thaw.system.stats.StatInstance;
import goat.thaw.system.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class OxygenManager implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager stats;
    private BukkitTask secondTick;
    private BukkitTask fiveSecondTick;

    private final Map<UUID, Boolean> deepUnderground = new HashMap<>();
    private final Map<UUID, Integer> regenCounter = new HashMap<>();

    public OxygenManager(JavaPlugin plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Oxygen depletion/regen tick every 1s
        secondTick = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
        // Deep underground probe every 5s
        fiveSecondTick = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFiveSeconds, 100L, 100L);
        for (Player p : Bukkit.getOnlinePlayers()) ensureState(p.getUniqueId());
    }

    public void stop() {
        if (secondTick != null) { secondTick.cancel(); secondTick = null; }
        if (fiveSecondTick != null) { fiveSecondTick.cancel(); fiveSecondTick = null; }
        deepUnderground.clear();
        regenCounter.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { ensureState(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { deepUnderground.remove(e.getPlayer().getUniqueId()); regenCounter.remove(e.getPlayer().getUniqueId()); }

    private void ensureState(UUID id) {
        deepUnderground.putIfAbsent(id, false);
        regenCounter.putIfAbsent(id, 0);
    }

    public boolean isDeepUnderground(UUID id) { return deepUnderground.getOrDefault(id, false); }

    private void tickSecond() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ensureState(p.getUniqueId());
            boolean isDeep = deepUnderground.getOrDefault(p.getUniqueId(), false);
            StatInstance oxy = stats.get(p.getUniqueId(), "Oxygen");
            if (oxy == null) continue;

            if (isDeep) {
                // Deplete 1 oxygen per second while deep underground
                oxy.subtract(1.0);
                regenCounter.put(p.getUniqueId(), 0); // no regen while deep
            } else {
                // Regenerate depending on sky exposure and altitude
                boolean sky = isColumnOpenToSky(p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
                boolean highAlt = p.getLocation().getBlockY() > 200;
                int period = (!sky || highAlt) ? 6 : 3; // seconds per +1 oxygen
                int c = regenCounter.getOrDefault(p.getUniqueId(), 0) + 1;
                if (c >= period) {
                    oxy.add(1.0);
                    c = 0;
                }
                regenCounter.put(p.getUniqueId(), c);
            }
        }
    }
    // Simple vertical scan above the given coordinates.
// Returns true if nothing but air is found between (x,y,z) and world max height.
    private boolean isColumnOpenToSky(World w, int x, int y, int z) {
        int top = w.getMaxHeight();
        for (int yy = y + 1; yy < top; yy++) {
            if (!w.getBlockAt(x, yy, z).getType().isAir()) {
                return false; // blocked by a solid
            }
        }
        return true; // clear path to the sky
    }


    private void tickFiveSeconds() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ensureState(p.getUniqueId());

            // Stone-based suspicion ("stone sandwich") before running bounded flood fill
            if (!suspectUnderground(p)) {
                deepUnderground.put(p.getUniqueId(), false);
                continue;
            }

            boolean deep = !floodFindsSkyWithinSpaceStyle(p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ(), 1000);
            deepUnderground.put(p.getUniqueId(), deep);
        }
    }

    // Stone-sandwich suspicion:
    // - Block directly under feet is stone-like
    // - Column above (y+1..y+100) is air up to the first solid; that first solid must be stone-like
    private boolean suspectUnderground(Player p) {
        World w = p.getWorld();
        int x = p.getLocation().getBlockX();
        int y = p.getLocation().getBlockY();
        int z = p.getLocation().getBlockZ();

        Material below = w.getBlockAt(x, y - 1, z).getType();
        if (!isStoneLike(below)) return false;

        int top = Math.min(w.getMaxHeight(), y + 100);
        boolean sawAir = false;
        for (int yy = y + 1; yy < top; yy++) {
            Material m = w.getBlockAt(x, yy, z).getType();
            if (m.isAir()) { sawAir = true; continue; }
            // Hit a solid before sky
            return sawAir && isStoneLike(m);
        }
        // No solid above within 100 blocks -> likely open field/sky
        return false;
    }

    private boolean isStoneLike(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE || m == Material.ANDESITE
                || m == Material.DIORITE || m == Material.GRANITE || m == Material.TUFF
                || m == Material.DEEPSLATE_TILES || m == Material.COBBLESTONE || m == Material.BLACKSTONE
                || (m.isSolid() && m.isOccluding()); // fallback: conservative
    }

    // Flood fill matching Space system semantics (DFS-style), bounded by maxSteps.
    // Start one block above feet, stop immediately on sky exposure.
    private boolean floodFindsSkyWithinSpaceStyle(World w, int sx, int sy, int sz, int maxSteps) {
        java.util.ArrayDeque<org.bukkit.block.Block> stack = new java.util.ArrayDeque<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        org.bukkit.block.Block start = w.getBlockAt(sx, sy + 1, sz);
        pushIfValidSpaceStyle(w, start, visited, stack);
        int steps = 0;
        while (!stack.isEmpty() && steps < maxSteps) {
            org.bukkit.block.Block b = stack.pop();
            steps++;
            int y = b.getY();
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;

            if (isSkyExposed(w, b.getX(), y, b.getZ())) return true; // immediate abort on sky
            if (!b.getType().isAir()) continue; // boundary

            // traverse neighbors (push first, type checked upon pop as in Space)
            pushIfValidSpaceStyle(w, b.getRelative(1,0,0), visited, stack);
            pushIfValidSpaceStyle(w, b.getRelative(-1,0,0), visited, stack);
            pushIfValidSpaceStyle(w, b.getRelative(0,1,0), visited, stack);
            pushIfValidSpaceStyle(w, b.getRelative(0,-1,0), visited, stack);
            pushIfValidSpaceStyle(w, b.getRelative(0,0,1), visited, stack);
            pushIfValidSpaceStyle(w, b.getRelative(0,0,-1), visited, stack);
        }
        return false;
    }

    private void pushIfValidSpaceStyle(World w, org.bukkit.block.Block n, java.util.Set<String> visited, java.util.ArrayDeque<org.bukkit.block.Block> stack) {
        if (n.getWorld() != w) return;
        int y = n.getY();
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return;
        String key = n.getX() + "," + y + "," + n.getZ();
        if (visited.add(key)) stack.push(n);
    }

    // Same approach as Space: try skylight, verify by vertical scan when needed
    private boolean isSkyExposed(World world, int x, int y, int z) {
        org.bukkit.block.Block block = world.getBlockAt(x, y, z);
        boolean suspect;
        try {
            suspect = block.getLightFromSky() > 0;
        } catch (Throwable t) {
            suspect = true;
        }
        if (!suspect) return false;
        int top = world.getMaxHeight();
        for (int yy = y + 1; yy < top; yy++) {
            if (!world.getBlockAt(x, yy, z).getType().isAir()) return false;
        }
        return true;
    }
}
