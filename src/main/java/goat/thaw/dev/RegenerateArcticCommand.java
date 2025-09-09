package goat.thaw.dev;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;

public class RegenerateArcticCommand implements CommandExecutor {

    private static final String WORLD_NAME = "arctic";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        Player player = (Player) sender;

        // 1) Decommission existing world (if present)
        World existing = Bukkit.getWorld(WORLD_NAME);
        if (existing != null) {
            // Move any players out first
            World fallback = Bukkit.getWorlds().get(0);
            for (Player p : existing.getPlayers()) {
                p.teleport(fallback.getSpawnLocation());
            }
            Bukkit.unloadWorld(existing, false);
        }
        File container = Bukkit.getWorldContainer();
        File folder = new File(container, WORLD_NAME);
        deleteRecursively(folder);

        // 2) Generate new Arctic world
        WorldCreator creator = new WorldCreator(WORLD_NAME).generator(new ArcticChunkGenerator());
        World world = creator.createWorld();
        if (world == null) {
            sender.sendMessage("§cFailed to create world '" + WORLD_NAME + "'.");
            return true;
        }

        // 3) Warp executor to safe Y in the new world
        Location spawn = world.getSpawnLocation().clone();
        int x = spawn.getBlockX();
        int z = spawn.getBlockZ();
        int highest = world.getHighestBlockYAt(x, z);
        int safeY = Math.max(highest + 1, world.getMinHeight() + 2);
        spawn.setY(safeY);
        spawn.setYaw(player.getLocation().getYaw());
        spawn.setPitch(player.getLocation().getPitch());

        player.teleport(spawn);
        player.sendMessage("§aRegenerated and warped to §f" + WORLD_NAME);
        return true;
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                if (!deleteRecursively(f)) return false;
            }
        }
        return file.delete();
    }
}

