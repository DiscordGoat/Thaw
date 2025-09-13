package goat.thaw.system.capsule;

import goat.thaw.Thaw;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;

/**
 * Handles behaviour of iron golems spawned from capsule spawners.
 */
public class CapsuleGolemListener implements Listener {

    private static final String META_KEY = "capsule_golem";

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.IRON_GOLEM) return;
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) return;

        IronGolem golem = (IronGolem) event.getEntity();
        Player target = findNearestPlayer(golem.getLocation());
        if (target != null) {
            // Delay target assignment to ensure entity is fully spawned
            Bukkit.getScheduler().runTask(Thaw.getInstance(), () -> golem.setTarget(target));
        }
        golem.setMetadata(META_KEY, new FixedMetadataValue(Thaw.getInstance(), true));
    }

    private Player findNearestPlayer(Location loc) {
        double best = Double.MAX_VALUE;
        Player nearest = null;
        for (Player p : loc.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(loc);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntityType() != EntityType.IRON_GOLEM) return;
        if (!event.getEntity().hasMetadata(META_KEY)) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
    }
}

