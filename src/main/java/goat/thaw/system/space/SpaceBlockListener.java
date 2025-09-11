package goat.thaw.system.space;

import goat.thaw.system.space.temperature.TemperatureRegistry;
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
        Optional<Space> opt = manager.findSpaceAt(w, x, y, z);
        if (!opt.isPresent()) {
            // Not in a space: if placing a heat source, try to create a new space near block
            org.bukkit.block.Block placed = e.getBlockPlaced();
            double influence = TemperatureRegistry.influence(placed);
            if (influence > 0.0) {
                long now = placed.getWorld().getFullTime();
                long last = autoCreateCooldown.getOrDefault(p.getUniqueId(), 0L);
                if (now - last >= AUTO_CREATE_COOLDOWN_TICKS) {
                    org.bukkit.block.Block seed = findNearestAir(placed, 5);
                    if (seed != null) {
                        autoCreateCooldown.put(p.getUniqueId(), now);
                        manager.createFromFloodFillAt(p, seed, 32, new SpaceManager.FloodFillCallback() {
                            @Override public void onComplete(Space space) {
                                p.sendMessage("New space created: air=" + space.getAirBlocks()
                                        + ", influence=" + String.format("%.2f", space.getTotalInfluence())
                                        + ", temp=" + String.format("%.1fF", space.getTemperature()));
                            }
                            @Override public void onUnsealed() {
                                p.sendMessage("Could not create space: not sealed (sky exposure).");
                            }
                        });
                    }
                }
            }
            return;
        }

        // Reconfirm sealed by recalculating from player's feet+1 (DFS). If unsealed, delete. If sealed, overwrite (shape may change)
        Space current = opt.get();
        // Can't easily call private methods; inline simple recompute here
        // DFS recalc (sealed check)
        java.util.ArrayDeque<org.bukkit.block.Block> stack = new java.util.ArrayDeque<>();
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        java.util.HashSet<BlockPos> collected = new java.util.HashSet<>();
        org.bukkit.block.Block seed = p.getLocation().getBlock().getRelative(0, 1, 0);
        pushIfValid(w, stack, visited, seed);
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        int scanBudget = 200000;
        while (!stack.isEmpty() && scanBudget-- > 0) {
            Block b = stack.pop();
            if (b.getWorld() != w) continue;
            int yy = b.getY();
            if (yy < minY || yy >= maxY) continue;

            if (!b.getType().isAir()) continue; // <--- skip non-air first!

            if (isSkyExposed(w, b.getX(), yy, b.getZ())) {
                manager.deleteSpace(current.getId());
                p.sendMessage("Space " + current.getId() + " unsealed and removed.");
                return;
            }

            collected.add(new BlockPos(b.getX(), yy, b.getZ()));
            pushIfValid(w, stack, visited, b.getRelative(1, 0, 0));
            pushIfValid(w, stack, visited, b.getRelative(-1, 0, 0));
            pushIfValid(w, stack, visited, b.getRelative(0, 1, 0));
            pushIfValid(w, stack, visited, b.getRelative(0, -1, 0));
            pushIfValid(w, stack, visited, b.getRelative(0, 0, 1));
            pushIfValid(w, stack, visited, b.getRelative(0, 0, -1));
        }

        // Still sealed: overwrite geometry and temperature
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
        if (visited.add(key)) stack.push(n);
    }

    private boolean isSkyExposed(World world, int x, int y, int z) {
        int top = world.getMaxHeight();
        for (int yy = y; yy < top; yy++) {
            if (!world.getBlockAt(x, yy, z).getType().isAir()) {
                return false; // blocked by ceiling
            }
        }
        return true; // reached world top with no blocks
    }

}
