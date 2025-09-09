package goat.thaw.dev;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;

public class DecomissionCommand implements CommandExecutor {

    private static final String WORLD_NAME = "arctic";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            sender.sendMessage("§eWorld '" + WORLD_NAME + "' is not loaded.");
            return true;
        }

        // Move players to the default world spawn
        World fallback = Bukkit.getWorlds().get(0);
        for (Player p : world.getPlayers()) {
            p.teleport(fallback.getSpawnLocation());
            p.sendMessage("§7World is being decommissioned. You were moved to §f" + fallback.getName());
        }

        boolean unloaded = Bukkit.getServer().unloadWorld(world, false);
        if (!unloaded) {
            sender.sendMessage("§cFailed to unload world '" + WORLD_NAME + "'.");
            return true;
        }

        File container = Bukkit.getWorldContainer();
        File folder = new File(container, WORLD_NAME);
        if (!folder.exists()) {
            sender.sendMessage("§aWorld unloaded. No folder found to delete.");
            return true;
        }

        if (deleteRecursively(folder)) {
            sender.sendMessage("§aWorld '" + WORLD_NAME + "' decommissioned and deleted.");
        } else {
            sender.sendMessage("§eWorld unloaded, but some files could not be deleted. Check server logs/locks.");
        }
        return true;
    }

    private boolean deleteRecursively(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                if (!deleteRecursively(f)) return false;
            }
        }
        return file.delete();
    }
}

