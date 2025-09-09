package goat.thaw.hunting;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.DyeColor;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class TrailManager implements Listener {

    private static final String ANIMAL_META = "thaw.trailAnimal";

    private final JavaPlugin plugin;
    private final Map<String, ActiveTrail> activeByBlockKey = new HashMap<>();
    private final java.util.Map<java.util.UUID, ActiveTrail> byEntityId = new java.util.HashMap<>();
    private BukkitTask tickTask;
    private BukkitTask randomSpawnTask;
    private final java.util.Map<java.util.UUID, Integer> dailySpawns = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> lastDay = new java.util.HashMap<>();

    private static class ActiveTrail {
        TrailStartInstance start;
        AnimalLocation target;
        boolean revealed;
        boolean revealing;
        boolean spawned;
        UUID spawnedEntityId;
        long revealTime;
        long revealStartMs;
        List<Location> path = new ArrayList<>();
        boolean glowing;
        int revealAttempts;
        java.util.List<Double> headingOrderDeg;
        int headingIdx;
    }

    private void randomTrailTick() {
        // Use overworld day to reset per-player counters
        World overworld = Bukkit.getWorlds().get(0);
        long day = overworld.getFullTime() / 24000L;
        Bukkit.getOnlinePlayers().forEach(p -> {
            UUID id = p.getUniqueId();
            Long last = lastDay.get(id);
            if (last == null || last != day) {
                lastDay.put(id, day);
                dailySpawns.put(id, 0);
            }
            int count = dailySpawns.getOrDefault(id, 0);
            if (count >= 5) return;
            // 25% chance each tick window until we hit 5/day
            if (Math.random() < 0.25) {
                Location base = p.getLocation();
                // pick a ring 16..48 blocks away
                double ang = Math.random() * Math.PI * 2.0;
                double dist = 16.0 + Math.random() * 32.0;
                double tx = base.getX() + Math.cos(ang) * dist;
                double tz = base.getZ() + Math.sin(ang) * dist;
                World w = base.getWorld();
                int bx = (int) Math.floor(tx);
                int bz = (int) Math.floor(tz);
                // reject water tops
                if (isWaterTop(w, bx, bz)) return;
                Location blockLoc = new Location(w, bx, w.getHighestBlockYAt(bx, bz), bz);
                if (hasStartAt(blockLoc)) return;
                spawnTrailStart(blockLoc);
                dailySpawns.put(id, count + 1);
            }
        });
    }

    public TrailManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 2 tick tick for particles and proximity checks
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 2L);
        // Random trail starts around players — check every 12s
        randomSpawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::randomTrailTick, 40L, 240L);
    }

    public void stop() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        if (randomSpawnTask != null) { randomSpawnTask.cancel(); randomSpawnTask = null; }
        activeByBlockKey.clear();
        byEntityId.clear();
        dailySpawns.clear();
        lastDay.clear();
    }

    // API: spawn a single footprint at location (target set after successful path)
    public TrailStartInstance spawnTrailStart(Location loc) {
        Location base = loc.clone();
        base.setX(loc.getBlockX()); base.setY(loc.getBlockY()); base.setZ(loc.getBlockZ());
        TrailStartInstance tsi = new TrailStartInstance(null, base);
        ActiveTrail at = new ActiveTrail();
        at.start = tsi; at.target = null; at.revealed = false; at.spawned = false; at.spawnedEntityId = null; at.revealTime = 0L;
        activeByBlockKey.put(blockKey(base), at);
        // initial footprint particle (conduit effect)
        spawnInitialFootprint(footprintCenter(base));
        return tsi;
    }

    // API: reveal a trail from a start (auto-retries until success)
    public void spawnTrail(TrailStartInstance tsi) { spawnTrail(tsi, null); }

    // Start reveal with optional player to notify on failure
    public void spawnTrail(TrailStartInstance tsi, org.bukkit.entity.Player notifyPlayer) {
        ActiveTrail at = activeByBlockKey.get(blockKey(tsi.getBlockLocation()));
        if (at == null || at.revealed || at.revealing) return;
        at.revealing = true;
        at.revealAttempts = 0;
        at.revealStartMs = System.currentTimeMillis();
        // Prepare a shuffled list of general directions (degrees)
        java.util.List<Double> dirs = new java.util.ArrayList<>();
        for (int d = 0; d < 360; d += 30) dirs.add((double) d); // 12 directions
        java.util.Collections.shuffle(dirs, new java.util.Random(System.nanoTime()));
        at.headingOrderDeg = dirs;
        at.headingIdx = 0;
        revealWithRetries(at, 0, notifyPlayer);
    }

    private void revealWithRetries(ActiveTrail at, int delayTicks, org.bukkit.entity.Player notifyPlayer) {
        if (delayTicks > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> revealWithRetries(at, 0, notifyPlayer), delayTicks);
            return;
        }
        if (!activeByBlockKey.containsKey(blockKey(at.start.getBlockLocation()))) return;
        // Try as many attempts as possible within 3 seconds window
        long deadline = at.revealStartMs + 3000L;
        int guard = 0;
        while (System.currentTimeMillis() < deadline && guard++ < 50) {
            if (attemptReveal(at)) {
                at.revealed = true;
                at.revealTime = System.currentTimeMillis();
                at.revealing = false;
                return;
            }
            at.revealAttempts++;
        }
        if (System.currentTimeMillis() >= deadline) {
            // Timed out after ~3 seconds
            activeByBlockKey.remove(blockKey(at.start.getBlockLocation()));
            if (notifyPlayer != null) {
                try { notifyPlayer.sendMessage("The trail disappears..."); } catch (Throwable ignore) {}
            }
            at.revealing = false;
            return;
        }
        // Not timed out yet: try again next tick
        Bukkit.getScheduler().runTaskLater(plugin, () -> revealWithRetries(at, 0, notifyPlayer), 1L);
    }

    private boolean attemptReveal(ActiveTrail at) {
        World w = at.start.getBlockLocation().getWorld();
        List<Location> path = null;
        boolean ok = false;
        int successHeadingDeg = -1;
        int successAttempt = -1;
        // Ensure heading order exists
        if (at.headingOrderDeg == null || at.headingOrderDeg.isEmpty()) {
            java.util.List<Double> dirs = new java.util.ArrayList<>();
            for (int d = 0; d < 360; d += 30) dirs.add((double) d);
            java.util.Collections.shuffle(dirs, new java.util.Random(System.nanoTime()));
            at.headingOrderDeg = dirs;
            at.headingIdx = 0;
        }
        int headingsTried = 0;
        while (!ok && headingsTried < at.headingOrderDeg.size()) {
            double deg = at.headingOrderDeg.get(at.headingIdx % at.headingOrderDeg.size());
            at.headingIdx++;
            headingsTried++;
            double rad = Math.toRadians(deg);
            Location syntheticTo = syntheticEndpoint(at.start.getBlockLocation(), rad);
            for (int attempt = 0; attempt < 16 && !ok; attempt++) {
                long salt = attempt + System.nanoTime();
                List<Location> sim = buildPath(at.start.getBlockLocation(), syntheticTo, salt, rad);
                String reason = validatePath(sim, w);
                if (reason == null) { path = sim; ok = true; successHeadingDeg = (int)deg; successAttempt = attempt; }
                else {
                    try { Bukkit.getLogger().info("Trail sim failed: " + reason + " (heading=" + (int)deg + ", attempt=" + attempt + ")"); } catch (Throwable ignore) {}
                }
            }
        }
        if (ok && path != null && !path.isEmpty()) {
            at.path = path;
            Location end = path.get(path.size() - 1);
            at.target = new AnimalLocation(end);
            try {
                Bukkit.getLogger().info("Trail sim SUCCESS: heading=" + successHeadingDeg + ", steps=" + path.size()
                        + ", end=(" + end.getBlockX() + "," + end.getBlockY() + "," + end.getBlockZ() + ")"
                        + ", attempt=" + successAttempt + ")");
            } catch (Throwable ignore) {}
            return true;
        }
        return false;
    }

    // Dev helper
    public boolean hasStartAt(Location loc) { return activeByBlockKey.containsKey(blockKey(loc)); }

    // Cancel natural animal spawns; allow only trail-spawned animals
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Animals)) return;
        // Allow our programmatic spawns; block everything else
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        Entity ent = e.getEntity();
        if (!ent.hasMetadata(ANIMAL_META)) {
            e.setCancelled(true);
        }
    }

    // Reveal on block break under start
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Location b = e.getBlock().getLocation();
        ActiveTrail at = activeByBlockKey.get(blockKey(b));
        if (at != null && !at.revealed) {
            // Cancel the actual block break and begin reveal attempts
            e.setCancelled(true);
            spawnTrail(at.start, e.getPlayer());
        }
    }

    private void tick() {
        // emit particles for unrevealed starts
        for (ActiveTrail at : new java.util.ArrayList<>(activeByBlockKey.values())) {
            if (!at.revealed) {
                spawnFootprintParticle(footprintCenter(at.start.getBlockLocation()));
            } else {
                // draw path particles intermittently
                if (at.path != null) {
                    for (int i = 0; i < at.path.size(); i += 2) {
                        spawnFootprintParticle(at.path.get(i));
                    }
                }
                // check proximity for spawn (20 blocks)
                if (!at.spawned) {
                    boolean anyNear = Bukkit.getOnlinePlayers().stream().anyMatch(p ->
                            p.getWorld().equals(at.target.getLocation().getWorld()) &&
                                    p.getLocation().distanceSquared(at.target.getLocation()) <= (20 * 20));
                    if (anyNear) {
                        Entity spawned = spawnWhitelistedAnimal(at.target.getLocation());
                        if (spawned != null) {
                            spawned.setMetadata(ANIMAL_META, new FixedMetadataValue(plugin, true));
                            at.spawned = true; at.spawnedEntityId = spawned.getUniqueId();
                            // remove trail immediately so particles stop
                            activeByBlockKey.remove(blockKey(at.start.getBlockLocation()));
                            // schedule despawn after 5 minutes
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                Entity ent = Bukkit.getEntity(at.spawnedEntityId);
                                if (ent != null && ent.isValid()) ent.remove();
                            }, 5 * 60 * 20L);
                            // schedule glow after 30 seconds if still alive
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                Entity ent = Bukkit.getEntity(at.spawnedEntityId);
                                if (ent != null && ent.isValid() && !ent.isDead()) ent.setGlowing(true);
                            }, 30 * 20L);
                        } else {
                            Bukkit.getLogger().info("Failed to spawn Hunted Animal");
                        }
                    }
                }
                // cleanup: if animal died, remove trail immediately
                if (at.spawned) {
                    Entity ent = Bukkit.getEntity(at.spawnedEntityId);
                    if (ent == null || ent.isDead()) {
                        activeByBlockKey.remove(blockKey(at.start.getBlockLocation()));
                        if (at.spawnedEntityId != null) byEntityId.remove(at.spawnedEntityId);
                        continue;
                    }
                }
            }
        }
        // (Glow is now scheduled per-spawn after 30 seconds; no continuous glow control here)
    }

    private Location randomNearbyTarget(Location base, int minDist, int maxDist) {
        Random rng = new Random(Objects.hash(base.getWorld().getSeed(), base.getBlockX(), base.getBlockZ()));
        double ang = rng.nextDouble() * Math.PI * 2.0;
        double dist = minDist + rng.nextInt(Math.max(1, maxDist - minDist + 1));
        double tx = base.getX() + Math.cos(ang) * dist;
        double tz = base.getZ() + Math.sin(ang) * dist;
        World w = base.getWorld();
        int hx = (int) Math.floor(tx);
        int hz = (int) Math.floor(tz);
        int hy = w.getHighestBlockYAt(hx, hz);
        return new Location(w, hx + 0.5, hy + 0.1, hz + 0.5);
    }

