package goat.thaw.system.dev;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Snow;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dev SLED system: spawns a gravity-less boat steered by the player's crosshair.
 */
public class SledManager implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static class Session {
        UUID playerId;
        Boat boat;            // direct boat control (vanilla steering)
        ArmorStand marker;    // steering target
        BukkitTask task;
        double heightTargetY;
        int heightLerpTicks;
        java.util.List<ArmorStand> dogPlatforms = new java.util.ArrayList<>();
        java.util.List<org.bukkit.entity.Wolf> dogs = new java.util.ArrayList<>();
        ArmorStand leadHolder;
        int animTick;
        int dogCount = 2; // 1-2
        int descendDelayTicks; // delay before starting descent
        boolean descentArmed;  // prevents re-arming while hovering above target
    }

    public SledManager(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean hasSession(Player p) { return sessions.containsKey(p.getUniqueId()); }

    public void startSled(Player p) { startSled(p, 2); }

    public void startSled(Player p, int dogCount) {
        stopSled(p); // ensure clean state
        World w = p.getWorld();
        Location base = p.getLocation().clone();
        int bx = base.getBlockX();
        int bz = base.getBlockZ();
        int by = w.getHighestBlockYAt(bx, bz);
        double clearance = 1.2; // meters above ground to avoid friction
        Location boatLoc = new Location(w, bx + 0.5, by + clearance, bz + 0.5, base.getYaw(), base.getPitch());
        // Spawn a boat and mount player
        Boat boat = (Boat) w.spawnEntity(boatLoc, EntityType.BOAT);
        boat.setBoatType(Boat.Type.BAMBOO);
        try { boat.setGravity(false); } catch (Throwable ignore) {}
        try { boat.setInvulnerable(true); } catch (Throwable ignore) {}
        try { boat.setPersistent(false); } catch (Throwable ignore) {}
        try { boat.setSilent(true); } catch (Throwable ignore) {}
        try { boat.addPassenger(p); } catch (Throwable ignore) {}

        // Spawn marker 10 blocks ahead of crosshair
        Location target = computeTarget(p, 6.0);
        ArmorStand mark = (ArmorStand) w.spawnEntity(target, EntityType.ARMOR_STAND);
        try { mark.setVisible(false); } catch (Throwable ignore) {}
        try { mark.setMarker(true); } catch (Throwable ignore) {}
        try { mark.setGravity(false); } catch (Throwable ignore) {}
        try { mark.setSmall(true); } catch (Throwable ignore) {}
        try { mark.setSilent(true); } catch (Throwable ignore) {}
        try { mark.setCollidable(false); } catch (Throwable ignore) {}

        Session s = new Session();
        s.playerId = p.getUniqueId();
        s.boat = boat;
        s.marker = mark;
        s.heightTargetY = mark.getLocation().getY();
        s.heightLerpTicks = 1;
        s.dogCount = Math.max(1, Math.min(2, dogCount));
        // Spawn husky team visuals anchored relative to the steering marker
        Vector initialDir = s.marker.getLocation().toVector().subtract(boatLoc.toVector());
        if (initialDir.lengthSquared() < 1e-6) initialDir = computeForwardDir(p, boatLoc);
        spawnHuskyTeam(p.getWorld(), s, boatLoc, s.marker.getLocation(), initialDir.normalize());
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickSession(p, s), 0L, 1L); // max freq (every tick, no initial delay)
        sessions.put(p.getUniqueId(), s);
    }

    public void stopSled(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.task != null) { s.task.cancel(); s.task = null; }
        safeRemove(s.marker);
        safeRemove(s.boat);
        // Cleanup husky team
        if (s.dogs != null) for (Entity e : new java.util.ArrayList<>(s.dogs)) safeRemove(e);
        if (s.dogPlatforms != null) for (Entity e : new java.util.ArrayList<>(s.dogPlatforms)) safeRemove(e);
        safeRemove(s.leadHolder);
    }

    private void tickSession(Player p, Session s) {
        if (p == null || !p.isOnline()) { stopSledById(s.playerId); return; }
        if (s.boat == null || !s.boat.isValid()) { stopSled(p); return; }
        if (s.marker == null || !s.marker.isValid()) { stopSled(p); return; }
        // If player dismounted (not in our boat), stop
        if (p.getVehicle() == null || !p.getVehicle().getUniqueId().equals(s.boat.getUniqueId())) { stopSled(p); return; }

        // Cache boat location for this tick
        Location boatLoc = s.boat.getLocation();

        // Update marker to ~6m along crosshair, with ground-aware height and max 1-block step
        Location next = computeMarkerNext(p, s.marker.getLocation(), 6.0);
        if (next != null) {
            s.marker.teleport(next);
            // Immediately pivot boat to face the marker on update for snappy response
            try {
                float targetYaw = yawTo(boatLoc, s.marker.getLocation());
                s.boat.setRotation(targetYaw, 0f);
            } catch (Throwable ignore) {}
        }

        // Steer towards marker; keep a 5m buffer to avoid cliffs / steep climbs
        Vector to = s.marker.getLocation().toVector().subtract(boatLoc.toVector());
        to.setY(0);
        double dist = to.length();
        if (dist < 0.001) { s.boat.setVelocity(new Vector(0, 0, 0)); return; }
        Vector dirToMarker = to.multiply(1.0 / dist);

        // Base speed with near-marker slowdown; stop at <=3 blocks, full at >=6 blocks
        double baseSpeed = 0.8; // nerfed speed (50%)
        double speedScale = clamp((dist - 3.0) / 3.0, 0.0, 1.0); // 0 at 3m, 1 at 6m+
        double desiredSpeed = baseSpeed * (0.6 + 0.4 * Math.min(1.0, dist / 8.0)) * speedScale;
        // Linear dog power scaling: 1-4 dogs -> 1x..4x speed
        int dogCount = Math.max(1, Math.min(2, s.dogCount));
        desiredSpeed *= dogCount;
        Vector desiredVel = dirToMarker.clone().multiply(desiredSpeed);

        // Predictive vertical target: aim to match hover height at the marker's column
        World bw = boatLoc.getWorld();
        // Level thick snow (>2 layers) in 3x3 at both boat and marker columns to avoid getting caught
        int bxCol = boatLoc.getBlockX();
        int bzCol = boatLoc.getBlockZ();
        levelThickSnowArea(bw, bxCol, bzCol, 1);
        int mx = s.marker.getLocation().getBlockX();
        int mz = s.marker.getLocation().getBlockZ();
        levelThickSnowArea(bw, mx, mz, 1);
        double targetHoverY = hoverYAt(bw, mx, mz);
        // Step-up/down limits for sled (just like marker)
        double curY = boatLoc.getY();
        double stepDelta = targetHoverY - curY;

// allow max +1 up, max -6 down
        if (stepDelta > 1.0) {
            targetHoverY = curY + 1.0; // climb only 1 block, cap
        } else if (stepDelta < -6.0) {
            targetHoverY = curY - 6.0; // fall only 6 blocks, cap
        }

        // Compute ticks to arrival based on current desired speed; clamp to a reasonable window
        int ticksToArrive = Math.max(3, Math.min(12, (int) Math.ceil(dist / Math.max(0.2, desiredSpeed + 1e-3))))
                + 1; // small guard
        if (Math.abs(targetHoverY - s.heightTargetY) > 1e-3 || s.heightLerpTicks <= 0) {
            s.heightTargetY = targetHoverY;
            s.heightLerpTicks = ticksToArrive;
        }
        // Descent gating: arm once when we first see a downward target; do not re-arm until descent completes
        boolean targetBelow = targetHoverY < boatLoc.getY() - 1e-3;
        if (targetBelow) {
            if (!s.descentArmed && s.descendDelayTicks <= 0) {
                s.descendDelayTicks = 30; // ~1.5s delay
                s.descentArmed = true;
            }
        } else {
            s.descendDelayTicks = 0;
            s.descentArmed = false;
        }
        // Smooth vertical adjustment so we are at height by the time we arrive
        double vy = 0.0;
        double vertDelta = s.heightTargetY - boatLoc.getY();
        boolean delayingDescent = s.descentArmed && (vertDelta < -1e-3) && s.descendDelayTicks > 0;
        if (delayingDescent) {
            vy = 0.0; // hold altitude
            s.descendDelayTicks--;
            // do not consume lerp ticks while delaying
        } else {
            if (s.heightLerpTicks > 0) {
                vy = clamp(vertDelta / s.heightLerpTicks, -1.0, 1.0);
                s.heightLerpTicks = Math.max(0, s.heightLerpTicks - 1);
            } else {
                vy = clamp(vertDelta * 0.4, -1.0, 1.0);
            }
        }
        // Descend slower than ascend
        if (vy < 0) vy *= 0.6;
        // If we finished descending to (or past) the target, disarm so future drops can re-arm delay
        if (s.descentArmed && boatLoc.getY() <= targetHoverY + 0.05) {
            s.descentArmed = false;
            s.descendDelayTicks = 0;
        }

        // Hybrid momentum: blend current velocity toward desired velocity (horizontal only)
        Vector curVel = s.boat.getVelocity().clone();
        Vector curHoriz = curVel.clone(); curHoriz.setY(0);
        double accelAlpha = 0.35; // how quickly we align to desired
        Vector blended = curHoriz.multiply(1.0 - accelAlpha).add(desiredVel.multiply(accelAlpha));

        // Determine facing direction as a blend between player look (arcade) and velocity (physics)
        Vector look = p.getEyeLocation().getDirection().clone(); look.setY(0);
        if (look.lengthSquared() < 1e-6) look = dirToMarker.clone(); else look.normalize();
        Vector velDir = blended.lengthSquared() > 1e-6 ? blended.clone().normalize() : dirToMarker.clone();
        double speedMag = blended.length();
        double lookWeight = clamp(1.0 - speedMag / 1.2, 0.25, 0.8); // more look influence at low speed
        double velWeight = 1.0 - lookWeight;
        Vector faceDir = look.multiply(lookWeight).add(velDir.multiply(velWeight));
        if (faceDir.lengthSquared() < 1e-6) faceDir = dirToMarker.clone(); else faceDir.normalize();

        // Lateral drift damping relative to facing direction
        double forwardDot = blended.dot(faceDir);
        Vector forwardComp = faceDir.clone().multiply(forwardDot);
        Vector sideComp = blended.clone().subtract(forwardComp);
        double sideDamp = 0.60; // remove 60% of sideways each tick
        Vector dampedHoriz = forwardComp.add(sideComp.multiply(1.0 - sideDamp));

        // Compose final velocity with vertical component and apply to the boat directly
        Vector finalVel = dampedHoriz.setY(vy);
        s.boat.setVelocity(finalVel);
        // Smooth yaw toward facing
        try {
            float targetYaw2 = yawTo(boatLoc, boatLoc.clone().add(faceDir));
            float currentYaw2 = s.boat.getLocation().getYaw();
            float newYaw2 = lerpYaw(currentYaw2, targetYaw2, 0.35f);
            s.boat.setRotation(newYaw2, 0f);
        } catch (Throwable ignore) {}

        // Update husky team visuals (anchor to steering marker for stability)
        try { updateHuskyTeam(s, boatLoc, s.marker.getLocation()); } catch (Throwable ignore) {}
    }

    private Location computeTarget(Player p, double ahead) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().clone().normalize();
        World w = p.getWorld();
        double tx = eye.getX() + dir.getX() * ahead;
        double tz = eye.getZ() + dir.getZ() * ahead;
        int bx = (int) Math.floor(tx);
        int bz = (int) Math.floor(tz);
        double ground = surfaceTopY(w, bx, bz);
        double clampedY = ground + 0.25;
        return new Location(w, tx, clampedY, tz);

    }

    // Compute the next marker position respecting the 1-block vertical change limit. If the desired move
    // exceeds the limit (e.g., cliffs), the marker stays in place (acts as a stop).
    private Location computeMarkerNext(Player p, Location currentMarker, double ahead) {
        Location desired = computeTarget(p, ahead);
        World w = desired.getWorld();
        // Allow step up max +1 block, step down up to -6 blocks (cliff launch)
        double curY = currentMarker.getY();
        double ground = surfaceTopY(w, desired.getBlockX(), desired.getBlockZ());
        double targetY = ground + 0.25; // quarter above the block
        double dy = targetY - curY;
        if (dy > 1.0 + 1e-6) {
            return null;
        }
        if (dy < -6.0 - 1e-6) {
            return null;
        }
        // Accept movement with limited vertical step
        targetY = curY + dy; // already within limit
        return new Location(desired.getWorld(), desired.getX(), targetY, desired.getZ(), desired.getYaw(), desired.getPitch());
    }

    private double surfaceTopY(World w, int x, int z) {
        int yBase = w.getHighestBlockYAt(x, z); // returns block under layered snow
        double top = yBase; // will compute absolute top height
        try {
            // Base block at yBase
            Block base = w.getBlockAt(x, yBase, z);
            Material m = base.getType();
            if (m == Material.WATER || m == Material.KELP || m == Material.KELP_PLANT ||
                    m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.BUBBLE_COLUMN ||
                    m == Material.LAVA) {
                return Double.NEGATIVE_INFINITY; // mark as invalid
            }
            double baseTop;
            if (m.name().endsWith("_CARPET")) {
                baseTop = yBase + (1.0 / 16.0);
            } else {
                BlockData bd = base.getBlockData();
                if (bd instanceof Slab slab) {
                    switch (slab.getType()) {
                        case DOUBLE: baseTop = yBase + 1.0; break;
                        case TOP: baseTop = yBase + 1.0; break;
                        case BOTTOM: baseTop = yBase + 0.5; break;
                        default: baseTop = yBase + 1.0; break;
                    }
                } else {
                    baseTop = yBase + 1.0;
                }
            }
            top = baseTop;

            // Check for layered snow at yBase+1 (Bukkit HighestBlockYAt often ignores snow)
            Block above = w.getBlockAt(x, yBase + 1, z);
            if (above.getType() == Material.SNOW) {
                BlockData abd = above.getBlockData();
                if (abd instanceof Snow snow) {
                    int layers = Math.max(1, Math.min(8, snow.getLayers()));
                    double snowTop = (yBase + 1) + (layers / 8.0);
                    if (snowTop > top) top = snowTop;
                }
            }
        } catch (Throwable ignore) {
            top = yBase + 1.0;
        }
        return top;
    }

    private double hoverYAt(World w, int x, int z) {
        double base = surfaceTopY(w, x, z) + 0.3; // base hover
        try {
            int yBase = w.getHighestBlockYAt(x, z);
            Block above = w.getBlockAt(x, yBase + 1, z);
            if (above.getType() == Material.SNOW) base += 0.2; // layered snow bonus
        } catch (Throwable ignore) {}
        return base;
    }

    // Flatten layered snow if layers > 2 at the highest block in this column.
    private void levelThickSnowAt(World w, int x, int z) {
        try {
            int yBase = w.getHighestBlockYAt(x, z);
            Block above = w.getBlockAt(x, yBase + 1, z);
            if (above.getType() == Material.SNOW) {
                BlockData bd = above.getBlockData();
                if (bd instanceof Snow snow) {
                    if (snow.getLayers() > 2) {
                        above.setType(Material.AIR, false);
                    }
                }
            }
        } catch (Throwable ignore) {}
    }

    // Flatten layered snow if layers > 2 in a square radius around (cx,cz)
    private void levelThickSnowArea(World w, int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                levelThickSnowAt(w, cx + dx, cz + dz);
            }
        }
    }

    private float yawTo(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return (float) yaw;
    }

    private float lerpYaw(float a, float b, float t) {
        float delta = wrapDegrees(b - a);
        return a + delta * t;
    }

    private float wrapDegrees(float deg) {
        deg = deg % 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private static double clamp(double v, double lo, double hi) { return (v < lo ? lo : (v > hi ? hi : v)); }

    private void safeRemove(Entity e) {
        try { if (e != null && e.isValid()) e.remove(); } catch (Throwable ignore) {}
    }

    private Vector computeForwardDir(Player p, Location boatLoc) {
        Vector dir = p.getEyeLocation().getDirection().clone();
        dir.setY(0);
        if (dir.lengthSquared() < 1e-6) dir = boatLoc.getDirection().clone();
        if (dir.lengthSquared() < 1e-6) dir = new Vector(1, 0, 0);
        return dir.normalize();
    }

    private void spawnHuskyTeam(World w, Session s, Location boatLoc, Location markerLoc, Vector forward) {
        // Lead holder anchored near dog plane (sync with platforms), ~1.9 below marker
        ArmorStand holder = (ArmorStand) w.spawnEntity(markerLoc.clone().add(0, -1.9, 0), EntityType.ARMOR_STAND);
        try { holder.setInvisible(true); } catch (Throwable ignore) {}
        try { holder.setMarker(false); } catch (Throwable ignore) {}
        try { holder.setGravity(false); } catch (Throwable ignore) {}
        try { holder.setSmall(true); } catch (Throwable ignore) {}
        try { holder.setCollidable(false); } catch (Throwable ignore) {}
        s.leadHolder = holder;

        Vector fwd = forward.clone().normalize();
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());
        // Place dogs between boat and marker by anchoring behind the marker along -fwd
        double[][] OFFSETS;
        if (s.dogCount <= 1) {
            OFFSETS = new double[][]{ {-2.2, 0.0} };
        } else {
            OFFSETS = new double[][]{ {-2.2, -0.7}, {-2.2, 0.7} };
        }
        int idx = 0;
        for (double[] off : OFFSETS) {
            if (idx >= s.dogCount) break;
            Vector pos = fwd.clone().multiply(off[0]).add(right.clone().multiply(off[1]));
            Location base = markerLoc.clone().add(pos.getX(), -1.9, pos.getZ());
            ArmorStand plat = (ArmorStand) w.spawnEntity(base, EntityType.ARMOR_STAND);
            try { plat.setInvisible(true); } catch (Throwable ignore) {}
            try { plat.setMarker(false); } catch (Throwable ignore) {}
            try { plat.setGravity(false); } catch (Throwable ignore) {}
            try { plat.setSmall(false); } catch (Throwable ignore) {}
            try { plat.setCollidable(false); } catch (Throwable ignore) {}
            s.dogPlatforms.add(plat);

            org.bukkit.entity.Wolf dog = (org.bukkit.entity.Wolf) w.spawnEntity(base, EntityType.WOLF);
            try { dog.setAI(false); } catch (Throwable ignore) {}
            try { dog.setGravity(false); } catch (Throwable ignore) {}
            try { dog.setAdult(); } catch (Throwable ignore) {}
            try { dog.setInvulnerable(true); } catch (Throwable ignore) {}
            try { dog.setSilent(true); } catch (Throwable ignore) {}
            try { dog.setCollidable(false); } catch (Throwable ignore) {}
            try { plat.addPassenger(dog); } catch (Throwable ignore) {}
            s.dogs.add(dog);
            idx++;
        }
        // Attach leashes to the PLAYER so rope goes to the player's hand
        Player leashPlayer = Bukkit.getPlayer(s.playerId);
        for (org.bukkit.entity.Wolf dog : s.dogs) {
            if (leashPlayer != null && leashPlayer.isOnline()) {
                try {
                    try { dog.getClass().getMethod("setLeashHolder", Entity.class, boolean.class).invoke(dog, leashPlayer, true); }
                    catch (NoSuchMethodException nsme) { dog.setLeashHolder(leashPlayer); }
                } catch (Throwable ignore) {}
            }
        }
    }

    private void updateHuskyTeam(Session s, Location boatLoc, Location markerLoc) {
        if (s.dogPlatforms == null || s.dogs == null) return;
        s.animTick++;
        // Use linear direction from boat to steering marker for alignment
        Vector fwd = markerLoc.toVector().subtract(boatLoc.toVector());
        if (fwd.lengthSquared() < 1e-6) fwd = new Vector(1, 0, 0); else fwd.normalize();
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());
        double[][] OFFSETS;
        if (s.dogCount <= 1) {
            OFFSETS = new double[][]{ {-2.2, 0.0} };
        } else {
            OFFSETS = new double[][]{ {-2.2, -0.7}, {-2.2, 0.7} };
        }
        World w = boatLoc.getWorld();
        // Move lead holder to marker plane (keeps rope length short and consistent)
        if (s.leadHolder != null && s.leadHolder.isValid()) {
            s.leadHolder.teleport(markerLoc.clone().add(0, -1.9, 0));
        }
        for (int i = 0; i < OFFSETS.length && i < s.dogPlatforms.size(); i++) {
            ArmorStand plat = s.dogPlatforms.get(i);
            if (plat == null || !plat.isValid()) continue;
            double[] off = OFFSETS[i];
            Vector pos = fwd.clone().multiply(off[0]).add(right.clone().multiply(off[1]));
            Location base = markerLoc.clone().add(pos.getX(), -1.9, pos.getZ());
            // Bobbing animation relative to the marker plane
            double bob = Math.sin((s.animTick + i * 8) * 0.25) * 0.08;
            base.add(0, bob, 0);
            plat.teleport(base);
            // Keep wolf riding and synced: teleport, face marker, re-mount, and re-leash if needed
            if (i < s.dogs.size()) {
                org.bukkit.entity.Wolf dog = s.dogs.get(i);
                if (dog != null && dog.isValid()) {
                    // Teleport and face toward the marker
                    Location dogLoc = base.clone();
                    Vector toMarker = markerLoc.toVector().subtract(base.toVector()).normalize();
                    dogLoc.setDirection(toMarker);
                    dog.teleport(dogLoc);
                    // Ensure dog is passenger of platform
                    boolean riding = false;
                    try { riding = dog.getVehicle() != null && dog.getVehicle().getUniqueId().equals(plat.getUniqueId()); } catch (Throwable ignore) {}
                    if (!riding) {
                        try { plat.addPassenger(dog); } catch (Throwable ignore) {}
                    }
                    // Maintain leash to PLAYER; if reattached, prevent dropping later via events
                    Player lp = Bukkit.getPlayer(s.playerId);
                    if (lp != null && lp.isOnline()) {
                        try {
                            boolean needs = !dog.isLeashed() || dog.getLeashHolder() == null || !dog.getLeashHolder().getUniqueId().equals(lp.getUniqueId());
                            if (needs) {
                                try { dog.getClass().getMethod("setLeashHolder", Entity.class, boolean.class).invoke(dog, lp, true); }
                                catch (NoSuchMethodException nsme) { dog.setLeashHolder(lp); }
                            }
                        } catch (Throwable ignore) {}
                    }
                }
            }
        }
    }

    private void stopSledById(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) stopSled(p);
        else {
            Session s = sessions.remove(id);
            if (s != null) {
                if (s.task != null) { s.task.cancel(); s.task = null; }
                safeRemove(s.marker);
                safeRemove(s.boat);
            }
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (!(e.getExited() instanceof Player)) return;
        Player p = (Player) e.getExited();
        Session s = sessions.get(p.getUniqueId());
        if (s != null && e.getVehicle().getUniqueId().equals(s.boat.getUniqueId())) {
            // allow exit then cleanup
            Bukkit.getScheduler().runTask(plugin, () -> stopSled(p));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stopSled(e.getPlayer());
    }

    @EventHandler
    public void onPlayerUnleash(PlayerUnleashEntityEvent e) {
        // Prevent manual unleashing of huskies (server leash stays on holder ArmorStand)
        if (e.getEntity() instanceof org.bukkit.entity.Wolf) {
            if (isSledDog((org.bukkit.entity.Wolf) e.getEntity())) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onUnleash(EntityUnleashEvent e) {
        // Reattach leash to the holder armorstand immediately if a sled dog gets unleashed
        if (e.getEntity() instanceof org.bukkit.entity.Wolf) {
            org.bukkit.entity.Wolf dog = (org.bukkit.entity.Wolf) e.getEntity();
            if (isSledDog(dog)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Session s = sessionForDog(dog);
                    if (s != null) {
                        try {
                            Player lp = Bukkit.getPlayer(s.playerId);
                            if (lp != null && lp.isOnline()) {
                                try { dog.getClass().getMethod("setLeashHolder", Entity.class, boolean.class).invoke(dog, lp, true); }
                                catch (NoSuchMethodException nsme) { dog.setLeashHolder(lp); }
                            }
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }
    }

    // Prevent dropped lead items when leash breaks by removing spawned LEAD items near sled dogs
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        try {
            if (e.getEntity() == null || e.getEntity().getItemStack() == null) return;
            if (e.getEntity().getItemStack().getType() != org.bukkit.Material.LEAD) return;
            // If a lead spawns within 3 blocks of any sled dog, remove it (convert break to silent)
            org.bukkit.Location loc = e.getEntity().getLocation();
            for (Session s : sessions.values()) {
                if (s.dogs == null) continue;
                for (org.bukkit.entity.Wolf d : s.dogs) {
                    if (d != null && d.isValid() && d.getWorld().equals(loc.getWorld())) {
                        if (d.getLocation().distanceSquared(loc) <= 9.0) { // 3 blocks
                            e.setCancelled(true);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {}
    }

    private boolean isSledDog(org.bukkit.entity.Wolf w) {
        for (Session s : sessions.values()) {
            if (s.dogs != null && s.dogs.contains(w)) return true;
        }
        return false;
    }

    private Session sessionForDog(org.bukkit.entity.Wolf w) {
        for (Session s : sessions.values()) {
            if (s.dogs != null && s.dogs.contains(w)) return s;
        }
        return null;
    }

    public void stopAll() {
        for (UUID id : new java.util.ArrayList<>(sessions.keySet())) stopSledById(id);
        sessions.clear();
    }
}
