package goat.thaw.system.space;

import goat.thaw.system.space.temperature.TemperatureRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Player;

import java.util.Optional;

import static goat.thaw.system.space.Space.BlockPos;

public class SpaceBlockListener implements Listener {
    private final SpaceManager manager;
    private final java.util.Map<java.util.UUID, Long> autoCreateCooldown = new java.util.HashMap<>();
    private static final long AUTO_CREATE_COOLDOWN_TICKS = 20L * 30L; // 30 seconds

    public SpaceBlockListener(SpaceManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        World w = p.getWorld();
        int x = p.getLocation().getBlockX();
        int y = p.getLocation().getBlockY() + 1;
        int z = p.getLocation().getBlockZ();

        Bukkit.getLogger().info("[SpaceDebug] Block placed by " + p.getName() + " at " + x + "," + y + "," + z);

        Optional<Space> opt = manager.findSpaceAt(w, x, y, z);
        if (!opt.isPresent()) {
            Bukkit.getLogger().info("[SpaceDebug] No existing space at this location.");
            org.bukkit.block.Block placed = e.getBlockPlaced();
            double influence = TemperatureRegistry.influence(placed);
            Bukkit.getLogger().info("[SpaceDebug] Influence of placed block: " + influence);
            if (influence > 0.0) {
                long now = placed.getWorld().getFullTime();
                long last = autoCreateCooldown.getOrDefault(p.getUniqueId(), 0L);
                if (now - last >= AUTO_CREATE_COOLDOWN_TICKS) {
                    org.bukkit.block.Block seed = findNearestAir(placed, 5);
                    Bukkit.getLogger().info("[SpaceDebug] Nearest air seed: " + (seed == null ? "null" : seed.getX()+","+seed.getY()+","+seed.getZ()));
                    if (seed != null) {
                        autoCreateCooldown.put(p.getUniqueId(), now);
                        manager.createFromFloodFillAt(p, seed, 32, new SpaceManager.FloodFillCallback() {
                            @Override public void onComplete(Space space) {
                                Bukkit.getLogger().info("[SpaceDebug] New space created: " + space.getAirBlocks() + " air blocks");
                                p.sendMessage("New space created: air=" + space.getAirBlocks()
                                        + ", influence=" + String.format("%.2f", space.getTotalInfluence())
                                        + ", temp=" + String.format("%.1fF", space.getTemperature()));
                            }
                            @Override public void onUnsealed() {
                                Bukkit.getLogger().info("[SpaceDebug] Space creation failed due to sky exposure.");
                                p.sendMessage("Could not create space: not sealed (sky exposure).");
                            }
                        });
                    }
                }
            }
            return;
        }

        Space current = opt.get();
        Bukkit.getLogger().info("[SpaceDebug] Found existing space ID=" + current.getId());

        java.util.ArrayDeque<org.bukkit.block.Block> stack = new java.util.ArrayDeque<>();
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        java.util.HashSet<BlockPos> collected = new java.util.HashSet<>();
        org.bukkit.block.Block seed = p.getLocation().getBlock().getRelative(0, 1, 0);
        Bukkit.getLogger().info("[SpaceDebug] DFS seed = " + seed.getX() + "," + seed.getY() + "," + seed.getZ());
        pushIfValid(w, stack, visited, seed);

        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        int scanBudget = 200000;

        while (!stack.isEmpty() && scanBudget-- > 0) {
            org.bukkit.block.Block b = stack.pop();
            int bx = b.getX(), by = b.getY(), bz = b.getZ();
            Bukkit.getLogger().info("[SpaceDebug] Visiting " + bx + "," + by + "," + bz + " type=" + b.getType());

            if (b.getWorld() != w) continue;
            if (by < minY || by >= maxY) continue;

            if (!b.getType().isAir()) continue; // skip non-air first!

            if (isSkyExposed(w, bx, by, bz)) {
                Bukkit.getLogger().info("[SpaceDebug] Sky exposure found at " + bx + "," + by + "," + bz);
                manager.deleteSpace(current.getId());
                p.sendMessage("Space " + current.getId() + " unsealed and removed.");
                return;
            }

            collected.add(new BlockPos(bx, by, bz));
            pushIfValid(w, stack, visited, b.getRelative(1, 0, 0));
            pushIfValid(w, stack, visited, b.getRelative(-1, 0, 0));
            pushIfValid(w, stack, visited, b.getRelative(0, 1, 0));
            pushIfValid(w, stack, visited, b.getRelative(0, -1, 0));
            pushIfValid(w, stack, visited, b.getRelative(0, 0, 1));
            pushIfValid(w, stack, visited, b.getRelative(0, 0, -1));
        }

        Bukkit.getLogger().info("[SpaceDebug] DFS complete. Collected air=" + collected.size());
        SpaceManager.InfluenceResult inf = manager.computeInfluence(w, collected);
        Space updated = new Space(current.getId(), current.getWorldName(), collected, inf.temperature, inf.totalInfluence, collected.size());
        manager.overwriteSpace(updated);
        p.sendMessage("Space updated: air=" + updated.getAirBlocks()
                + ", influence=" + String.format("%.2f", updated.getTotalInfluence())
                + ", temp=" + String.format("%.1fF", updated.getTemperature()));
    }

