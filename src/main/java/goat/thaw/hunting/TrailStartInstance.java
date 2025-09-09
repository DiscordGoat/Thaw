package goat.thaw.hunting;

import org.bukkit.Location;

import java.util.UUID;

public class TrailStartInstance {
    private final UUID owner; // may be null for global
    private final Location blockLocation; // snapped to block

    public TrailStartInstance(UUID owner, Location blockLocation) {
        this.owner = owner;
        this.blockLocation = blockLocation.clone();
        this.blockLocation.setX(blockLocation.getBlockX());
        this.blockLocation.setY(blockLocation.getBlockY());
        this.blockLocation.setZ(blockLocation.getBlockZ());
    }

    public UUID getOwner() { return owner; }
    public Location getBlockLocation() { return blockLocation; }
}

