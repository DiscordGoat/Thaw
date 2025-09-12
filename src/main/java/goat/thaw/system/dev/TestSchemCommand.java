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

    private final SchemManager schematicMgr;

    public TestSchemCommand(SchemManager schematicMgr) {
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

        // Check for required argument
        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <schematicName>");
            player.sendMessage(ChatColor.GRAY  + "Note: Schematics must be in the resources/schematics/ folder");
            return true;
        }

        String name = args[0].toLowerCase(Locale.ROOT);
        try {
            // Try to paste the schematic
            schematicMgr.placeStructure(name, player.getLocation());
            player.sendMessage(ChatColor.GREEN + "Pasted schematic '" + name + "' at your location.");
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Failed to paste schematic '" + name + "'. Check console for details.");
        }

        return true;
    }
}
