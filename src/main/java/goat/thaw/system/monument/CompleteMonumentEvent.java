package goat.thaw.system.monument;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a monument stand is fully completed.
 */
public class CompleteMonumentEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String monumentId;
    private final MonumentType monumentType;
    private final Location standLocation;
    private final UUID player;
    private final long timestamp;

    public CompleteMonumentEvent(String monumentId, MonumentType type, Location standLocation, UUID player, long timestamp) {
        this.monumentId = monumentId;
        this.monumentType = type;
        this.standLocation = standLocation;
        this.player = player;
        this.timestamp = timestamp;
    }

    public String getMonumentId() {
        return monumentId;
    }

    public MonumentType getMonumentType() {
        return monumentType;
    }

    public Location getStandLocation() {
        return standLocation;
    }

    public UUID getPlayer() {
        return player;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
