package goat.thaw.system.space;

import goat.thaw.system.space.event.SpaceEnterEvent;
import goat.thaw.system.space.event.SpaceLeaveEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SpacePresenceListener implements Listener {
    private final SpaceManager manager;
    private final Map<UUID, UUID> playerInSpace = new HashMap<>(); // player -> space id

    public SpacePresenceListener(SpaceManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        evaluate(e.getPlayer(), e.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        playerInSpace.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
            return;
        }
        evaluate(e.getPlayer(), e.getTo());
    }

    private void evaluate(Player player, Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY() + 1; // one block above feet
        int z = loc.getBlockZ();

        Optional<Space> space = manager.findSpaceAt(player.getWorld(), x, y, z);
        UUID current = playerInSpace.get(player.getUniqueId());
        UUID now = space.map(Space::getId).orElse(null);

        if (current == null && now != null) {
            // Entered
            playerInSpace.put(player.getUniqueId(), now);
            Bukkit.getPluginManager().callEvent(new SpaceEnterEvent(player, space.get()));
        } else if (current != null && (now == null || !current.equals(now))) {
            // Left previous
            Space prev = manager.get(current);
            playerInSpace.remove(player.getUniqueId());
            if (prev != null) Bukkit.getPluginManager().callEvent(new SpaceLeaveEvent(player, prev));
            // Entered new (switch)
            if (now != null && space.isPresent()) {
                playerInSpace.put(player.getUniqueId(), now);
                Bukkit.getPluginManager().callEvent(new SpaceEnterEvent(player, space.get()));
            }
        }
    }
}
