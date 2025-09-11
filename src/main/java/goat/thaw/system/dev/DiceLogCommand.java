package goat.thaw.system.dev;

import goat.thaw.system.logging.DiceLogger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DiceLogCommand implements CommandExecutor {
    private final DiceLogger logger;

    public DiceLogCommand(DiceLogger logger) { this.logger = logger; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        Player p = (Player) sender;
        logger.toggle(p.getUniqueId());
        p.sendMessage("DICE logging toggled.");
        return true;
    }
}

