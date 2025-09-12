package goat.thaw.system.dev;

import goat.thaw.system.space.Space;
import goat.thaw.system.space.SpaceManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Dev command: /floodfillalgorithm
 * Starts a DFS flood fill from one block above the player's feet.
 * Collects all reachable air blocks. If a queued block is exposed
 * to the sky, the fill aborts and no space is created.
 */
public class FloodFillAlgorithmCommand implements CommandExecutor {

    private final Plugin plugin;
    private final SpaceManager spaces;

    public FloodFillAlgorithmCommand(Plugin plugin, SpaceManager spaces) {
        this.plugin = plugin;
        this.spaces = spaces;
    }

    private static class FillSession {
        final Player player;
        final World world;
        final Deque<Block> stack = new ArrayDeque<>(); // DFS stack
        final Set<String> visited = new HashSet<>();
        final Set<Space.BlockPos> collected = new HashSet<>();
        BukkitTask task;
        int blocksPerTick;

        FillSession(Player player) {
            this.player = player;
            this.world = player.getWorld();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;
        World.Environment env = player.getWorld().getEnvironment();
        if (env != World.Environment.NORMAL && env != World.Environment.THE_END) {
            player.sendMessage("Space mapping is only supported in the Overworld and the End.");
            return true;
        }

        Block start = player.getLocation().getBlock().getRelative(0, 1, 0);
        FillSession session = new FillSession(player);

        // Parse optional rate arg
        int rate = 16; // default
        if (args.length >= 1) {
            try {
                rate = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }
        // Clamp to safe range
        rate = Math.max(1, Math.min(2048, rate));
        session.blocksPerTick = rate;

        if (!start.getType().isAir()) {
            player.sendMessage("No air above feet to map space.");
            return true;
        }

        pushIfValid(session, start);
        if (session.stack.isEmpty()) {
            player.sendMessage("Nothing to map.");
            return true;
        }

        player.sendMessage("Mapping space via DFS (" + session.blocksPerTick + " blocks/tick)...");
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(session), 0L, 1L);
        return true;
    }

    private void step(FillSession s) {
        int worldMin = s.world.getMinHeight();
        int worldMax = s.world.getMaxHeight();
        int collectedThisTick = 0;
        int scanBudget = 4096;

        while (collectedThisTick < s.blocksPerTick && scanBudget-- > 0) {
            if (s.stack.isEmpty()) {
                if (s.task != null) s.task.cancel();
                // Completed successfully -> compute influence, create and persist space
                SpaceManager.InfluenceResult inf = spaces.computeInfluence(s.world, s.collected);
                Space space = new Space(UUID.randomUUID(), s.world.getName(), s.collected, inf.temperature, inf.totalInfluence, s.collected.size());
                spaces.register(space);
                spaces.save();
                s.player.sendMessage("Space created: id=" + space.getId() +
                        ", air=" + space.getAirBlocks() +
                        ", influence=" + String.format("%.2f", space.getTotalInfluence()) +
                        ", temp=" + String.format("%.1fF", space.getTemperature()));
                return;
            }

            Block b = s.stack.pop();
            if (b.getWorld() != s.world) continue;
            int y = b.getY();
            if (y < worldMin || y >= worldMax) continue;

            if (isSkyExposed(s.world, b.getX(), y, b.getZ())) {
                abort(s, "Sky exposure at " + b.getX() + "," + y + "," + b.getZ());
                return;
            }

            if (!b.getType().isAir()) continue; // boundary

            // collect this block
            s.collected.add(new Space.BlockPos(b.getX(), y, b.getZ()));
            collectedThisTick++;

            pushIfValid(s, b.getRelative(1, 0, 0));
            pushIfValid(s, b.getRelative(-1, 0, 0));
            pushIfValid(s, b.getRelative(0, 1, 0));
            pushIfValid(s, b.getRelative(0, -1, 0));
            pushIfValid(s, b.getRelative(0, 0, 1));
            pushIfValid(s, b.getRelative(0, 0, -1));
        }
    }

    private void pushIfValid(FillSession s, Block n) {
        if (n.getWorld() != s.world) return;
        int y = n.getY();
        if (y < s.world.getMinHeight() || y >= s.world.getMaxHeight()) return;
        String key = key(n.getX(), y, n.getZ());
        if (s.visited.add(key)) s.stack.push(n);
    }

    private void abort(FillSession s, String reason) {
        if (s.task != null) s.task.cancel();
        s.player.sendMessage("Space mapping aborted: " + reason);
    }

    private String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    // More reliable sky exposure test using skylight
    private boolean isSkyExposed(org.bukkit.World world, int x, int y, int z) {
        org.bukkit.block.Block block = world.getBlockAt(x, y, z);
        boolean suspect;
        try {
            suspect = block.getLightFromSky() > 0;
        } catch (Throwable t) {
            // if we cannot query skylight, be conservative and verify by scan
            suspect = true;
        }
        if (!suspect) return false; // definitely not exposed

        // Verify: any non-air block above seals it (glass, leaves, water, etc. count as sealing here)
        int top = world.getMaxHeight();
        for (int yy = y + 1; yy < top; yy++) {
            if (!world.getBlockAt(x, yy, z).getType().isAir()) {
                return false; // sealed by any block
            }
        }
        return true; // true exposure (no blocks above before sky)
    }
}
