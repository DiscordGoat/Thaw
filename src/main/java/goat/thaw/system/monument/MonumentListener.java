package goat.thaw.system.monument;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Handles placement detection and sign protection around monuments.
 */
public class MonumentListener implements Listener {
    private final MonumentManager manager;

    public MonumentListener(MonumentManager manager) {
        this.manager = manager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE && !p.hasPermission("thaw.dev")) return;
        Location loc = e.getBlockPlaced().getLocation();
        Monument m = manager.getMonumentAt(loc);
        if (m == null) return;
        if (m.getBase().distance(loc) > 2) return;
        if (m.isCompleted()) return;
        if (!manager.validateStations(m)) return;
        long ts = System.currentTimeMillis();
        manager.markCompleted(m.getId(), p.getUniqueId(), ts);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isSign(b.getType())) return;
        Monument m = manager.getMonumentAt(b.getLocation());
        if (m != null && !e.getPlayer().hasPermission("thaw.dev")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        Monument m = manager.getMonumentAt(e.getBlock().getLocation());
        if (m != null && !e.getPlayer().hasPermission("thaw.dev")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (!isSign(e.getClickedBlock().getType())) return;
        Monument m = manager.getMonumentAt(e.getClickedBlock().getLocation());
        if (m != null && !e.getPlayer().hasPermission("thaw.dev")) {
            e.setCancelled(true);
        }
    }

    private boolean isSign(Material m) {
        return m.name().endsWith("_SIGN") || m.name().endsWith("_WALL_SIGN");
    }
}
