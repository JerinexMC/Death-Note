package com.deathnote;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DeathNotePlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey noteKey;
    private final Map<UUID, String> pendingNames = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> executingVictims = new HashSet<>();
    private final Map<String, Arena> arenas = new HashMap<>();
    private String activeVictim;
    private BukkitTask countdownTask;

    private static final List<String> METHODS = List.of(
        "Random", "DeathBot", "Lightning Strike", "High Fall", "Drowning",
        "Wall Suffocation", "Lava Execution", "Starvation", "Freezing",
        "Void Fall", "Cactus Impalement", "Falling Anvil", "Dripstone",
        "Heart Attack", "Creeper Ambush", "Poison Curse", "Phantom Swarm",
        "Warden Sonic Boom", "TNT Minecart", "End Crystal", "Trident Strike",
        "Elytra Crash", "Ravager Charge", "Iron Golem", "Ghast Fireball",
        "Vex Swarm", "Silverfish Swarm", "Firestorm", "Meteor Strike",
        "Black Hole", "Soul Drain", "Divine Smite", "Blood Moon Curse",
        "Random Teleport", "Soul Fire", "Cursed Blindness", "Wither Curse",
        "Enderman Hunt", "Riptide Drowning", "Earthquake"
    );

    @Override public void onEnable() {
        saveDefaultConfig();
        noteKey = new NamespacedKey(this, "death_note");
        Objects.requireNonNull(getCommand("deathnote")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        restoreBackupOnStartup();
        getLogger().info("DeathNote enabled. PvpBot integration is command-based.");
    }

    @Override public void onDisable() {
        if (countdownTask != null) countdownTask.cancel();
        // Restore any arena still active before disabling.
        for (Arena arena : new ArrayList<>(arenas.values())) restoreArena(arena);
        arenas.clear();
        saveBackup();
    }

    private ItemStack createNote() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        var meta = item.getItemMeta();
        meta.setTitle(ChatColor.DARK_RED + "Death Note");
        meta.setAuthor("Shinigami");
        meta.setDisplayName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Death Note");
        meta.setLore(List.of(ChatColor.GRAY + "A name written here shall meet its end.",
                             ChatColor.DARK_GRAY + "Right-click to open."));
        meta.getPersistentDataContainer().set(noteKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isNote(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(noteKey, PersistentDataType.BYTE);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /deathnote <give|execute|methods|cancel|reload>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (!sender.hasPermission("deathnote.admin") || args.length < 2) return false;
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(ChatColor.RED + "Player is not online."); return true; }
                target.getInventory().addItem(createNote());
                sender.sendMessage(ChatColor.GREEN + "Death Note given to " + target.getName() + ".");
            }
            case "methods" -> sender.sendMessage(ChatColor.DARK_RED + "DeathNote methods: " + ChatColor.GRAY + String.join(", ", METHODS));
            case "execute" -> {
                if (!sender.hasPermission("deathnote.admin") || args.length < 2) return false;
                String method = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Random";
                execute(args[1], method, sender);
            }
            case "cancel" -> {
                if (!sender.hasPermission("deathnote.admin")) return false;
                cancelExecution();
                sender.sendMessage(ChatColor.YELLOW + "DeathNote execution cancelled and arena restored.");
            }
            case "reload" -> {
                if (!sender.hasPermission("deathnote.admin")) return false;
                reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "DeathNote config reloaded.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }
        return true;
    }

    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (!isNote(event.getItem())) return;
        if (event.getAction().isRightClick()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.DARK_RED + "Whose name shall be written? Type the player's exact username in chat.");
            pendingNames.put(event.getPlayer().getUniqueId(), "");
        }
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!pendingNames.containsKey(id)) return;
        event.setCancelled(true);
        String name = event.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (name.isBlank() || name.length() > 16 || !name.matches("[A-Za-z0-9_]+")) {
                event.getPlayer().sendMessage(ChatColor.RED + "Invalid Minecraft username. Right-click the book to try again.");
                pendingNames.remove(id);
                return;
            }
            pendingNames.put(id, name);
            openMethodMenu(event.getPlayer(), name, 0);
        });
    }

    private void openMethodMenu(Player player, String victim, int page) {
        int perPage = 45;
        int pages = (METHODS.size() + perPage - 1) / perPage;
        Inventory inv = Bukkit.createInventory(new MenuHolder(victim, page), 54, ChatColor.DARK_RED + "Death Note: " + victim);
        int start = page * perPage, end = Math.min(METHODS.size(), start + perPage);
        for (int i = start; i < end; i++) {
            String method = METHODS.get(i);
            ItemStack icon = new ItemStack(method.equals("DeathBot") ? Material.WITHER_SKELETON_SKULL :
                method.equals("Random") ? Material.ENDER_EYE : Material.PAPER);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.RED + method);
            meta.setLore(List.of(ChatColor.GRAY + "Execute " + victim, ChatColor.DARK_GRAY + "Click to select"));
            icon.setItemMeta(meta);
            inv.setItem(i - start, icon);
        }
        if (page > 0) inv.setItem(45, named(Material.ARROW, ChatColor.YELLOW + "Previous page"));
        inv.setItem(49, named(Material.BARRIER, ChatColor.RED + "Cancel"));
        if (page + 1 < pages) inv.setItem(53, named(Material.ARROW, ChatColor.YELLOW + "Next page"));
        player.openInventory(inv);
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name); item.setItemMeta(meta); return item;
    }

    @EventHandler public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot == 49) { event.getWhoClicked().closeInventory(); return; }
        if (slot == 45 && holder.page > 0) { openMethodMenu((Player) event.getWhoClicked(), holder.victim, holder.page - 1); return; }
        if (slot == 53 && (holder.page + 1) * 45 < METHODS.size()) { openMethodMenu((Player) event.getWhoClicked(), holder.victim, holder.page + 1); return; }
        int index = holder.page * 45 + slot;
        if (slot < 0 || slot >= 45 || index >= METHODS.size()) return;
        Player player = (Player) event.getWhoClicked();
        player.closeInventory();
        execute(holder.victim, METHODS.get(index), player);
    }

    private void execute(String victimName, String method, CommandSender sender) {
        Player victim = Bukkit.getPlayerExact(victimName);
        if (victim == null) { sender.sendMessage(ChatColor.RED + "That player must be online."); return; }
        if (activeVictim != null) { sender.sendMessage(ChatColor.RED + "Another DeathNote execution is already active."); return; }
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(sender instanceof Player p ? p.getUniqueId() : new UUID(0,0), 0L);
        if (sender instanceof Player p && now - last < getConfig().getLong("execution.cooldown-seconds", 30) * 1000L) {
            sender.sendMessage(ChatColor.RED + "Death Note is recharging."); return;
        }
        if (method.equalsIgnoreCase("Random")) method = METHODS.get(1 + new Random().nextInt(METHODS.size() - 1));
        if (method.equalsIgnoreCase("DeathBot")) {
            startDeathBot(victim, sender);
            if (sender instanceof Player p) cooldowns.put(p.getUniqueId(), now);
            return;
        }
        final String selected = method;
        sender.sendMessage(ChatColor.DARK_RED + "The Death Note has selected " + selected + " for " + victim.getName() + ".");
        Bukkit.getScheduler().runTaskLater(this, () -> applyMethod(victim, selected), getConfig().getLong("execution.countdown-seconds", 3) * 20L);
        if (sender instanceof Player p) cooldowns.put(p.getUniqueId(), now);
    }

    private void applyMethod(Player p, String method) {
        if (!p.isOnline() || p.isDead()) return;
        Location loc = p.getLocation();
        World w = p.getWorld();
        switch (method) {
            case "Lightning Strike", "Divine Smite" -> w.strikeLightningEffect(loc);
            case "High Fall", "Meteor Strike" -> { p.setVelocity(new Vector(0, 3.5, 0)); p.setFallDistance(0); }
            case "Drowning", "Riptide Drowning" -> p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 0, 0));
            case "Wall Suffocation" -> p.damage(1000);
            case "Lava Execution", "Soul Fire" -> p.setFireTicks(200);
            case "Starvation" -> { p.setFoodLevel(0); p.setSaturation(0); p.damage(40); }
            case "Freezing" -> { p.setFreezeTicks(p.getMaxFreezeTicks()); p.damage(1000); }
            case "Void Fall" -> p.teleport(new Location(w, loc.getX(), w.getMinHeight() - 5, loc.getZ()));
            case "Cactus Impalement", "Dripstone" -> p.damage(1000, w.spawnEntity(loc, EntityType.CACTUS)); 
            case "Falling Anvil" -> p.damage(1000);
            case "Heart Attack", "Black Hole", "Soul Drain", "Blood Moon Curse", "Wither Curse", "Poison Curse" -> p.damage(1000);
            case "Creeper Ambush", "TNT Minecart", "End Crystal", "Ghast Fireball", "Firestorm" -> {
                p.damage(1000);
            }
            case "Phantom Swarm", "Vex Swarm", "Silverfish Swarm", "Enderman Hunt", "Ravager Charge", "Iron Golem", "Warden Sonic Boom", "Trident Strike" -> p.damage(1000);
            case "Elytra Crash", "Earthquake", "Random Teleport" -> { p.setVelocity(new Vector(0, 2.5, 0)); p.damage(1000); }
            case "Cursed Blindness" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 1)); p.damage(1000); }
            default -> p.damage(1000);
        }
        if (getConfig().getBoolean("execution.broadcast", true))
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "☠ " + p.getName() + " was claimed by the Death Note: " + method + ".");
    }

    private void startDeathBot(Player victim, CommandSender sender) {
        if (!getConfig().getBoolean("deathbot.enabled", true) || !getConfig().getBoolean("arena.enabled", true)) {
            sender.sendMessage(ChatColor.RED + "DeathBot or arena is disabled in config."); return;
        }
        if (activeVictim != null) return;
        activeVictim = victim.getName();
        executingVictims.add(victim.getUniqueId());
        Location center = victim.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        Arena arena = buildArena(center);
        if (arena == null) {
            activeVictim = null; executingVictims.remove(victim.getUniqueId());
            sender.sendMessage(ChatColor.RED + "Arena exceeds configured block limit or could not be built."); return;
        }
        arenas.put(victim.getName(), arena);
        saveBackup();
        victim.teleport(center.clone().add(0, 1, 0));
        String bot = getConfig().getString("deathbot.name", "Death");
        String spawn = getConfig().getString("deathbot.spawn-command", "pvpbot spawn {name}").replace("{name}", bot).replace("{victim}", victim.getName());
        String kit = getConfig().getString("deathbot.kit-command", "pvpbot kit give {kit} {name}").replace("{name}", bot).replace("{victim}", victim.getName()).replace("{kit}", getConfig().getString("deathbot.kit", "long"));
        String tp = getConfig().getString("deathbot.teleport-command", "pvpbot tp {name} {victim}").replace("{name}", bot).replace("{victim}", victim.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), spawn);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), kit);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tp);
            sender.sendMessage(ChatColor.DARK_RED + "Death has entered the arena. The hunt begins.");
        }, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player current = Bukkit.getPlayerExact(victim.getName());
            if (current == null || current.isDead()) {
                finishDeathBot(victim.getName());
            }
        }, 20L, 20L);
    }

    private Arena buildArena(Location center) {
        World world = center.getWorld();
        int radius = getConfig().getInt("arena.radius", 30);
        Material material = Material.matchMaterial(getConfig().getString("arena.material", "BLACK_CONCRETE"));
        if (world == null || material == null) return null;
        Arena arena = new Arena(world.getName(), center.getBlockX(), center.getBlockY(), center.getBlockZ());
        int r2 = radius * radius;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (x*x + z*z > r2) continue;
            arena.add(world.getBlockAt(center.getBlockX()+x, center.getBlockY(), center.getBlockZ()+z));
            for (int y = 1; y <= radius; y++) {
                double d = x*x + z*z + y*y;
                if (d <= r2 && d >= (radius-1)*(radius-1)) {
                    arena.add(world.getBlockAt(center.getBlockX()+x, center.getBlockY()+y, center.getBlockZ()+z));
                }
            }
        }
        if (arena.original.size() > getConfig().getInt("arena.max-blocks", 12000)) return null;
        for (String key : arena.original.keySet()) {
            String[] a = key.split(",");
            world.getBlockAt(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])).setType(material, false);
        }
        return arena;
    }

    private void finishDeathBot(String victim) {
        Arena arena = arenas.remove(victim);
        String bot = getConfig().getString("deathbot.name", "Death");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), getConfig().getString("deathbot.remove-command", "pvpbot remove {name}").replace("{name}", bot));
        if (arena != null) Bukkit.getScheduler().runTaskLater(this, () -> restoreArena(arena), getConfig().getLong("arena.restore-delay-ticks", 40));
        Player p = Bukkit.getPlayerExact(victim);
        if (p != null) executingVictims.remove(p.getUniqueId());
        activeVictim = null;
        saveBackup();
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "☠ Death's execution is complete.");
    }

    private void cancelExecution() {
        if (activeVictim == null) return;
        finishDeathBot(activeVictim);
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        if (executingVictims.contains(event.getEntity().getUniqueId())) finishDeathBot(event.getEntity().getName());
    }

    private boolean insideArena(Location l) {
        for (Arena a : arenas.values()) {
            if (!a.world.equals(l.getWorld().getName())) continue;
            double dx = l.getX() - (a.x + 0.5), dz = l.getZ() - (a.z + 0.5), dy = l.getY() - a.y;
            int r = getConfig().getInt("arena.radius", 30);
            if (dx*dx + dz*dz <= r*r && dy >= -2 && dy <= r + 2) return true;
        }
        return false;
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (!getConfig().getBoolean("arena.protect-players", true) || e.getTo() == null) return;
        boolean from = insideArena(e.getFrom()), to = insideArena(e.getTo());
        if (!from && to && !executingVictims.contains(e.getPlayer().getUniqueId())) {
            e.setTo(e.getFrom()); e.getPlayer().sendMessage(ChatColor.RED + "A Death Note execution is in progress.");
        }
        if (from && !to && executingVictims.contains(e.getPlayer().getUniqueId())) {
            Location safe = e.getFrom().clone(); safe.setX(arenas.values().iterator().next().x + 0.5); safe.setZ(arenas.values().iterator().next().z + 0.5);
            e.setTo(safe);
        }
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        if (insideArena(e.getBlock().getLocation())) e.setCancelled(true);
    }
    @EventHandler public void onPlace(BlockPlaceEvent e) {
        if (insideArena(e.getBlock().getLocation())) e.setCancelled(true);
    }
    @EventHandler public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> insideArena(b.getLocation()));
    }

    private void saveBackup() {
        File f = new File(getDataFolder(), "arena-backup.yml");
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        int n = 0;
        for (Arena a : arenas.values()) {
            y.set("arenas." + n + ".world", a.world);
            y.set("arenas." + n + ".x", a.x); y.set("arenas." + n + ".y", a.y); y.set("arenas." + n + ".z", a.z);
            y.set("arenas." + n + ".blocks", a.original);
            n++;
        }
        try { y.save(f); } catch (Exception ex) { getLogger().severe("Could not save arena backup: " + ex.getMessage()); }
    }

    private void restoreBackupOnStartup() {
        File f = new File(getDataFolder(), "arena-backup.yml");
        if (!f.exists()) return;
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        for (String key : y.getConfigurationSection("arenas") == null ? Collections.<String>emptyList() : y.getConfigurationSection("arenas").getKeys(false)) {
            String path = "arenas." + key;
            World w = Bukkit.getWorld(y.getString(path + ".world", ""));
            if (w == null) continue;
            var blocks = y.getConfigurationSection(path + ".blocks");
            if (blocks == null) continue;
            for (String loc : blocks.getKeys(false)) {
                String[] xyz = loc.split(",");
                try { w.getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])).setBlockData(Bukkit.createBlockData(blocks.getString(loc)), false); }
                catch (Exception ignored) {}
            }
        }
        y.set("arenas", null);
        try { y.save(f); } catch (Exception ignored) {}
    }

    private void restoreArena(Arena arena) {
        World w = Bukkit.getWorld(arena.world);
        if (w == null) return;
        for (Map.Entry<String, String> e : arena.original.entrySet()) {
            String[] xyz = e.getKey().split(",");
            try { w.getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])).setBlockData(Bukkit.createBlockData(e.getValue()), false); }
            catch (Exception ex) { getLogger().warning("Failed restoring arena block " + e.getKey()); }
        }
        arenas.remove(arena.world + ":" + arena.x + ":" + arena.y + ":" + arena.z);
        saveBackup();
    }

    private final class Arena {
        final String world; final int x,y,z;
        final Map<String,String> original = new LinkedHashMap<>();
        Arena(String world, int x, int y, int z) { this.world=world; this.x=x; this.y=y; this.z=z; }
        void add(Block b) {
            String key = b.getX()+","+b.getY()+","+b.getZ();
            original.putIfAbsent(key, b.getBlockData().getAsString());
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        final String victim; final int page;
        MenuHolder(String victim, int page) { this.victim=victim; this.page=page; }
        @Override public Inventory getInventory() { return null; }
    }
}
