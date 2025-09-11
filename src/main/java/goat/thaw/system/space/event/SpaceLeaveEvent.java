package goat.thaw.system.space.event;

import goat.thaw.system.space.Space;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class SpaceLeaveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Space space;

    public SpaceLeaveEvent(Player player, Space space) {
        this.player = player;
        this.space = space;
    }

    public Player getPlayer() { return player; }
    public Space getSpace() { return space; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

