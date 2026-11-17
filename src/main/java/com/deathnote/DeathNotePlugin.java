
package com.deathnote;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class DeathNotePlugin extends org.bukkit.plugin.java.JavaPlugin
        implements Listener {

    private NamespacedKey bookKey;

    private final Map<UUID, DeathMethod> awaitingVictim = new HashMap<>();
    private final Map<UUID, Arena> arenas = new HashMap<>();
    private final Set<BlockKey> protectedBlocks = new HashSet<>();
    private final Map<UUID, List<String>> history = new HashMap<>();

    private enum DeathMethod {
        RANDOM,
        LIGHTNING,
        FALL,
        DROWN,
        SUFFOCATE,
        LAVA,
        STARVE,
        FREEZE,
        VOID,
        CACTUS,
        ANVIL,
        HEART,
        CREEPER,
        POISON,
        ELYTRA,
        TRIDENT,
        PHANTOM,
        WARDEN,
        TNT_MINECART,
        CRYSTAL,
        RIPTIDE,
        FIRESTORM,
        ENDERMAN,
        RAVAGER,
        GOLEM,
        WITHER,
        GHAST,
        VEX,
        SILVERFISH,
        METEOR,
        EARTHQUAKE,
        BLACK_HOLE,
        SOUL_DRAIN,
        DIVINE,
        BLOOD_MOON,
        RANDOM_TRAP,
        SOUL_FIRE,
        BLINDNESS,
        DEATHBOT
    }

    private static final int METHODS_PER_PAGE = 36;

    private static class DeathMenu implements InventoryHolder {
        private final int page;
        private Inventory inventory;

        DeathMenu(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        }
    }

    private static class Arena {
        final UUID victim;
        final Map<BlockKey, BlockState> originalBlocks =
                new LinkedHashMap<>();
        BukkitTask timeout;

        Arena(UUID victim) {
            this.victim = victim;
        }
    }

    @Override
    public void onEnable() {
        bookKey = new NamespacedKey(this, "death_note");
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("DeathNote enabled.");
    }

    @Override
    public void onDisable() {
        for (Arena arena : new ArrayList<>(arenas.values())) {
            restoreArena(arena);
        }
        getLogger().info("DeathNote disabled.");
    }

    // --------------------------------------------------
    // DEATH NOTE ITEM
    // --------------------------------------------------

    private ItemStack createDeathNote() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.DARK_RED + "Death Note");

        meta.getPersistentDataContainer().set(
                bookKey,
                PersistentDataType.BYTE,
                (byte) 1
        );

        if (meta instanceof org.bukkit.inventory.meta.BookMeta bookMeta) {
            bookMeta.setTitle("Death Note");
            bookMeta.setAuthor("Death");
            bookMeta.setPages(
                    ChatColor.DARK_RED + "DEATH NOTE\n\n" +
                    ChatColor.BLACK +
                    "Write the name of the person you wish to eliminate.\n\n" +
                    "Right-click to open the death menu."
            );
        }

        item.setItemMeta(meta);
        return item;
    }

    private boolean isDeathNote(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        Byte value = item.getItemMeta()
                .getPersistentDataContainer()
                .get(bookKey, PersistentDataType.BYTE);

        return value != null && value == (byte) 1;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (!isDeathNote(event.getItem())) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().openInventory(createMenu(0));
    }

    // --------------------------------------------------
    // GUI
    // --------------------------------------------------

    private Inventory createMenu(int page) {
        DeathMenu holder = new DeathMenu(page);

        Inventory inventory = Bukkit.createInventory(
                holder,
                54,
                ChatColor.DARK_RED + "Death Note"
        );

        holder.inventory = inventory;

        DeathMethod[] methods = DeathMethod.values();

        int start = page * METHODS_PER_PAGE;
        int end = Math.min(start + METHODS_PER_PAGE, methods.length);

        for (int i = start; i < end; i++) {
            DeathMethod method = methods[i];
            int slot = 9 + (i - start);

            ItemStack item = new ItemStack(getMethodIcon(method));
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(
                    ChatColor.RED + formatMethod(method)
            );

            meta.setLore(List.of(
                    ChatColor.GRAY + "Click to select this death method",
                    ChatColor.DARK_GRAY + "Page " + (page + 1)
            ));

            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }

        if (page > 0) {
            ItemStack previous = new ItemStack(Material.ARROW);
            ItemMeta meta = previous.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + "Previous page");
            previous.setItemMeta(meta);
            inventory.setItem(45, previous);
        }

        if (end < methods.length) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + "Next page");
            next.setItemMeta(meta);
            inventory.setItem(53, next);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();

        infoMeta.setDisplayName(ChatColor.GOLD + "Death Note");
        infoMeta.setLore(List.of(
                ChatColor.GRAY + "Choose a death method.",
                ChatColor.GRAY + "Then type the victim's username in chat."
        ));

        info.setItemMeta(infoMeta);
        inventory.setItem(4, info);

        return inventory;
    }

    private Material getMethodIcon(DeathMethod method) {
        return switch (method) {
            case RANDOM -> Material.NETHER_STAR;
            case LIGHTNING, DIVINE -> Material.LIGHTNING_ROD;
            case FALL, METEOR, ANVIL, RANDOM_TRAP -> Material.ANVIL;
            case DROWN -> Material.WATER_BUCKET;
            case SUFFOCATE -> Material.STONE;
            case LAVA, FIRESTORM, SOUL_FIRE -> Material.LAVA_BUCKET;
            case STARVE -> Material.ROTTEN_FLESH;
            case FREEZE -> Material.POWDER_SNOW_BUCKET;
            case VOID, BLACK_HOLE -> Material.ENDER_PEARL;
            case CACTUS -> Material.CACTUS;
            case HEART, BLOOD_MOON -> Material.REDSTONE;
            case CREEPER -> Material.CREEPER_HEAD;
            case POISON -> Material.SPIDER_EYE;
            case ELYTRA -> Material.ELYTRA;
            case TRIDENT, RIPTIDE -> Material.TRIDENT;
            case PHANTOM -> Material.PHANTOM_MEMBRANE;
            case WARDEN, SOUL_DRAIN -> Material.SCULK;
            case TNT_MINECART -> Material.TNT_MINECART;
            case CRYSTAL -> Material.END_CRYSTAL;
            case ENDERMAN -> Material.ENDER_PEARL;
            case RAVAGER -> Material.RAVAGER_SPAWN_EGG;
            case GOLEM -> Material.IRON_BLOCK;
            case WITHER -> Material.WITHER_SKELETON_SKULL;
            case GHAST -> Material.GHAST_TEAR;
            case VEX -> Material.ALLAY_SPAWN_EGG;
            case SILVERFISH -> Material.INFESTED_STONE;
            case EARTHQUAKE -> Material.DEEPSLATE;
            case BLINDNESS -> Material.BLACK_DYE;
            case DEATHBOT -> Material.IRON_SWORD;
        };
    }

    private String formatMethod(DeathMethod method) {
        return method.name().replace('_', ' ');
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DeathMenu menu)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= 54) {
            return;
        }

        if (slot == 45 && menu.page > 0) {
            player.openInventory(createMenu(menu.page - 1));
            return;
        }

        if (slot == 53) {
            int maxPage =
                    (DeathMethod.values().length - 1) / METHODS_PER_PAGE;

            if (menu.page < maxPage) {
                player.openInventory(createMenu(menu.page + 1));
            }
            return;
        }

        if (slot < 9 || slot > 44) {
            return;
        }

        int index = menu.page * METHODS_PER_PAGE + (slot - 9);

        if (index >= DeathMethod.values().length) {
            return;
        }

        DeathMethod method = DeathMethod.values()[index];

        awaitingVictim.put(player.getUniqueId(), method);
        player.closeInventory();

        player.sendMessage(
                ChatColor.DARK_RED + "Death Note > " +
                ChatColor.WHITE + "Selected: " +
                ChatColor.RED + formatMethod(method)
        );

        player.sendMessage(
                ChatColor.GRAY +
                "Type the victim's exact username in chat."
        );
    }

    // --------------------------------------------------
    // CHAT INPUT
    // --------------------------------------------------

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        DeathMethod method = awaitingVictim.get(player.getUniqueId());

        if (method == null) {
            return;
        }

        String victimName = event.getMessage().trim();
        event.setCancelled(true);

        Bukkit.getScheduler().runTask(this, () -> {
            if (!awaitingVictim.containsKey(player.getUniqueId())) {
                return;
            }

            awaitingVictim.remove(player.getUniqueId());

            Player victim = Bukkit.getPlayerExact(victimName);

            if (victim == null) {
                player.sendMessage(
                        ChatColor.RED + "That player is not online."
                );
                return;
            }

            if (victim.equals(player)) {
                player.sendMessage(
                        ChatColor.RED +
                        "You cannot use the Death Note on yourself."
                );
                return;
            }

            executeDeath(player, victim, method);
        });
    }

    // --------------------------------------------------
    // EXECUTION
    // --------------------------------------------------

    private void executeDeath(
            Player executor,
            Player victim,
            DeathMethod method
    ) {
        if (method == DeathMethod.RANDOM) {
            DeathMethod[] values = Arrays.stream(DeathMethod.values())
                    .filter(m -> m != DeathMethod.RANDOM)
                    .toArray(DeathMethod[]::new);

            method = values[new Random().nextInt(values.length)];
        }

        history.computeIfAbsent(
                victim.getUniqueId(),
                k -> new ArrayList<>()
        ).add(method.name() + " - " + executor.getName());

        executor.sendMessage(
                ChatColor.DARK_RED +
                "The Death Note has selected " +
                victim.getName() + "."
        );

        victim.sendTitle(
                ChatColor.DARK_RED + "DEATH NOTE",
                ChatColor.GRAY + "Your name has been written...",
                10, 60, 20
        );

        if (method == DeathMethod.DEATHBOT) {
            executeDeathBot(victim);
            return;
        }

        performDeath(victim, method);
    }

    // --------------------------------------------------
    // DEATHBOT
    // --------------------------------------------------

    private void executeDeathBot(Player victim) {
        createArena(victim);

        Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "pvpbot spawn Death"
        );

        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    "pvpbot kit give long Death"
            );
        }, 20L);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!victim.isOnline()) {
                return;
            }

            // Teleport Death to the victim using the server command.
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    "tp Death " + victim.getName()
            );

            // If Death is also registered as a Bukkit Player,
            // move it two blocks behind the victim.
            Player deathPlayer = Bukkit.getPlayerExact("Death");

            if (deathPlayer != null) {
                Location behind = victim.getLocation().clone();
                behind.add(
                        victim.getLocation().getDirection().multiply(-2)
                );
                deathPlayer.teleport(behind);
            }

            victim.sendMessage(
                    ChatColor.DARK_RED +
                    "Death has arrived. Survive."
            );
        }, 40L);
    }

    // --------------------------------------------------
    // DEATH ARENA
    // --------------------------------------------------

    private void createArena(Player victim) {
        restoreArenaForVictim(victim.getUniqueId());

        Location center = victim.getLocation().getBlock().getLocation();
        World world = center.getWorld();

        if (world == null) {
            return;
        }

        Arena arena = new Arena(victim.getUniqueId());

        int radius = 30;
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = 0; y <= radius; y++) {
                    double distance = Math.sqrt(
                            x * x + y * y + z * z
                    );

                    if (distance < radius - 1.0 ||
                            distance > radius) {
                        continue;
                    }

                    int blockY = centerY + y;

                    if (blockY <= minY || blockY >= maxY) {
                        continue;
                    }

                    Block block = world.getBlockAt(
                            centerX + x,
                            blockY,
                            centerZ + z
                    );

                    BlockKey key = BlockKey.of(block);

                    if (protectedBlocks.contains(key)) {
                        continue;
                    }

                    arena.originalBlocks.put(key, block.getState());

                    block.setType(Material.BLACK_CONCRETE, false);
                    protectedBlocks.add(key);
                }
            }
        }

        arenas.put(victim.getUniqueId(), arena);

        arena.timeout = Bukkit.getScheduler().runTaskLater(
                this,
                () -> restoreArena(arena),
                20L * 60L * 10L
        );

        victim.sendMessage(
                ChatColor.DARK_GRAY +
                "A black concrete Death Domain has surrounded you."
        );
    }

    private void restoreArenaForVictim(UUID victim) {
        Arena old = arenas.get(victim);

        if (old != null) {
            restoreArena(old);
        }
    }

    private void restoreArena(Arena arena) {
        if (arena.timeout != null) {
            arena.timeout.cancel();
        }

        for (Map.Entry<BlockKey, BlockState> entry :
                arena.originalBlocks.entrySet()) {

            BlockKey key = entry.getKey();
            protectedBlocks.remove(key);

            World world = Bukkit.getWorld(key.world());

            if (world == null) {
                continue;
            }

            entry.getValue().update(true, false);
        }

        arenas.remove(arena.victim);
    }

    @EventHandler
    public void onVictimDeath(PlayerDeathEvent event) {
        restoreArenaForVictim(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onVictimQuit(PlayerQuitEvent event) {
        restoreArenaForVictim(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (protectedBlocks.contains(BlockKey.of(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (protectedBlocks.contains(BlockKey.of(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(
                block -> protectedBlocks.contains(BlockKey.of(block))
        );
    }

    // --------------------------------------------------
    // DEATH METHODS
    // --------------------------------------------------

    private void performDeath(Player target, DeathMethod method) {
        Location location = target.getLocation();
        World world = target.getWorld();

        switch (method) {
            case LIGHTNING, DIVINE -> {
                world.strikeLightningEffect(location);
                kill(target);
            }

            case FALL, METEOR -> {
                world.spawnParticle(
                        Particle.EXPLOSION,
                        location,
                        20
                );

                target.setVelocity(target.getVelocity().setY(2.5));
                Bukkit.getScheduler().runTaskLater(
                        this,
                        () -> kill(target),
                        35L
                );
            }

            case DROWN -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.WATER_BREATHING,
                        1,
                        0
                ));
                target.damage(1000);
            }

            case SUFFOCATE -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        100,
                        10
                ));
                kill(target);
            }

            case LAVA, FIRESTORM -> {
                target.setFireTicks(100);
                world.spawnParticle(Particle.FLAME, location, 80);
                kill(target);
            }

            case STARVE -> {
                target.setFoodLevel(0);
                target.setSaturation(0);
                kill(target);
            }

            case FREEZE -> {
                target.setFreezeTicks(400);
                world.spawnParticle(Particle.SNOWFLAKE, location, 60);
                kill(target);
            }

            case VOID, BLACK_HOLE -> {
                target.setVelocity(target.getVelocity().setY(-5));
                world.spawnParticle(Particle.PORTAL, location, 100);
                kill(target);
            }

            case CACTUS -> {
                world.spawnParticle(Particle.CRIT, location, 50);
                target.damage(1000);
            }

            case ANVIL, RANDOM_TRAP -> {
                world.spawnParticle(
                        Particle.BLOCK,
                        location,
                        60,
                        Material.ANVIL.createBlockData()
                );
                kill(target);
            }

            case HEART, BLOOD_MOON -> {
                world.spawnParticle(
                        Particle.DAMAGE_INDICATOR,
                        location,
                        50
                );
                target.damage(1000);
            }

            case CREEPER -> {
                world.spawnEntity(location, EntityType.CREEPER);
                world.createExplosion(location, 4.0f, false, false);
                kill(target);
            }

            case POISON -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.POISON,
                        200,
                        10
                ));
                kill(target);
            }

            case ELYTRA -> {
                target.setVelocity(target.getVelocity().setY(5));
                Bukkit.getScheduler().runTaskLater(
                        this,
                        () -> kill(target),
                        30L
                );
            }

            case TRIDENT, RIPTIDE -> {
                world.spawnParticle(Particle.SPLASH, location, 80);
                target.damage(1000);
            }

            case PHANTOM -> {
                world.spawnEntity(location, EntityType.PHANTOM);
                kill(target);
            }

            case WARDEN -> {
                world.spawnEntity(location, EntityType.WARDEN);
                kill(target);
            }

            case TNT_MINECART -> {
                world.createExplosion(location, 5.0f, false, false);
                kill(target);
            }

            case CRYSTAL -> {
                world.spawnParticle(Particle.EXPLOSION, location, 100);
                world.createExplosion(location, 6.0f, false, false);
                kill(target);
            }

            case ENDERMAN -> {
                world.spawnEntity(location, EntityType.ENDERMAN);
                kill(target);
            }

            case RAVAGER -> {
                world.spawnEntity(location, EntityType.RAVAGER);
                kill(target);
            }

            case GOLEM -> {
                world.spawnEntity(location, EntityType.IRON_GOLEM);
                kill(target);
            }

            case WITHER -> {
                world.spawnEntity(location, EntityType.WITHER);
                kill(target);
            }

            case GHAST -> {
                world.spawnEntity(location, EntityType.GHAST);
                kill(target);
            }

            case VEX -> {
                world.spawnEntity(location, EntityType.VEX);
                kill(target);
            }

            case SILVERFISH -> {
                world.spawnEntity(location, EntityType.SILVERFISH);
                kill(target);
            }

            case EARTHQUAKE -> {
                world.spawnParticle(
                        Particle.BLOCK,
                        location,
                        100,
                        Material.DEEPSLATE.createBlockData()
                );
                target.setVelocity(target.getVelocity().setY(2));
                kill(target);
            }

            case SOUL_DRAIN -> {
                world.spawnParticle(Particle.SOUL, location, 100);
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.WITHER,
                        200,
                        5
                ));
                kill(target);
            }

            case SOUL_FIRE -> {
                world.spawnParticle(
                        Particle.SOUL_FIRE_FLAME,
                        location,
                        100
                );
                target.setFireTicks(200);
                kill(target);
            }

            case BLINDNESS -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.BLINDNESS,
                        200,
                        1
                ));
                kill(target);
            }

            case RANDOM, DEATHBOT -> kill(target);
        }
    }

    private void kill(Player target) {
        if (target.isOnline() && !target.isDead()) {
            target.setHealth(0);
        }
    }

    // --------------------------------------------------
    // COMMANDS
    // --------------------------------------------------

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("deathnote")) {
            return false;
        }

        if (!sender.hasPermission("deathnote.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED +
                    "/deathnote give <player>");
            sender.sendMessage(ChatColor.RED +
                    "/deathnote execute <victim> <method>");
            sender.sendMessage(ChatColor.RED +
                    "/deathnote methods");
            sender.sendMessage(ChatColor.RED +
                    "/deathnote history <player>");
            sender.sendMessage(ChatColor.RED +
                    "/deathnote cancel <player>");
            sender.sendMessage(ChatColor.RED +
                    "/deathnote reload");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (args.length < 2) {
                    sender.sendMessage(
                            ChatColor.RED +
                            "Usage: /deathnote give <player>"
                    );
                    return true;
                }

                Player player = Bukkit.getPlayerExact(args[1]);

                if (player == null) {
                    sender.sendMessage(
                            ChatColor.RED + "Player not found."
                    );
                    return true;
                }

                player.getInventory().addItem(createDeathNote());

                sender.sendMessage(
                        ChatColor.GREEN +
                        "Gave Death Note to " + player.getName()
                );
            }

            case "methods" -> {
                sender.sendMessage(
                        ChatColor.DARK_RED + "Death Note methods:"
                );

                for (DeathMethod method : DeathMethod.values()) {
                    sender.sendMessage(
                            ChatColor.GRAY + "- " + formatMethod(method)
                    );
                }
            }

            case "execute" -> {
                if (args.length < 3) {
                    sender.sendMessage(
                            ChatColor.RED +
                            "Usage: /deathnote execute <victim> <method>"
                    );
                    return true;
                }

                Player victim = Bukkit.getPlayerExact(args[1]);

                if (victim == null) {
                    sender.sendMessage(
                            ChatColor.RED + "Victim is not online."
                    );
                    return true;
                }

                DeathMethod method;

                try {
                    method = DeathMethod.valueOf(
                            args[2].toUpperCase(Locale.ROOT)
                                    .replace(' ', '_')
                    );
                } catch (IllegalArgumentException exception) {
                    sender.sendMessage(
                            ChatColor.RED + "Unknown death method."
                    );
                    return true;
                }

                Player executor =
                        sender instanceof Player p ? p : victim;

                executeDeath(executor, victim, method);
            }

            case "history" -> {
                if (args.length < 2) {
                    sender.sendMessage(
                            ChatColor.RED +
                            "Usage: /deathnote history <player>"
                    );
                    return true;
                }

                Player player = Bukkit.getPlayerExact(args[1]);

                if (player == null) {
                    sender.sendMessage(
                            ChatColor.RED + "Player must be online."
                    );
                    return true;
                }

                List<String> entries =
                        history.get(player.getUniqueId());

                sender.sendMessage(
                        ChatColor.DARK_RED +
                        "Death history for " + player.getName()
                );

                if (entries == null || entries.isEmpty()) {
                    sender.sendMessage(
                            ChatColor.GRAY + "No history."
                    );
                } else {
                    for (String entry : entries) {
                        sender.sendMessage(
                                ChatColor.GRAY + "- " + entry
                        );
                    }
                }
            }

            case "cancel" -> {
                if (args.length < 2) {
                    sender.sendMessage(
                            ChatColor.RED +
                            "Usage: /deathnote cancel <player>"
                    );
                    return true;
                }

                Player player = Bukkit.getPlayerExact(args[1]);

                if (player == null) {
                    sender.sendMessage(
                            ChatColor.RED + "Player not found."
                    );
                    return true;
                }

                awaitingVictim.remove(player.getUniqueId());
                restoreArenaForVictim(player.getUniqueId());

                sender.sendMessage(
                        ChatColor.GREEN +
                        "Cancelled Death Note execution for " +
                        player.getName()
                );
            }

            case "reload" -> {
                reloadConfig();
                sender.sendMessage(
                        ChatColor.GREEN + "DeathNote reloaded."
                );
            }

            default -> sender.sendMessage(
                    ChatColor.RED + "Unknown subcommand."
            );
        }

        return true;
    }
}
