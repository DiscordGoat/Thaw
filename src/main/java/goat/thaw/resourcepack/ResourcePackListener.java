package goat.thaw.resourcepack;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class ResourcePackListener implements Listener {

    private static final String PACK_URL =
            "https://discordgoat.github.io/Continuity/continuity.zip";
    private static final String PACK_HASH_HEX =
            "34854302fcf7b5855c93800dace5f9ddb483b90c";
    private static final byte[] PACK_HASH =
            hexStringToByteArray(PACK_HASH_HEX);

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String prompt = ChatColor.translateAlternateColorCodes(
                '&',
                "&aLatest Continuity Textures"
        );
        player.setResourcePack(
                PACK_URL,
                PACK_HASH,
                prompt,
                true
        );
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }
}