    private org.bukkit.block.Block findNearestAir(org.bukkit.block.Block origin, int maxRadius) {
        java.util.ArrayDeque<org.bukkit.block.Block> q = new java.util.ArrayDeque<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        java.util.function.Function<org.bukkit.block.Block,String> key = b -> b.getX()+","+b.getY()+","+b.getZ();
        q.add(origin);
        seen.add(key.apply(origin));
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        while (!q.isEmpty()) {
            org.bukkit.block.Block b = q.poll();
            int dx = Math.abs(b.getX()-ox), dy = Math.abs(b.getY()-oy), dz = Math.abs(b.getZ()-oz);
            if (Math.max(Math.max(dx,dy),dz) > maxRadius) continue;
            if (b.getType().isAir()) return b;
            org.bukkit.block.Block[] ns = new org.bukkit.block.Block[]{
                    b.getRelative(1,0,0), b.getRelative(-1,0,0), b.getRelative(0,1,0), b.getRelative(0,-1,0), b.getRelative(0,0,1), b.getRelative(0,0,-1)
            };
            for (org.bukkit.block.Block nb : ns) {
                String k = key.apply(nb);
                if (seen.add(k)) q.add(nb);
            }
        }
        return null;
    }

    @EventHandler
    public void onForm(BlockFormEvent e) {
        World w = e.getBlock().getWorld();
        int x = e.getBlock().getX();
        int y = e.getBlock().getY();
        int z = e.getBlock().getZ();
        // If this block is inside a space, recompute that space
        Optional<Space> opt = manager.findSpaceAt(w, x, y, z);
        if (!opt.isPresent()) return;
        Space space = opt.get();
        SpaceManager.InfluenceResult inf = manager.computeInfluence(w, space.getBlocks());
        Space updated = new Space(space.getId(), space.getWorldName(), space.getBlocks(), inf.temperature, inf.totalInfluence, space.getBlocks().size());
        manager.overwriteSpace(updated);
    }

    private void pushIfValid(World world, java.util.Deque<org.bukkit.block.Block> stack, java.util.Set<String> visited, org.bukkit.block.Block n) {
        if (n.getWorld() != world) return;
        int y = n.getY();
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        String key = world.getName() + "|" + n.getX() + "," + y + "," + n.getZ();
        if (visited.add(key)) {
            stack.push(n);
            Bukkit.getLogger().info("[SpaceDebug] Pushed neighbor " + n.getX() + "," + y + "," + n.getZ());
        }
    }

    private boolean isSkyExposed(World world, int x, int y, int z) {
        Bukkit.getLogger().info("[SpaceDebug] isSkyExposed check at " + x + "," + y + "," + z);
        int top = world.getMaxHeight();
        for (int yy = y + 1; yy < top; yy++) {
            String type = world.getBlockAt(x, yy, z).getType().toString();
            Bukkit.getLogger().info("[SpaceDebug]   Scan at Y=" + yy + " type=" + type);
            if (!world.getBlockAt(x, yy, z).getType().isAir()) {
                Bukkit.getLogger().info("[SpaceDebug]   Blocked at Y=" + yy);
                return false;
            }
        }
        Bukkit.getLogger().info("[SpaceDebug]   No blockers, open to sky!");
        return true;
    }
}