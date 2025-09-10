package goat.thaw.system.dev;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GenerateArcticCommand implements CommandExecutor {

    private static final String WORLD_NAME = "arctic";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        World existing = Bukkit.getWorld(WORLD_NAME);
        if (existing != null) {
            sender.sendMessage("§eWorld '" + WORLD_NAME + "' already exists.");
            return true;
        }

        WorldCreator creator = new WorldCreator(WORLD_NAME)
                .generator(new ArcticChunkGenerator());

        World world = creator.createWorld();
        if (world == null) {
            sender.sendMessage("§cFailed to create world '" + WORLD_NAME + "'.");
            return true;
        }

        sender.sendMessage("§aArctic world created: §f" + world.getName());
        if (sender instanceof Player) {
            sender.sendMessage("§7Use §f/warpto " + WORLD_NAME + " §7to travel there.");
        }
        return true;
    }
}

