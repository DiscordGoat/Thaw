package goat.thaw.system.dev;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class LocateBungalowsCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        List<Location> bungalows = ArcticChunkGenerator.getInstance().placedBungalows;
        if (bungalows.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No bungalows have been placed yet.");
            return true;
        }

        player.sendMessage(ChatColor.GREEN + "Bungalows found:");

        for (Location loc : bungalows) {
            if (loc.getWorld() == null) continue;
            String worldName = loc.getWorld().getName();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();

            String labelText = "- " + worldName + " @ [" + x + ", " + y + ", " + z + "]";

            BaseComponent[] message = new ComponentBuilder(labelText)
                    .color(ChatColor.AQUA)
                    .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/tp " + player.getName() + " " + x + " " + y + " " + z))
                    .create();

            player.spigot().sendMessage(message);
        }

        return true;
    }
}
