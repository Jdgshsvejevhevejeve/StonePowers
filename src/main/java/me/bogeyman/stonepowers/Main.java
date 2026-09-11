package me.bogeyman.stonepowers;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

public class Main extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Random random = new Random();
    private NamespacedKey stoneKey;
    private final List<String> stones = List.of("FIRE","ICE","ENDER","LIGHTNING","WATER","EARTH","WIND","WITHER");

    private static final double START_HEARTS = 10.0;   // 10 hearts = 20 health
    private static final double MAX_HEARTS = 20.0;       // 20 hearts = 40 health
    private static final double HEARTS_PER_KILL = 1.0;   // killer gains 1 heart
    private static final double HEARTS_LOST_ON_DEATH = 1.0;

    private NamespacedKey heartsKey;

    private void initPlayer(Player p) {
        if (!p.getPersistentDataContainer().has(heartsKey, PersistentDataType.DOUBLE)) {
            setHearts(p, START_HEARTS);
        }
        applyHearts(p);
    }

    private double getHearts(Player p) {
        Double h = p.getPersistentDataContainer().get(heartsKey, PersistentDataType.DOUBLE);
        return h == null ? START_HEARTS : h;
    }

    private void setHearts(Player p, double hearts) {
        double value = Math.max(1.0, Math.min(MAX_HEARTS, hearts));
        p.getPersistentDataContainer().set(heartsKey, PersistentDataType.DOUBLE, value);
        applyHearts(p);
    }

    private void applyHearts(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(getHearts(p) * 2.0);
            if (p.getHealth() > attr.getValue()) p.setHealth(attr.getValue());
        }
    }

    private void showHearts(Player p) {
        p.sendMessage(color("&c❤ &fHearts: &c" + format(getHearts(p)) + " &7/ &c" + format(MAX_HEARTS)));
    }

    private String format(double value) {
        return value == Math.rint(value) ? String.valueOf((int)value) : String.format(Locale.US, "%.1f", value);
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        stoneKey = new NamespacedKey(this, "stone");
        heartsKey = new NamespacedKey(this, "hearts");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("stone")).setExecutor(this);
        Objects.requireNonNull(getCommand("stone")).setTabCompleter(this);
        getLogger().info("Stone Powers SMP enabled.");
    }

    @Override public void onDisable() {}

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        initPlayer(p);
        if (!p.hasPlayedBefore() || getStone(p) == null) {
            String stone = stones.get(random.nextInt(stones.size()));
            setStone(p, stone);
            giveStoneItem(p, stone);
            p.sendMessage(color("&8[&bStone Powers&8] &aYour first stone is &f" + pretty(stone) + "&a!"));
            p.sendMessage(color("&7Use &f/stone info &7to see your power."));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick()) return;
        ItemStack item = e.getItem();
        if (item == null || !isStoneItem(item)) return;
        Player p = e.getPlayer();
        String stone = item.getItemMeta().getPersistentDataContainer().get(stoneKey, PersistentDataType.STRING);
        if (stone == null) return;
        activate(p, stone);
        e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof Player target)) return;
        String stone = getStone(p);
        if ("FIRE".equals(stone)) target.setFireTicks(Math.max(target.getFireTicks(), 60));
        if ("ICE".equals(stone)) target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
        if ("LIGHTNING".equals(stone) && random.nextInt(10) == 0)
            target.getWorld().strikeLightningEffect(target.getLocation());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) return;

        initPlayer(killer);
        initPlayer(dead);

        // Heart transfer: killer gains a heart, victim loses a heart.
        double victimHearts = getHearts(dead);
        double killerHearts = getHearts(killer);

        setHearts(dead, victimHearts - HEARTS_LOST_ON_DEATH);
        setHearts(killer, killerHearts + HEARTS_PER_KILL);

        killer.sendMessage(color("&c❤ &aYou gained &c1 heart&a! &7Now: &c" + format(getHearts(killer)) + " ❤"));
        dead.sendMessage(color("&c❤ &cYou lost 1 heart. &7Now: &c" + format(getHearts(dead)) + " ❤"));

        String victimStone = getStone(dead);
        if (victimStone == null) return;

        // Stone transfers on a player elimination.
        if (getConfig().getBoolean("transfer-on-kill", true)) {
            setStone(killer, victimStone);
            removeAllStoneItems(killer);
            giveStoneItem(killer, victimStone);
            dead.getPersistentDataContainer().remove(stoneKey);
            killer.sendMessage(color("&bYou claimed &f" + pretty(victimStone) + "&b."));
        }
    }

    private void activate(Player p, String stone) {
        switch (stone) {
            case "FIRE" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 240, 0));
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0,1,0), 35, .6,.7,.6,.02);
            }
            case "ICE" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0));
                p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0,1,0), 50, .8,.8,.8,.03);
            }
            case "ENDER" -> {
                Location target = p.getTargetBlockExact(12) != null
                        ? p.getTargetBlockExact(12).getLocation().add(.5,1,.5) : p.getLocation();
                p.teleport(target);
                p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation(), 80, .5,.8,.5,.2);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
            }
            case "LIGHTNING" -> {
                Location l = p.getTargetBlockExact(18) != null
                        ? p.getTargetBlockExact(18).getLocation() : p.getLocation();
                p.getWorld().strikeLightningEffect(l);
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, l.add(.5,1,.5), 60, .7,.7,.7,.05);
            }
            case "WATER" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 400, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 200, 0));
                p.getWorld().spawnParticle(Particle.SPLASH, p.getLocation().add(0,1,0), 45, .6,.7,.6,.1);
            }
            case "EARTH" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 240, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 0));
                p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation().add(0,1,0), 50, .7,.8,.7,.05, Material.STONE.createBlockData());
            }
            case "WIND" -> {
                Vector v = p.getLocation().getDirection().normalize().multiply(1.8);
                v.setY(.7);
                p.setVelocity(v);
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 50, .7,.7,.7,.08);
            }
            case "WITHER" -> {
                for (Player target : p.getWorld().getPlayers()) {
                    if (target != p && target.getLocation().distance(p.getLocation()) <= 6) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 0));
                    }
                }
                p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0,1,0), 70, 1,1,1,.04);
            }
        }
        p.setCooldown(Material.ECHO_SHARD, getConfig().getInt("ability-cooldown-ticks", 100));
    }

    private boolean isStoneItem(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(stoneKey, PersistentDataType.STRING);
    }

    private void giveStoneItem(Player p, String stone) {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&b" + pretty(stone) + " Stone"));
        meta.setCustomModelData(stones.indexOf(stone) + 1);
        meta.getPersistentDataContainer().set(stoneKey, PersistentDataType.STRING, stone);
        item.setItemMeta(meta);
        p.getInventory().addItem(item);
    }

    private void removeAllStoneItems(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && isStoneItem(it)) p.getInventory().setItem(i, null);
        }
    }

    private String getStone(Player p) {
        return p.getPersistentDataContainer().get(stoneKey, PersistentDataType.STRING);
    }

    private void setStone(Player p, String stone) {
        p.getPersistentDataContainer().set(stoneKey, PersistentDataType.STRING, stone);
    }

    private String pretty(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase() + " Stone";
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void info(Player p) {
        String stone = getStone(p);
        p.sendMessage(color("&8&m------------------------------"));
        p.sendMessage(color("&b&lSTONE POWERS"));
        p.sendMessage(color("&7Your stone: &f" + (stone == null ? "None" : pretty(stone))));
        p.sendMessage(color("&7Your hearts: &c" + format(getHearts(p)) + " ❤"));
        p.sendMessage(color("&7Right-click your stone to use its ability."));
        p.sendMessage(color("&8&m------------------------------"));
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!cmd.getName().equalsIgnoreCase("stone")) return false;

        if (a.length == 0 || a[0].equalsIgnoreCase("info")) {
            if (sender instanceof Player p) info(p);
            else sender.sendMessage("Stone Powers SMP");
            return true;
        }

        if (a[0].equalsIgnoreCase("hearts")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only a player can use this.");
                return true;
            }
            initPlayer(p);
            showHearts(p);
            return true;
        }

        if (!sender.hasPermission("stonepowers.admin")) {
            sender.sendMessage(color("&cNo permission."));
            return true;
        }

        if (a[0].equalsIgnoreCase("sethearts") && a.length >= 3) {
            Player target = Bukkit.getPlayerExact(a[1]);
            try {
                double hearts = Double.parseDouble(a[2]);
                if (target == null || hearts < 1 || hearts > MAX_HEARTS) {
                    sender.sendMessage(color("&cPlayer not found or hearts must be 1-" + format(MAX_HEARTS) + "."));
                    return true;
                }
                setHearts(target, hearts);
                sender.sendMessage(color("&aSet " + target.getName() + "'s hearts to &c" + format(hearts) + " ❤"));
            } catch (NumberFormatException ex) {
                sender.sendMessage(color("&cInvalid number."));
            }
            return true;
        }

        if (a[0].equalsIgnoreCase("resethearts") && a.length >= 2) {
            Player target = Bukkit.getPlayerExact(a[1]);
            if (target == null) {
                sender.sendMessage(color("&cPlayer not found."));
                return true;
            }
            setHearts(target, START_HEARTS);
            sender.sendMessage(color("&aReset " + target.getName() + "'s hearts to &c" + format(START_HEARTS) + " ❤"));
            return true;
        }

        if (a[0].equalsIgnoreCase("giveall")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only a player can use this."); return true; }
            removeAllStoneItems(p);
            for (String s : stones) giveStoneItem(p, s);
            p.sendMessage(color("&aYou received all " + stones.size() + " stones."));
            return true;
        }

        if (a[0].equalsIgnoreCase("give") && a.length >= 3) {
            Player target = Bukkit.getPlayerExact(a[1]);
            String stone = a[2].toUpperCase();
            if (target == null || !stones.contains(stone)) {
                sender.sendMessage(color("&cUsage: /stone give <player> <stone>"));
                return true;
            }
            setStone(target, stone);
            removeAllStoneItems(target);
            giveStoneItem(target, stone);
            sender.sendMessage(color("&aGave " + pretty(stone) + " to " + target.getName()));
            return true;
        }

        if (a[0].equalsIgnoreCase("reroll") && a.length >= 2) {
            Player target = Bukkit.getPlayerExact(a[1]);
            if (target == null) { sender.sendMessage(color("&cPlayer not found.")); return true; }
            String stone = stones.get(random.nextInt(stones.size()));
            setStone(target, stone);
            removeAllStoneItems(target);
            giveStoneItem(target, stone);
            sender.sendMessage(color("&aRerolled " + target.getName() + " to " + pretty(stone)));
            return true;
        }

        if (a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(color("&aConfig reloaded."));
            return true;
        }

        sender.sendMessage(color("&7/stone info"));
        sender.sendMessage(color("&7/stone hearts"));
        sender.sendMessage(color("&7/stone giveall"));
        sender.sendMessage(color("&7/stone give <player> <stone>"));
        sender.sendMessage(color("&7/stone reroll <player>"));
        sender.sendMessage(color("&7/stone sethearts <player> <amount>"));
        sender.sendMessage(color("&7/stone resethearts <player>"));
        sender.sendMessage(color("&7/stone reload"));
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("info","hearts","giveall","give","reroll","sethearts","resethearts","reload");
        if (a.length == 3 && a[0].equalsIgnoreCase("give")) return stones;
        return Collections.emptyList();
    }
}
