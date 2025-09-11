package goat.thaw.system.dev;

import goat.thaw.subsystems.temperature.DiceManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ExternalTempDebugCommand implements CommandExecutor {
    private final DiceManager dice;

    public ExternalTempDebugCommand(DiceManager dice) {
        this.dice = dice;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        Player p = (Player) sender;
        dice.toggleDebug(p.getUniqueId());
        sender.sendMessage("External temperature debug toggled.");
        return true;
    }
}

