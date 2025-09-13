package goat.thaw.system.monument;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired after a monument is durably marked completed and persisted.
 */
public class CompleteMonumentStandEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String monumentId;
    private final MonumentType monumentType;
    private final Location standLoc;
    private final UUID playerUUID;
    private final long timestamp;

    public CompleteMonumentStandEvent(String monumentId, MonumentType monumentType,
                                      Location standLoc, UUID playerUUID, long timestamp) {
        this.monumentId = monumentId;
        this.monumentType = monumentType;
        this.standLoc = standLoc;
        this.playerUUID = playerUUID;
        this.timestamp = timestamp;
    }

    public String getMonumentId() {
        return monumentId;
    }

    public MonumentType getMonumentType() {
        return monumentType;
    }

    public Location getStandLoc() {
        return standLoc.clone();
    }

    public UUID getPlayerUUID() {
        return playerUUID;
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
