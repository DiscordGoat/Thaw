package goat.thaw.system.monument;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Handles rewards and world-side effects when a monument is completed.
 */
public class MonumentRewardListener implements Listener {
    private final JavaPlugin plugin;
    private final MonumentManager manager;

    public MonumentRewardListener(JavaPlugin plugin, MonumentManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onComplete(CompleteMonumentStandEvent e) {
        Monument m = manager.get(e.getMonumentId());
        if (m == null) return;
        World w = m.getBase().getWorld();
        if (w == null) return;
        for (int[] off : m.getStationOffsets()) {
            int x = m.getBase().getBlockX() + off[0];
            int y = m.getBase().getBlockY() + off[1];
            int z = m.getBase().getBlockZ() + off[2];
            w.getBlockAt(x, y, z).setType(m.getType().getGlassMaterial());
        }
        Location drop = m.getBase().clone().add(0.5, 1.0, 0.5);
        ItemStack reward = new ItemStack(m.getType().getStationMaterial());
        Item item = w.dropItem(drop, reward);
        Player target = Bukkit.getPlayer(e.getPlayerUUID());
        if (target != null) {
            Vector vel = target.getLocation().toVector().subtract(drop.toVector()).normalize().multiply(0.4);
            item.setVelocity(vel);
        }
        w.playSound(drop, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        spawnParticles(w, drop, m.getType());
    }

    private void spawnParticles(World w, Location loc, MonumentType type) {
        Particle.DustOptions dust = new Particle.DustOptions(type.getColor(), 1.0f);
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks >= 600) { cancel(); return; }
                w.spawnParticle(Particle.REDSTONE, loc, 20, 0.5, 0.5, 0.5, dust);
                ticks += 20;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
}
