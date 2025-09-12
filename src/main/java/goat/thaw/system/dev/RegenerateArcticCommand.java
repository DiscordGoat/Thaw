package goat.thaw.system.dev;

import goat.thaw.Thaw;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class RegenerateArcticCommand implements CommandExecutor {

    private static final String WORLD_NAME = "arctic";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        sender.sendMessage("§e[Thaw] Starting regeneration of Arctic...");

        // 1) Decommission existing world (if present)
        World existing = Bukkit.getWorld(WORLD_NAME);
        if (existing != null) {
            World fallback = Bukkit.getWorlds().get(0);
            for (Player p : existing.getPlayers()) {
                p.teleport(fallback.getSpawnLocation());
                p.sendMessage("§cThe Arctic world is being regenerated. You have been teleported to spawn.");
            }
            Bukkit.unloadWorld(existing, false);
            sender.sendMessage("§e[Thaw] Unloaded existing Arctic world.");
        }
        File container = Bukkit.getWorldContainer();
        File folder = new File(container, WORLD_NAME);
        deleteRecursively(folder);

        // 2) Generate new Arctic world
        WorldCreator creator = new WorldCreator(WORLD_NAME).generator(new ArcticChunkGenerator());
        World world = creator.createWorld();
        if (world == null) {
            sender.sendMessage("§c[Thaw] Failed to create world '" + WORLD_NAME + "'.");
            return true;
        }

        // 3) Handle player teleport if executed by a player
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Location spawn = world.getSpawnLocation().clone();
            int x = spawn.getBlockX();
            int z = spawn.getBlockZ();
            int highest = world.getHighestBlockYAt(x, z);
            int safeY = Math.max(highest + 1, world.getMinHeight() + 2);
            spawn.setY(safeY);
            spawn.setYaw(player.getLocation().getYaw());
            spawn.setPitch(player.getLocation().getPitch());

            player.teleport(spawn);
            player.sendMessage("§a[Thaw] Regenerated and warped to §f" + WORLD_NAME);
        } else {
            sender.sendMessage("§a[Thaw] Regenerated Arctic world (console execution, no teleport).");
        }
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
