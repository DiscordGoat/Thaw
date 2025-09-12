package goat.thaw.system.dev;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.block.Biome;

import java.util.*;

public class ArcticChunkGenerator extends ChunkGenerator {

    // Audit band: generate only in this vertical slice
    private static final int AUDIT_Y_MIN = -64;
    private static final int AUDIT_Y_MAX = 320; // inclusive, extended for high terrain work

    // Cave carve vertical limits
    private static final int CAVE_MIN_Y = -50; // protect bedrock band below
    private static final int CAVE_MAX_Y = 260; // reduce surface cutting; no river-like carving above 180

    // Structure size (X x Z)


    // Ore weights (relative rarity baseline, lower is rarer)
    // User-specified
    private static final int W_COAL = 40;
    private static final int W_IRON = 20;
    private static final int W_GOLD = 10;
    private static final int W_DIAMOND = 1;
    private static final int W_COPPER = 2;
    private static final int W_EMERALD = 5;
    // Inferred for mid-depth ores
    private static final int W_REDSTONE = 8;
    private static final int W_LAPIS = 6;

    // Ocean/mountain control
    private static final int OCEAN_SEA_LEVEL = 154;
    private static final int MOUNTAIN_Y = 200; // any surface >= this is considered mountain
    private static final int OCEAN_FROM_MOUNTAIN_DIST = 160; // farther shores: oceans start this far from mountains
    private static final int OCEAN_COAST_WIDTH = 56; // wider smoothing band for coastline blend
    private static final int MOUNTAIN_SEARCH_RADIUS = 224; // how far to look for mountains when computing distance
    private static final int SHORE_JITTER_AMPLITUDE = 12; // +/- amplitude to avoid geometric edges

    // Distance field configuration
    // Distance field configuration
    private static final int BASE_HALO = 64;
    // halo should at least cover the shoreline distance
    private static final int MAX_HALO = Math.min(
            MOUNTAIN_SEARCH_RADIUS,
            Math.max(BASE_HALO, OCEAN_FROM_MOUNTAIN_DIST)
    );

    private static final int CHAMFER_AXIS = 5;        // cost for axial steps
    private static final int CHAMFER_DIAGONAL = 7;    // cost for diagonals
    private static final int CHAMFER_INF = Integer.MAX_VALUE / 4;

    private static final ThreadLocal<boolean[][]> TL_MOUNTAIN =
            ThreadLocal.withInitial(() -> new boolean[16 + MAX_HALO * 2][16 + MAX_HALO * 2]);
    private static final ThreadLocal<int[][]> TL_DIST =
            ThreadLocal.withInitial(() -> new int[16 + MAX_HALO * 2][16 + MAX_HALO * 2]);

    // --- Bungalows & reserved flats ---
    private static final int MIN_BUNGALOW_DIST = 100;           // already there
    private static final int BUNGALOW_RESERVE_RADIUS = 9;       // flat radius (blocks)
    private static final int MIN_DIST_TO_MOUNTAIN = 120;        // don't hug mountains

    private static final class Reserve {
        final int x, z, y, r;
        Reserve(int x, int z, int y, int r) { this.x = x; this.z = z; this.y = y; this.r = r; }
    }
    public final List<Reserve> reservedFlats = Collections.synchronizedList(new ArrayList<>());

    public final List<Location> bungalowQueue = Collections.synchronizedList(new ArrayList<>());
    public final List<Location> placedBungalows = Collections.synchronizedList(new ArrayList<>());
    private static ArcticChunkGenerator instance;

    public ArcticChunkGenerator() {
        instance = this;
    }
    public static ArcticChunkGenerator getInstance() {
        return instance;
    }

    public void registerBungalow(Location loc) {
        synchronized (placedBungalows) {
            placedBungalows.add(loc);
        }
    }

    private boolean canPlaceBungalow(World world, ChunkData data, int lx, int lz, int wx, int wz) {
        int baseY = getHighestBlockY(data, world, lx, lz);
        if (baseY < 154 || baseY > 158) return false;

        // reject if any water within 6 blocks from sea band (keeps them out of coves/shallows)
        if (hasWaterNearby(data, world, lx, lz, 6, 145, OCEAN_SEA_LEVEL + 4)) return false;

        int minY = baseY, maxY = baseY;
        Map<Integer, Integer> heightCounts = new HashMap<>();

        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) {
            int x = (lx + dx) & 15, z = (lz + dz) & 15;
            int y = getHighestBlockY(data, world, x, z);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            heightCounts.merge(y, 1, Integer::sum);

            if (y < 154 || y > 158) return false;
            Material topType = data.getType(x, y, z);
            if (!(topType == Material.SNOW || topType == Material.SNOW_BLOCK || topType == Material.DIRT)) return false;
            Material below = data.getType(x, y - 1, z);
            if (below == Material.WATER || below == Material.SAND) return false;
        }

        if (maxY - minY > 2) return false;

        int dominant = heightCounts.values().stream().max(Integer::compareTo).orElse(0);
        int total = heightCounts.values().stream().mapToInt(i -> i).sum();
        if ((dominant / (double) total) < 0.65) return false;

        // keep away from mountains
        if (distanceToNearestPeak(world.getSeed(), wx, wz, 16) < MIN_DIST_TO_MOUNTAIN) return false;

