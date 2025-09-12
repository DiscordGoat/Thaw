package goat.thaw.system;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class FirstJoinListener implements Listener {

    private final JavaPlugin plugin;

    public FirstJoinListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // If they have a bed/spawn already, don’t touch
        if (player.hasPlayedBefore()) return;

        World arctic = Bukkit.getWorld("Arctic");
        if (arctic == null) return;

        Location spawn = arctic.getSpawnLocation();
        player.teleport(spawn);

        // Set bed/spawnpoint permanently to Arctic
        player.setBedSpawnLocation(spawn);
        player.setRespawnLocation(spawn);
        player.getWorld().setDifficulty(Difficulty.HARD);
        player.getWorld().setHardcore(true);
        player.getWorld().setSpawnLocation(spawn);

        player.sendMessage(ChatColor.AQUA + "Welcome to the Arctic! Your spawn has been set.");
    }
}
