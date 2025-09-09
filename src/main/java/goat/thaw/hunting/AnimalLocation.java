package goat.thaw.hunting;

import org.bukkit.Location;

public class AnimalLocation {
    private final Location location; // target location

    public AnimalLocation(Location location) {
        this.location = location.clone();
    }

    public Location getLocation() { return location; }
}

