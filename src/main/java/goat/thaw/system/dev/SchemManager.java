package goat.thaw.system.dev;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.bukkit.BukkitAdapter;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;

public class SchemManager {

    private final JavaPlugin plugin;

    public SchemManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads a schematic from resources/schematics/<name>.schem and pastes it
     * at the given Bukkit Location.
     *
     * @param name The base name of the schematic (without “.schem”).
     * @param loc  The Bukkit Location to paste at.
     */
    public void placeStructure(String name, Location loc) {
        String resourcePath = "schematics/" + name + ".schem";

        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is == null) {
                plugin.getLogger().severe("Schematic not found: " + resourcePath);
                return;
            }

            // detect format by file-extension alias ("schem")
            String ext = resourcePath.substring(resourcePath.lastIndexOf('.') + 1);
            ClipboardFormat format = ClipboardFormats.findByAlias(ext);
            if (format == null) {
                plugin.getLogger().severe("Unrecognized schematic format: " + ext);
                return;
            }

            // read the clipboard
            try (ClipboardReader reader = format.getReader(is)) {
                Clipboard clipboard = reader.read();

                // perform the paste
                var world = BukkitAdapter.adapt(loc.getWorld());
                try (EditSession editSession = WorldEdit.getInstance().newEditSession(world)) {
                    Operation pasteOp = new ClipboardHolder(clipboard)
                            .createPaste(editSession)
                            .to(BukkitAdapter.asBlockVector(loc))
                            .ignoreAirBlocks(false)
                            .build();
                    Operations.complete(pasteOp);
                    editSession.flushSession();
                }
            }

            plugin.getLogger().info("Pasted schematic “" + name + "” at " + loc);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to paste schematic “" + name + "”: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
