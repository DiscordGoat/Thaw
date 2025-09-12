package goat.thaw.system.dev;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;

/**
 * Command to paste a loaded schematic at the player's location.
 * Usage: /testschem <name>
 */
public class TestSchemCommand implements CommandExecutor {

    private final SchematicManager schematicMgr;

    public TestSchemCommand(SchematicManager schematicMgr) {
        this.schematicMgr = schematicMgr;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // only players
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;

        // get the up-to-date list of names
        Set<String> available = schematicMgr.getAvailableSchematics();

        // no args → show usage + available
        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <schematicName>");
            player.sendMessage(ChatColor.GRAY  + "Available: " + String.join(", ", available));
            return true;
        }

        String name = args[0].toLowerCase(Locale.ROOT);
        if (!available.contains(name)) {
            player.sendMessage(ChatColor.RED   + "Unknown schematic: '" + name + "'.");
            player.sendMessage(ChatColor.GRAY  + "Available: " + String.join(", ", available));
            return true;
        }

        // paste it
        Location loc = player.getLocation();
        boolean success = schematicMgr.spawnSchematic(name, loc);

        if (success) {
            player.sendMessage(ChatColor.GREEN + "Pasted schematic '" + name + "' at your location.");
        } else {
            player.sendMessage(ChatColor.RED   + "Failed to paste schematic '" + name + "'. Check console.");
        }

        return true;
    }
}