/* <<<<<<<<<<<<<<  ✨ Windsurf Command ⭐ >>>>>>>>>>>>>>>> */
    /**
     * Spawns a randomly-chosen whitelisted animal at the given location. If the block above the given location is air, the spawn location is moved to the ground.
     * @param center the location to spawn the animal at
     * @return the spawned entity, or null if the spawn failed
     */
/* <<<<<<<<<<  9d2ad7d2-be0c-4eba-9df4-7abb8535666f  >>>>>>>>>>> */
    private Entity spawnWhitelistedAnimal(Location center) {
        World w = center.getWorld();
        // Try small random offsets around endpoint to place more naturally
        Location ground = null;
        for (int i = 0; i < 12; i++) {
            double ang = Math.random() * Math.PI * 2.0;
            double dist = 2.0 + Math.random() * 28.0; // 2..30 blocks
            double ox = center.getX() + Math.cos(ang) * dist;
            double oz = center.getZ() + Math.sin(ang) * dist;
            int bx = (int) Math.floor(ox);
            int bz = (int) Math.floor(oz);
            if (isWaterTop(w, bx, bz)) continue;
            Location candidate = new Location(w, bx, 0, bz);
            Location hg = highestNonAirAbove(w, candidate);
            if (hg != null) { ground = hg; break; }
        }
        if (ground == null) ground = highestNonAirAbove(w, center);
        if (ground == null) ground = center;
        double r = Math.random();
        if (r < 1/3.0) {
            return w.spawnEntity(ground, EntityType.CHICKEN);
        } else if (r < 2/3.0) {
            return w.spawnEntity(ground, EntityType.COW);
        } else {
            return w.spawnEntity(ground, EntityType.SHEEP);
        }
    }

    private Location highestNonAirAbove(World w, Location at) {
        int x = at.getBlockX();
        int z = at.getBlockZ();
        double y = computeFootprintY(w, x, z); // top + 1.0 (+ snow lift)
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    private List<Location> buildPath(Location fromBlock, Location to) { return buildPath(fromBlock, to, 0L, Double.NaN); }

    private List<Location> buildPath(Location fromBlock, Location to, long salt) { return buildPath(fromBlock, to, salt, Double.NaN); }

    private List<Location> buildPath(Location fromBlock, Location to, long salt, double forcedHeadingRad) {
        List<Location> pts = new ArrayList<>();
        World w = fromBlock.getWorld();
        Location cur = footprintCenter(fromBlock);
        Location dst = footprintCenter(to);
        Random rng = new Random(fromBlock.getWorld().getSeed() ^ fromBlock.getBlockX() * 31L ^ fromBlock.getBlockZ() * 131L ^ (salt * 0x9E3779B97F4A7C15L));
        int steps = 100 + rng.nextInt(401); // 100..500
        // Heading toward destination
        double lastHeading;
        {
            double dx0 = dst.getX() - cur.getX();
            double dz0 = dst.getZ() - cur.getZ();
            lastHeading = Double.isNaN(forcedHeadingRad) ? Math.atan2(dz0, dx0) : forcedHeadingRad;
        }
        final double MAX_UP_STEP = 5.0;
        for (int i = 0; i < steps; i++) {
            double curY = cur.getY();
            double curDist = cur.distance(dst);
            double dx = dst.getX() - cur.getX();
            double dz = dst.getZ() - cur.getZ();
            double baseHeading = Double.isNaN(forcedHeadingRad) ? Math.atan2(dz, dx) : forcedHeadingRad;

            // Sample candidates around the base heading (no pivot/skirt logic)
            Location chosen = null;
            double bestScore = -1e9;
            for (int c = 0; c < 7; c++) {
                double jitter = (rng.nextDouble() - 0.5) * 0.9; // +/- ~25-30 degrees
                double stepLen = 1.8 + rng.nextDouble() * 0.7; // 1.8..2.5
                double ang = baseHeading + jitter;
                Location cand = cur.clone().add(Math.cos(ang) * stepLen, 0, Math.sin(ang) * stepLen);
                int bx = cand.getBlockX(); int bz = cand.getBlockZ();
                double y = computeFootprintY(w, bx, bz);
                cand.setY(y);
                double up = y - curY;
                boolean water = isWaterTop(w, bx, bz);
                double candDist = cand.distance(dst);
                double progress = curDist - candDist; // positive is good
                double score = progress - Math.max(0.0, up) * 1.2 - Math.abs(up) * 0.5 - (water ? 1.5 : 0.0);
                if (up > MAX_UP_STEP) score -= 5.0;
                if (score > bestScore) { bestScore = score; chosen = cand; }
            }

            if (chosen == null) break;
            // Advance
            lastHeading = Math.atan2(chosen.getZ() - cur.getZ(), chosen.getX() - cur.getX());
            cur = chosen;
            pts.add(cur.clone());
            // Early finish if close enough to destination horizontally
            double hdx = Math.abs(cur.getX() - dst.getX());
            double hdz = Math.abs(cur.getZ() - dst.getZ());
            if ((hdx * hdx + hdz * hdz) <= (4.0 * 4.0)) {
                // snap last point to ground at destination x/z
                int bx = dst.getBlockX(); int bz = dst.getBlockZ();
                double y = computeFootprintY(w, bx, bz);
                Location end = new Location(w, bx + 0.5, y, bz + 0.5);
                pts.add(end);
                break;
            }
        }
        return pts;
    }

    // Validates that the path contains no water contacts and no vertical step >= 10 between consecutive samples
    private String validatePath(List<Location> pts, World w) {
        if (pts == null || pts.isEmpty()) return "empty";
        double prevY = pts.get(0).getY();
        for (Location l : pts) {
            int bx = l.getBlockX(); int bz = l.getBlockZ();
            if (isWaterTop(w, bx, bz)) return "water_contact at (" + bx + "," + bz + ")";
            double y = l.getY();
            if (Math.abs(y - prevY) >= 10.0) return "vertical_step_exceeded";
            prevY = y;
        }
        return null;
    }

    // Generates a synthetic distant endpoint along a heading for path shaping
    private Location syntheticEndpoint(Location startBlock, double headingRad) {
        World w = startBlock.getWorld();
        Random rng = new Random(System.nanoTime());
        double dist = 100.0 + rng.nextInt(401); // 100..500
        double tx = startBlock.getX() + Math.cos(headingRad) * dist;
        double tz = startBlock.getZ() + Math.sin(headingRad) * dist;
        int bx = (int) Math.floor(tx);
        int bz = (int) Math.floor(tz);
        int by = w.getHighestBlockYAt(bx, bz);
        return new Location(w, bx + 0.5, by + 0.1, bz + 0.5);
    }

    private String validatePath(List<Location> pts, World w, Location dst) {
        if (pts == null || pts.isEmpty()) return "empty";
        double prevY = pts.get(0).getY();
        for (Location l : pts) {
            int bx = l.getBlockX(); int bz = l.getBlockZ();
            if (isWaterTop(w, bx, bz)) return "water_contact at (" + bx + "," + bz + ")";
            double y = l.getY();
            if (Math.abs(y - prevY) >= 5) return "vertical_step_exceeded";
            prevY = y;
        }
        Location last = pts.get(pts.size() - 1);
        double dx = last.getX() - dst.getX();
        double dz = last.getZ() - dst.getZ();
        if ((dx * dx + dz * dz) > (16.0 * 16.0)) return "did_not_reach_destination";
        return null;
    }

    private boolean isWaterTop(World w, int x, int z) {
        int by = w.getHighestBlockYAt(x, z);
        Material t = w.getBlockAt(x, by, z).getType();
        switch (t) {
            case WATER:
            case SEAGRASS:
            case TALL_SEAGRASS:
            case KELP:
            case KELP_PLANT:
            case BUBBLE_COLUMN:
                return true;
            default:
                return false;
        }
    }

    private boolean isSandTop(World w, int x, int z) {
        int by = w.getHighestBlockYAt(x, z);
        Material top = w.getBlockAt(x, by, z).getType();
        if (top == Material.SAND) return true;
        // If snow is on top, check the block below
        if (top == Material.SNOW) {
            Material below = w.getBlockAt(x, by - 1, z).getType();
            return below == Material.SAND;
        }
        return false;
    }

    private double computeFootprintY(World w, int x, int z) {
        int by = w.getHighestBlockYAt(x, z);
        double snowLift = 0.0;
        try {
            Block top = w.getBlockAt(x, by, z);
            if (top.getType() == Material.SNOW) {
                Snow snow = (Snow) top.getBlockData();
                snowLift = Math.max(0, snow.getLayers()) * 0.2;
            }
        } catch (Throwable ignore) {}
        return by + 1.0 + snowLift;
    }

    @EventHandler
    public void onAnimalDeath(EntityDeathEvent e) {
        Entity ent = e.getEntity();
        if (!(ent instanceof Animals)) return;
        if (!ent.hasMetadata(ANIMAL_META)) return; // only our hunted animals
        e.getDrops().clear();
        Random r = new Random();
        if (ent instanceof Chicken) {
            e.getDrops().add(new ItemStack(Material.CHICKEN, 1));
            e.getDrops().add(new ItemStack(Material.FEATHER, 16));
            int eggs = 1 + r.nextInt(4); // 1-4
            e.getDrops().add(new ItemStack(Material.EGG, eggs));
        } else if (ent instanceof Cow) {
            int beef = 4 + r.nextInt(5); // 4-8
            int leather = 1 + r.nextInt(6); // 1-6
            e.getDrops().add(new ItemStack(Material.BEEF, beef));
            e.getDrops().add(new ItemStack(Material.LEATHER, leather));
        } else if (ent instanceof Sheep) {
            int mutton = 1 + r.nextInt(8); // 1-8
            int wool = 1 + r.nextInt(6); // 1-6
            e.getDrops().add(new ItemStack(Material.MUTTON, mutton));
            Material woolMat = woolFromSheep((Sheep) ent);
            e.getDrops().add(new ItemStack(woolMat, wool));
        }
    }

    private Material woolFromSheep(Sheep sheep) {
        try {
            String key = sheep.getColor().name() + "_WOOL";
            return Material.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return Material.WHITE_WOOL;
        }
    }

    private void spawnFootprintParticle(Location loc) {
        try {
            // Keep explosion-like particle for trail; use EXPLOSION for broad compatibility
            loc.getWorld().spawnParticle(Particle.EXPLOSION, loc, 1, 0.05, 0.0, 0.05, 0.0);
        } catch (Throwable ignore) {
            // fallback
            loc.getWorld().spawnParticle(Particle.CRIT, loc, 1, 0.05, 0.0, 0.05, 0.0);
        }
    }

    private void spawnInitialFootprint(Location loc) {
        try {
            loc.getWorld().spawnParticle(Particle.NAUTILUS, loc, 1, 0.0, 0.0, 0.0, 0.0);
        } catch (Throwable ignore) {
            spawnFootprintParticle(loc);
        }
    }

    private static Location footprintCenter(Location blockLoc) {
        // Lift 0.3 higher than before to avoid underground clipping
        return new Location(blockLoc.getWorld(), blockLoc.getBlockX() + 0.5, blockLoc.getBlockY() + 1, blockLoc.getBlockZ() + 0.5);
    }

    private static String blockKey(Location l) {
        return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }
}
