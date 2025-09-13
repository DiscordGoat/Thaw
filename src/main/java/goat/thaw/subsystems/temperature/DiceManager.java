package goat.thaw.subsystems.temperature;

import goat.thaw.system.space.Space;
import goat.thaw.system.space.SpaceManager;
import goat.thaw.system.space.temperature.TemperatureRegistry;
import goat.thaw.system.stats.StatInstance;
import goat.thaw.system.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class DiceManager implements Listener {
    private final JavaPlugin plugin;
    private final StatsManager stats;
    private final SpaceManager spaces;
    private BukkitTask task;
    private final goat.thaw.system.logging.DiceLogger logger;

    private static final int TICKS = 20; // drift once per second
    private static final int RADIUS = 5; // DFS radius in blocks
    // Absolute drift rates (degrees per second)
    private static final double HEAT_RATE_DEG_PER_SEC = 5; // warming speed (twice as fast)
    private static final double COOL_RATE_DEG_PER_SEC = 5; // cooling speed
    private static final double BASELINE = 0;
    private static final double AIR_COOLING = -0.01; // per air voxel in region
    private static final double WATER_COOLING = -4.0; // per water voxel in region (strong cooling)
    private static final double SKY_COOL_DAY = -1.0; // legacy (unused in new DICE)
    private static final double SKY_COOL_NIGHT = -2.0; // legacy (unused)
    private static final double NEAR_STRENGTH = 1.5; // 50% stronger nearby
    private static final double FAR_STRENGTH = 0.5;  // 50% weaker at radius
    private static final double COLD_MULTIPLIER = 1; // buff cold influences

    public DiceManager(JavaPlugin plugin, StatsManager stats, SpaceManager spaces, goat.thaw.system.logging.DiceLogger logger) {
        this.plugin = plugin;
        this.stats = stats;
        this.spaces = spaces;
        this.logger = logger;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICKS, TICKS);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            updatePlayer(p);
        }
    }

    private void updatePlayer(Player p) {
        World w = p.getWorld();
        int x = p.getLocation().getBlockX();
        int y = p.getLocation().getBlockY() + 1; // one above feet
        int z = p.getLocation().getBlockZ();

        double externalBias;
        if (w.getEnvironment() == World.Environment.NETHER) {
            // Nether baseline is extremely hot
            externalBias = 200.0;
        } else {
            externalBias = computeExternalTemperatureDFS(w, x, y, z);
        }

        // Update ExternalTemperature stat
        StatInstance ext = stats.get(p.getUniqueId(), "ExternalTemperature");
        if (ext != null) ext.set(externalBias);

        // Drift core Temperature toward ExternalTemperature bias without overshooting (no +65 anywhere)
        StatInstance body = stats.get(p.getUniqueId(), "Temperature");
        double dtSeconds = TICKS / 20.0;
        if (body != null) {
            double cur = body.get();
            // Work in bias space to compare targets without converting external
            double bodyBias = cur; // bias of current body temp relative to neutral
            double deltaBias = externalBias - bodyBias; // how far to move in bias space
            double step;
            if (deltaBias > 0) {
                step = Math.min(deltaBias, HEAT_RATE_DEG_PER_SEC * dtSeconds);
            } else if (deltaBias < 0) {
                step = -Math.min(-deltaBias, COOL_RATE_DEG_PER_SEC * dtSeconds);
            } else {
                step = 0.0;
            }
            double newTemp = cur + step;
            body.set(newTemp);
            if (debugEnabled(p)) sendDebugDrift(p, externalBias, cur, step);
            if (logger != null && logger.isEnabled(p.getUniqueId())) {
                logger.log(p.getUniqueId(), String.format(java.util.Locale.US,
                        "tick world=%s pos=(%d,%d,%d) time=%d weather=%s bias=%.2f temp=%.2f applied=%+.3f",
                        w.getName(), x, y, z, w.getTime(), (w.hasStorm()||w.isThundering()?"storm":"clear"), externalBias, cur, step));
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Player p = e.getPlayer();
        World w = p.getWorld();
        int x = e.getTo().getBlockX();
        int y = e.getTo().getBlockY() + 1;
        int z = e.getTo().getBlockZ();
        double externalBias = computeExternalTemperatureDFS(w, x, y, z);
        StatInstance ext = stats.get(p.getUniqueId(), "ExternalTemperature");
        if (ext != null) ext.set(externalBias);
    }

    private double computeExternalTemperatureDFS(World w, int sx, int sy, int sz) {
        // If in a sealed space, use its temperature bias (convert absolute -> bias temporarily)
        Space s = spaces.findSpaceAt(w, sx, sy, sz).orElse(null);
        if (s != null) return s.getTemperature();

        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        push(stack, visited, sx, sy, sz);

        double total = 0.0;
        int samples = 0;

        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0], y = p[1], z = p[2];
            if (!withinRadius(sx, sy, sz, x, y, z)) continue;

            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();

            double base = 0.0;
            boolean counted = false;
            if (m.isAir()) { base = AIR_COOLING; counted = true; }
            else if (m == Material.WATER) { base = WATER_COOLING; counted = true; }
            else {
                base = TemperatureRegistry.influence(b);
                if (base != 0.0) counted = true;
            }

            if (counted) {
                if (base < 0) base *= COLD_MULTIPLIER; // buff cold influences
                int d = Math.max(Math.max(Math.abs(x - sx), Math.abs(y - sy)), Math.abs(z - sz));
                double t = Math.min(1.0, Math.max(0.0, d / (double) RADIUS));
                double weight = NEAR_STRENGTH + (FAR_STRENGTH - NEAR_STRENGTH) * t; // 1.5 -> 0.5
                total += base * weight;
                samples++;
            }

            // Traverse all neighbors within radius (regardless of solidity)
            push(stack, visited, x+1, y, z);
            push(stack, visited, x-1, y, z);
            push(stack, visited, x, y+1, z);
            push(stack, visited, x, y-1, z);
            push(stack, visited, x, y, z+1);
            push(stack, visited, x, y, z-1);
        }

        if (samples == 0) return 0.0;
        double fieldBias = total / samples;
        double sunBias = computeSunBias(w, sx, sy, sz);
        return fieldBias + sunBias;
    }

    private boolean withinRadius(int sx, int sy, int sz, int x, int y, int z) {
        int dx = Math.abs(x - sx), dy = Math.abs(y - sy), dz = Math.abs(z - sz);
        return Math.max(Math.max(dx, dy), dz) <= RADIUS;
    }

    private void push(java.util.ArrayDeque<int[]> stack, java.util.Set<String> visited, int x, int y, int z) {
        String key = x + "," + y + "," + z;
        if (visited.add(key)) stack.push(new int[]{x,y,z});
    }

    private void pushIfTraversable(World w, java.util.ArrayDeque<int[]> stack, java.util.Set<String> visited, int x, int y, int z, int sx, int sy, int sz) {
        if (!withinRadius(sx, sy, sz, x, y, z)) return;
        if (isTraversable(w.getBlockAt(x, y, z).getType())) push(stack, visited, x, y, z);
    }

    private double boundaryOrPushWeighted(World w, java.util.ArrayDeque<int[]> stack, java.util.Set<String> visited, int x, int y, int z, int sx, int sy, int sz) {
        if (!withinRadius(sx, sy, sz, x, y, z)) return 0.0;
        Block n = w.getBlockAt(x, y, z);
        Material m = n.getType();
        if (isTraversable(m)) { push(stack, visited, x, y, z); return 0.0; }
        double inf = TemperatureRegistry.influence(n);
        if (inf == 0.0) return 0.0;
        int d = Math.max(Math.max(Math.abs(x - sx), Math.abs(y - sy)), Math.abs(z - sz));
        double weight = 1.0 / (1.0 + d);
        return inf * weight;
    }

    private boolean isTraversable(Material m) {
        return m.isAir() || m == Material.WATER;
    }

    private boolean isNight(World w) {
        long t = w.getTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    private boolean isDay(World w) {
        long t = w.getTime() % 24000L;
        return t < 12000L;
    }

    private boolean isColumnOpenToSky(World w, int x, int y, int z) {
        int top = w.getMaxHeight();
        for (int yy = y + 1; yy < top; yy++) {
            if (!w.getBlockAt(x, yy, z).getType().isAir()) return false;
        }
        return true;
    }

    // Compute a sun bias up to +30F when daytime and the sun ray has line of sight to the player.
    private double computeSunBias(World w, int x, int y, int z) {
        if (!isDay(w)) return 0.0;
        if (w.hasStorm() || w.isThundering()) return 0.0;

        long t = w.getTime() % 24000L; // 0..23999
        // Strength peaks at noon (6000), 0 at sunrise(0)/sunset(12000)
        double dist = Math.abs(t - 6000L) / 6000.0; // 0..1
        double strength = Math.max(0.0, 1.0 - dist); // 0..1
        if (strength <= 0.0) return 0.0;

        // Sun direction approximation: east (+X) at dawn -> up at noon -> west (-X) at dusk; no Z component.
        double theta = (Math.PI * t) / 12000.0; // 0..pi from dawn to dusk
        double dx = Math.cos(theta); // +1 east at dawn, 0 at noon, -1 west at dusk
        double dy = Math.sin(theta); // 0 at dawn/dusk, +1 at noon
        double dz = 0.0;
        // Normalize
        double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len <= 1e-6) return 0.0;
        dx /= len; dy /= len; // dz already 0

        if (!hasLineOfSightAlong(w, x + 0.5, y + 0.5, z + 0.5, dx, dy, dz)) return 0.0;
        return 50 * strength;
    }

    // Ray-march along the given direction; return true if no solid block encountered before reaching sky/out of world.
    private boolean hasLineOfSightAlong(World w, double sx, double sy, double sz, double dx, double dy, double dz) {
        final double step = 0.5; // half-block steps
        final int maxSteps = 512;
        double x = sx, y = sy, z = sz;
        for (int i = 0; i < maxSteps; i++) {
            x += dx * step; y += dy * step; z += dz * step;
            int bx = (int) Math.floor(x);
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z);
            if (by >= w.getMaxHeight()) return true; // reached sky
            if (by < w.getMinHeight()) return false; // below world; consider blocked
            if (!w.getBlockAt(bx, by, bz).getType().isAir()) return false; // any block blocks sun
        }
        return true; // no block within ray budget
    }

    

    // Debug toggle and actionbar output
    private final java.util.Set<java.util.UUID> debugPlayers = new java.util.HashSet<>();
    public void toggleDebug(java.util.UUID id) {
        if (debugPlayers.contains(id)) debugPlayers.remove(id); else debugPlayers.add(id);
    }
    private boolean debugEnabled(Player p) { return debugPlayers.contains(p.getUniqueId()); }
    private void sendDebugDrift(Player p, double externalBias, double bodyBefore, double applied) {
        String extStr = String.format(java.util.Locale.US, "%+d", (int)Math.round(externalBias));
        String bodyStr = String.format(java.util.Locale.US, "%.1f", bodyBefore);
        String heatWord = applied >= 0 ? "Heated up" : "Cooled";
        String mag = String.format(java.util.Locale.US, "%.2f", Math.abs(applied));
        String msg = "External Temp: " + extStr + " | Body Temp: " + bodyStr + "\n" + heatWord + ": " + mag;
        try {
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(msg));
        } catch (Throwable ignore) {}
    }
}
