package fr.furnaceall;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class FurnaceAllPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer SERIALIZER =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private static final String TYPE_FURNACE = "furnace";
    private static final String TYPE_ALL = "furnaceall";

    /** type -> (joueur -> timestamp de fin du délai en ms) */
    private final Map<String, Map<UUID, Long>> cooldowns = new HashMap<>();
    private final List<FurnaceRecipe> recipes = new ArrayList<>();
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        reloadAll();

        Objects.requireNonNull(getCommand("furnace")).setExecutor(this);
        Objects.requireNonNull(getCommand("furnace")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("furnaceall")).setExecutor(this);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------------
    //  Chargement
    // ------------------------------------------------------------------

    private void reloadAll() {
        reloadConfig();

        blacklist.clear();
        for (String s : getConfig().getStringList("blacklist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blacklist.add(m);
            else getLogger().warning("Material inconnu dans la blacklist : " + s);
        }

        recipes.clear();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            if (it.next() instanceof FurnaceRecipe fr) recipes.add(fr);
        }
        getLogger().info(recipes.size() + " recettes de cuisson chargées.");
    }

    private void loadData() {
        cooldowns.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        long now = System.currentTimeMillis();
        for (String type : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(type);
            if (sec == null) continue;
            Map<UUID, Long> map = cooldowns.computeIfAbsent(type, k -> new HashMap<>());
            for (String uuid : sec.getKeys(false)) {
                long end = sec.getLong(uuid);
                if (end > now) {
                    try {
                        map.put(UUID.fromString(uuid), end);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        cooldowns.forEach((type, map) -> map.forEach((uuid, end) -> {
            if (end > now) yml.set(type + "." + uuid, end);
        }));
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Impossible de sauvegarder data.yml : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Commandes
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("furnace") && args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("furnace.reload")) {
                sender.sendMessage(msg("no-permission", null));
                return true;
            }
            reloadAll();
            sender.sendMessage(msg("reloaded", null));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("only-players", null));
            return true;
        }

        if (name.equals("furnaceall")) {
            handleAll(player);
        } else {
            handleHand(player, args);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("furnace") && args.length == 1) {
            List<String> out = new ArrayList<>(List.of("stack", "one"));
            if (sender.hasPermission("furnace.reload")) out.add("reload");
            String start = args[0].toLowerCase(Locale.ROOT);
            out.removeIf(s -> !s.startsWith(start));
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    //  /furnace
    // ------------------------------------------------------------------

    private void handleHand(Player player, String[] args) {
        if (!player.hasPermission("furnace.use")) {
            player.sendMessage(msg("no-permission", null));
            return;
        }

        String mode = getConfig().getString("default-mode", "stack");
        if (args.length >= 1) {
            if (args[0].equalsIgnoreCase("one") || args[0].equalsIgnoreCase("stack")) {
                mode = args[0].toLowerCase(Locale.ROOT);
            } else {
                player.sendMessage(msg("usage", null));
                return;
            }
        }

        if (checkCooldown(player, TYPE_FURNACE)) return;

        PlayerInventory inv = player.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(msg("no-item-in-hand", null));
            return;
        }

        ItemStack single = hand.asOne();
        if (blacklist.contains(hand.getType()) || findRecipe(single) == null) {
            player.sendMessage(msg("not-cookable", null));
            return;
        }

        int toCook = mode.equals("one") ? 1 : hand.getAmount();
        ItemStack result = cook(hand.asQuantity(toCook));

        if (toCook >= hand.getAmount()) {
            inv.setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - toCook);
            inv.setItemInMainHand(hand);
        }

        boolean dropped = give(player, List.of(result));
        applyCooldown(player, TYPE_FURNACE);
        playSound(player);

        player.sendMessage(msg("cooked-hand", Map.of("count", String.valueOf(toCook))));
        if (dropped) player.sendMessage(msg("inventory-full", null));
    }

    // ------------------------------------------------------------------
    //  /furnaceall
    // ------------------------------------------------------------------

    private void handleAll(Player player) {
        if (!player.hasPermission("furnace.all")) {
            player.sendMessage(msg("no-permission", null));
            return;
        }
        if (checkCooldown(player, TYPE_ALL)) return;

        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();

        List<ItemStack> results = new ArrayList<>();
        int count = 0;

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir() || blacklist.contains(item.getType())) continue;
            if (findRecipe(item.asOne()) == null) continue;

            count += item.getAmount();
            results.add(cook(item));
            contents[i] = null;
        }

        if (results.isEmpty()) {
            player.sendMessage(msg("nothing-to-cook", null));
            return;
        }

        inv.setStorageContents(contents);
        boolean dropped = give(player, results);
        applyCooldown(player, TYPE_ALL);
        playSound(player);

        player.sendMessage(msg("cooked-all", Map.of("count", String.valueOf(count))));
        if (dropped) player.sendMessage(msg("inventory-full", null));
    }

    // ------------------------------------------------------------------
    //  Cuisson
    // ------------------------------------------------------------------

    private FurnaceRecipe findRecipe(ItemStack single) {
        for (FurnaceRecipe r : recipes) {
            if (r.getInputChoice().test(single)) return r;
        }
        return null;
    }

    /** Cuit un stack complet (le stack d'entrée doit être cuisable). */
    private ItemStack cook(ItemStack stack) {
        FurnaceRecipe r = findRecipe(stack.asOne());
        ItemStack result = r.getResult().clone();
        result.setAmount(result.getAmount() * stack.getAmount());
        return result;
    }

    /** Donne les items, jette le surplus. Retourne true si du surplus a été jeté. */
    private boolean give(Player player, List<ItemStack> items) {
        boolean dropped = false;
        for (ItemStack item : items) {
            Map<Integer, ItemStack> left = player.getInventory().addItem(item);
            for (ItemStack rest : left.values()) {
                player.getWorld().dropItem(player.getLocation(), rest);
                dropped = true;
            }
        }
        return dropped;
    }

    // ------------------------------------------------------------------
    //  Délais
    // ------------------------------------------------------------------

    private String cdKey(String type) {
        return getConfig().getBoolean("shared-cooldown", false) ? "shared" : type;
    }

    /** Retourne true si le joueur est encore en délai (et lui envoie le message). */
    private boolean checkCooldown(Player player, String type) {
        if (player.hasPermission("furnace.bypass")) return false;
        Long end = cooldowns.getOrDefault(cdKey(type), Map.of()).get(player.getUniqueId());
        if (end == null) return false;

        long remaining = end - System.currentTimeMillis();
        if (remaining <= 0) return false;

        player.sendMessage(msg("cooldown", Map.of("time", formatTime((remaining + 999) / 1000))));
        return true;
    }

    private void applyCooldown(Player player, String type) {
        long seconds = cooldownSeconds(player, type);
        if (seconds <= 0) return;
        cooldowns.computeIfAbsent(cdKey(type), k -> new HashMap<>())
                .put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    /** Le délai le plus court parmi les grades du joueur, sinon le délai par défaut. */
    private long cooldownSeconds(Player player, String type) {
        if (player.hasPermission("furnace.bypass")) return 0;

        long best = -1;
        ConfigurationSection groups = getConfig().getConfigurationSection("groups");
        if (groups != null) {
            for (String group : groups.getKeys(false)) {
                if (!player.hasPermission("furnace.group." + group)) continue;
                long v = groups.getLong(group + "." + type, -1);
                if (v < 0) continue;
                if (best < 0 || v < best) best = v;
            }
        }
        if (best < 0) best = getConfig().getLong("default-cooldown." + type, 3600);
        return best;
    }

    private String formatTime(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;

        ConfigurationSection f = getConfig().getConfigurationSection("time-format");
        String fh = f != null ? f.getString("hours", "{h}h") : "{h}h";
        String fm = f != null ? f.getString("minutes", "{m}m") : "{m}m";
        String fs = f != null ? f.getString("seconds", "{s}s") : "{s}s";
        String sep = f != null ? f.getString("separator", " ") : " ";

        List<String> parts = new ArrayList<>();
        if (h > 0) parts.add(fh.replace("{h}", String.valueOf(h)));
        if (m > 0) parts.add(fm.replace("{m}", String.valueOf(m)));
        if (s > 0 || parts.isEmpty()) parts.add(fs.replace("{s}", String.valueOf(s)));
        return String.join(sep, parts);
    }

    // ------------------------------------------------------------------
    //  Messages / sons
    // ------------------------------------------------------------------

    private Component msg(String key, Map<String, String> placeholders) {
        String raw = getConfig().getString("messages." + key, "&cMessage manquant : " + key);
        raw = raw.replace("{prefix}", getConfig().getString("messages.prefix", ""));
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return SERIALIZER.deserialize(raw);
    }

    private void playSound(Player player) {
        if (!getConfig().getBoolean("sound.enabled", true)) return;
        try {
            player.playSound(Sound.sound(
                    Key.key(getConfig().getString("sound.name", "block.furnace.fire_crackle")),
                    Sound.Source.PLAYER,
                    (float) getConfig().getDouble("sound.volume", 1.0),
                    (float) getConfig().getDouble("sound.pitch", 1.0)));
        } catch (Exception e) {
            getLogger().warning("Son invalide dans la config : " + e.getMessage());
        }
    }
}
