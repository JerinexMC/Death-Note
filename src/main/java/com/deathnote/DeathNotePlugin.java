
package com.deathnote;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public final class DeathNotePlugin extends JavaPlugin
        implements Listener, CommandExecutor {

    private NamespacedKey bookKey;

    private final Map<UUID, String> waitingForVictim = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> scheduledDeaths = new HashMap<>();
    private final Map<UUID, List<String>> history = new HashMap<>();

    private static final String GUI_TITLE = "§0§lDEATH NOTE";
    private static final String BOOK_NAME = "§0§lDeath Note";

    private enum DeathMethod {
        RANDOM("Random Death", Material.NETHER_STAR),
        LIGHTNING("Lightning Strike", Material.LIGHTNING_ROD),
        FALL("Extreme Fall", Material.FEATHER),
        DROWN("Drowning", Material.WATER_BUCKET),
        SUFFOCATE("Wall Suffocation", Material.STONE),
        LAVA("Lava Execution", Material.LAVA_BUCKET),
        STARVE("Starvation", Material.ROTTEN_FLESH),
        FREEZE("Freezing", Material.POWDER_SNOW_BUCKET),
        VOID("Void Execution", Material.ENDER_PEARL),
        CACTUS("Cactus Impalement", Material.CACTUS),
        ANVIL("Falling Anvil", Material.ANVIL),
        HEART("Heart Attack", Material.REDSTONE),
        CREEPER("Creeper Ambush", Material.CREEPER_HEAD),
        POISON("Deadly Poison", Material.SPIDER_EYE),
        ELYTRA("Elytra Crash", Material.ELYTRA),
        TRIDENT("Trident Strike", Material.TRIDENT),
        PHANTOM("Phantom Swarm", Material.PHANTOM_MEMBRANE),
        WARDEN("Warden Sonic Boom", Material.SCULK_SHRIEKER),
        TNT_MINECART("TNT Minecart", Material.TNT_MINECART),
        CRYSTAL("End Crystal", Material.END_CRYSTAL),
        RIPTIDE("Riptide Execution", Material.TRIDENT),
        FIRESTORM("Firestorm", Material.BLAZE_POWDER),
        ENDERMAN("Enderman Execution", Material.ENDER_PEARL),
        RAVAGER("Ravager Charge", Material.RAVAGER_SPAWN_EGG),
        GOLEM("Iron Golem Attack", Material.IRON_BLOCK),
        WITHER("Wither Execution", Material.WITHER_SKELETON_SKULL),
        GHAST("Ghast Fireball", Material.GHAST_TEAR),
        VEX("Vex Swarm", Material.IRON_SWORD),
        SILVERFISH("Silverfish Infestation", Material.INFESTED_STONE),
        METEOR("Meteor Strike", Material.FIRE_CHARGE),
        EARTHQUAKE("Earthquake", Material.DEEPSLATE),
        BLACK_HOLE("Black Hole", Material.DRAGON_EGG),
        SOUL_DRAIN("Soul Drain", Material.SOUL_SAND),
        DIVINE("Divine Smite", Material.NETHER_STAR),
        BLOOD_MOON("Blood Moon Curse", Material.REDSTONE_BLOCK),
        RANDOM_TRAP("Random Teleport Trap", Material.COBWEB),
        SOUL_FIRE("Soul Fire Execution", Material.SOUL_FIRE),
        BLINDNESS("Cursed Blindness", Material.BLACK_DYE),
        DEATHBOT("DeathBot", Material.NETHERITE_SWORD);

        final String display;
        final Material icon;

        DeathMethod(String display, Material icon) {
            this.display = display;
            this.icon = icon;
        }
    }

    @Override
    public void onEnable() {
        bookKey = new NamespacedKey(this, "death_note");

        getConfig().addDefault("cooldown-seconds", 30);
        getConfig().addDefault("death-delay-seconds", 2);
        getConfig().addDefault("deathbot.enabled", true);
        getConfig().addDefault(
                "deathbot.spawn-command",
                "pvpbot spawn Death %player%"
        );
        getConfig().addDefault(
                "deathbot.kit-command",
                "pvpbot kit give long Death"
        );
        getConfig().options().copyDefaults(true);
        saveConfig();

        if (getCommand("deathnote") != null) {
            getCommand("deathnote").setExecutor(this);
        } else {
            getLogger().severe("Missing deathnote command in plugin.yml!");
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DeathNote enabled!");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : scheduledDeaths.values()) {
            task.cancel();
        }
        scheduledDeaths.clear();
    }

    // =====================================================
    // DEATH NOTE ITEM
    // =====================================================

    private ItemStack createBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();

        if (meta == null) {
            return book;
        }

        meta.setTitle("Death Note");
        meta.setAuthor("Ryuk");
        meta.setDisplayName(BOOK_NAME);
        meta.setLore(Arrays.asList(
                "§8A notebook of death.",
                "§7Write a person's name.",
                "§7Choose how their story ends.",
                "",
                "§cRight-click to open."
        ));

        meta.addEnchant(Enchantment.UNBREAKING, 1, true);

        meta.getPersistentDataContainer().set(
                bookKey,
                PersistentDataType.BYTE,
                (byte) 1
        );

        meta.setPages(
                "§0DEATH NOTE\n\n" +
                "§8Write the name of the person whose fate you wish to decide.\n\n" +
                "§7Their story ends when their name is written."
        );

        book.setItemMeta(meta);
        return book;
    }

    private boolean isDeathNote(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK) {
            return false;
        }

        if (!(item.getItemMeta() instanceof BookMeta meta)) {
            return false;
        }

        return meta.getPersistentDataContainer().has(
                bookKey,
                PersistentDataType.BYTE
        );
    }

    @EventHandler
    public void onBookUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (!isDeathNote(event.getItem())) {
            return;
        }

        event.setCancelled(true);
        openMenu(event.getPlayer());
    }

    // =====================================================
    // GUI
    // =====================================================

    private void openMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_TITLE);

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();

        if (fillerMeta != null) {
            fillerMeta.setDisplayName("§0");
            filler.setItemMeta(fillerMeta);
        }

        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        addGuiItem(
                inventory,
                4,
                Material.NETHER_STAR,
                "§c§lRANDOM DEATH",
                "§7Choose a random execution method."
        );

        int slot = 9;

        for (DeathMethod method : DeathMethod.values()) {
            if (method == DeathMethod.RANDOM) {
                continue;
            }

            if (slot >= 45) {
                break;
            }

            addGuiItem(
                    inventory,
                    slot,
                    method.icon,
                    "§c" + method.display,
                    "§7Click to select this method."
            );

            slot++;
        }

        addGuiItem(
                inventory,
                49,
                Material.BARRIER,
                "§cClose",
                "§7Close the Death Note."
        );

        player.openInventory(inventory);
    }

    private void addGuiItem(
            Inventory inventory,
            int slot,
            Material material,
            String name,
            String lore
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Collections.singletonList(lore));
            item.setItemMeta(meta);
        }

        inventory.setItem(slot, item);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) {
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

        if (slot == 49) {
            player.closeInventory();
            return;
        }

        if (slot == 4) {
            selectMethod(player, DeathMethod.RANDOM);
            return;
        }

        List<DeathMethod> selectable = new ArrayList<>();

        for (DeathMethod method : DeathMethod.values()) {
            if (method != DeathMethod.RANDOM) {
                selectable.add(method);
            }
        }

        int methodIndex = slot - 9;

        if (methodIndex < 0 || methodIndex >= selectable.size()) {
            return;
        }

        selectMethod(player, selectable.get(methodIndex));
    }

    private void selectMethod(Player player, DeathMethod method) {
        waitingForVictim.put(player.getUniqueId(), method.name());
        player.closeInventory();

        player.sendMessage("§8§m--------------------------------");
        player.sendMessage("§c§lDEATH NOTE");
        player.sendMessage("§7Selected: §c" + method.display);
        player.sendMessage("§7Type the victim's exact Minecraft username in chat.");
        player.sendMessage("§7Type §ccancel §7to cancel.");
        player.sendMessage("§8§m--------------------------------");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!waitingForVictim.containsKey(uuid)) {
            return;
        }

        event.setCancelled(true);

        String victimName = event.getMessage().trim();
        String methodName = waitingForVictim.remove(uuid);

        Bukkit.getScheduler().runTask(this, () -> {
            if (victimName.equalsIgnoreCase("cancel")) {
                player.sendMessage("§cDeath Note execution cancelled.");
                return;
            }

            DeathMethod method;

            try {
                method = DeathMethod.valueOf(methodName);
            } catch (IllegalArgumentException exception) {
                player.sendMessage("§cInvalid death method.");
                return;
            }

            executeDeath(player, victimName, method);
        });
    }

    // =====================================================
    // COMMANDS
    // =====================================================

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§8§m--------------------------------");
            sender.sendMessage("§c§lDEATH NOTE COMMANDS");
            sender.sendMessage("§7/deathnote give <player>");
            sender.sendMessage("§7/deathnote execute <victim>");
            sender.sendMessage("§7/deathnote methods");
            sender.sendMessage("§7/deathnote history <player>");
            sender.sendMessage("§7/deathnote cancel <victim>");
            sender.sendMessage("§7/deathnote reload");
            sender.sendMessage("§8§m--------------------------------");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 2) {
                sender.sendMessage("§c/deathnote give <player>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);

            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }

            Map<Integer, ItemStack> leftovers =
                    target.getInventory().addItem(createBook());

            leftovers.values().forEach(item ->
                    target.getWorld().dropItemNaturally(
                            target.getLocation(),
                            item
                    )
            );

            sender.sendMessage(
                    "§aDeath Note given to " + target.getName() + "."
            );
            return true;
        }

        if (args[0].equalsIgnoreCase("execute")) {
            if (args.length < 2) {
                sender.sendMessage("§c/deathnote execute <victim>");
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }

            executeDeath(player, args[1], DeathMethod.RANDOM);
            return true;
        }

        if (args[0].equalsIgnoreCase("methods")) {
            sender.sendMessage("§c§lDEATH NOTE METHODS:");

            for (DeathMethod method : DeathMethod.values()) {
                sender.sendMessage("§7- §c" + method.display);
            }

            return true;
        }

        if (args[0].equalsIgnoreCase("history")) {
            if (args.length < 2) {
                sender.sendMessage("§c/deathnote history <player>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);

            if (target == null) {
                sender.sendMessage("§cPlayer must be online.");
                return true;
            }

            List<String> records = history.get(target.getUniqueId());

            sender.sendMessage("§c§lDEATH HISTORY: §f" + target.getName());

            if (records == null || records.isEmpty()) {
                sender.sendMessage("§7No recorded executions.");
                return true;
            }

            for (String record : records) {
                sender.sendMessage("§7- §f" + record);
            }

            return true;
        }

        if (args[0].equalsIgnoreCase("cancel")) {
            if (args.length < 2) {
                sender.sendMessage("§c/deathnote cancel <victim>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);

            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }

            BukkitTask task = scheduledDeaths.remove(target.getUniqueId());

            if (task == null) {
                sender.sendMessage("§cNo scheduled death for that player.");
            } else {
                task.cancel();
                sender.sendMessage("§aExecution cancelled.");
                target.sendMessage("§aYour scheduled execution was cancelled.");
            }

            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aDeathNote configuration reloaded.");
            return true;
        }

        sender.sendMessage("§cUnknown command. Use /deathnote help");
        return true;
    }

    // =====================================================
    // EXECUTION SYSTEM
    // =====================================================

    private void executeDeath(
            Player executor,
            String victimName,
            DeathMethod selectedMethod
    ) {
        Player victim = Bukkit.getPlayerExact(victimName);

        if (victim == null) {
            executor.sendMessage("§cThat player is not online.");
            return;
        }

        if (victim.equals(executor)) {
            executor.sendMessage("§cYou cannot execute yourself.");
            return;
        }

        long cooldown = getConfig().getLong("cooldown-seconds", 30);
        long now = System.currentTimeMillis();

        if (!executor.hasPermission("deathnote.bypass")) {
            long last = cooldowns.getOrDefault(
                    executor.getUniqueId(),
                    0L
            );

            long remaining = cooldown * 1000L - (now - last);

            if (remaining > 0) {
                executor.sendMessage(
                        "§cWait " + (remaining / 1000 + 1) +
                        " seconds before using the Death Note again."
                );
                return;
            }
        }

        DeathMethod method = selectedMethod;

        if (method == DeathMethod.RANDOM) {
            List<DeathMethod> choices = new ArrayList<>(
                    Arrays.asList(DeathMethod.values())
            );

            choices.remove(DeathMethod.RANDOM);
            method = choices.get(new Random().nextInt(choices.size()));
        }

        if (method == DeathMethod.DEATHBOT) {
            executeDeathBot(executor, victim);
            cooldowns.put(executor.getUniqueId(), now);
            return;
        }

        final DeathMethod finalMethod = method;

        BukkitTask previous = scheduledDeaths.remove(victim.getUniqueId());

        if (previous != null) {
            previous.cancel();
        }

        executor.sendMessage("§cThe Death Note has accepted its victim.");
        executor.sendMessage("§7Method: §f" + method.display);

        victim.sendTitle(
                "§4§lDEATH NOTE",
                "§cYour name has been written...",
                10, 50, 10
        );

        victim.playSound(
                victim.getLocation(),
                Sound.ENTITY_WARDEN_HEARTBEAT,
                1f,
                0.6f
        );

        int delay = Math.max(
                0,
                getConfig().getInt("death-delay-seconds", 2)
        );

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            scheduledDeaths.remove(victim.getUniqueId());

            if (!victim.isOnline() || victim.isDead()) {
                return;
            }

            performDeath(victim, finalMethod);

            String record = finalMethod.display + " - " + new Date();

            history.computeIfAbsent(
                    victim.getUniqueId(),
                    key -> new ArrayList<>()
            ).add(record);

            Bukkit.broadcastMessage(
                    "§4§lDEATH NOTE §8» §c" +
                    victim.getName() +
                    " §7was executed by §c" +
                    finalMethod.display +
                    "§7."
            );

        }, delay * 20L);

        scheduledDeaths.put(victim.getUniqueId(), task);
        cooldowns.put(executor.getUniqueId(), now);
    }

    private void kill(Player target) {
        if (target.isOnline() && !target.isDead()) {
            target.setHealth(0);
        }
    }

    private void damage(Player target, double amount) {
        if (target.isOnline() && !target.isDead()) {
            target.damage(amount);
        }
    }

    private void spawnMob(
            World world,
            Location location,
            EntityType type,
            Player target
    ) {
        Entity entity = world.spawnEntity(location, type);

        if (entity instanceof Mob mob) {
            mob.setTarget(target);
        }
    }

    private void performDeath(Player target, DeathMethod method) {
        Location loc = target.getLocation();
        World world = target.getWorld();

        world.playSound(
                loc,
                Sound.ENTITY_WITHER_SPAWN,
                1f,
                0.7f
        );

        world.spawnParticle(
                Particle.SMOKE,
                loc.clone().add(0, 1, 0),
                50,
                0.5, 0.8, 0.5,
                0.03
        );

        switch (method) {

            case LIGHTNING, DIVINE -> {
                world.strikeLightning(target.getLocation());
                kill(target);
            }

            case FALL -> {
                target.setVelocity(new Vector(0, 2.5, 0));
                Bukkit.getScheduler().runTaskLater(
                        this,
                        () -> damage(target, 1000),
                        30L
                );
            }

            case DROWN, RIPTIDE -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        100,
                        5
                ));
                damage(target, 1000);
            }

            case SUFFOCATE -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        100,
                        10
                ));
                damage(target, 1000);
            }

            case LAVA -> {
                target.setFireTicks(200);
                world.spawnParticle(
                        Particle.LAVA,
                        target.getLocation(),
                        30
                );
                damage(target, 1000);
            }

            case STARVE -> {
                target.setFoodLevel(0);
                target.setSaturation(0);
                damage(target, 1000);
            }

            case FREEZE -> {
                target.setFreezeTicks(target.getMaxFreezeTicks());
                damage(target, 1000);
            }

            case VOID -> {
                target.teleport(new Location(
                        world,
                        target.getLocation().getX(),
                        world.getMinHeight() - 10,
                        target.getLocation().getZ()
                ));

                Bukkit.getScheduler().runTaskLater(
                        this,
                        () -> damage(target, 1000),
                        10L
                );
            }

            case CACTUS -> {
                world.spawnParticle(
                        Particle.BLOCK,
                        target.getLocation().add(0, 1, 0),
                        40,
                        0.5, 0.8, 0.5,
                        Material.CACTUS.createBlockData()
                );
                damage(target, 1000);
            }

            case ANVIL -> {
                Location above = target.getLocation().add(0, 5, 0);
                world.spawnFallingBlock(
                        above,
                        Material.ANVIL.createBlockData()
                );
                damage(target, 1000);
            }

            case HEART -> {
                target.playSound(
                        target.getLocation(),
                        Sound.ENTITY_PLAYER_HURT,
                        1f,
                        0.3f
                );

                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.DARKNESS,
                        100,
                        1
                ));

                damage(target, 1000);
            }

            case CREEPER -> {
                Creeper creeper = (Creeper) world.spawnEntity(
                        target.getLocation(),
                        EntityType.CREEPER
                );

                creeper.setCustomName("§4Death's Creeper");
                creeper.setCustomNameVisible(true);
                creeper.ignite();
                damage(target, 1000);
            }

            case POISON -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.POISON,
                        200,
                        10
                ));

                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.WITHER,
                        200,
                        5
                ));

                damage(target, 1000);
            }

            case ELYTRA -> {
                target.setVelocity(new Vector(0, 2, 0));
                target.playSound(
                        loc,
                        Sound.ENTITY_PLAYER_ATTACK_CRIT,
                        1f,
                        0.5f
                );

                Bukkit.getScheduler().runTaskLater(
                        this,
                        () -> damage(target, 1000),
                        20L
                );
            }

            case TRIDENT -> {
                world.spawnParticle(
                        Particle.CRIT,
                        target.getLocation().add(0, 1, 0),
                        40,
                        0.5, 0.5, 0.5
                );
                damage(target, 1000);
            }

            case PHANTOM -> {
                for (int i = 0; i < 5; i++) {
                    Phantom phantom = (Phantom) world.spawnEntity(
                            target.getLocation().add(i - 2, 4, i - 2),
                            EntityType.PHANTOM
                    );
                    phantom.setTarget(target);
                }
                damage(target, 1000);
            }

            case WARDEN -> {
                spawnMob(
                        world,
                        target.getLocation().add(3, 0, 0),
                        EntityType.WARDEN,
                        target
                );
                damage(target, 1000);
            }

            case TNT_MINECART, RANDOM_TRAP -> {
                world.spawnEntity(
                        target.getLocation(),
                        EntityType.TNT_MINECART
                );
                damage(target, 1000);
            }

            case CRYSTAL -> {
                world.createExplosion(
                        target.getLocation(),
                        20f,
                        false,
                        false
                );
                damage(target, 1000);
            }

            case FIRESTORM, SOUL_FIRE -> {
                target.setFireTicks(200);
                world.spawnParticle(
                        Particle.FLAME,
                        target.getLocation(),
                        100,
                        1, 1, 1,
                        0.1
                );
                damage(target, 1000);
            }

            case ENDERMAN -> {
                spawnMob(
                        world,
                        target.getLocation().add(2, 0, 0),
                        EntityType.ENDERMAN,
                        target
                );
                damage(target, 1000);
            }

            case RAVAGER -> {
                spawnMob(
                        world,
                        target.getLocation().add(3, 0, 0),
                        EntityType.RAVAGER,
                        target
                );
                damage(target, 1000);
            }

            case GOLEM -> {
                spawnMob(
                        world,
                        target.getLocation().add(2, 0, 0),
                        EntityType.IRON_GOLEM,
                        target
                );
                damage(target, 1000);
            }

            case WITHER -> {
                spawnMob(
                        world,
                        target.getLocation().add(4, 0, 0),
                        EntityType.WITHER,
                        target
                );
                damage(target, 1000);
            }

            case GHAST -> {
                spawnMob(
                        world,
                        target.getLocation().add(5, 3, 0),
                        EntityType.GHAST,
                        target
                );
                damage(target, 1000);
            }

            case VEX -> {
                for (int i = 0; i < 5; i++) {
                    spawnMob(
                            world,
                            target.getLocation().add(i, 2, i),
                            EntityType.VEX,
                            target
                    );
                }
                damage(target, 1000);
            }

            case SILVERFISH -> {
                for (int i = 0; i < 10; i++) {
                    spawnMob(
                            world,
                            target.getLocation().add(i % 3, 0, i % 2),
                            EntityType.SILVERFISH,
                            target
                    );
                }
                damage(target, 1000);
            }

            case METEOR -> {
                Location meteor = target.getLocation().add(0, 8, 0);

                world.spawnParticle(
                        Particle.FLAME,
                        meteor,
                        100,
                        1, 1, 1
                );

                world.createExplosion(
                        target.getLocation(),
                        15f,
                        true,
                        false
                );

                damage(target, 1000);
            }

            case EARTHQUAKE -> {
                world.spawnParticle(
                        Particle.BLOCK,
                        target.getLocation(),
                        100,
                        2, 1, 2,
                        Material.DEEPSLATE.createBlockData()
                );

                target.setVelocity(new Vector(0, 2, 0));
                damage(target, 1000);
            }

            case BLACK_HOLE -> {
                world.spawnParticle(
                        Particle.PORTAL,
                        target.getLocation(),
                        200,
                        2, 2, 2,
                        0.5
                );

                target.setVelocity(new Vector(0, 2, 0));
                damage(target, 1000);
            }

            case SOUL_DRAIN -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.WITHER,
                        200,
                        10
                ));

                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.DARKNESS,
                        200,
                        1
                ));

                damage(target, 1000);
            }

            case BLOOD_MOON -> {
                world.setTime(18000);

                world.spawnParticle(
                        Particle.DAMAGE_INDICATOR,
                        target.getLocation(),
                        100,
                        1, 1, 1
                );

                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.WEAKNESS,
                        200,
                        5
                ));

                damage(target, 1000);
            }

            case BLINDNESS -> {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.BLINDNESS,
                        200,
                        1
                ));

                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.DARKNESS,
                        200,
                        1
                ));

                damage(target, 1000);
            }

            case DEATHBOT, RANDOM -> kill(target);
        }
    }

    // =====================================================
    // DEATHBOT INTEGRATION
    // =====================================================

    private void executeDeathBot(Player executor, Player victim) {
        if (!getConfig().getBoolean("deathbot.enabled", true)) {
            executor.sendMessage(
                    "§cDeathBot is disabled in the configuration."
            );
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("PvpBot") == null) {
            executor.sendMessage(
                    "§cPvpBot is not installed. DeathBot cannot run."
            );
            return;
        }

        String spawnCommand = getConfig().getString(
                "deathbot.spawn-command",
                "pvpbot spawn Death %player%"
        );

        String kitCommand = getConfig().getString(
                "deathbot.kit-command",
                "pvpbot kit give long Death"
        );

        if (spawnCommand == null || kitCommand == null) {
            executor.sendMessage("§cDeathBot commands are not configured.");
            return;
        }

        spawnCommand = spawnCommand.replace(
                "%player%",
                victim.getName()
        );

        boolean spawned = Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                spawnCommand
        );

        Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                kitCommand
        );

        Bukkit.broadcastMessage(
                "§4§lDEATH NOTE §8» §cDeath has arrived for " +
                victim.getName() + "§7."
        );

        executor.sendMessage(
                spawned
                        ? "§aDeathBot commands dispatched."
                        : "§eDeathBot spawn command was dispatched; check PvpBot syntax."
        );
    }
}