        return true;
    }



    private boolean isRegionAnchor(int chunkX, int chunkZ) {
        // Only run on 8×8 chunk anchors
        return (chunkX % 8 == 0) && (chunkZ % 8 == 0);
    }
    public Location findBungalowSpot(World world, ChunkData data, int chunkX, int chunkZ) {
        int radius = 4;
        int baseX = (chunkX << 4) + 8, baseZ = (chunkZ << 4) + 8;

        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int wx = baseX + (dx << 4), wz = baseZ + (dz << 4);
            int lx = wx & 15, lz = wz & 15;

            if (!canPlaceBungalow(world, data, lx, lz, wx, wz)) continue;
            int y = getHighestBlockY(data, world, lx, lz);
            Location candidate = new Location(world, wx, y, wz);
            if (!isFarEnough(candidate)) continue;

            // remember a flat reservation (used inside terrain fill)
            reserveFlatArea(candidate, y, BUNGALOW_RESERVE_RADIUS);
            // locally flatten right now (covers the current chunk; neighbors will honor the reserve when they gen)
            flattenForBungalowNow(data, world, lx, lz, y, BUNGALOW_RESERVE_RADIUS);
            return candidate;
        }
        return null;
    }

    public boolean isFarEnough(Location loc) {
        synchronized (placedBungalows) {
            for (Location other : placedBungalows) {
                if (other.getWorld().equals(loc.getWorld())) {
                    if (other.distanceSquared(loc) < MIN_BUNGALOW_DIST * MIN_BUNGALOW_DIST) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void flattenForBungalowNow(ChunkData data, World world, int cx, int cz, int y, int r) {
        int yTop = Math.min(world.getMaxHeight() - 1, AUDIT_Y_MAX);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int lx = cx + dx, lz = cz + dz; if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;
            if (dx * dx + dz * dz > r * r) continue;
            for (int yy = 150; yy <= yTop; yy++) {
                if (yy < y) data.setBlock(lx, yy, lz, Material.DIRT);
                else if (yy == y) data.setBlock(lx, yy, lz, Material.SNOW_BLOCK);
                else data.setBlock(lx, yy, lz, Material.AIR);
            }
        }
    }


    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        ChunkData data = createChunkData(world);

        final long seed = world.getSeed();

        // 1) Stone fill below sub-land only (leave 150+ empty for land tiers)
        final int SUBLAND_MIN = 150;

        int yFillMin = Math.max(world.getMinHeight(), AUDIT_Y_MIN);
        int yFillMax = Math.min(world.getMaxHeight() - 1, SUBLAND_MIN - 1); // up to 149
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = yFillMin; y <= yFillMax; y++) {
                    data.setBlock(x, y, z, Material.STONE);
                }
            }
        }

        // 2) Sub-land (Tier 1): disabled while iterating terrain
        // generateSubLandTransition(world, data, seed, chunkX, chunkZ);

        // 3) Surface terrain: mountains + plains with smooth transitions
        generateMountains(world, data, seed, chunkX, chunkZ, biome);

        // 4) Ores (vein attempts, depth-biased)
        placeOres(world, data, seed, chunkX, chunkZ);

        // 5) Caves: component-based carver (worms, arenas, stairs, ominous, chaos)
        carveCavesV2(world, data, seed, chunkX, chunkZ);

        // 6) Decoration pass on surface (after carving): trees, boulders
        placeTaigaClusters(world, data, seed, chunkX, chunkZ, biome);

        // 7) Structures (emerald slabs) after carving; air-exposed
        // bungalow logic only for region anchors
        if (isRegionAnchor(chunkX, chunkZ)) {
            Bukkit.getLogger().info("[Bungalow] Checking region anchor ("+chunkX+","+chunkZ+")");
            Location spot = findBungalowSpot(world, data, chunkX, chunkZ);
            if (spot != null) {
                synchronized (bungalowQueue) {
                    bungalowQueue.add(spot);
                }
                Bukkit.getLogger().info("[Bungalow] Added bungalow to queue at "+spot);
            }
        }


        placeBoulders(world, data, seed, chunkX, chunkZ, biome);


        // 8) Bedrock band overwrite at bottom (vanilla-like)
        placeBedrock(world, data, seed, chunkX, chunkZ);

        // 9) Post-process: enforce sand rules near water and above sand
        postProcessSandAndDirt(world, data);

        return data;
    }
    // Safe alternative to world.getHighestBlockYAt, works on in-progress ChunkData
    private int getHighestBlockY(ChunkData data, World world, int lx, int lz) {
        int yTop = Math.min(world.getMaxHeight() - 1, AUDIT_Y_MAX);
        int yBottom = Math.max(world.getMinHeight(), AUDIT_Y_MIN);
        for (int y = yTop; y >= yBottom; y--) {
            Material m = data.getType(lx, y, lz);
            if (m != Material.AIR) {
                return y;
            }
        }
        return yBottom; // fallback
    }

    private boolean hasWaterNearby(ChunkData data, World world, int lx, int lz, int radius, int yMin, int yMax) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            int x = lx + dx; if (x < 0 || x > 15) continue;
            int dx2 = dx * dx;
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx2 + dz * dz > r2) continue;
                int z = lz + dz; if (z < 0 || z > 15) continue;
                for (int y = yMin; y <= yMax; y++) {
                    try { if (data.getType(x, y, z) == Material.WATER) return true; } catch (Throwable ignore) {}
                }
            }
        }
        return false;
    }

    private boolean insideReserve(int wx, int wz) {
        synchronized (reservedFlats) {
            for (Reserve r : reservedFlats) {
                int dx = wx - r.x, dz = wz - r.z;
                if (dx * dx + dz * dz <= r.r * r.r) return true;
            }
        }
        return false;
    }

    private int reservedHeightAt(int wx, int wz, int def) {
        synchronized (reservedFlats) {
            for (Reserve r : reservedFlats) {
                int dx = wx - r.x, dz = wz - r.z;
                if (dx * dx + dz * dz <= r.r * r.r) return r.y;
            }
        }
        return def;
    }

    private void reserveFlatArea(Location center, int y, int radius) {
        synchronized (reservedFlats) { reservedFlats.add(new Reserve(center.getBlockX(), center.getBlockZ(), y, radius)); }
    }



    private void placeBedrock(World world, ChunkData data, long seed, int chunkX, int chunkZ) {
        final int yMin = Math.max(world.getMinHeight(), -64);
        final int yMax = Math.min(world.getMaxHeight() - 1, -60);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                for (int y = yMin; y <= yMax; y++) {
                    double chance;
                    if (y <= -64) chance = 1.0;
                    else if (y == -63) chance = 0.75;
                    else if (y == -62) chance = 0.50;
                    else if (y == -61) chance = 0.25;
                    else chance = 0.0; // -60 and above
                    if (chance <= 0) continue;
                    double r = random01(hash(seed, worldX, y, worldZ, 0xB3D20A1DL));
                    if (r < chance) data.setBlock(x, y, z, Material.BEDROCK);
                }
            }
        }
    }

    private void carveCaves(World world, ChunkData data, long seed, int chunkX, int chunkZ) {
        final int REGION = 64; // region size (blocks)
        int yMin = Math.max(CAVE_MIN_Y, AUDIT_Y_MIN);
        int yMax = Math.min(CAVE_MAX_Y, AUDIT_Y_MAX);
        if (yMin > yMax) return;

        int chunkBaseX = (chunkX << 4);
        int chunkBaseZ = (chunkZ << 4);

        int rx0 = Math.floorDiv(chunkBaseX - 1, REGION);
        int rz0 = Math.floorDiv(chunkBaseZ - 1, REGION);
        int rx1 = Math.floorDiv(chunkBaseX + 16, REGION);
        int rz1 = Math.floorDiv(chunkBaseZ + 16, REGION);

        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                long rseed = hash(seed, rx, rz, 0, 0x57EEDCAFL);
                Random rr = new Random(rseed);
                int worms = 2 + rr.nextInt(2); // 2-3 worms per region
                for (int i = 0; i < worms; i++) {
                    // Start within region
                    double x = rx * REGION + rr.nextDouble() * REGION;
                    double z = rz * REGION + rr.nextDouble() * REGION;
                    int y = yMin + rr.nextInt(yMax - yMin + 1);

                    double yaw = rr.nextDouble() * Math.PI * 2.0;
                    double pitch = (rr.nextDouble() - 0.5) * 0.3; // gentle slope

                    int steps = 200 + rr.nextInt(100); // ~200-300 steps for longer tunnels
                    double stepLen = 1.5; // longer step length than before
                    double rMin = 1.0, rMax = 5.0; // broader variation in tunnel thickness
                    // Radius modulation parameters
                    double rPhase = rr.nextDouble() * Math.PI * 2.0;
                    double rFreq = 0.05 + rr.nextDouble() * 0.05; // slow oscillation

                    for (int s = 0; s < steps; s++) {
                        x += Math.cos(yaw) * stepLen;
                        z += Math.sin(yaw) * stepLen;
                        y += (int) Math.round(Math.sin(pitch));

                        if (y < yMin + 1) { y = yMin + 1; pitch = Math.abs(pitch) * 0.5; }
                        if (y > yMax - 1) { y = yMax - 1; pitch = -Math.abs(pitch) * 0.5; }

                        yaw += (rr.nextDouble() - 0.5) * 0.18;   // slightly steadier direction
                        pitch += (rr.nextDouble() - 0.5) * 0.08; // gentler vertical changes
                        if (pitch < -0.6) pitch = -0.6;
                        if (pitch > 0.6) pitch = 0.6;

                        // Base radius with smooth modulation and clamp
                        double radiusBase = rMin + rr.nextDouble() * (rMax - rMin);
                        double rJitter = 0.5 * Math.sin(rPhase + s * rFreq);
                        double radius = Math.max(0.8, Math.min(6.0, radiusBase + rJitter));
                        int ix = (int) Math.floor(x);
                        int iy = y;
                        int iz = (int) Math.floor(z);

                        int minX = Math.max(ix - (int) Math.ceil(radius), chunkBaseX);
                        int maxX = Math.min(ix + (int) Math.ceil(radius), chunkBaseX + 15);
                        int minZ = Math.max(iz - (int) Math.ceil(radius), chunkBaseZ);
                        int maxZ = Math.min(iz + (int) Math.ceil(radius), chunkBaseZ + 15);
                        int minY = Math.max(iy - (int) Math.ceil(radius), yMin);
                        int maxY = Math.min(iy + (int) Math.ceil(radius), yMax);

                        for (int wx = minX; wx <= maxX; wx++) {
                            int lx = wx - chunkBaseX;
                            for (int wz = minZ; wz <= maxZ; wz++) {
                                int lz = wz - chunkBaseZ;
                                for (int wy = minY; wy <= maxY; wy++) {
                                    if (wy <= -61) continue; // protect bedrock
                                    double dx = wx - x;
                                    double dy = wy - y;
                                    double dz = wz - z;
                                    if ((dx*dx + dy*dy + dz*dz) <= radius * radius) {
                                        data.setBlock(lx, wy, lz, Material.AIR);
                                    }
                                }
                            }
                        }

                        // Occasional larger room carve
                        if (rr.nextInt(40) == 0) {
                            double roomR = Math.min(6.0, radius + 2.5);
                            int ix2 = (int) Math.floor(x);
                            int iy2 = y;
                            int iz2 = (int) Math.floor(z);
                            int minX2 = Math.max(ix2 - (int) Math.ceil(roomR), chunkBaseX);
                            int maxX2 = Math.min(ix2 + (int) Math.ceil(roomR), chunkBaseX + 15);
                            int minZ2 = Math.max(iz2 - (int) Math.ceil(roomR), chunkBaseZ);
                            int maxZ2 = Math.min(iz2 + (int) Math.ceil(roomR), chunkBaseZ + 15);
                            int minY2 = Math.max(iy2 - (int) Math.ceil(roomR), yMin);
                            int maxY2 = Math.min(iy2 + (int) Math.ceil(roomR), yMax);
                            for (int wx2 = minX2; wx2 <= maxX2; wx2++) {
                                int lx2 = wx2 - chunkBaseX;
                                for (int wz2 = minZ2; wz2 <= maxZ2; wz2++) {
                                    int lz2 = wz2 - chunkBaseZ;
                                    for (int wy2 = minY2; wy2 <= maxY2; wy2++) {
                                        if (wy2 <= -61) continue;
                                        double dx2 = wx2 - x;
                                        double dy2 = wy2 - y;
                                        double dz2 = wz2 - z;
                                        if ((dx2*dx2 + dy2*dy2 + dz2*dz2) <= roomR * roomR) {
                                            data.setBlock(lx2, wy2, lz2, Material.AIR);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private int surfaceY(ChunkData data, int lx, int lz, int top) {
        for (int y = Math.min(top, AUDIT_Y_MAX); y >= 0; y--) {
            try {
                Material m = data.getType(lx, y, lz);
                if (m != Material.AIR && m != Material.SNOW) return y;
            } catch (Throwable ignore) {}
        }
        return -1;
    }

    private int slopeAt(ChunkData data, int lx, int lz, int top) {
        int y0 = surfaceY(data, lx, lz, top);
        if (y0 < 0) return 0;
        int maxDiff = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int x = lx + dx, z = lz + dz;
                if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                int y1 = surfaceY(data, x, z, top);
                if (y1 >= 0) maxDiff = Math.max(maxDiff, Math.abs(y1 - y0));
            }
        }
        return maxDiff;
    }

    private void placeTreeSpruce(ChunkData data, int lx, int y, int lz, int height) {
        // Guard: prevent trees from spawning on leaves or logs
        try {
            Material ground = data.getType(lx, Math.max(0, y - 1), lz);
            if (isLeavesOrLog(ground)) return;
        } catch (Throwable ignore) {}
        // Simple taiga spruce: log column with layered leaf rings
        for (int i = 0; i < height; i++) data.setBlock(lx, y + i, lz, Material.SPRUCE_LOG);
        int top = y + height - 1;
        int radius = Math.max(1, height / 4);
        for (int ry = 0; ry <= radius; ry++) {
            int r = Math.max(1, radius - ry);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > r + 1) continue;
                    int xx = lx + dx, zz = lz + dz, yy = top - ry;
                    if (xx < 0 || xx > 15 || zz < 0 || zz > 15) continue;
                    if (yy >= y && yy <= AUDIT_Y_MAX) {
                        if (data.getType(xx, yy, zz) == Material.AIR) data.setBlock(xx, yy, zz, Material.SPRUCE_LEAVES);
                    }
                }
            }
        }
        // top spike
        if (top + 1 <= AUDIT_Y_MAX) data.setBlock(lx, top + 1, lz, Material.SPRUCE_LEAVES);
    }

    private boolean isLeavesOrLog(Material m) {
        if (m == null) return false;
        String n = m.name();
        return n.endsWith("_LEAVES") || n.endsWith("_LOG") || n.contains("LEAVES") || n.contains("LOG");
    }

    private void placeTaigaClusters(World world, ChunkData data, long seed, int chunkX, int chunkZ, BiomeGrid biome) {
        int chunkBaseX = (chunkX << 4);
        int chunkBaseZ = (chunkZ << 4);
        // Cluster decision
        double clusterMask = fbm2(seed ^ 0x7AE3C15DL, chunkX * 0.13, chunkZ * 0.13);
        if (clusterMask < 0.55) return; // roughly 45% of chunks
        int clusters = 1 + (int)Math.floor((clusterMask - 0.55) * 6.0); // 1..3
        java.util.Random rng = new java.util.Random(hash(seed, chunkX, 77, chunkZ, 0x515EC0DEL));
        for (int c = 0; c < clusters; c++) {
            int cx = rng.nextInt(16), cz = rng.nextInt(16);
            int baseY = surfaceY(data, cx, cz, 220);
            // Prefer mid-level slopes; also allow occasional high-peak clusters
            if (baseY < 165) continue;
            boolean onPeak = baseY >= 205;
            if (!onPeak && baseY > 195) continue;
            int slope = slopeAt(data, cx, cz, 220);
            if (slope < 2 || slope > 7) continue; // avoid peaks/flats
            int trees = onPeak ? (3 + rng.nextInt(4)) : (5 + rng.nextInt(6));
            for (int t = 0; t < trees; t++) {
                int lx = cx + rng.nextInt(7) - 3;
                int lz = cz + rng.nextInt(7) - 3;
                if (lx < 1 || lx > 14 || lz < 1 || lz > 14) continue;
                int y = surfaceY(data, lx, lz, 220);
                // Prevent trees from generating in ocean/deep ocean biomes
                try { Biome b = biome.getBiome(lx, lz); if (b == Biome.OCEAN || b == Biome.DEEP_OCEAN) continue; } catch (Throwable ignore) {}
                if (!onPeak) { if (y < 165 || y > 195) continue; } else { if (y < 200 || y > 220) continue; }
                Material top = data.getType(lx, y, lz);
                if (isLeavesOrLog(top)) continue;
                if (top != Material.GRASS_BLOCK && top != Material.DIRT && top != Material.SNOW_BLOCK && top != Material.STONE) continue;
                int h = 5 + rng.nextInt(5);
                placeTreeSpruce(data, lx, y + 1, lz, h);
            }
            // rare singleton nearby
            if (rng.nextDouble() < 0.2) {
                int lx = cx + rng.nextInt(11) - 5;
                int lz = cz + rng.nextInt(11) - 5;
                if (lx >= 1 && lx <= 14 && lz >= 1 && lz <= 14) {
                    int y = surfaceY(data, lx, lz, 220);
                    // Prevent trees from generating in ocean/deep ocean biomes
                    try { Biome b = biome.getBiome(lx, lz); if (b == Biome.OCEAN || b == Biome.DEEP_OCEAN) continue; } catch (Throwable ignore) {}
                    Material top2 = data.getType(lx, y, lz);
                    if (isLeavesOrLog(top2)) continue;
                    if ((y >= 165 && y <= 195) || (y >= 205 && y <= 220)) placeTreeSpruce(data, lx, y + 1, lz, 6 + rng.nextInt(3));
                }
            }
        }

        // Additional foothill trees: small clusters in valleys 160..175 with gentle slopes
        for (int t = 0; t < 6; t++) {
            int lx = 1 + rng.nextInt(14);
            int lz = 1 + rng.nextInt(14);
            int y = surfaceY(data, lx, lz, 220);
            // Prevent trees from generating in ocean/deep ocean biomes
            try { Biome b = biome.getBiome(lx, lz); if (b == Biome.OCEAN || b == Biome.DEEP_OCEAN) continue; } catch (Throwable ignore) {}
            Material top = data.getType(lx, y, lz);
            if (isLeavesOrLog(top)) continue;
            if (y >= 160 && y <= 175 && slopeAt(data, lx, lz, 220) <= 3) {
                placeTreeSpruce(data, lx, y + 1, lz, 6 + rng.nextInt(5));
            }
        }

        // (Hill vegetation for injected hills removed per revert)
    }

    private void placeBoulders(World world, ChunkData data, long seed, int chunkX, int chunkZ, BiomeGrid biome) {
        java.util.Random rng = new java.util.Random(hash(seed, chunkX, 0xB00B1EDL, chunkZ, 0xDEADB0B0L));
        // Gate the whole boulder pass (40% chance per chunk)
        if (rng.nextDouble() >= 0.4) return;

        // Check if this chunk contains a bungalow
        for (Location bungalowLoc : placedBungalows) {
            if (bungalowLoc.getChunk().getX() == chunkX && bungalowLoc.getChunk().getZ() == chunkZ) {
                return; // Skip boulder generation in chunks with bungalows
            }
        }

        // Track placed boulder centers within this chunk to enforce spacing
        java.util.ArrayList<int[]> placedCenters = new java.util.ArrayList<>();

        // Stone boulders with embedded ores (stone, coal, iron, rare gold)
        int rocks = rng.nextInt(5); // 0..4 when it triggers
        for (int i = 0; i < rocks; i++) {
            int lx = 2 + rng.nextInt(12);
            int lz = 2 + rng.nextInt(12);
            int y = surfaceY(data, lx, lz, 220);
            if (y < 150) continue;
            // Restrict boulders to plains only (keep off slopes/peaks)
            if (y > 165) continue; // plains band ~150..165
            if (slopeAt(data, lx, lz, 220) > 2) continue; // avoid slopes
            // Prevent boulders from generating in ocean/deep ocean biomes
            try { Biome b = biome.getBiome(lx, lz); if (b == Biome.OCEAN || b == Biome.DEEP_OCEAN) continue; } catch (Throwable ignore) {}
            // Prevent boulders where the highest block in the column is water
            try {
                Material topMat = highestNonAir(data, world, lx, lz);
                if (topMat == Material.WATER) continue;
            } catch (Throwable ignore) {}
            // Enforce spacing: skip if within 10 blocks of an existing boulder in this chunk
            if (isTooCloseToCenters(placedCenters, lx, lz, 10 * 10)) continue;
            int r = 1 + rng.nextInt(2);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = 0; dy <= r; dy++) {
                        if (dx*dx + dz*dz + dy*dy > r*r) continue;
                        int x = lx + dx, z = lz + dz, yy = y + 1 + dy;
                        if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                        Material m = Material.STONE;
                        // Embed ores: coal common, iron less, gold rare
                        double roll = rng.nextDouble();
                        if (roll < 0.10) m = Material.COAL_ORE; // 10%
                        else if (roll < 0.16) m = Material.IRON_ORE; // +6%
                        else if (roll < 0.165) m = Material.GOLD_ORE; // +0.5% rare
                        data.setBlock(x, yy, z, m);
                    }
                }
            }
            placedCenters.add(new int[]{lx, lz});
        }

        // Occasional ice-only boulders (packed/blue ice), placed on cold plains near coast but not in water
        int iceRocks = rng.nextInt(2); // 0..1 per chunk when triggered
        for (int i = 0; i < iceRocks; i++) {
            int lx = 2 + rng.nextInt(12);
            int lz = 2 + rng.nextInt(12);
            int y = surfaceY(data, lx, lz, 220);
            if (y < 150 || y > 170) continue; // prefer low, near-shore areas
            if (slopeAt(data, lx, lz, 220) > 2) continue;
            // Prevent boulders from generating in ocean/deep ocean biomes
            try { Biome b = biome.getBiome(lx, lz); if (b == Biome.OCEAN || b == Biome.DEEP_OCEAN) continue; } catch (Throwable ignore) {}
            // Prevent boulders where the highest block in the column is water
            try {
                Material topMat = highestNonAir(data, world, lx, lz);
                if (topMat == Material.WATER) continue;
            } catch (Throwable ignore) {}
            if (isTooCloseToCenters(placedCenters, lx, lz, 10 * 10)) continue;
            int r = 1 + rng.nextInt(2);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = 0; dy <= r; dy++) {
                        if (dx*dx + dz*dz + dy*dy > r*r) continue;
                        int x = lx + dx, z = lz + dz, yy = y + 1 + dy;
                        if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                        // Majority packed ice, some blue ice
                        Material m = (rng.nextDouble() < 0.20) ? Material.BLUE_ICE : Material.PACKED_ICE;
                        data.setBlock(x, yy, z, m);
                    }
                }
            }
            placedCenters.add(new int[]{lx, lz});
        }
    }

    private boolean isTooCloseToCenters(java.util.List<int[]> centers, int x, int z, int minDistSq) {
        for (int[] c : centers) {
            int dx = x - c[0];
            int dz = z - c[1];
            if (dx * dx + dz * dz < minDistSq) return true;
        }
        return false;
    }

    // Returns the highest non-air material in the column (lx,lz) within world bounds
    private Material highestNonAir(ChunkData data, World world, int lx, int lz) {
        int yTop = Math.min(world.getMaxHeight() - 1, AUDIT_Y_MAX);
        int yBottom = Math.max(world.getMinHeight(), AUDIT_Y_MIN);
        for (int y = yTop; y >= yBottom; y--) {
            Material m = data.getType(lx, y, lz);
            if (m != Material.AIR) return m;
        }
        return Material.AIR;
    }

    private void placeOres(World world, ChunkData data, long seed, int chunkX, int chunkZ) {
        Random rng = new Random(hash(seed, chunkX, chunkZ, 0, 0x0DE50F00DL));

        for (int i = 0; i < 72; i++) { // 200% buff over 24x => 72x attempts
            // Coal: more common higher (TOP bias), up to Y=150
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.COAL_ORE, W_COAL, -64, 150, Bias.TOP, 0, false);
            // Iron: uniform across all depths up to 150
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.IRON_ORE, W_IRON, -64, 150, Bias.UNIFORM, 0, false);
            // Gold: triangular around -50, allowed up to 150
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.GOLD_ORE, W_GOLD, -64, 150, Bias.TRIANGULAR, -50, false);
            // Lapis: deeper more common, up to 150
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.LAPIS_ORE, W_LAPIS, -64, 150, Bias.BOTTOM, 0, false);
            // Redstone: deeper more common, up to 150
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.REDSTONE_ORE, W_REDSTONE, -64, 150, Bias.BOTTOM, 0, false);
            // Emeralds: uniform, very rare, up to 150
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.EMERALD_ORE, W_EMERALD, -64, 150, Bias.UNIFORM, 0, false);
            // Diamonds: deeper more common, max Y=-10 (unchanged max)
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.DIAMOND_ORE, W_DIAMOND, -64, -10, Bias.BOTTOM, 0, false);
            // Obsidian: as often as diamonds below -40 (keep cap), uses 5% per-block chance in 2x2x2 cluster
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.OBSIDIAN, W_DIAMOND, -64, -40, Bias.BOTTOM, 0, true);
            // Copper: uniform from -32 up to 150
            oreAttempts(world, data, rng, chunkX, chunkZ, Material.COPPER_ORE, W_COPPER, -32, 150, Bias.UNIFORM, 0, false);
        }
    }

    private enum Bias { TOP, BOTTOM, UNIFORM, TRIANGULAR }

    private void oreAttempts(World world, ChunkData data, Random rng, int chunkX, int chunkZ,
                             Material ore, int weight, int minY, int maxY, Bias bias, int peakY, boolean isObsidian) {
        int yMin = Math.max(AUDIT_Y_MIN, minY);
        int yMax = Math.min(AUDIT_Y_MAX, maxY);
        if (yMin > yMax) return;

        double chance = Math.min(1.0, weight * 0.02);
        if (rng.nextDouble() >= chance) return;

        int clusters = 1 + (weight >= 20 && rng.nextDouble() < 0.25 ? 1 : 0);
        for (int c = 0; c < clusters; c++) {
            // Pick anchor within chunk; ensure space for 2x2x2
            int ax = rng.nextInt(15); // 0..14 so ax+1 <= 15
            int az = rng.nextInt(15);
            int ay = biasedY(rng, yMin, yMax - 1, bias, peakY); // reserve ay+1

            int wx = (chunkX << 4) + ax;
            int wz = (chunkZ << 4) + az;
            long salt = ore.ordinal() * 0x9E3779B97F4A7C15L + c;
            Random local = new Random(hash(world.getSeed(), wx, ay, wz, salt));

            // Build list of 8 positions in 2x2x2
            int[][] positions = new int[8][3];
            int idx = 0;
            for (int dx = 0; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        positions[idx][0] = ax + dx;
                        positions[idx][1] = ay + dy;
                        positions[idx][2] = az + dz;
                        idx++;
                    }
                }
            }

            // Shuffle order to make forced minimums look natural
            for (int i = positions.length - 1; i > 0; i--) {
                int j = local.nextInt(i + 1);
                int[] tmp = positions[i]; positions[i] = positions[j]; positions[j] = tmp;
            }

            // Per-block probability: 20% for normal ores, 5% for obsidian
            double p = isObsidian ? 0.05 : 0.20;
            int placed = 0;
            boolean[] chosen = new boolean[8];
            for (int i = 0; i < 8; i++) {
                if (local.nextDouble() < p) chosen[i] = true;
            }
            // Enforce min=3
            int chosenCount = 0; for (boolean b : chosen) if (b) chosenCount++;
            for (int i = 0; i < 8 && chosenCount < 3; i++) { if (!chosen[i]) { chosen[i] = true; chosenCount++; } }

            // Place up to max 8 (all possible) where stone is present
            for (int i = 0; i < 8; i++) {
                if (!chosen[i]) continue;
                int lx = positions[i][0]; int ly = positions[i][1]; int lz = positions[i][2];
                if (ly < yMin || ly > yMax) continue;
                Material current;
                try { current = data.getType(lx, ly, lz); } catch (Throwable t) { current = Material.AIR; }
                if (current == Material.STONE) {
                    data.setBlock(lx, ly, lz, ore);
                    placed++;
                }
            }
        }
    }

    private int biasedY(Random rng, int yMin, int yMax, Bias bias, int peakY) {
        if (yMax == yMin) return yMin;
        for (int attempts = 0; attempts < 8; attempts++) {
            int y = yMin + rng.nextInt(yMax - yMin + 1);
            double w;
            switch (bias) {
                case UNIFORM:
                    return y; // first sample is fine
                case TOP:
                    w = triangularWeight(y, yMin, yMax, yMax);
                    break;
                case BOTTOM:
                    w = triangularWeight(y, yMin, yMax, yMin);
                    break;
                case TRIANGULAR:
                default:
                    w = triangularWeight(y, yMin, yMax, peakY);
                    break;
            }
            if (rng.nextDouble() < w) return y;
        }
        return yMin + rng.nextInt(yMax - yMin + 1);
    }

    private double triangularWeight(int y, int yMin, int yMax, int peakY) {
        if (yMax == yMin) return 1.0;
        double range = yMax - yMin;
        double d = 1.0 - Math.min(1.0, Math.abs(y - peakY) / (range * 0.5));
        return Math.max(0.05, d);
    }

    // --- Noise helpers ---
    private static long hash(long seed, long x, long y, long z, long salt) {
        long h = seed ^ salt;
        h ^= x * 0x9E3779B185EBCA87L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }

    private static double random01(long h) {
        long v = (h >>> 11) & 0x1FFFFFFFFFFFFFL; // 53 bits
        return v / (double) (1L << 53);
    }

    private static double fade(double t) {
        // 6t^5 - 15t^4 + 10t^3 (Perlin fade)
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double valueAt(long seed, int xi, int yi, int zi) {
        return random01(hash(seed, xi, yi, zi, 0x12345678L));
    }

    private static double valueNoise3(long seed, double x, double y, double z) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;

        double tx = fade(x - x0);
        double ty = fade(y - y0);
        double tz = fade(z - z0);

        double c000 = valueAt(seed, x0, y0, z0);
        double c100 = valueAt(seed, x1, y0, z0);
        double c010 = valueAt(seed, x0, y1, z0);
        double c110 = valueAt(seed, x1, y1, z0);
        double c001 = valueAt(seed, x0, y0, z1);
        double c101 = valueAt(seed, x1, y0, z1);
        double c011 = valueAt(seed, x0, y1, z1);
        double c111 = valueAt(seed, x1, y1, z1);

        double x00 = lerp(c000, c100, tx);
        double x10 = lerp(c010, c110, tx);
        double x01 = lerp(c001, c101, tx);
        double x11 = lerp(c011, c111, tx);

        double y0v = lerp(x00, x10, ty);
        double y1v = lerp(x01, x11, ty);

        return lerp(y0v, y1v, tz);
    }

    private static double valueNoise2(long seed, double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = x0 + 1;
        int y1 = y0 + 1;

        double tx = fade(x - x0);
        double ty = fade(y - y0);

        double c00 = valueAt(seed, x0, y0, 0);
        double c10 = valueAt(seed, x1, y0, 0);
        double c01 = valueAt(seed, x0, y1, 0);
        double c11 = valueAt(seed, x1, y1, 0);

        double x0v = lerp(c00, c10, tx);
        double x1v = lerp(c01, c11, tx);
        return lerp(x0v, x1v, ty);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    // --- Surface Terrain (hills-only) ---
    private static final double PEAK_CHANCE = 1.0 / 120; // per chunk (increase spacing between peaks)
    private static final int PEAK_MIN_Y = 200;
    private static final int PEAK_MAX_Y = 280;
    private static final int PEAK_INFLUENCE = 96; // blocks

    // Hills-only surface generator: rolling terrain without mountains


    private void generateMountains(World world, ChunkData data, long seed, int chunkX, int chunkZ, BiomeGrid biome) {
        int chunkBaseX = (chunkX << 4);
        int chunkBaseZ = (chunkZ << 4);
        // Cap mountains similar to our target range
        int worldMaxY = Math.min(world.getMaxHeight() - 1, 320);

        // Collect anchors (hill centers) from neighboring chunks within influence radius
        int rChunks = (PEAK_INFLUENCE + 15) >> 4; // ceil(radius/16)
        class Peak { int cx, cz, x, z, h; Peak(int cx,int cz,int x,int z,int h){this.cx=cx;this.cz=cz;this.x=x;this.z=z;this.h=h;} }
        java.util.ArrayList<Peak> peaks = new java.util.ArrayList<>();
        for (int cx = chunkX - rChunks; cx <= chunkX + rChunks; cx++) {
            for (int cz = chunkZ - rChunks; cz <= chunkZ + rChunks; cz++) {
                if (!hasPeak(seed, cx, cz)) continue;
                int[] p = peakParams(seed, cx, cz);
                int px = p[0]; int pz = p[1];
                // Taller anchors for mountains: 240..300
                long hh = hash(seed, cx, 4, cz, 0xBEEFC0FEL);
                int ph = 240 + (int)Math.floor(random01(hh) * 61.0); // 240..300
                // If peak center is far beyond influence from this chunk, skip
                int minWX = chunkBaseX - PEAK_INFLUENCE, maxWX = chunkBaseX + 15 + PEAK_INFLUENCE;
                int minWZ = chunkBaseZ - PEAK_INFLUENCE, maxWZ = chunkBaseZ + 15 + PEAK_INFLUENCE;
                if (px < minWX || px > maxWX || pz < minWZ || pz > maxWZ) continue;
                peaks.add(new Peak(cx, cz, px, pz, ph));

                // Generate up to 8 child hill anchors around this anchor (deterministic)
                long baseHash = hash(seed, cx, 2, cz, 0xC13579A1L);
                int plateauCount = (int) Math.floor(random01(baseHash) * 9.0); // 0..8
                for (int i = 0; i < plateauCount; i++) {
                    long ih = hash(seed, cx, 3 + i, cz, 0xA5B4C3D2L);
                    double angle = random01(ih) * Math.PI * 2.0;
                    double dist = 24.0 + random01(ih ^ 0x9E3779B97F4A7C15L) * 72.0; // 24..96
                    int cpx = px + (int) Math.round(Math.cos(angle) * dist);
                    int cpz = pz + (int) Math.round(Math.sin(angle) * dist);
                    int ch = 200 + (int)Math.floor(random01(ih ^ 0xDEADBEE1L) * 61.0); // 200..260
                    if (cpx < minWX || cpx > maxWX || cpz < minWZ || cpz > maxWZ) continue;
                    peaks.add(new Peak(cx, cz, cpx, cpz, ch));
                }
            }
        }
        // Continue even if empty (to allow lakes/ground)

        // No core pillars for hill-only mode


        // Build surface heightmap using a base + secondary + edge-taper approach
        double[][] surf = new double[16][16];

        // Detect long stretches of plains (~200+ blocks around chunk center) to boost mountains locally
        boolean longPlains = false;
        {
            int centerX = chunkBaseX + 8;
            int centerZ = chunkBaseZ + 8;
            int radius = 224; // ~200 block radius
            int step = 64;
            int total = 0, plains = 0;
            for (int dx = -radius; dx <= radius; dx += step) {
                for (int dz = -radius; dz <= radius; dz += step) {
                    int sx = centerX + dx;
                    int sz = centerZ + dz;
                    double lowS = fbm2(seed ^ 0xA1B2C3D4L, sx * 0.003, sz * 0.003);
                    double bandS = (lowS - 0.5) * 2.0;
                    if (bandS < 0.15) plains++;
                    total++;
                }
            }
            if (total > 0 && (plains / (double) total) > 0.80) longPlains = true;
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkBaseX + lx; int wz = chunkBaseZ + lz;

                // A) Base terrain control
                double base = 155.0; // local ground reference for this world
                double low = fbm2(seed ^ 0xA1B2C3D4L, wx * 0.003, wz * 0.003); // [0,1]
                // Normalize around 0 with amplitude ~Â±30..35
                double mountainBand = (low - 0.5) * 2.0; // [-1,1]
                // Mask for mountain placement (smooth thresholded)
                double t0 = 0.15, t1 = 0.45; // lower->upper thresholds on band
                if (longPlains) { t0 -= 0.06; t1 -= 0.06; }
                double mask = clamp01((mountainBand - t0) / (t1 - t0));
                mask = mask * mask; // soften base, stronger near centers

                // Even spacing bias: gently encourage mountains near jittered cell centers
                // to reduce clustering and wide gaps without changing overall shapes
                final double cellSize = 288.0; // ~18 chunks (increase spacing)
                int nx = (int) Math.round((double) wx / cellSize);
                int nz = (int) Math.round((double) wz / cellSize);
                double jx = (random01(hash(seed, nx, 51, nz, 0xA1C3E5L)) - 0.5) * (cellSize * 0.35);
                double jz = (random01(hash(seed, nx, 87, nz, 0xB2D4F6L)) - 0.5) * (cellSize * 0.35);
                double cx = nx * cellSize + jx;
                double cz = nz * cellSize + jz;
                double ddx = wx - cx, ddz = wz - cz;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                double R = cellSize * 0.5; // influence radius ~144 blocks
                double w = clamp01(1.0 - d / R); // 1 at center, 0 at edge
                double evenBoost = (w - 0.5) * 0.20; // small bias [-0.1, +0.1]
                mask = clamp01(mask + evenBoost);
                // Base mountain lift, clamped to ~60 above base
                double baseLift = mask * 60.0; // <= 60

                // B) Secondary detail (ridges/variation) with smaller amplitude
                double ridge = fbm2(seed ^ 0xB3C4D5E6L, wx * 0.02, wz * 0.02); // [0,1]
                double ridgeAdd = (ridge - 0.5) * 2.0 * 10.0; // Â±10
                ridgeAdd *= mask; // only influence mountain areas

                // C) Edge taper/erosion-like falloff near mountain edges
                // Compute edge strength from how close mask is to threshold
                double edge = clamp01((mountainBand - t0) / (t1 - t0));
                double edgeTaper = edge * edge * (3 - 2 * edge); // smoothstep
                baseLift *= edgeTaper;
                ridgeAdd *= edgeTaper;

                // Plains micro undulation
                double plains = (fbm2(seed ^ 0x600DD00EL, wx * 0.02, wz * 0.02) - 0.5) * 2.0; // Â±1

                double h = base + plains + baseLift + ridgeAdd;

                // Erosion-style rise to sharpen peaks while smoothing bases
                double erosion = fbm2(seed ^ 0x0E01234L, wx * 0.004, wz * 0.004); // [0,1]
                h += (erosion * erosion) * 40.0 * mask;

                // Dramatic peaks: ridged gain near top without plateauing
                if (h > 190) {
                    double n = fbm2(seed ^ 0xC0FFEE1L, wx * 0.02, wz * 0.02);
                    double ridged = 1.0 - Math.abs(n * 2.0 - 1.0);
                    double gain = Math.min(1.0, (h - 190.0) / Math.max(1.0, (worldMaxY - 190.0)));
                    h += Math.pow(gain, 1.4) * ridged * 20.0;
                }
                // Clamp to world and subland bounds
                if (h > worldMaxY) h = worldMaxY;
                if (h < 150) h = 150; // keep above subland
                surf[lx][lz] = h;
            }
        }

        // Set default biome to snowy mountains for this chunk
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            try { biome.setBiome(x, z, Biome.SNOWY_SLOPES); } catch (Throwable ignore) {}
        }

        // Precompute ocean mask (smoothed) for this chunk
        double[][] distToMtn = computeMountainDistances(world, seed, surf, chunkBaseX, chunkBaseZ, worldMaxY, MOUNTAIN_SEARCH_RADIUS);
        double[][] threshold = new double[16][16];
        double[][] oceanRaw = new double[16][16];
        double[][] oceanMask = new double[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkBaseX + lx; int wz = chunkBaseZ + lz;
                // Low-frequency shoreline jitter to avoid geometric borders
                double shoreJitter = (fbm2(seed ^ 0x7A1B2C3DL, wx * 0.01, wz * 0.01) - 0.5) * (SHORE_JITTER_AMPLITUDE * 2.0);
                threshold[lx][lz] = OCEAN_FROM_MOUNTAIN_DIST + shoreJitter; // base threshold with low-freq jitter
                double d = distToMtn[lx][lz];
                double raw = (d - threshold[lx][lz]) / (double) OCEAN_COAST_WIDTH; // <0 land, >0 ocean
                if (Double.isFinite(raw)) raw = clamp01(0.5 + raw); else raw = 1.0; // far from mountains => ocean
                oceanRaw[lx][lz] = raw;
            }
        }
        // Smooth ocean mask with a 3x3 Gaussian-like kernel for gentle coastlines (3 passes)
        double[][] buf = new double[16][16];
        for (int pass = 0; pass < 3; pass++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int numW = 0; double sum = 0.0;
                    for (int ox = -1; ox <= 1; ox++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            int x = lx + ox, z = lz + oz; if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                            int wgt = (ox == 0 && oz == 0) ? 4 : ((Math.abs(ox) + Math.abs(oz) == 2) ? 1 : 2);
                            sum += oceanRaw[x][z] * wgt; numW += wgt;
                        }
                    }
                    buf[lx][lz] = sum / Math.max(1, numW);
                }
            }
            // swap
            double[][] tmp = oceanRaw; oceanRaw = buf; buf = tmp;
        }
        // Final mask
        for (int lx = 0; lx < 16; lx++) System.arraycopy(oceanRaw[lx], 0, oceanMask[lx], 0, 16);

        // Precompute iceberg influence (height above sea) and sea ice sheets
        double[][] icebergDelta = new double[16][16];
        boolean[][] iceSheet = new boolean[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkBaseX + lx; int wz = chunkBaseZ + lz;
                double oceanFactor = oceanMask[lx][lz];
                boolean isOceanHere = oceanFactor >= 0.5;
                if (!isOceanHere) continue;
                // Estimate local depth
                double over = Math.max(0.0, distToMtn[lx][lz] - threshold[lx][lz]);
                double depth = 2.0 + Math.min(40.0, over * 0.25);
                boolean deep = depth >= 22.0;
                if (!deep) continue;
                // Require deep ocean neighborhood (avoid near shores)
                boolean deepNeighborhood = true;
                for (int dx = -6; dx <= 6 && deepNeighborhood; dx++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        int x = lx + dx, z = lz + dz; if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                        if (oceanMask[x][z] < 0.6) { deepNeighborhood = false; break; }
                        double overN = Math.max(0.0, distToMtn[x][z] - threshold[x][z]);
                        double dN = 2.0 + Math.min(40.0, overN * 0.25);
                        if (dN < 18.0) { deepNeighborhood = false; break; }
                    }
                }
                if (!deepNeighborhood) continue;
                // Chance to seed an iceberg center
                double chance = 0.004 + 0.006 * clamp01((depth - 22.0) / 20.0); // deeper -> slightly higher chance
                double roll = random01(hash(seed, wx, 0x1CEB3E7L, wz, 0xAA551CE1L));
                if (roll > chance) continue;

                // Iceberg parameters
                int coreR = 4 + (int) Math.floor(random01(hash(seed, wx, 3, wz, 0xBEEF111L)) * 4.0); // 4..7
                int coreH = 6 + (int) Math.floor(random01(hash(seed, wx, 5, wz, 0xCE1100L)) * 7.0);  // 6..12 above sea
                // Expand sea ice reach for broader sheets around icebergs
                int sheetR = coreR + 9;

                // Paint iceberg height delta with a rounded profile and shape noise
                for (int dx = -sheetR; dx <= sheetR; dx++) {
                    for (int dz = -sheetR; dz <= sheetR; dz++) {
                        int x = lx + dx, z = lz + dz; if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                        double r = Math.hypot(dx, dz);
                        if (r <= coreR) {
                            double t = clamp01(1.0 - (r / coreR));
                            double noise = (fbm2(seed ^ 0x11C3B3E7L, (wx + dx) * 0.08, (wz + dz) * 0.08) - 0.5) * 0.35; // +/-0.175
                            double h = coreH * (t * t) * (0.9 + noise);
                            if (h > icebergDelta[x][z]) icebergDelta[x][z] = h;
                        } else if (r <= sheetR) {
                            // Sea ice sheet probability decays with distance and includes noise (more generous)
                            double fall = clamp01(1.0 - (r - coreR) / (sheetR - coreR));
                            double n = fbm2(seed ^ 0x55C1C35EL, (wx + dx) * 0.10, (wz + dz) * 0.10); // [0,1]
                            if (n * fall > 0.45) iceSheet[x][z] = true;
                        }
                    }
                }
            }
        }

        // Light dilation for sea ice to make sheets feel more continuous around icebergs
        {
            boolean[][] grown = new boolean[16][16];
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) grown[lx][lz] = iceSheet[lx][lz];
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (iceSheet[lx][lz]) continue;
                    // if at least two neighbors are ice sheet, grow
                    int neighbors = 0;
                    for (int ox = -1; ox <= 1; ox++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            if (ox == 0 && oz == 0) continue;
                            int x = lx + ox, z = lz + oz; if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                            if (iceSheet[x][z]) neighbors++;
                        }
                    }
                    if (neighbors >= 3) grown[lx][lz] = true;
                }
            }
            for (int lx = 0; lx < 16; lx++) System.arraycopy(grown[lx], 0, iceSheet[lx], 0, 16);
        }

        // Fill columns: compute ground + hills unified surface, then lay materials; set biomes
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkBaseX + lx; int wz = chunkBaseZ + lz;

                // If this column is inside a reserved flat (e.g., bungalow pad), force its surface
                int reservedY = reservedHeightAt(wx, wz, Integer.MIN_VALUE);
                if (reservedY != Integer.MIN_VALUE) {
                    surf[lx][lz] = Math.max(150, Math.min(reservedY, worldMaxY));
                }

                // Ocean/mountain logic using smoothed mask
                double oceanFactor = oceanMask[lx][lz]; // 0..1
                boolean isOcean = oceanFactor >= 0.5;

                // Treat shoreline as hill-immune band
                boolean nearShore = (oceanFactor > 0.40 && oceanFactor < 0.75);
                // If near shore, don't apply the ring/hill blending; clamp to sea level if above it
                if (nearShore && surf[lx][lz] > OCEAN_SEA_LEVEL) {
                    surf[lx][lz] = OCEAN_SEA_LEVEL;
                }

                // Use multi-noise surface height
                int finalSurf = (int) Math.floor(Math.max(150, Math.min(surf[lx][lz], worldMaxY)));

                // Inverted ring blending (applies only for surfaces between Y 155..200):
                // peak/slope (near) -> hills -> plains -> hills (far)
                double distPeak = distanceToNearestPeak(seed, wx, wz, 16); // search ~256 blocks radius
                if (!nearShore) {
                    if (Double.isFinite(distPeak) && finalSurf >= 155 && finalSurf <= 200) {
                        double rSlope = 80.0;   // inside this: keep peak/slope as-is
                        double rHill = 140.0;   // inner hills ring ends here
                        double rPlains = 220.0; // plains band ends here; beyond becomes outer hills
                        if (distPeak > rSlope && distPeak <= rHill) {
                            // Inner hills ring: add gentle hills that fade out toward rHill
                            double t = (distPeak - rSlope) / (rHill - rSlope);
                            double s = 1.0 - fade(Math.max(0.0, Math.min(1.0, t))); // 1 near rSlope -> 0 at rHill
                            double n = fbm2(seed ^ 0x1A1B551L, wx * 0.02, wz * 0.02); // [0,1]
                            int delta = (int) Math.round((n - 0.5) * 20.0 * s); // up to ±10 scaled
                            finalSurf = Math.max(150, Math.min(worldMaxY, finalSurf + delta));
                        } else if (distPeak > rHill && distPeak <= rPlains) {
                            // Plains band: blend toward ~156, strongest at mid-band
                            double t = (distPeak - rHill) / (rPlains - rHill); // 0..1 across band
                            double s = fade(Math.max(0.0, Math.min(1.0, t)));
                            int plainsHeight = 156;
                            double blended = finalSurf * (1.0 - s) + plainsHeight * s;
                            finalSurf = (int) Math.round(blended);
                        } else if (distPeak > rPlains) {
                            // Outer hills ring: add gentle hills that fade in beyond plains band
                            double t = Math.min(1.0, (distPeak - rPlains) / 100.0);
                            double s = fade(Math.max(0.0, t));
                            double n = fbm2(seed ^ 0x2A1B552L, wx * 0.02, wz * 0.02);
                            int delta = (int) Math.round((n - 0.5) * 20.0 * (0.5 + 0.5 * s)); // up to ±10
                            finalSurf = Math.max(150, Math.min(worldMaxY, finalSurf + delta));
                        }
                    }
                }
                if (finalSurf < 150) continue;
                finalSurf = Math.min(finalSurf, worldMaxY);

                // Chaos on mountain slopes: do not affect mountain tops/cores
                // Apply only outside the peak core (distPeak > ~80) and below very high caps
                if (!isOcean && finalSurf >= 170 && (!Double.isFinite(distPeak) || distPeak > 80.0) && finalSurf < 195) {
                    int lx0 = Math.max(0, lx - 1), lx1 = Math.min(15, lx + 1);
                    int lz0 = Math.max(0, lz - 1), lz1 = Math.min(15, lz + 1);
                    double sMax = 0.0;
                    double h0 = surf[lx][lz];
                    for (int ax = lx0; ax <= lx1; ax++) {
                        for (int az = lz0; az <= lz1; az++) {
                            if (ax == lx && az == lz) continue;
                            sMax = Math.max(sMax, Math.abs(surf[ax][az] - h0));
                        }
                    }
                    double slopeStrength = clamp01(sMax / 12.0); // normalize
                    if (slopeStrength > 0) {
                        double n = fbm2(seed ^ 0xC1A05C1AL, wx * 0.06, wz * 0.06); // high frequency
                        int delta = (int) Math.round((n - 0.5) * 2.0 * (2.0 + 6.0 * slopeStrength));
                        finalSurf = Math.max(150, Math.min(worldMaxY, finalSurf + delta));
                    }
                }

                if (isOcean) {
                    // Ocean: flood water to sea level, deepslate floor that deepens with distance
                    // Depth grows with distance beyond threshold, with noise
                    double over = Math.max(0.0, distToMtn[lx][lz] - threshold[lx][lz]);
                    double depth = 2.0 + Math.min(40.0, over * 0.25); // shallower at shore -> deeper offshore
                    // Subtle bed undulation before kelp
                    double dNoise = (fbm2(seed ^ 0x12345ABCL, wx * 0.03, wz * 0.03) - 0.5) * 6.0; // +/-3
                    double bedNoise = (fbm2(seed ^ 0x0BEDBEDL, wx * 0.08, wz * 0.08) - 0.5) * (oceanFactor < 0.7 ? 2.0 : 4.0);
                    int floorY = (int) Math.round(OCEAN_SEA_LEVEL - depth + dNoise + bedNoise);
                    if (floorY < 130) floorY = 130; // keep above deep stone fill floor
                    if (floorY > OCEAN_SEA_LEVEL - 1) floorY = OCEAN_SEA_LEVEL - 1;

                    // Ensure solid deepslate floor (top ~6 layers deepslate to satisfy "only deepslate" visually)
                    int yStart = Math.max(120, Math.min(floorY - 6, OCEAN_SEA_LEVEL));
                    for (int y = yStart; y <= floorY; y++) data.setBlock(lx, y, lz, Material.DEEPSLATE);
                    // Near-shore sandy shelf: place a thin sand veneer above deepslate close to shore
                    if (oceanFactor < 0.70) {
                        int sandLayers = 1 + (int) Math.round((0.70 - oceanFactor) * 3.0); // 1..3
                        sandLayers = Math.min(sandLayers, Math.max(0, OCEAN_SEA_LEVEL - (floorY + 1)));
                        for (int i = 0; i < sandLayers; i++) {
                            int y = floorY - i;
                            if (y >= yStart) data.setBlock(lx, y, lz, Material.SAND);
                        }
                    }

                    // Fill water up to sea level
                    for (int y = floorY + 1; y <= OCEAN_SEA_LEVEL; y++) data.setBlock(lx, y, lz, Material.WATER);

                    // Clear space above water surface
                    int clearTop = Math.min(OCEAN_SEA_LEVEL + 8, worldMaxY);
                    for (int y = OCEAN_SEA_LEVEL + 1; y <= clearTop; y++) data.setBlock(lx, y, lz, Material.AIR);

                    // Apply iceberg body with underwater noise and blue-ice bottom band
                    int icebergUp = (int) Math.floor(icebergDelta[lx][lz]);
                    if (icebergUp > 0) {
                        int topIce = Math.min(worldMaxY, OCEAN_SEA_LEVEL + icebergUp);
                        // Add per-column underwater depth noise (0..4 extra) for more organic submerged shapes
                        int subExtra = (int) Math.floor(fbm2(seed ^ 0x042CEB1L, wx * 0.07, wz * 0.07) * 4.0);
                        int base = Math.max(floorY + 1, (OCEAN_SEA_LEVEL - 8) - subExtra);
                        for (int y = base; y <= topIce; y++) {
                            boolean underwater = y < OCEAN_SEA_LEVEL;
                            double roll = random01(hash(seed, wx, y, wz, 0x1CE1CE1L));
                            Material mIce;
                            if (underwater) {
                                // Stronger blue ice presence underwater; enforce a bottom band
                                if (y <= base + 1) mIce = Material.BLUE_ICE; // solid blue-ice bottom
                                else mIce = (roll < 0.40) ? Material.BLUE_ICE : Material.PACKED_ICE;
                            } else {
                                mIce = (roll < 0.15) ? Material.BLUE_ICE : Material.PACKED_ICE;
                            }
                            data.setBlock(lx, y, lz, mIce);
                        }
                    }

                    // Apply sea ice sheet at sea level around icebergs (expanded)
                    if (iceSheet[lx][lz]) {
                        try {
                            if (data.getType(lx, OCEAN_SEA_LEVEL, lz) == Material.WATER) {
                                data.setBlock(lx, OCEAN_SEA_LEVEL, lz, Material.ICE);
                            }
                        } catch (Throwable ignore) {}
                    }

                    // Occasional kelp
                    if (icebergUp == 0 && floorY + 5 < OCEAN_SEA_LEVEL) {
                        double r = random01(hash(seed, wx, floorY, wz, 0x9A7BCDEL));
                        if (r < 0.08) {
                            int maxLen = Math.min(12, OCEAN_SEA_LEVEL - (floorY + 1));
                            int len = 3 + (int) Math.floor(r * maxLen);
                            for (int i = 0; i < len; i++) {
                                int y = floorY + 1 + i;
                                if (y >= OCEAN_SEA_LEVEL) break;
                                data.setBlock(lx, y, lz, (i == len - 1) ? Material.KELP : Material.KELP_PLANT);
                            }
                        }
                    }

                    // Guarantee sand at the shoreline: top solid under water is sand within 6 blocks of shore
                    if (oceanMask[lx][lz] < 0.70) {
                        int y = Math.min(OCEAN_SEA_LEVEL - 1, worldMaxY);
                        while (y >= 140 && data.getType(lx, y, lz) == Material.WATER) y--;
                        if (y >= 140 && data.getType(lx, y, lz) != Material.AIR) data.setBlock(lx, y, lz, Material.SAND);
                    }

                    // Biomes: beach near shore, ocean deeper offshore
                    try {
                        if (over < 12) biome.setBiome(lx, lz, Biome.BEACH);
                        else if (depth >= 24) biome.setBiome(lx, lz, Biome.DEEP_OCEAN);
                        else biome.setBiome(lx, lz, Biome.OCEAN);
                    } catch (Throwable ignore) {}
                } else {
                    // Land: fill stone to surface
                    for (int y = 150; y <= finalSurf; y++) data.setBlock(lx, y, lz, Material.STONE);

                    // Ensure transitional ground exists from 150..155 around every hill
                    for (int y = 150; y <= Math.min(155, finalSurf); y++) {
                        data.setBlock(lx, y, lz, Material.DIRT);
                    }

                    // Beaches: widen and gently slope to sea level using the ocean factor
                    double s = (oceanFactor - 0.25) / (0.80 - 0.25); // 0 near land, 1 near sea
                    if (s > 0 && s < 1 && finalSurf <= OCEAN_SEA_LEVEL + 12) {
                        s = clamp01(s);
                        // Hard clamp beaches to sea level
                        if (finalSurf > OCEAN_SEA_LEVEL) finalSurf = OCEAN_SEA_LEVEL;
                        // Sand veneer thickness (tunable)
                        int sandLayers = 4 + (int) Math.round(5.0 * (1.0 - s)); // 4..9
                        for (int i = 0; i < sandLayers; i++) {
                            int y = finalSurf - i; if (y < 150) break;
                            data.setBlock(lx, y, lz, Material.SAND);
                        }
                        try { biome.setBiome(lx, lz, Biome.BEACH); } catch (Throwable ignore) {}
                    } else {
                        // Top materials and transitional ground: add snow blocks at higher elevations
                        boolean rocky = false;
                        if (finalSurf >= 185 && (!Double.isFinite(distPeak) || distPeak > 80.0)) {
                            // Chance to expose rock on steeper outer slopes (not peak cores)
                            double nRock = fbm2(seed ^ 0x51A0BEF5L, wx * 0.05, wz * 0.05);
                            rocky = nRock > 0.65; // ~35% in steep areas
                        }
                        if (finalSurf > 180 && rocky) {
                            data.setBlock(lx, finalSurf, lz, Material.STONE);
                        } else if (finalSurf > 180) {
                            data.setBlock(lx, finalSurf, lz, Material.SNOW_BLOCK);
                        } else if (finalSurf < 200) {
                            data.setBlock(lx, finalSurf, lz, Material.SNOW_BLOCK);
                            int dirtMin = 3 + (int) Math.floor(random01(hash(seed, wx, finalSurf, wz, 0xD1A7D1A7L)) * 3); // 3..5
                            int dirtLayers = Math.min(Math.min(6, dirtMin), finalSurf - 150);
                            for (int i = 1; i <= dirtLayers; i++) data.setBlock(lx, finalSurf - i, lz, Material.DIRT);
                        } else {
                            data.setBlock(lx, finalSurf, lz, Material.STONE);
                        }

                        // Snow layer on top (snow drifts via layered snow)
                        int ySnow = finalSurf + 1;
                        if (ySnow <= worldMaxY) {
                            try {
                                // Wind/drift field
                                double drift = fbm2(seed ^ 0x5A0BD123L, wx * 0.03, wz * 0.03); // [0,1]
                                int layers = 1 + (int) Math.floor(Math.max(0, (drift - 0.4) * 6.0)); // 1..4 typically
                                if (layers < 1) layers = 1; if (layers > 8) layers = 8;
                                org.bukkit.block.data.type.Snow snow = (org.bukkit.block.data.type.Snow) org.bukkit.Bukkit.createBlockData(Material.SNOW);
                                snow.setLayers(layers);
                                data.setBlock(lx, ySnow, lz, snow);
                            } catch (Throwable ignore) {
                                try { data.setBlock(lx, ySnow, lz, Material.SNOW); } catch (Throwable ignore2) {}
                            }
                            // Ensure grassy tops get snowy grass appearance with at least 1 layer
                            try {
                                if (data.getType(lx, finalSurf, lz) == Material.GRASS_BLOCK) {
                                    org.bukkit.block.data.type.Snow one = (org.bukkit.block.data.type.Snow) org.bukkit.Bukkit.createBlockData(Material.SNOW);
                                    one.setLayers(1);
                                    data.setBlock(lx, ySnow, lz, one);
                                }
                            } catch (Throwable ignore) {}
                        }

                        // Biomes by height band (plains/taiga/slopes) for inland (non-beach)
                        if (!(s > 0 && s < 1 && finalSurf <= OCEAN_SEA_LEVEL + 12)) {
                            try {
                                if (finalSurf <= 162) biome.setBiome(lx, lz, Biome.SNOWY_PLAINS);
                                else if (finalSurf >= 185) biome.setBiome(lx, lz, Biome.SNOWY_SLOPES);
                                else biome.setBiome(lx, lz, Biome.SNOWY_TAIGA);
                            } catch (Throwable ignore) {}
                        }
                    }

                    // Edge-touch rule: if any 4-neighbor at sea level is water, make this top ≤ sea level sand
                    boolean waterAdj = false;
                    if (OCEAN_SEA_LEVEL >= 0 && OCEAN_SEA_LEVEL <= worldMaxY) {
                        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
                        for (int[] d : dirs) {
                            int nx = lx + d[0], nz = lz + d[1];
                            if (nx<0||nx>15||nz<0||nz>15) continue;
                            try { if (data.getType(nx, OCEAN_SEA_LEVEL, nz) == Material.WATER) { waterAdj = true; break; } }
                            catch (Throwable ignore) {}
                        }
                    }
                    if (waterAdj) {
                        int y = Math.min(finalSurf, OCEAN_SEA_LEVEL);
                        data.setBlock(lx, y, lz, Material.SAND);
                        for (int i = 1; i <= 3 && y-i >= 150; i++) data.setBlock(lx, y - i, lz, Material.SAND);
                        try { biome.setBiome(lx, lz, Biome.BEACH); } catch (Throwable ignore) {}
                    }

                    // Cleanup: prevent stone from existing above any sand in this land column
                    fixStoneAboveSand(data, lx, lz, 150, finalSurf);
                }
            }
        }

        // (Glaciers and surface cave entrances were removed per undo request)

        // (Frozen lakes removed for now)
    }

    private boolean hasPeak(long seed, int chunkX, int chunkZ) {
        double r = random01(hash(seed, chunkX, 0, chunkZ, 0xC0FFEEB1L));
        return r < PEAK_CHANCE;
    }

    // Returns [centerX, centerZ, height]
    private int[] peakParams(long seed, int chunkX, int chunkZ) {
        long h = hash(seed, chunkX, 1, chunkZ, 0xDEC0DE11L);
        // center inside chunk with 2..13 offset
        int cx = (chunkX << 4) + 2 + (int) Math.floor(random01(h) * 12.0);
        int cz = (chunkZ << 4) + 2 + (int) Math.floor(random01(h ^ 0x9E3779B97F4A7C15L) * 12.0);
        int hMin = PEAK_MIN_Y;
        int hMax = PEAK_MAX_Y;
        int peakY = hMin + (int) Math.floor(random01(h ^ 0xA55A55A5L) * (hMax - hMin + 1));
        return new int[]{cx, cz, peakY};
    }

    // Distance to nearest peak anchor (in blocks), searching a radius of chunks around wx/wz
    private double distanceToNearestPeak(long seed, int wx, int wz, int searchRadiusChunks) {
        int chunkX = Math.floorDiv(wx, 16);
        int chunkZ = Math.floorDiv(wz, 16);
        double bestDist2 = Double.MAX_VALUE;
        for (int cx = chunkX - searchRadiusChunks; cx <= chunkX + searchRadiusChunks; cx++) {
            for (int cz = chunkZ - searchRadiusChunks; cz <= chunkZ + searchRadiusChunks; cz++) {
                if (!hasPeak(seed, cx, cz)) continue;
                int[] p = peakParams(seed, cx, cz);
                int px = p[0];
                int pz = p[1];
                double dx = px - (double) wx;
                double dz = pz - (double) wz;
                double d2 = dx * dx + dz * dz;
                if (d2 < bestDist2) bestDist2 = d2;
            }
        }
        return (bestDist2 == Double.MAX_VALUE) ? Double.POSITIVE_INFINITY : Math.sqrt(bestDist2);
    }

    private double fbm2(long seed, double x, double z) {
        double sum = 0.0;
        double amp = 1.0;
        double freq = 1.0;
        for (int o = 0; o < 5; o++) {
            sum += (valueNoise2(seed, x * freq, z * freq) - 0.5) * 2.0 * amp;
            amp *= 0.55;
            freq *= 1.9;
        }
        // Normalize roughly to [0,1]
        return clamp01((sum * 0.5) + 0.5);
    }

    // Compute distance in blocks to the nearest mountain using a 2-pass chamfer transform
    private double[][] computeMountainDistances(World world, long seed, double[][] surf, int chunkBaseX, int chunkBaseZ, int worldMaxY, int maxDist) {
        int halo = Math.min(MAX_HALO, maxDist);
        int size = 16 + halo * 2;
        int off = halo;
        boolean[][] mountain = TL_MOUNTAIN.get();
        int[][] dist = TL_DIST.get();

        for (int x = 0; x < size; x++) {
            java.util.Arrays.fill(mountain[x], 0, size, false);
            java.util.Arrays.fill(dist[x], 0, size, CHAMFER_INF);
        }

        boolean any = false;
        for (int gx = -halo; gx < 16 + halo; gx++) {
            for (int gz = -halo; gz < 16 + halo; gz++) {
                double h;
                if (gx >= 0 && gx < 16 && gz >= 0 && gz < 16) {
                    h = surf[gx][gz];
                } else {
                    int wx = chunkBaseX + gx;
                    int wz = chunkBaseZ + gz;
                    h = surfaceApprox(world, seed, wx, wz, worldMaxY);
                }
                if (h >= MOUNTAIN_Y) {
                    mountain[gx + off][gz + off] = true;
                    dist[gx + off][gz + off] = 0;
                    any = true;
                }
            }
        }

        double[][] result = new double[16][16];
        if (!any) {
            for (int lx = 0; lx < 16; lx++) java.util.Arrays.fill(result[lx], Double.POSITIVE_INFINITY);
            return result;
        }

        // Forward pass
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int v = dist[x][z];
                if (v == 0) continue;
                if (x > 0) v = Math.min(v, dist[x - 1][z] + CHAMFER_AXIS);
                if (z > 0) v = Math.min(v, dist[x][z - 1] + CHAMFER_AXIS);
                if (x > 0 && z > 0) v = Math.min(v, dist[x - 1][z - 1] + CHAMFER_DIAGONAL);
                if (x < size - 1 && z > 0) v = Math.min(v, dist[x + 1][z - 1] + CHAMFER_DIAGONAL);
                dist[x][z] = v;
            }
        }

        // Backward pass
        for (int x = size - 1; x >= 0; x--) {
            for (int z = size - 1; z >= 0; z--) {
                int v = dist[x][z];
                if (x < size - 1) v = Math.min(v, dist[x + 1][z] + CHAMFER_AXIS);
                if (z < size - 1) v = Math.min(v, dist[x][z + 1] + CHAMFER_AXIS);
                if (x < size - 1 && z < size - 1) v = Math.min(v, dist[x + 1][z + 1] + CHAMFER_DIAGONAL);
                if (x > 0 && z < size - 1) v = Math.min(v, dist[x - 1][z + 1] + CHAMFER_DIAGONAL);
                dist[x][z] = v;
            }
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int v = dist[lx + off][lz + off];
                double d;
                if (v >= CHAMFER_INF) {
                    d = maxDist;
                } else {
                    d = Math.min(maxDist, v / (double) CHAMFER_AXIS);
                }
                result[lx][lz] = d;
            }
        }
        return result;
    }

    // Recomputes the same surface height logic used to fill surf[][] (approximate, fast)
    private int surfaceApprox(World world, long seed, int wx, int wz, int worldMaxY) {
        double base = 155.0;
        double low = fbm2(seed ^ 0xA1B2C3D4L, wx * 0.003, wz * 0.003);
        double mountainBand = (low - 0.5) * 2.0;
        double t0 = 0.15, t1 = 0.45;
        double mask = clamp01((mountainBand - t0) / (t1 - t0));
        // Even spacing bias
        final double cellSize = 288.0;
        int nx = (int) Math.round((double) wx / cellSize);
        int nz = (int) Math.round((double) wz / cellSize);
        double jx = (random01(hash(seed, nx, 51, nz, 0xA1C3E5L)) - 0.5) * (cellSize * 0.35);
        double jz = (random01(hash(seed, nx, 87, nz, 0xB2D4F6L)) - 0.5) * (cellSize * 0.35);
        double cx = nx * cellSize + jx;
        double cz = nz * cellSize + jz;
        double ddx = wx - cx, ddz = wz - cz;
        double d = Math.sqrt(ddx * ddx + ddz * ddz);
        double R = cellSize * 0.5;
        double w = clamp01(1.0 - d / R);
        double evenBoost = (w - 0.5) * 0.20;
        mask = clamp01(mask + evenBoost);

        double baseLift = mask * 60.0;
        double ridge = fbm2(seed ^ 0xB3C4D5E6L, wx * 0.02, wz * 0.02);
        double ridgeAdd = (ridge - 0.5) * 2.0 * 10.0;
        // Edge taper
        double edge = clamp01((mountainBand - t0) / (t1 - t0));
        double edgeTaper = edge * edge * (3 - 2 * edge);
        baseLift *= edgeTaper; ridgeAdd *= edgeTaper;
        double plains = (fbm2(seed ^ 0x600DD00EL, wx * 0.02, wz * 0.02) - 0.5) * 2.0;
        double h = base + plains + (baseLift * mask) + (ridgeAdd * mask);
        double erosion = fbm2(seed ^ 0x0E01234L, wx * 0.004, wz * 0.004);
        h += (erosion * erosion) * 40.0 * mask;
        if (h > worldMaxY) h = worldMaxY; if (h < 150) h = 150;
        return (int) Math.floor(h);
    }

    // --- Component-based cave carver (V2) ---
    private enum CaveType { WORM, CHAOS_TUNNEL, ARENA, STAIRCASE, OMINOUS_PASSAGE }

    private static final class CaveTask {
        final CaveType type; final double x, z; final int y; final double yaw, pitch; final int steps;
        CaveTask(CaveType type, double x, int y, double z, double yaw, double pitch, int steps) {
            this.type = type; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.steps = steps;
        }
    }

    private static final class StepResult { final double x, z, yaw; final int y; StepResult(double x, int y, double z, double yaw){this.x=x;this.y=y;this.z=z;this.yaw=yaw;} }

    private void carveCavesV2(World world, ChunkData data, long seed, int chunkX, int chunkZ) {
        final int REGION = 64;
        final int MAX_TASKS_PER_REGION = 10;
        final double INTERSECT_CHANCE = 0.20;

        int yMin = Math.max(CAVE_MIN_Y, AUDIT_Y_MIN);
        int yMax = Math.min(CAVE_MAX_Y, AUDIT_Y_MAX);
        if (yMin > yMax) return;

        int chunkBaseX = (chunkX << 4);
        int chunkBaseZ = (chunkZ << 4);

        int rx0 = Math.floorDiv(chunkBaseX - 1, REGION);
        int rz0 = Math.floorDiv(chunkBaseZ - 1, REGION);
        int rx1 = Math.floorDiv(chunkBaseX + 16, REGION);
        int rz1 = Math.floorDiv(chunkBaseZ + 16, REGION);

        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                long rseed = hash(seed, rx, rz, 0, 0x57EEDCAFL);
                Random rr = new Random(rseed);
                ArrayDeque<CaveTask> queue = new ArrayDeque<>();
                int created = 0;
                int worms = 2 + rr.nextInt(2);
                for (int i = 0; i < worms && created < MAX_TASKS_PER_REGION; i++) {
                    double sx = rx * REGION + rr.nextDouble() * REGION;
                    double sz = rz * REGION + rr.nextDouble() * REGION;
                    int sy = yMin + rr.nextInt(yMax - yMin + 1);
                    double yaw = rr.nextDouble() * Math.PI * 2.0;
                    double pitch = (rr.nextDouble() - 0.5) * 0.4;
                    int steps = 220 + rr.nextInt(100);
                    queue.add(new CaveTask(CaveType.WORM, sx, sy, sz, yaw, pitch, steps));
                    created++;
                }

                while (!queue.isEmpty()) {
                    CaveTask t = queue.pollFirst();
                    switch (t.type) {
                        case WORM -> {
                            StepResult res = carveWorm(data, rr, t, chunkBaseX, chunkBaseZ, yMin, yMax,
                                    1.6, 1.0, 4.5, true, true);
                            if (res != null && rr.nextDouble() < INTERSECT_CHANCE && created < MAX_TASKS_PER_REGION) {
                                CaveType inter = pickIntersector(rr);
                                switch (inter) {
                                    case ARENA -> { queue.add(new CaveTask(CaveType.ARENA, res.x, res.y, res.z, res.yaw, 0, 1)); created++; }
                                    case STAIRCASE -> { double py = -0.6 + rr.nextDouble() * 0.2; queue.add(new CaveTask(CaveType.STAIRCASE, res.x, res.y, res.z, res.yaw, py, 140)); created++; }
                                    case OMINOUS_PASSAGE -> { queue.add(new CaveTask(CaveType.OMINOUS_PASSAGE, res.x, res.y, res.z, res.yaw, -1.0, 80 + rr.nextInt(60))); created++; }
                                    default -> {}
                                }
                            }
                            // Dent connection at worm end toward nearest air
                            if (res != null && res.y <= 165) dentTowardsAir(data, chunkBaseX, chunkBaseZ, res.x, res.y, res.z, yMin, yMax);
                        }
                        case CHAOS_TUNNEL -> {
                            // Widened and lengthened chaos tunnels
                            StepResult res = carveWorm(data, rr, t, chunkBaseX, chunkBaseZ, yMin, yMax,
                                    1.9, 1.5, 6.5, false, false);
                            if (res != null && res.y <= 165) dentTowardsAir(data, chunkBaseX, chunkBaseZ, res.x, res.y, res.z, yMin, yMax);
                        }
                        case ARENA -> {
                            carveArenaAndSpur(data, rr, t, queue, created, MAX_TASKS_PER_REGION, yMin, yMax, chunkBaseX, chunkBaseZ);
                            created = Math.min(MAX_TASKS_PER_REGION, created + 1);
                        }
                        case STAIRCASE -> {
                            StepResult res = carveWorm(data, rr, t, chunkBaseX, chunkBaseZ, yMin, yMax,
                                    1.5, 1.0, 4.0, true, true);
                            if (res != null && res.y <= 165) dentTowardsAir(data, chunkBaseX, chunkBaseZ, res.x, res.y, res.z, yMin, yMax);
                        }
                        case OMINOUS_PASSAGE -> {
                            carveOminousPassage(data, rr, t, queue, created, MAX_TASKS_PER_REGION, yMin, yMax, chunkBaseX, chunkBaseZ);
                        }
                    }
                    if (created >= MAX_TASKS_PER_REGION) break;
                }
            }
        }
    }

    private CaveType pickIntersector(Random rr) {
        double d = rr.nextDouble();
        if (d < 0.50) return CaveType.OMINOUS_PASSAGE; // bias toward ominous to ensure visibility
        if (d < 0.80) return CaveType.ARENA;
        return CaveType.STAIRCASE;
    }

    private StepResult carveWorm(ChunkData data, Random rr, CaveTask t, int chunkBaseX, int chunkBaseZ,
                                 int yMin, int yMax, double stepLen, double rMin, double rMax,
                                 boolean shorter, boolean chaos) {
        double x=t.x, z=t.z, yaw=t.yaw, pitch=t.pitch; int y=t.y;
        double rPhase = rr.nextDouble() * Math.PI * 2.0; double rFreq = 0.05 + rr.nextDouble() * 0.05;
        for (int s=0; s<t.steps; s++){
            x += Math.cos(yaw) * stepLen; z += Math.sin(yaw) * stepLen; y += (int)Math.round(Math.sin(pitch));
            if (chaos) {
                yaw += (rr.nextDouble()-0.5)*0.6; pitch += (rr.nextDouble()-0.5)*0.3; if (rr.nextDouble()<0.07) yaw += (rr.nextDouble()-0.5)*Math.PI;
            } else {
                yaw += (rr.nextDouble()-0.5)*0.4; pitch += (rr.nextDouble()-0.5)*0.2; if (rr.nextDouble()<0.05) yaw += (rr.nextDouble()-0.5)*Math.PI;
            }
            if (y < yMin + 1) { y = yMin + 1; pitch = Math.abs(pitch) * 0.5; }
            if (y > yMax - 1) { y = yMax - 1; pitch = -Math.abs(pitch) * 0.5; }
            if (pitch < -1.0) pitch = -1.0; if (pitch > 1.0) pitch = 1.0;
            double baseR = rMin + rr.nextDouble() * (rMax - rMin); if (shorter) baseR = Math.max(0.8, baseR - 1.0);
            double radius = Math.max(0.8, Math.min(6.0, baseR + 0.5*Math.sin(rPhase + s*rFreq)));
            carveSphere(data, chunkBaseX, chunkBaseZ, x, y, z, radius, yMin, yMax);
            if (rr.nextDouble() < 0.01) carveSphere(data, chunkBaseX, chunkBaseZ, x, y, z, 6.0 + rr.nextInt(7), yMin, yMax);
        }
        // Overshoot: continue a bit past the end to help connections
        int overshoot = 6 + rr.nextInt(10); // 6..15 extra steps
        double tailPitch = pitch * 0.6; // damp vertical variance to stay level
        for (int s = 0; s < overshoot; s++) {
            x += Math.cos(yaw) * stepLen;
            z += Math.sin(yaw) * stepLen;
            y += (int)Math.round(Math.sin(tailPitch) * 0.5);
            // very gentle drift to reduce parallel miss
            yaw += (rr.nextDouble() - 0.5) * 0.10;
            tailPitch *= 0.9;

            double baseR = rMin + rr.nextDouble() * (rMax - rMin);
            double taper = 0.8 - 0.5 * (s / (double)Math.max(1, overshoot - 1)); // taper down along tail
            double radius = Math.max(0.8, Math.min(5.0, baseR * taper));
            carveSphere(data, chunkBaseX, chunkBaseZ, x, y, z, radius, yMin, yMax);
        }
        return new StepResult(x,y,z,yaw);
    }

    private void carveArenaAndSpur(ChunkData data, Random rr, CaveTask t, ArrayDeque<CaveTask> queue,
                                   int created, int maxTasks, int yMin, int yMax, int chunkBaseX, int chunkBaseZ) {
        double cx=t.x, cz=t.z; int cy=t.y; double radH=6.0+rr.nextDouble()*6.0, radV=1.5+rr.nextDouble()*1.0; // shorter roof
        carveEllipsoid(data, chunkBaseX, chunkBaseZ, cx, cy, cz, radH, radV, yMin, yMax);
        // 50% chance to add an ominous passage at the center; counts as the arena's connection
        if (created < maxTasks) {
            if (rr.nextDouble() < 0.5) {
                int steps = 80 + rr.nextInt(80);
                queue.add(new CaveTask(CaveType.OMINOUS_PASSAGE, cx, cy, cz, 0, -1.0, steps));
            } else {
                // otherwise spawn a lateral tunnel from a random wall
                double phi = rr.nextDouble()*Math.PI*2.0;
                double sx=cx+Math.cos(phi)*(radH-0.5); double sz=cz+Math.sin(phi)*(radH-0.5);
                int sy=cy; CaveType next = rr.nextBoolean()?CaveType.WORM:CaveType.CHAOS_TUNNEL;
                int steps = (next == CaveType.CHAOS_TUNNEL)
                        ? 220 + rr.nextInt(100)  // lengthen chaos tunnels
                        : 160 + rr.nextInt(80);
                queue.add(new CaveTask(next, sx, sy, sz, phi, 0, steps));
            }
        }
    }

    private void carveOminousPassage(ChunkData data, Random rr, CaveTask t, ArrayDeque<CaveTask> queue,
                                     int created, int maxTasks, int yMin, int yMax, int chunkBaseX, int chunkBaseZ) {
        double x=t.x, z=t.z; int y=t.y; int steps=t.steps;
        double radiusBase = 2.8 + rr.nextDouble()*1.8; // wider shafts ~2.8..4.6
        // Top flare to guarantee a clear entrance
        carveSphere(data, chunkBaseX, chunkBaseZ, x, y, z, radiusBase + 1.0, yMin, yMax);
        // Straight drop with maximum height limit of 25 blocks
        int carved = 0;
        for (int s=0; s<steps && y>yMin+1 && carved < 25; s++){
            double r = radiusBase + (s%7==0 ? 0.4 : 0.0); // subtle periodic widening
            carveSphere(data, chunkBaseX, chunkBaseZ, x,y,z,r,yMin,yMax);
            y -= 1 + (rr.nextDouble()<0.15?1:0); // mostly 1/block step, sometimes 2
            carved++;
        }
        // Bottom flare + connector
        carveSphere(data, chunkBaseX, chunkBaseZ, x, y, z, radiusBase + 1.2, yMin, yMax);
        // Keep ominous passage connector but only if it ends deeper
        if (y <= yMin + 8) dentTowardsAir(data, chunkBaseX, chunkBaseZ, x, y, z, yMin, yMax);
        if (created < maxTasks) {
            double yaw = rr.nextDouble()*Math.PI*2.0;
            queue.add(new CaveTask(CaveType.WORM, x, Math.max(yMin+2,y), z, yaw, 0, 160));
        }
    }

    private void carveSphere(ChunkData data, int chunkBaseX, int chunkBaseZ, double cx, int cy, double cz, double r, int yMin, int yMax){
        int ix=(int)Math.floor(cx), iz=(int)Math.floor(cz); int minX=ix-(int)Math.ceil(r), maxX=ix+(int)Math.ceil(r); int minZ=iz-(int)Math.ceil(r), maxZ=iz+(int)Math.ceil(r);
        int minY=Math.max(cy-(int)Math.ceil(r), yMin), maxY=Math.min(cy+(int)Math.ceil(r), yMax);
        for(int wx=Math.max(minX,chunkBaseX); wx<=Math.min(maxX,chunkBaseX+15); wx++){
            int lx=wx-chunkBaseX;
            for(int wz=Math.max(minZ,chunkBaseZ); wz<=Math.min(maxZ,chunkBaseZ+15); wz++){
                int lz=wz-chunkBaseZ;
                for(int wy=minY; wy<=maxY; wy++){
                    if(wy<=-61) continue;
                    double dx=wx-cx, dy=wy-cy, dz=wz-cz;
                    if(dx*dx+dy*dy+dz*dz<=r*r){
                        // Prevent carving above Y=120 if there's water above this point (protect oceans)
                        if (wy > 120) {
                            boolean waterAbove = false;
                            int upper = Math.min(yMax, OCEAN_SEA_LEVEL);
                            for (int ya = wy + 1; ya <= upper; ya++) {
                                try {
                                    if (data.getType(lx, ya, lz) == Material.WATER) { waterAbove = true; break; }
                                } catch (Throwable ignore) {}
                            }
                            // Also prevent carving near water columns horizontally (shorelines)
                            if (waterAbove || isNearWaterColumn(data, lx, wy, lz, 4, yMin, yMax)) continue;
                        }
                        data.setBlock(lx,wy,lz,Material.AIR);
                        hardenHighCaveWalls(data, lx, wy, lz);
                    }
                }
            }
        }
    }

    private void carveEllipsoid(ChunkData data, int chunkBaseX, int chunkBaseZ, double cx, int cy, double cz, double radH, double radV, int yMin, int yMax){
        int ix=(int)Math.floor(cx), iz=(int)Math.floor(cz); int minX=ix-(int)Math.ceil(radH), maxX=ix+(int)Math.ceil(radH); int minZ=iz-(int)Math.ceil(radH), maxZ=iz+(int)Math.ceil(radH);
        int minY=Math.max(cy-(int)Math.ceil(radV), yMin), maxY=Math.min(cy+(int)Math.ceil(radV), yMax); double invH2=1.0/(radH*radH);
        for(int wx=Math.max(minX,chunkBaseX); wx<=Math.min(maxX,chunkBaseX+15); wx++){
            int lx=wx-chunkBaseX;
            for(int wz=Math.max(minZ,chunkBaseZ); wz<=Math.min(maxZ,chunkBaseZ+15); wz++){
                int lz=wz-chunkBaseZ;
                for(int wy=minY; wy<=maxY; wy++){
                    if(wy<=-61) continue;
                    double dx=wx-cx, dy=(wy-cy)/radV, dz=wz-cz; double rho=(dx*dx+dz*dz)*invH2 + dy*dy;
                    if(rho<=1.0){
                        if (wy > 120) {
                            boolean waterAbove = false;
                            int upper = Math.min(yMax, OCEAN_SEA_LEVEL);
                            for (int ya = wy + 1; ya <= upper; ya++) {
                                try {
                                    if (data.getType(lx, ya, lz) == Material.WATER) { waterAbove = true; break; }
                                } catch (Throwable ignore) {}
                            }
                            if (waterAbove || isNearWaterColumn(data, lx, wy, lz, 4, yMin, yMax)) continue;
                        }
                        data.setBlock(lx,wy,lz,Material.AIR);
                        hardenHighCaveWalls(data, lx, wy, lz);
                    }
                }
            }
        }
    }

    // Replace any stone or dirt blocks above the first encountered sand with sand (within [yMin, yMax])
    private void fixStoneAboveSand(ChunkData data, int lx, int lz, int yMin, int yMax) {
        boolean seenSand = false;
        for (int y = Math.max(0, yMin); y <= Math.min(AUDIT_Y_MAX, yMax); y++) {
            try {
                Material m = data.getType(lx, y, lz);
                if (m == Material.SAND) {
                    seenSand = true;
                } else if (seenSand && (m == Material.STONE || m == Material.DIRT)) {
                    data.setBlock(lx, y, lz, Material.SAND);
                }
            } catch (Throwable ignore) {}
        }
    }

    // Post-process pass:
    //  - Convert all dirt (and grassy dirt) within 20 blocks of any water column (near sea level band) to sand
    //  - Ensure no dirt/grass/stone sits above sand in any column within the audit range
    private void postProcessSandAndDirt(World world, ChunkData data) {
        final int r = 20; // horizontal radius in blocks
        final int yMin = Math.max(world.getMinHeight(), 140);
        final int yMax = Math.min(world.getMaxHeight() - 1, OCEAN_SEA_LEVEL + 16);

        // Precompute which local columns contain water in [yMin, yMax]
        boolean[][] hasWater = new boolean[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                boolean found = false;
                for (int y = yMin; y <= yMax; y++) {
                    try {
                        if (data.getType(lx, y, lz) == Material.WATER) { found = true; break; }
                    } catch (Throwable ignore) {}
                }
                hasWater[lx][lz] = found;
            }
        }

        // Precompute near-water mask by radius check against water columns
        boolean[][] nearWater = new boolean[16][16];
        int r2 = r * r;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                boolean nw = false;
                for (int dx = -r; dx <= r && !nw; dx++) {
                    int x = lx + dx; if (x < 0 || x > 15) continue;
                    int dx2 = dx * dx;
                    for (int dz = -r; dz <= r; dz++) {
                        int z = lz + dz; if (z < 0 || z > 15) continue;
                        if (dx2 + dz*dz > r2) continue;
                        if (hasWater[x][z]) { nw = true; break; }
                    }
                }
                nearWater[lx][lz] = nw;
            }
        }

        // A) Convert dirt near water to sand
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (!nearWater[lx][lz]) continue;
                for (int y = yMin; y <= yMax; y++) {
                    try {
                        Material m = data.getType(lx, y, lz);
                        if (m == Material.DIRT || m == Material.GRASS_BLOCK) {
                            data.setBlock(lx, y, lz, Material.SAND);
                        }
                    } catch (Throwable ignore) {}
                }
            }
        }

        // B) Ensure nothing dirt-like or stone sits above sand within the band
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                boolean seenSand = false;
                for (int y = yMin; y <= yMax; y++) {
                    try {
                        Material m = data.getType(lx, y, lz);
                        if (m == Material.SAND) {
                            seenSand = true;
                        } else if (seenSand && (m == Material.DIRT || m == Material.GRASS_BLOCK || m == Material.STONE)) {
                            data.setBlock(lx, y, lz, Material.SAND);
                        }
                    } catch (Throwable ignore) {}
                }
            }
        }
    }

    // Detects if there is any water block in a horizontal radius around (lx,ly,lz) up to sea level.
    private boolean isNearWaterColumn(ChunkData data, int lx, int ly, int lz, int radius, int yMin, int yMax) {
        int r2 = radius * radius;
        int yStart = Math.max(ly - 2, yMin);
        int yEnd = Math.min(OCEAN_SEA_LEVEL + 2, yMax);
        for (int dx = -radius; dx <= radius; dx++) {
            int x = lx + dx; if (x < 0 || x > 15) continue;
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx*dx + dz*dz > r2) continue;
                int z = lz + dz; if (z < 0 || z > 15) continue;
                for (int y = yStart; y <= yEnd; y++) {
                    try {
                        if (data.getType(x, y, z) == Material.WATER) return true;
                    } catch (Throwable ignore) {}
                }
            }
        }
        return false;
    }

    // Convert exposed dirt/grass adjacent to newly carved air into stone above Y=147 to avoid dirt/grass cave walls
    private void hardenHighCaveWalls(ChunkData data, int lx, int ly, int lz) {
        if (ly <= 147) return;
        final int[][] dirs = new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) {
            int nx = lx + d[0];
            int ny = ly + d[1];
            int nz = lz + d[2];
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
            if (ny <= 147) continue;
            try {
                Material m = data.getType(nx, ny, nz);
                if (m == Material.DIRT || m == Material.GRASS_BLOCK) {
                    data.setBlock(nx, ny, nz, Material.STONE);
                }
            } catch (Throwable ignore) {}
        }
    }

    // Dents: carve a short connector from an endpoint toward the nearest existing air in this chunk
    private void dentTowardsAir(ChunkData data, int chunkBaseX, int chunkBaseZ,
                                double ex, int ey, double ez, int yMin, int yMax) {
        int bestLX = -1, bestLY = -1, bestLZ = -1;
        double bestDist2 = Double.POSITIVE_INFINITY;
        int exi = (int)Math.floor(ex), ezi = (int)Math.floor(ez);
        int lx0 = exi - chunkBaseX;
        int lz0 = ezi - chunkBaseZ;
        // Search within radius up to 12 blocks for air
        int R = 12;
        for (int dy = -R; dy <= R; dy++) {
            int y = ey + dy; if (y < yMin || y > yMax) continue; if (y <= -61) continue;
            for (int dx = -R; dx <= R; dx++) {
                int lx = lx0 + dx; if (lx < 0 || lx > 15) continue;
                for (int dz = -R; dz <= R; dz++) {
                    int lz = lz0 + dz; if (lz < 0 || lz > 15) continue;
                    double d2 = dx*dx + dy*dy + dz*dz; if (d2 >= bestDist2) continue;
                    try {
                        if (data.getType(lx, y, lz) == Material.AIR) {
                            bestDist2 = d2; bestLX = lx; bestLY = y; bestLZ = lz;
                        }
                    } catch (Throwable ignore) {}
                }
            }
        }
        if (bestLX == -1) return; // no air nearby
        // Carve a small line toward the found air block
        double tx = chunkBaseX + bestLX + 0.5;
        double tz = chunkBaseZ + bestLZ + 0.5;
        double ty = bestLY + 0.5;
        int steps = (int)Math.ceil(Math.sqrt(bestDist2));
        steps = Math.max(2, Math.min(steps, 16));
        for (int s = 0; s <= steps; s++) {
            double t = s / (double)steps;
            double cx = ex + (tx - ex) * t;
            double cy = ey + (ty - ey) * t;
            double cz = ez + (tz - ez) * t;
            carveSphere(data, chunkBaseX, chunkBaseZ, cx, (int)Math.round(cy), cz, 1.0, yMin, yMax);
        }
    }
}
