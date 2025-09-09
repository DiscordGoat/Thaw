package goat.thaw.dev;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class WarpToCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("§7Usage: §f/warpto <worldname>");
            return true;
        }

        String worldName = args[0];
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWorld not found: §f" + worldName);
            return true;
        }

        Player player = (Player) sender;
        Location spawn = world.getSpawnLocation().clone();

        int x = spawn.getBlockX();
        int z = spawn.getBlockZ();
        int highest = world.getHighestBlockYAt(x, z);
        int safeY = Math.max(highest + 1, world.getMinHeight() + 2);
        spawn.setY(safeY);

        spawn.setYaw(player.getLocation().getYaw());
        spawn.setPitch(player.getLocation().getPitch());

        boolean ok = player.teleport(spawn);
        if (ok) {
            player.sendMessage("§aWarped to §f" + world.getName());
        } else {
            player.sendMessage("§cTeleport failed.");
        }
        return true;
    }
}

