package goat.thaw.system.dev;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TeleportToPeakCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        World world = player.getWorld();

        int maxRadius = 128;
        if (args.length >= 1) {
            try {
                maxRadius = Math.max(8, Math.min(512, Integer.parseInt(args[0])));
            } catch (NumberFormatException ignored) {
                player.sendMessage("Invalid radius. Using default 128.");
            }
        }

        Location start = player.getLocation();
        Location peak = climbToPeak(world, start, maxRadius);

        if (peak == null) {
            player.sendMessage("No peak found within radius " + maxRadius + ".");
            return true;
        }

        // Keep player orientation
        peak.setYaw(player.getLocation().getYaw());
        peak.setPitch(player.getLocation().getPitch());

        boolean ok = player.teleport(peak);
        if (ok) {
            player.sendMessage("Teleported to nearest peak at "
                    + peak.getBlockX() + ", " + (peak.getBlockY() - 1) + ", " + peak.getBlockZ());
        } else {
            player.sendMessage("Teleport failed.");
        }
        return true;
    }

    private Location climbToPeak(World world, Location start, int maxRadius) {
        int baseX = start.getBlockX();
        int baseZ = start.getBlockZ();
        int sea = world.getSeaLevel();

        int curX = baseX;
        int curZ = baseZ;

        int curY = groundHeight(world, curX, curZ);
        if (curY <= sea) {
            // Nudge off water level to nearest land within small radius
            for (int r = 1; r <= 8 && curY <= sea; r++) {
                for (int dx = -r; dx <= r && curY <= sea; dx++) {
                    for (int dz = -r; dz <= r && curY <= sea; dz++) {
                        int h = groundHeight(world, baseX + dx, baseZ + dz);
                        if (h > sea) {
                            curX = baseX + dx; curZ = baseZ + dz; curY = h;
                        }
                    }
                }
            }
        }

        int bestX = curX, bestZ = curZ, bestY = curY;

        // Hill climb with small neighborhood search; cap iterations
        for (int iter = 0; iter < 2048; iter++) {
            int nextX = bestX, nextZ = bestZ, nextY = bestY;

            // search a 5x5 neighborhood for a strictly higher ground height
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int x = bestX + dx;
                    int z = bestZ + dz;
                    // respect search radius
                    if (squared(x - baseX) + squared(z - baseZ) > maxRadius * maxRadius) continue;
                    int h = groundHeight(world, x, z);
                    if (h > nextY) {
                        nextY = h; nextX = x; nextZ = z;
                    }
                }
            }

            if (nextY <= bestY) break; // no higher neighbor -> summit
            bestX = nextX; bestZ = nextZ; bestY = nextY;
        }

        // Basic prominence check: ensure it's meaningfully above surroundings
        int neighborMax = bestY;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx == 0 && dz == 0) continue;
                int h = groundHeight(world, bestX + dx, bestZ + dz);
                if (h > neighborMax) neighborMax = h;
            }
        }
        if (bestY <= sea + 10 || bestY - neighborMax < 0) {
            return null;
        }

        int safeY = Math.max(world.getMinHeight() + 2, bestY + 1);
        return new Location(world, bestX + 0.5, safeY, bestZ + 0.5);
    }

    private int groundHeight(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        if (y <= world.getMinHeight()) return y;
        // Step down through foliage to solid ground if needed
        Material m = world.getBlockAt(x, y, z).getType();
        int min = Math.max(world.getMinHeight(), y - 6);
        while (isFoliageOrFluid(m) && y > min) {
            y--;
            m = world.getBlockAt(x, y, z).getType();
        }
        // Avoid water/lava surfaces counting as peaks
        if (m == Material.WATER || m == Material.KELP || m == Material.SEAGRASS || m == Material.LAVA) {
            return world.getSeaLevel() - 1;
        }
        return y;
    }

    private boolean isFoliageOrFluid(Material m) {
        switch (m) {
            case ACACIA_LEAVES: case BIRCH_LEAVES: case DARK_OAK_LEAVES: case JUNGLE_LEAVES:
            case MANGROVE_LEAVES: case OAK_LEAVES: case SPRUCE_LEAVES:
            case AZALEA_LEAVES: case FLOWERING_AZALEA_LEAVES:
            case CHERRY_LEAVES:
            case MOSS_CARPET:
            case VINE:
            case BAMBOO: case BAMBOO_SAPLING:
            case KELP: case KELP_PLANT: case SEAGRASS: case TALL_SEAGRASS:
            case WATER: case LAVA:
                return true;
            default:
                return false;
        }
    }

    private int squared(int v) { return v * v; }
}
