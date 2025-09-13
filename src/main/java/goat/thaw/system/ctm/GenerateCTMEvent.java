package goat.thaw.system.ctm;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a CTM structure is generated.
 */
public class GenerateCTMEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Location location;

    public GenerateCTMEvent(Location location) {
        this.location = location;
    }

    public Location getLocation() {
        return location;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
