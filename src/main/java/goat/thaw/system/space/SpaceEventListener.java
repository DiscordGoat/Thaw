package goat.thaw.system.space;

import goat.thaw.system.space.event.SpaceEnterEvent;
import goat.thaw.system.space.event.SpaceLeaveEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static goat.thaw.system.space.Space.BlockPos;

public class SpaceEventListener implements Listener {
    private final SpaceManager manager;
    private final Plugin plugin;

    public SpaceEventListener(SpaceManager manager, Plugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onEnter(SpaceEnterEvent e) {
        Player player = e.getPlayer();
        Space space = e.getSpace();
        player.sendMessage("Welcome to the space " + space.getId() + ".");

        // Passively recalc: confirm still sealed; if unsealed, delete; else overwrite
        // Run on next tick to ensure we are on main thread and player has settled
        Bukkit.getScheduler().runTask(plugin, () -> recalcAndValidate(player, space));
    }

    @EventHandler
    public void onLeave(SpaceLeaveEvent e) {
        e.getPlayer().sendMessage("Safe travels from space " + e.getSpace().getId() + ".");
    }

    private void recalcAndValidate(Player player, Space existing) {
        Block seed = player.getLocation().getBlock().getRelative(0, 1, 0);
        World world = player.getWorld();
        if (!seed.getType().isAir()) {
            // If player is not in air one-above-feet, we can still try from any block within existing space near player
            // but for simplicity, we'll bail out quietly.
            return;
        }

        final Deque<Block> stack = new ArrayDeque<>();
        final Set<String> visited = new HashSet<>();
        final Set<BlockPos> collected = new HashSet<>();

        pushIfValid(world, stack, visited, seed);

        final int minY = world.getMinHeight();
        final int maxY = world.getMaxHeight();

        int scanBudget = 200000; // generous safety limit
        while (!stack.isEmpty() && scanBudget-- > 0) {
            Block b = stack.pop();
            if (b.getWorld() != world) continue;
            int y = b.getY();
            if (y < minY || y >= maxY) continue;

            if (isSkyExposed(world, b.getX(), y, b.getZ())) {
                // Unsealed now: delete space
                manager.deleteSpace(existing.getId());
                player.sendMessage("Space " + existing.getId() + " is no longer sealed and was removed.");
                return;
            }

            if (!b.getType().isAir()) continue;
            collected.add(new BlockPos(b.getX(), y, b.getZ()));

            pushIfValid(world, stack, visited, b.getRelative(1, 0, 0));
            pushIfValid(world, stack, visited, b.getRelative(-1, 0, 0));
            pushIfValid(world, stack, visited, b.getRelative(0, 1, 0));
            pushIfValid(world, stack, visited, b.getRelative(0, -1, 0));
            pushIfValid(world, stack, visited, b.getRelative(0, 0, 1));
            pushIfValid(world, stack, visited, b.getRelative(0, 0, -1));
        }

        // Overwrite existing with recalculated set and updated influence/temperature
        SpaceManager.InfluenceResult inf = manager.computeInfluence(world, collected);
        Space updated = new Space(existing.getId(), existing.getWorldName(), collected, inf.temperature, inf.totalInfluence, collected.size());
        manager.overwriteSpace(updated);
        player.sendMessage("Space " + existing.getId() + " validated: air=" + collected.size()
                + ", influence=" + String.format("%.2f", inf.totalInfluence)
                + ", temp=" + String.format("%.1fF", inf.temperature));
    }

    private boolean isSkyExposed(org.bukkit.World world, int x, int y, int z) {
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
            if (!world.getBlockAt(x, yy, z).getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private void pushIfValid(World world, Deque<Block> stack, Set<String> visited, Block n) {
        if (n.getWorld() != world) return;
        int y = n.getY();
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        String key = world.getName() + "|" + n.getX() + "," + y + "," + n.getZ();
        if (visited.add(key)) stack.push(n);
    }
}
