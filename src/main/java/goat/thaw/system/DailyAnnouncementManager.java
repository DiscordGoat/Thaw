package goat.thaw.system;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class DailyAnnouncementManager {

    public void announceNewDay(long dayCount, double monsterPopulation) {
        String title = ChatColor.GOLD + "Day " + dayCount;
        String subtitle = ChatColor.RED + "Monster population increased to "
                + ChatColor.WHITE + String.format("%.1f", monsterPopulation);
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.sendTitle(title, subtitle, 10, 60, 10);
            } catch (Throwable ignore) {
                p.sendMessage(title + " - " + subtitle);
            }
        }
    }
}

