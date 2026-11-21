package kr.nightbeam.deathrules;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DeathRulesPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingEffects = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("DeathRules enabled.");
    }

    @Override
    public void onDisable() {
        combatUntil.clear();
        pendingEffects.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        long until = System.currentTimeMillis() + getConfig().getLong("combat-tag-seconds", 15) * 1000L;
        combatUntil.put(victim.getUniqueId(), until);
        combatUntil.put(attacker.getUniqueId(), until);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        var inventory = player.getInventory();

        // Keep the selected equipment in the actual inventory; manually drop only slots 9-35.
        event.setKeepInventory(true);
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                Location at = player.getLocation();
                player.getWorld().dropItemNaturally(at, item.clone());
                inventory.setItem(slot, null);
            }
        }
        event.getDrops().clear();

        pendingEffects.put(player.getUniqueId(), getConfig().getLong("respawn-effects-seconds", 120));
        combatUntil.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Long seconds = pendingEffects.remove(id);
        if (seconds == null || seconds <= 0) return;
        int durationTicks = (int) Math.min(Integer.MAX_VALUE, seconds * 20L);
        int amplifier = Math.max(0, getConfig().getInt("respawn-effects-amplifier", 0));
        UUID playerId = id;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, amplifier, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, durationTicks, amplifier, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, durationTicks, amplifier, false, true, true));
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Long until = combatUntil.get(player.getUniqueId());
        if (until == null) return;
        if (until <= System.currentTimeMillis()) {
            combatUntil.remove(player.getUniqueId());
            return;
        }
        // Trigger the normal death pipeline while the player is still present, so the same
        // inventory retention and respawn penalties are applied to combat logging.
        if (player.isOnline() && player.getHealth() > 0.0) {
            player.setHealth(0.0);
        }
        combatUntil.remove(player.getUniqueId());
    }

    private Player resolvePlayer(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        return null;
    }
}
