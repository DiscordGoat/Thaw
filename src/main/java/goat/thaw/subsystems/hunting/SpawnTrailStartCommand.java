package goat.thaw.subsystems.hunting;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SpawnTrailStartCommand implements CommandExecutor {

    private final TrailManager trails;

    public SpawnTrailStartCommand(TrailManager trails) {
        this.trails = trails;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Player-only command.");
            return true;
        }
        Location feet = p.getLocation();
        // Snap to block below feet
        Location under = feet.clone().subtract(0, 1, 0);
        var tsi = trails.spawnTrailStart(under, p.getUniqueId());
        if (tsi == null) {
            sender.sendMessage(ChatColor.RED + "You already have 2 active trails.");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Spawned trail start at your feet.");
        }
        return true;
    }
}
