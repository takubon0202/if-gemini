package com.gemininpc.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import org.bukkit.command.TabCompleter;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;

public class GeminiNPC extends JavaPlugin implements Listener, TabCompleter {

    private String apiKey;
    private String defaultModelName;
    private String systemPrompt;
    private String npcName;
    private int maxHistorySize;

    // Available text models
    private static final String MODEL_FLASH = "gemini-3-flash-preview";
    private static final String MODEL_FLASH_THINKING = "gemini-3-flash-thinking";
    private static final String MODEL_PRO = "gemini-3-pro-preview";

    // Available image models (Nanobanana)
    private static final String MODEL_NANOBANANA = "gemini-2.5-flash-image";
    private static final String MODEL_NANOBANANA_PRO = "gemini-3-pro-image-preview";

    // Image aspect ratios
    private static final List<String> VALID_ASPECT_RATIOS = Arrays.asList(
        "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"
    );

    // Image resolutions (uppercase K required by API)
    private static final String RESOLUTION_1K = "1K";
    private static final String RESOLUTION_2K = "2K";
    private static final String RESOLUTION_4K = "4K";
    private static final List<String> VALID_RESOLUTIONS_FLASH = Arrays.asList(RESOLUTION_1K);
    private static final List<String> VALID_RESOLUTIONS_PRO = Arrays.asList(RESOLUTION_1K, RESOLUTION_2K, RESOLUTION_4K);

    // Thinking levels
    private static final String THINKING_MINIMAL = "minimal";
    private static final String THINKING_LOW = "low";
    private static final String THINKING_MEDIUM = "medium";
    private static final String THINKING_HIGH = "high";

    // Session modes
    private enum SessionMode {
        INACTIVE,    // Plugin not started
        MAIN_MENU,   // Main menu displayed
        CHAT,        // Chat/counseling mode
        SEARCH,      // Search mode
        IMAGE,       // Image generation mode
        COMMAND      // Command generation mode
    }

    private String imageHosting;
    private String imgbbApiKey;
    private String defaultImageModel;
    private String defaultAspectRatio;
    private String defaultResolution;

    // Spreadsheet logging
    private boolean spreadsheetEnabled;
    private String gasUrl;
    private String spreadsheetId;

    private final Map<UUID, JsonArray> conversationHistories = new ConcurrentHashMap<>();
    private final Map<UUID, SessionMode> playerSessionMode = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerModels = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerImageModels = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerAspectRatios = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerResolutions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> imageGenerationCooldown = new ConcurrentHashMap<>();
    private static final long IMAGE_COOLDOWN_MS = 10000; // 10 seconds cooldown

    // Library
    private final Map<UUID, List<ImageRecord>> playerImageLibrary = new ConcurrentHashMap<>();
    private int maxLibrarySize = 50;
    // URL detection pattern for Image-to-Image
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)");
    private static final long MAX_IMAGE_DOWNLOAD_SIZE = 10 * 1024 * 1024; // 10MB

    private static class ImageRecord {
        String prompt;
        String modelName;
        String aspectRatio;
        String resolution;
        String imageUrl;
        long timestamp;

        ImageRecord(String prompt, String modelName, String aspectRatio, String resolution, String imageUrl, long timestamp) {
            this.prompt = prompt;
            this.modelName = modelName;
            this.aspectRatio = aspectRatio;
            this.resolution = resolution;
            this.imageUrl = imageUrl;
            this.timestamp = timestamp;
        }
    }

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);

        // Register tab completers for commands
        if (getCommand("gemini") != null) {
            getCommand("gemini").setTabCompleter(this);
        }
        if (getCommand("geminimodel") != null) {
            getCommand("geminimodel").setTabCompleter(this);
        }
        if (getCommand("geminihelp") != null) {
            getCommand("geminihelp").setTabCompleter(this);
        }

        // Ensure library directory exists
        File libraryDir = new File(getDataFolder(), "library");
        if (!libraryDir.exists()) {
            libraryDir.mkdirs();
        }

        getLogger().info("========================================");
        getLogger().info("if-Gemini Plugin v2.2.1 Enabled!");
        getLogger().info("Default Model: " + defaultModelName);
        getLogger().info("Default Image Model: " + defaultImageModel);
        getLogger().info("Image Hosting: " + imageHosting);
        getLogger().info("Library Max: " + maxLibrarySize + " images/player");
        getLogger().info("Available Models: Flash, Flash Thinking, Pro");
        getLogger().info("Image Models: Nanobanana, Nanobanana Pro");
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        // Save all loaded libraries before shutdown
        for (Map.Entry<UUID, List<ImageRecord>> entry : playerImageLibrary.entrySet()) {
            saveLibrary(entry.getKey(), entry.getValue());
        }
        playerImageLibrary.clear();
        conversationHistories.clear();
        playerSessionMode.clear();
        playerModels.clear();
        playerImageModels.clear();
        playerAspectRatios.clear();
        playerResolutions.clear();
        imageGenerationCooldown.clear();
        getLogger().info("if-Gemini Plugin Disabled!");
    }

    private void loadConfiguration() {
        reloadConfig();
        FileConfiguration config = getConfig();

        apiKey = config.getString("gemini.api-key", "YOUR_API_KEY_HERE");
        String configModel = config.getString("gemini.model", MODEL_FLASH);
        defaultModelName = normalizeModelName(configModel);
        systemPrompt = config.getString("gemini.system-prompt", getDefaultSystemPrompt());
        npcName = config.getString("npc.name", "相談員");
        maxHistorySize = config.getInt("conversation.max-history", 20);

        // Image generation settings
        imageHosting = config.getString("image.hosting", "catbox");
        imgbbApiKey = config.getString("image.imgbb-api-key", "YOUR_IMGBB_API_KEY_HERE");
        defaultImageModel = config.getString("image.default-model", MODEL_NANOBANANA);
        defaultAspectRatio = config.getString("image.aspect-ratio", "1:1");
        defaultResolution = config.getString("image.default-resolution", RESOLUTION_1K);

        // Library settings
        maxLibrarySize = config.getInt("library.max-history", 50);

        // Validate aspect ratio
        if (!VALID_ASPECT_RATIOS.contains(defaultAspectRatio)) {
            getLogger().warning("Invalid aspect ratio '" + defaultAspectRatio + "', defaulting to 1:1");
            defaultAspectRatio = "1:1";
        }

        // Validate resolution
        if (!VALID_RESOLUTIONS_PRO.contains(defaultResolution)) {
            getLogger().warning("Invalid resolution '" + defaultResolution + "', defaulting to 1K");
            defaultResolution = RESOLUTION_1K;
        }

        if (apiKey.equals("YOUR_API_KEY_HERE")) {
            getLogger().warning("Please set your Gemini API key in config.yml!");
        }

        if (imageHosting.equals("imgbb") && imgbbApiKey.equals("YOUR_IMGBB_API_KEY_HERE")) {
            getLogger().warning("ImgBB API key not set. Falling back to Catbox for image hosting.");
            imageHosting = "catbox";
        }

        // Spreadsheet logging settings
        spreadsheetEnabled = config.getBoolean("spreadsheet.enabled", false);
        gasUrl = config.getString("spreadsheet.gas-url", "");
        // spreadsheet-url からIDを自動抽出（旧spreadsheet-idも後方互換でサポート）
        String spreadsheetUrl = config.getString("spreadsheet.spreadsheet-url", "");
        String legacyId = config.getString("spreadsheet.spreadsheet-id", "");
        spreadsheetId = extractSpreadsheetId(spreadsheetUrl, legacyId);
        if (spreadsheetEnabled) {
            if (gasUrl.isEmpty() || gasUrl.contains("YOUR_")) {
                getLogger().warning("[if-Gemini] spreadsheet.gas-url が未設定です。スプレッドシート連携を無効化しました。");
                spreadsheetEnabled = false;
            } else if (spreadsheetId.isEmpty()) {
                getLogger().warning("[if-Gemini] spreadsheet.spreadsheet-url が未設定です。スプレッドシート連携を無効化しました。");
                spreadsheetEnabled = false;
            }
        }
        if (spreadsheetEnabled) {
            getLogger().info("[if-Gemini] Spreadsheet logging enabled (ID: " + spreadsheetId.substring(0, Math.min(8, spreadsheetId.length())) + "...)");
        }

        getLogger().info("Model configured: " + defaultModelName);
    }

    private String extractSpreadsheetId(String url, String legacyId) {
        // URLからスプレッドシートIDを自動抽出
        // 対応形式: https://docs.google.com/spreadsheets/d/XXXXX/edit...
        if (url != null && !url.isEmpty() && !url.contains("YOUR_")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("/spreadsheets/d/([a-zA-Z0-9_-]+)")
                .matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
            // URLではなくIDが直接入力された場合（英数字・ハイフン・アンダースコアのみ）
            if (url.matches("[a-zA-Z0-9_-]+")) {
                return url;
            }
        }
        // 旧設定 spreadsheet-id からの後方互換
        if (legacyId != null && !legacyId.isEmpty() && !legacyId.contains("YOUR_")) {
            return legacyId;
        }
        return "";
    }

    private String normalizeModelName(String modelName) {
        // Normalize model name to ensure it matches our known constants
        if (modelName == null || modelName.trim().isEmpty()) {
            return MODEL_FLASH;
        }

        String normalized = modelName.trim().toLowerCase();

        // Check for exact matches first
        if (normalized.equals(MODEL_FLASH) || normalized.equals(MODEL_PRO)) {
            return modelName.trim();
        }

        // Map common variations to known models
        if (normalized.contains("pro")) {
            return MODEL_PRO;
        } else if (normalized.contains("flash-thinking") || normalized.contains("thinking")) {
            return MODEL_FLASH_THINKING;
        } else if (normalized.contains("flash")) {
            return MODEL_FLASH;
        }

        // Default to Flash for unknown models
        getLogger().warning("Unknown model '" + modelName + "', defaulting to " + MODEL_FLASH);
        return MODEL_FLASH;
    }

    private String getDefaultSystemPrompt() {
        return "あなたはMinecraftの世界で生徒の相談に乗るカウンセラーです。\n" +
               "以下のルールを必ず守ってください：\n" +
               "1. 回答は必ず400文字以内で簡潔に\n" +
               "2. まず相手の言葉を受け止め、共感を示す\n" +
               "3. 相手が言ったことを分かりやすく言い換えてまとめる\n" +
               "4. 次に何をすればいいか、具体的なアクションを1つ提案する\n" +
               "5. 優しく励ますトーンで話す\n" +
               "6. 質問で終わり、対話を促す";
    }

    private String getImageGenerationSystemPrompt() {
        return "あなたは画像生成AIアシスタントです。\n" +
               "以下のルールを必ず守ってください：\n\n" +
               "【ローマ字入力の解釈】\n" +
               "ユーザーはMinecraftのチャットから入力するため、日本語を直接入力できず、ローマ字（romaji）で日本語の意味を伝えることがあります。\n" +
               "ローマ字で書かれた日本語を自動的に理解して、その意味に従って画像を生成してください。\n\n" +
               "例:\n" +
               "- 'animefuunisite' → 'アニメ風にして' という意味です\n" +
               "- 'kawaiinekonomimi' → 'かわいい猫耳' という意味です\n" +
               "- 'yuuyakenoumi' → '夕焼けの海' という意味です\n" +
               "- 'nihonnoteien' → '日本の庭園' という意味です\n" +
               "- 'fantajiinosekai' → 'ファンタジーの世界' という意味です\n" +
               "- 'suibokugafuu' → '水墨画風' という意味です\n" +
               "- 'minecraftnosekai' → 'Minecraftの世界' という意味です\n\n" +
               "【画像生成の方針】\n" +
               "- ユーザーの指示に忠実に従う\n" +
               "- 高品質な画像を生成する\n" +
               "- Image-to-Image（元画像がある場合）は元画像の構図を維持しつつ指示に従って変換する";
    }

    private String getWebSearchSystemPrompt() {
        return "あなたはMinecraftの世界でWeb検索を手伝うアシスタントです。\n" +
               "以下のルールを必ず守ってください：\n" +
               "1. 検索結果を簡潔にまとめる（400文字以内）\n" +
               "2. 重要なポイントを箇条書きで示す\n" +
               "3. Minecraftのチャットで読みやすい形式にする\n" +
               "4. 日本語で回答する\n" +
               "5. 思考過程や推論プロセスは出力しない\n" +
               "6. 最終的な回答のみを出力する\n" +
               "7. 「調べています」「検索中」などの途中経過は書かない";
    }

    private String getPlayerModel(UUID playerId) {
        // Always default to MODEL_FLASH (gemini-3-flash-preview) for new players
        // This ensures thinkingConfig works correctly from the first use
        if (!playerModels.containsKey(playerId)) {
            playerModels.put(playerId, MODEL_FLASH);
        }
        return playerModels.get(playerId);
    }

    private SessionMode getSessionMode(UUID playerId) {
        return playerSessionMode.getOrDefault(playerId, SessionMode.INACTIVE);
    }

    private boolean isSessionActive(UUID playerId) {
        SessionMode mode = getSessionMode(playerId);
        return mode != SessionMode.INACTIVE;
    }

    private String getModelDisplayName(String modelName) {
        if (MODEL_FLASH_THINKING.equals(modelName) || modelName.contains("flash-thinking")) {
            return "Gemini 3 Flash Thinking (深思考)";
        } else if (MODEL_FLASH.equals(modelName) || (modelName.contains("flash") && !modelName.contains("thinking"))) {
            return "Gemini 3 Flash (高速)";
        } else if (MODEL_PRO.equals(modelName) || modelName.contains("pro")) {
            return "Gemini 3 Pro (高性能)";
        }
        return "Gemini 3 Flash (高速)";
    }

    private String getActualModelName(String modelName) {
        // Flash Thinking uses the same API model as Flash, but with different thinking_level
        if (MODEL_FLASH_THINKING.equals(modelName)) {
            return MODEL_FLASH;
        }
        return modelName;
    }

    private String getThinkingLevel(String modelName) {
        // All Gemini 3 models support thinkingLevel
        // Flash Thinking and Pro use "high" for deep reasoning
        // Regular Flash uses "minimal" for fast responses
        if (MODEL_FLASH_THINKING.equals(modelName)) {
            return THINKING_HIGH;  // Flash Thinking = Flash + high thinking
        } else if (MODEL_PRO.equals(modelName) || modelName.contains("pro")) {
            return THINKING_HIGH;  // Pro uses high thinking by default
        } else if (MODEL_FLASH.equals(modelName) || modelName.contains("flash")) {
            return THINKING_MINIMAL;  // Regular Flash uses minimal for speed
        } else {
            return THINKING_LOW;  // Fallback
        }
    }

    private boolean supportsThinking(String modelName) {
        // Only known Gemini 3 models support thinkingLevel
        // Be strict to avoid sending thinkingConfig to unsupported models
        return MODEL_FLASH.equals(modelName) ||
               MODEL_FLASH_THINKING.equals(modelName) ||
               MODEL_PRO.equals(modelName);
    }

    private String getModeDisplayName(SessionMode mode) {
        switch (mode) {
            case CHAT: return "相談モード";
            case SEARCH: return "検索モード";
            case IMAGE: return "画像生成モード";
            case COMMAND: return "コマンド生成";
            case MAIN_MENU: return "メインメニュー";
            default: return "未起動";
        }
    }

    private String getPlayerImageModel(UUID playerId) {
        return playerImageModels.getOrDefault(playerId, defaultImageModel);
    }

    private String getImageModelDisplayName(String modelName) {
        if (MODEL_NANOBANANA_PRO.equals(modelName) || modelName.contains("pro-image")) {
            return "Nanobanana Pro (高品質・4K)";
        }
        return "Nanobanana (高速)";
    }

    private String getPlayerAspectRatio(UUID playerId) {
        return playerAspectRatios.getOrDefault(playerId, defaultAspectRatio);
    }

    private String getPlayerResolution(UUID playerId) {
        String model = getPlayerImageModel(playerId);
        if (MODEL_NANOBANANA.equals(model)) {
            return RESOLUTION_1K; // Flash only supports 1K
        }
        return playerResolutions.getOrDefault(playerId, defaultResolution);
    }

    private String getResolutionDisplayName(String resolution) {
        switch (resolution) {
            case "1K": return "1K (1024px)";
            case "2K": return "2K (2048px)";
            case "4K": return "4K (4096px)";
            default: return resolution;
        }
    }

    private String getAspectRatioDescription(String ratio) {
        switch (ratio) {
            case "1:1":  return "正方形";
            case "2:3":  return "縦長 (ポートレート)";
            case "3:2":  return "横長 (風景)";
            case "3:4":  return "縦長";
            case "4:3":  return "横長 (標準)";
            case "4:5":  return "縦長 (SNS)";
            case "5:4":  return "横長";
            case "9:16": return "縦長 (スマホ)";
            case "16:9": return "横長 (ワイド)";
            case "21:9": return "超横長 (シネマ)";
            default: return "";
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Initialize default settings for this player
        playerModels.putIfAbsent(playerId, MODEL_FLASH);
        playerImageModels.putIfAbsent(playerId, defaultImageModel);
        playerAspectRatios.putIfAbsent(playerId, defaultAspectRatio);
        playerResolutions.putIfAbsent(playerId, defaultResolution);

        // Lazy-load library from disk
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<ImageRecord> library = loadLibrary(playerId);
            if (library != null && !library.isEmpty()) {
                playerImageLibrary.put(playerId, library);
            }
        });

        Bukkit.getScheduler().runTaskLater(this, () -> {
            showWelcomeMessage(player);
        }, 40L);
    }

    private void showWelcomeMessage(Player player) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.GREEN + "if-Gemini AI相談システム" + ChatColor.GOLD + "                   ║");
        player.sendMessage(ChatColor.GOLD + "╠═══════════════════════════════════════════════╣");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.WHITE + "AIがあなたをサポートします！" + ChatColor.GOLD + "                ║");
        player.sendMessage(ChatColor.GOLD + "║                                               ║");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.YELLOW + "/gemini" + ChatColor.GRAY + " でシステムを起動してください" + ChatColor.GOLD + "     ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        // Save library on quit, then remove from memory
        List<ImageRecord> library = playerImageLibrary.remove(playerId);
        if (library != null && !library.isEmpty()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> saveLibrary(playerId, library));
        }
        conversationHistories.remove(playerId);
        playerSessionMode.remove(playerId);
        playerModels.remove(playerId);
        playerImageModels.remove(playerId);
        playerAspectRatios.remove(playerId);
        playerResolutions.remove(playerId);
        imageGenerationCooldown.remove(playerId);
    }

    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player)) return;
        Player player = (Player) event.getSender();
        UUID playerId = player.getUniqueId();
        SessionMode mode = getSessionMode(playerId);
        if (mode == SessionMode.INACTIVE) return;

        String buffer = event.getBuffer();
        if (!buffer.startsWith("/")) return;
        String input = buffer.substring(1).toLowerCase();

        List<String> suggestions = new ArrayList<>();

        if (mode == SessionMode.MAIN_MENU) {
            List<String> cmds = Arrays.asList("1", "2", "3", "4", "5", "6", "7", "chat", "search", "command", "image", "model", "help", "status", "library", "exit");
            for (String cmd : cmds) {
                if (cmd.startsWith(input)) suggestions.add("/" + cmd);
            }
        } else if (mode == SessionMode.IMAGE) {
            // Image mode commands
            List<String> cmds = Arrays.asList("settings", "model", "ratio", "resolution", "library", "exit");
            if (input.isEmpty()) {
                for (String cmd : cmds) suggestions.add("/" + cmd);
            } else if (input.startsWith("model ")) {
                String arg = input.substring(6);
                for (String m : Arrays.asList("nanobanana", "nanobanana-pro")) {
                    if (m.startsWith(arg)) suggestions.add("/model " + m);
                }
            } else if (input.startsWith("ratio ")) {
                String arg = input.substring(6);
                for (String r : VALID_ASPECT_RATIOS) {
                    if (r.startsWith(arg)) suggestions.add("/ratio " + r);
                }
            } else if (input.startsWith("resolution ")) {
                String arg = input.substring(11);
                for (String r : Arrays.asList("1K", "2K", "4K")) {
                    if (r.toLowerCase().startsWith(arg)) suggestions.add("/resolution " + r);
                }
            } else {
                for (String cmd : cmds) {
                    if (cmd.startsWith(input)) suggestions.add("/" + cmd);
                }
            }
        } else if (mode == SessionMode.CHAT || mode == SessionMode.SEARCH || mode == SessionMode.COMMAND) {
            List<String> modeCmds = Arrays.asList("exit", "model");
            if (input.isEmpty()) {
                for (String cmd : modeCmds) suggestions.add("/" + cmd);
            } else if (input.startsWith("model ")) {
                String arg = input.substring(6);
                for (String m : Arrays.asList("flash", "thinking", "pro")) {
                    if (m.startsWith(arg)) suggestions.add("/model " + m);
                }
            } else {
                for (String cmd : modeCmds) {
                    if (cmd.startsWith(input)) suggestions.add("/" + cmd);
                }
            }
        }

        if (!suggestions.isEmpty()) {
            event.setCompletions(suggestions);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        SessionMode mode = getSessionMode(playerId);

        // If not in any active session, let commands proceed normally
        if (mode == SessionMode.INACTIVE) {
            return;
        }

        String rawMessage = event.getMessage();
        String command = rawMessage.toLowerCase();

        // Always allow these commands
        if (command.equals("/gemini") || command.startsWith("/gemini ")) {
            return; // Let the command handler process it
        }

        // Allow Minecraft vanilla commands to pass through in ALL modes
        String cmdName = command.substring(1).split(" ")[0].toLowerCase();
        if (cmdName.equals("give") || cmdName.equals("summon") || cmdName.equals("effect") ||
            cmdName.equals("tp") || cmdName.equals("teleport") || cmdName.equals("gamemode") ||
            cmdName.equals("kill") || cmdName.equals("clear") || cmdName.equals("weather") ||
            cmdName.equals("time") || cmdName.equals("xp") || cmdName.equals("experience") ||
            cmdName.equals("enchant") || cmdName.equals("fill") || cmdName.equals("setblock") ||
            cmdName.equals("clone") || cmdName.equals("particle") || cmdName.equals("playsound") ||
            cmdName.equals("title") || cmdName.equals("gamerule") || cmdName.equals("spawnpoint") ||
            cmdName.equals("setworldspawn") || cmdName.equals("difficulty") ||
            cmdName.equals("execute") || cmdName.equals("data") || cmdName.equals("attribute") ||
            cmdName.equals("item") || cmdName.equals("place") || cmdName.equals("ride") ||
            cmdName.equals("say") || cmdName.equals("msg") || cmdName.equals("tell") ||
            cmdName.equals("spreadplayers") || cmdName.equals("stopsound") ||
            cmdName.equals("scoreboard") || cmdName.equals("tag") || cmdName.equals("team") ||
            cmdName.equals("bossbar") || cmdName.equals("schedule") || cmdName.equals("function") ||
            cmdName.equals("forceload") || cmdName.equals("worldborder") || cmdName.equals("locate") ||
            cmdName.equals("loot") || cmdName.equals("replaceitem") || cmdName.equals("damage") ||
            cmdName.equals("op") || cmdName.equals("deop") || cmdName.equals("ban") ||
            cmdName.equals("kick") || cmdName.equals("whitelist") || cmdName.equals("stop") ||
            cmdName.equals("reload") || cmdName.equals("me") || cmdName.equals("seed") ||
            cmdName.equals("list") || cmdName.equals("help") || cmdName.equals("trigger") ||
            cmdName.equals("advancement") || cmdName.equals("recipe") || cmdName.equals("return") ||
            cmdName.equals("random") || cmdName.equals("tick") || cmdName.equals("debug")) {
            return; // Let the command pass through to the server
        }

        // Handle /exit - return to main menu or close
        if (command.equals("/exit") || command.equals("/back") || command.equals("/menu")) {
            event.setCancelled(true);
            if (mode == SessionMode.MAIN_MENU) {
                // Close the system entirely
                playerSessionMode.put(playerId, SessionMode.INACTIVE);
                player.sendMessage("");
                player.sendMessage(ChatColor.GOLD + "[" + npcName + "] " + ChatColor.YELLOW +
                    "システムを終了しました。また /gemini で起動できます！");
                player.sendMessage("");
            } else {
                // Return to main menu
                playerSessionMode.put(playerId, SessionMode.MAIN_MENU);
                showMainMenu(player);
            }
            return;
        }

        // Handle number shortcuts in main menu
        if (mode == SessionMode.MAIN_MENU) {
            event.setCancelled(true);
            String input = command.substring(1).trim();

            switch (input) {
                case "1":
                case "chat":
                case "相談":
                    startChatMode(player);
                    return;
                case "2":
                case "search":
                case "検索":
                    startSearchMode(player);
                    return;
                case "3":
                case "command":
                case "コマンド":
                    startCommandMode(player);
                    return;
                case "4":
                case "image":
                case "画像":
                    startImageMode(player);
                    return;
                case "5":
                case "help":
                case "ヘルプ":
                    showHelpPage(player, 1);
                    return;
                case "6":
                case "status":
                case "ステータス":
                    showStatus(player);
                    return;
                case "7":
                case "library":
                case "ライブラリ":
                    showLibrary(player, 1);
                    return;
                case "8":
                case "history":
                case "履歴":
                    showSpreadsheetHistory(player, 1);
                    return;
                case "model":
                case "モデル":
                    showModelSelection(player);
                    return;
                default:
                    player.sendMessage(ChatColor.RED + "無効な選択です。1〜8の数字を入力してください。");
                    return;
            }
        }

        // Handle chat mode
        if (mode == SessionMode.CHAT) {
            event.setCancelled(true);
            String message = command.substring(1).trim();

            if (message.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "チャットで相談内容を入力してください。ボタンからモデル変更・メニューに戻れます。");
                return;
            }

            // Handle model change within chat mode
            if (message.equals("model") || message.equals("モデル")) {
                showModelSelection(player);
                return;
            }
            if (message.startsWith("model ") || message.startsWith("モデル ")) {
                String modelArg = message.substring(message.indexOf(' ') + 1).trim();
                handleModelChange(player, modelArg);
                return;
            }

            player.sendMessage(ChatColor.GRAY + "[あなた] " + ChatColor.WHITE + message);

            // Show thinking indicator based on model
            String currentModel = getPlayerModel(playerId);
            if (currentModel.equals(MODEL_PRO) || currentModel.contains("pro")) {
                player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "深く思考中...");
            } else if (currentModel.equals(MODEL_FLASH_THINKING)) {
                player.sendMessage(ChatColor.GOLD + "✦ " + ChatColor.GRAY + "思考中...");
            } else {
                player.sendMessage(ChatColor.AQUA + "✦ " + ChatColor.GRAY + "応答中...");
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                processGeminiChat(player, message);
            });
            return;
        }

        // Handle search mode
        if (mode == SessionMode.SEARCH) {
            event.setCancelled(true);
            String query = command.substring(1).trim();

            if (query.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "チャットで検索ワードを入力してください。ボタンからモデル変更・メニューに戻れます。");
                return;
            }

            // Handle model change within search mode
            if (query.equals("model") || query.equals("モデル")) {
                showModelSelection(player);
                return;
            }
            if (query.startsWith("model ") || query.startsWith("モデル ")) {
                String modelArg = query.substring(query.indexOf(' ') + 1).trim();
                handleModelChange(player, modelArg);
                return;
            }

            player.sendMessage(ChatColor.GREEN + "[検索] " + ChatColor.WHITE + query);
            player.sendMessage(ChatColor.GREEN + "✦ " + ChatColor.GRAY + "Web検索中...");

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                processWebSearch(player, query);
            });
            return;
        }

        // Handle image generation mode
        if (mode == SessionMode.IMAGE) {
            event.setCancelled(true);
            String input = command.substring(1).trim(); // lowercase for command matching
            String rawInput = rawMessage.substring(1).trim(); // preserve original case for URLs/prompts

            // Handle image model change within image mode
            if (input.equals("model") || input.equals("モデル")) {
                showImageModelSelection(player);
                return;
            }
            if (input.startsWith("model ") || input.startsWith("モデル ")) {
                String modelArg = input.substring(input.indexOf(' ') + 1).trim();
                handleImageModelChange(player, modelArg);
                return;
            }

            // Handle aspect ratio change
            if (input.startsWith("ratio ") || input.startsWith("比率 ") || input.startsWith("aspect ")) {
                String ratioArg = input.substring(input.indexOf(' ') + 1).trim();
                handleAspectRatioChange(player, ratioArg);
                return;
            }

            // Handle resolution change (Pro only)
            if (input.startsWith("resolution ") || input.startsWith("解像度 ") || input.startsWith("size ")) {
                String resArg = input.substring(input.indexOf(' ') + 1).trim();
                handleResolutionChange(player, resArg);
                return;
            }

            // Handle settings display
            if (input.equals("settings") || input.equals("設定")) {
                showImageSettings(player);
                return;
            }

            // Handle library access from image mode
            if (input.equals("library") || input.equals("ライブラリ")) {
                showLibrary(player, 1);
                return;
            }

            if (input.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "チャットで画像の説明を入力してください。ボタンから設定変更・メニューに戻れます。");
                return;
            }

            // Cooldown check
            Long lastGeneration = imageGenerationCooldown.get(playerId);
            if (lastGeneration != null && (System.currentTimeMillis() - lastGeneration) < IMAGE_COOLDOWN_MS) {
                long remaining = (IMAGE_COOLDOWN_MS - (System.currentTimeMillis() - lastGeneration)) / 1000;
                player.sendMessage(ChatColor.RED + "画像生成のクールダウン中です。あと " + remaining + " 秒お待ちください。");
                return;
            }
            imageGenerationCooldown.put(playerId, System.currentTimeMillis());

            // Capture settings on main thread before async dispatch
            final String capturedModel = getPlayerImageModel(playerId);
            final String capturedRatio = getPlayerAspectRatio(playerId);
            final String capturedRes = getPlayerResolution(playerId);

            // Check for Image-to-Image: URL detection (use rawInput to preserve URL case)
            Matcher urlMatcher = URL_PATTERN.matcher(rawInput);
            if (urlMatcher.find()) {
                final String sourceUrl = urlMatcher.group(1);
                final String i2iPrompt = rawInput.replace(sourceUrl, "").trim();

                if (i2iPrompt.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "画像URLの後にプロンプトを入力してください。");
                    player.sendMessage(ChatColor.GRAY + "例: /" + sourceUrl + " アニメ風にして");
                    imageGenerationCooldown.remove(playerId); // Reset cooldown
                    return;
                }

                player.sendMessage(ChatColor.LIGHT_PURPLE + "[Image-to-Image] " + ChatColor.WHITE + i2iPrompt);
                player.sendMessage(ChatColor.GRAY + "  元画像: " + ChatColor.GRAY + sourceUrl);
                player.sendMessage(ChatColor.GRAY + "  モデル: " + getImageModelDisplayName(capturedModel) + " | 比率: " + capturedRatio + " | 解像度: " + capturedRes);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "元画像をダウンロード中...");

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    processI2IGeneration(player, i2iPrompt, sourceUrl, capturedModel, capturedRatio, capturedRes);
                });
                return;
            }

            player.sendMessage(ChatColor.LIGHT_PURPLE + "[画像生成] " + ChatColor.WHITE + rawInput);
            player.sendMessage(ChatColor.GRAY + "  モデル: " + getImageModelDisplayName(capturedModel) + " | 比率: " + capturedRatio + " | 解像度: " + capturedRes);
            if (MODEL_NANOBANANA_PRO.equals(capturedModel)) {
                if (RESOLUTION_4K.equals(capturedRes)) {
                    player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "4K高品質画像を生成中... (時間がかかります)");
                } else {
                    player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "高品質画像を生成中...");
                }
            } else {
                player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "画像を生成中...");
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                processImageGeneration(player, rawInput, capturedModel, capturedRatio, capturedRes);
            });
            return;
        }

        // Handle command generation mode
        if (mode == SessionMode.COMMAND) {
            String input = command.substring(1).trim();
            String rawInput = rawMessage.substring(1).trim();

            event.setCancelled(true);

            if (input.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "チャットでやりたいことを入力してください。ボタンからモデル変更・メニューに戻れます。");
                return;
            }

            // Handle model change within command mode
            if (input.equals("model") || input.equals("モデル")) {
                showModelSelection(player);
                return;
            }
            if (input.startsWith("model ") || input.startsWith("モデル ")) {
                String modelArg = input.substring(input.indexOf(' ') + 1).trim();
                handleModelChange(player, modelArg);
                return;
            }

            player.sendMessage(ChatColor.GOLD + "[あなた] " + ChatColor.WHITE + rawInput);
            player.sendMessage(ChatColor.GOLD + "✦ " + ChatColor.GRAY + "コマンド生成中...");

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                processCommandGeneration(player, rawInput);
            });
            return;
        }
    }

    // Handle regular chat messages for mode input (no / prefix needed)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        SessionMode mode = getSessionMode(playerId);

        if (mode == SessionMode.INACTIVE) return;

        event.setCancelled(true);
        String rawMessage = event.getMessage().trim();

        if (rawMessage.isEmpty()) return;

        // Switch to main thread and reuse existing command handling logic
        // by creating a synthetic PlayerCommandPreprocessEvent
        Bukkit.getScheduler().runTask(GeminiNPC.this, () -> {
            // Re-check session mode on main thread (may have changed)
            SessionMode currentMode = getSessionMode(playerId);
            if (currentMode == SessionMode.INACTIVE) return;

            // Create synthetic event with "/" prefix to reuse existing handler
            PlayerCommandPreprocessEvent syntheticEvent =
                new PlayerCommandPreprocessEvent(player, "/" + rawMessage);
            onPlayerCommand(syntheticEvent);
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /gemini command - main entry point
        if (command.getName().equalsIgnoreCase("gemini")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();

            // Handle subcommands
            if (args.length > 0) {
                String subCommand = args[0].toLowerCase();

                // Check if session is active for most subcommands
                if (!isSessionActive(playerId) && !subCommand.equals("help")) {
                    player.sendMessage(ChatColor.RED + "まず /gemini でシステムを起動してください。");
                    return true;
                }

                switch (subCommand) {
                    case "chat":
                    case "相談":
                    case "1":
                        startChatMode(player);
                        return true;
                    case "search":
                    case "検索":
                    case "2":
                        startSearchMode(player);
                        return true;
                    case "image":
                    case "画像":
                    case "4":
                        if (args.length > 1) {
                            String imageSub = args[1].toLowerCase();
                            if (imageSub.equals("model") || imageSub.equals("モデル")) {
                                if (args.length > 2) {
                                    handleImageModelChange(player, args[2]);
                                } else {
                                    showImageModelSelection(player);
                                }
                            } else if (imageSub.equals("ratio") || imageSub.equals("比率") || imageSub.equals("aspect")) {
                                if (args.length > 2) {
                                    handleAspectRatioChange(player, args[2]);
                                } else {
                                    player.sendMessage(ChatColor.RED + "使用方法: /gemini image ratio <値>");
                                    player.sendMessage(ChatColor.GRAY + "利用可能: " + String.join(", ", VALID_ASPECT_RATIOS));
                                }
                            } else if (imageSub.equals("resolution") || imageSub.equals("解像度") || imageSub.equals("size")) {
                                if (args.length > 2) {
                                    handleResolutionChange(player, args[2]);
                                } else {
                                    player.sendMessage(ChatColor.RED + "使用方法: /gemini image resolution <値>");
                                    player.sendMessage(ChatColor.GRAY + "利用可能: 1K, 2K, 4K (Proのみ)");
                                }
                            } else if (imageSub.equals("settings") || imageSub.equals("設定")) {
                                showImageSettings(player);
                            } else {
                                startImageMode(player);
                            }
                        } else {
                            startImageMode(player);
                        }
                        return true;
                    case "model":
                    case "モデル":
                        if (args.length > 1) {
                            handleModelChange(player, args[1]);
                        } else {
                            showModelSelection(player);
                        }
                        return true;
                    case "help":
                    case "ヘルプ":
                    case "5":
                        if (args.length > 1) {
                            try {
                                int page = Integer.parseInt(args[1]);
                                showHelpPage(player, page);
                            } catch (NumberFormatException e) {
                                showTopicHelp(player, args[1]);
                            }
                        } else {
                            showHelpPage(player, 1);
                        }
                        return true;
                    case "status":
                    case "ステータス":
                    case "6":
                        showStatus(player);
                        return true;
                    case "library":
                    case "ライブラリ":
                    case "7":
                        if (args.length > 1) {
                            String libSub = args[1].toLowerCase();
                            if (libSub.equals("page") && args.length > 2) {
                                try {
                                    int page = Integer.parseInt(args[2]);
                                    showLibrary(player, page);
                                } catch (NumberFormatException e) {
                                    showLibrary(player, 1);
                                }
                            } else if (libSub.equals("view") && args.length > 2) {
                                try {
                                    int idx = Integer.parseInt(args[2]);
                                    handleLibraryView(player, idx);
                                } catch (NumberFormatException e) {
                                    player.sendMessage(ChatColor.RED + "無効なインデックスです。");
                                }
                            } else if (libSub.equals("i2i") && args.length > 2) {
                                try {
                                    int idx = Integer.parseInt(args[2]);
                                    handleLibraryI2I(player, idx);
                                } catch (NumberFormatException e) {
                                    player.sendMessage(ChatColor.RED + "無効なインデックスです。");
                                }
                            } else {
                                showLibrary(player, 1);
                            }
                        } else {
                            showLibrary(player, 1);
                        }
                        return true;
                    case "command":
                    case "コマンド":
                    case "3":
                        startCommandMode(player);
                        return true;
                    case "history":
                    case "履歴":
                    case "8":
                        if (args.length > 1) {
                            String historySub = args[1].toLowerCase();
                            if (historySub.equals("page") && args.length > 2) {
                                try {
                                    int page = Integer.parseInt(args[2]);
                                    showSpreadsheetHistory(player, page);
                                } catch (NumberFormatException e) {
                                    showSpreadsheetHistory(player, 1);
                                }
                            } else {
                                showSpreadsheetHistory(player, 1);
                            }
                        } else {
                            showSpreadsheetHistory(player, 1);
                        }
                        return true;
                    case "imagemodel":
                        if (args.length > 1) {
                            handleImageModelChange(player, args[1]);
                        } else {
                            showImageModelSelection(player);
                        }
                        return true;
                    case "menu":
                    case "メニュー":
                        playerSessionMode.put(playerId, SessionMode.MAIN_MENU);
                        showMainMenu(player);
                        return true;
                    case "exit":
                    case "終了":
                        playerSessionMode.put(playerId, SessionMode.INACTIVE);
                        player.sendMessage("");
                        player.sendMessage(ChatColor.GOLD + "[" + npcName + "] " + ChatColor.YELLOW +
                            "システムを終了しました。また /gemini で起動できます！");
                        player.sendMessage("");
                        return true;
                    case "clear":
                        conversationHistories.remove(playerId);
                        player.sendMessage(ChatColor.GREEN + "会話履歴をクリアしました。");
                        return true;
                    default:
                        player.sendMessage(ChatColor.RED + "不明なサブコマンド: " + subCommand);
                        player.sendMessage(ChatColor.YELLOW + "使用方法: /gemini [chat|search|image|model|help|status|library|command|menu|exit]");
                        return true;
                }
            }

            // No subcommand - start or show main menu
            if (!isSessionActive(playerId)) {
                // Start the system
                playerSessionMode.put(playerId, SessionMode.MAIN_MENU);
                showStartupMessage(player);
            } else {
                // Already active - show main menu
                playerSessionMode.put(playerId, SessionMode.MAIN_MENU);
                showMainMenu(player);
            }
            return true;
        }

        // /geminireload command
        if (command.getName().equalsIgnoreCase("geminireload")) {
            if (!sender.hasPermission("gemininpc.reload")) {
                sender.sendMessage(ChatColor.RED + "権限がありません。");
                return true;
            }

            loadConfiguration();
            sender.sendMessage(ChatColor.GREEN + "if-Gemini設定をリロードしました。");
            return true;
        }

        // Legacy commands redirect
        if (command.getName().equalsIgnoreCase("websearch") ||
            command.getName().equalsIgnoreCase("geminimodel") ||
            command.getName().equalsIgnoreCase("geminiclear") ||
            command.getName().equalsIgnoreCase("geminihelp") ||
            command.getName().equalsIgnoreCase("geminiimage")) {

            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();

            if (!isSessionActive(playerId)) {
                player.sendMessage(ChatColor.RED + "まず /gemini でシステムを起動してください。");
                return true;
            }

            // Handle legacy commands
            if (command.getName().equalsIgnoreCase("websearch")) {
                if (args.length == 0) {
                    startSearchMode(player);
                } else {
                    playerSessionMode.put(playerId, SessionMode.SEARCH);
                    String query = String.join(" ", args);
                    player.sendMessage(ChatColor.GREEN + "[検索] " + ChatColor.WHITE + query);
                    player.sendMessage(ChatColor.GREEN + "✦ " + ChatColor.GRAY + "Web検索中...");
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        processWebSearch(player, query);
                    });
                }
                return true;
            }

            if (command.getName().equalsIgnoreCase("geminimodel")) {
                if (args.length == 0) {
                    showModelSelection(player);
                } else {
                    handleModelChange(player, args[0]);
                }
                return true;
            }

            if (command.getName().equalsIgnoreCase("geminiclear")) {
                conversationHistories.remove(playerId);
                player.sendMessage(ChatColor.GREEN + "会話履歴をクリアしました。");
                return true;
            }

            if (command.getName().equalsIgnoreCase("geminihelp")) {
                if (args.length == 0) {
                    showHelpPage(player, 1);
                } else {
                    try {
                        int page = Integer.parseInt(args[0]);
                        showHelpPage(player, page);
                    } catch (NumberFormatException e) {
                        showTopicHelp(player, args[0]);
                    }
                }
                return true;
            }

            if (command.getName().equalsIgnoreCase("geminiimage")) {
                if (args.length == 0) {
                    startImageMode(player);
                } else {
                    // Cooldown check
                    Long lastGen = imageGenerationCooldown.get(playerId);
                    if (lastGen != null && (System.currentTimeMillis() - lastGen) < IMAGE_COOLDOWN_MS) {
                        long rem = (IMAGE_COOLDOWN_MS - (System.currentTimeMillis() - lastGen)) / 1000;
                        player.sendMessage(ChatColor.RED + "画像生成のクールダウン中です。あと " + rem + " 秒お待ちください。");
                        return true;
                    }
                    imageGenerationCooldown.put(playerId, System.currentTimeMillis());

                    playerSessionMode.put(playerId, SessionMode.IMAGE);
                    String prompt = String.join(" ", args);
                    final String capModel = getPlayerImageModel(playerId);
                    final String capRatio = getPlayerAspectRatio(playerId);
                    final String capRes = getPlayerResolution(playerId);
                    player.sendMessage(ChatColor.LIGHT_PURPLE + "[画像生成] " + ChatColor.WHITE + prompt);
                    player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "画像を生成中...");
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        processImageGeneration(player, prompt, capModel, capRatio, capRes);
                    });
                }
                return true;
            }
        }

        return false;
    }

    // ==================== Tab Completion ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("gemini")) {
            if (args.length == 1) {
                // First argument - main subcommands
                List<String> subCommands = Arrays.asList(
                    "chat", "search", "image", "model", "help", "status", "library", "command", "history", "menu", "exit", "clear", "imagemodel"
                );
                String input = args[0].toLowerCase();
                completions = subCommands.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
            } else if (args.length == 2) {
                String subCommand = args[0].toLowerCase();
                String input = args[1].toLowerCase();

                if (subCommand.equals("model")) {
                    // Text model selection
                    List<String> models = Arrays.asList("flash", "thinking", "pro");
                    completions = models.stream()
                        .filter(s -> s.startsWith(input))
                        .collect(Collectors.toList());
                } else if (subCommand.equals("imagemodel")) {
                    // Image model selection
                    List<String> models = Arrays.asList("nanobanana", "nanobanana-pro");
                    completions = models.stream()
                        .filter(s -> s.startsWith(input))
                        .collect(Collectors.toList());
                } else if (subCommand.equals("image")) {
                    // Image subcommands
                    List<String> imageSubs = Arrays.asList("model", "ratio", "resolution", "settings");
                    completions = imageSubs.stream()
                        .filter(s -> s.startsWith(input))
                        .collect(Collectors.toList());
                } else if (subCommand.equals("help")) {
                    // Help topics
                    List<String> topics = Arrays.asList("1", "2", "3", "4", "5", "chat", "search", "image", "model", "commands");
                    completions = topics.stream()
                        .filter(s -> s.startsWith(input))
                        .collect(Collectors.toList());
                }
            } else if (args.length == 3) {
                String subCommand = args[0].toLowerCase();
                String subSub = args[1].toLowerCase();
                String input = args[2].toLowerCase();

                if (subCommand.equals("image")) {
                    if (subSub.equals("model")) {
                        List<String> models = Arrays.asList("nanobanana", "nanobanana-pro");
                        completions = models.stream()
                            .filter(s -> s.startsWith(input))
                            .collect(Collectors.toList());
                    } else if (subSub.equals("ratio")) {
                        completions = VALID_ASPECT_RATIOS.stream()
                            .filter(s -> s.startsWith(input))
                            .collect(Collectors.toList());
                    } else if (subSub.equals("resolution")) {
                        List<String> resolutions = Arrays.asList("1K", "2K", "4K");
                        completions = resolutions.stream()
                            .filter(s -> s.toLowerCase().startsWith(input))
                            .collect(Collectors.toList());
                    }
                }
            }
        } else if (command.getName().equalsIgnoreCase("geminimodel")) {
            if (args.length == 1) {
                List<String> models = Arrays.asList("flash", "thinking", "pro");
                String input = args[0].toLowerCase();
                completions = models.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
            }
        } else if (command.getName().equalsIgnoreCase("geminihelp")) {
            if (args.length == 1) {
                List<String> topics = Arrays.asList("1", "2", "3", "4", "5", "chat", "search", "image", "model", "commands");
                String input = args[0].toLowerCase();
                completions = topics.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
            }
        }

        return completions;
    }

    // ==================== UI Methods ====================

    private void showStartupMessage(Player player) {
        String model = getModelDisplayName(getPlayerModel(player.getUniqueId()));

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "  ✓ " + ChatColor.WHITE + "if-Gemini システムを起動しました！");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  使用モデル: " + ChatColor.AQUA + model);
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage("");

        // Show main menu after startup
        showMainMenu(player);
    }

    private void showMainMenu(Player player) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.GREEN + "if-Gemini メインメニュー" + ChatColor.GOLD + "                   ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  機能を選んでください" + ChatColor.GRAY + " (クリックで選択):");
        player.sendMessage("");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[1] 相談モード", "/gemini 1", "クリックで相談モードを開始", net.md_5.bungee.api.ChatColor.AQUA),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            text("AIに相談", net.md_5.bungee.api.ChatColor.GRAY));
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[2] 検索モード", "/gemini 2", "クリックで検索モードを開始", net.md_5.bungee.api.ChatColor.GREEN),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            text("Web検索", net.md_5.bungee.api.ChatColor.GRAY));
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[3] コマンド生成", "/gemini 3", "クリックでコマンド生成モードを開始", net.md_5.bungee.api.ChatColor.GOLD),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            text("AIでコマンド作成", net.md_5.bungee.api.ChatColor.GRAY));
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[4] 画像生成モード", "/gemini 4", "クリックで画像生成モードを開始", net.md_5.bungee.api.ChatColor.LIGHT_PURPLE),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            text("AI画像生成", net.md_5.bungee.api.ChatColor.GRAY));
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[5] ヘルプ", "/gemini 5", "クリックでヘルプを表示", net.md_5.bungee.api.ChatColor.BLUE));
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[6] ステータス", "/gemini 6", "クリックでステータスを表示", net.md_5.bungee.api.ChatColor.GRAY));
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[7] ライブラリ", "/gemini 7", "クリックで画像ライブラリを表示", net.md_5.bungee.api.ChatColor.GOLD),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            text("過去の画像", net.md_5.bungee.api.ChatColor.GRAY));
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[8] 履歴", "/gemini 8", "クリックで会話履歴を表示", net.md_5.bungee.api.ChatColor.DARK_AQUA),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            text("過去の会話履歴", net.md_5.bungee.api.ChatColor.GRAY));
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  ─────────────────────────────────");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[終了]", "/gemini exit", "クリックでシステム終了", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
    }

    private void startChatMode(Player player) {
        UUID playerId = player.getUniqueId();
        playerSessionMode.put(playerId, SessionMode.CHAT);
        String model = getModelDisplayName(getPlayerModel(playerId));

        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.AQUA + "║  " + ChatColor.WHITE + "相談モード" + ChatColor.AQUA + "                                 ║");
        player.sendMessage(ChatColor.AQUA + "╠═══════════════════════════════════════════════╣");
        player.sendMessage(ChatColor.AQUA + "║ " + ChatColor.GRAY + "モデル: " + ChatColor.WHITE + model + ChatColor.AQUA + "               ║");
        player.sendMessage(ChatColor.AQUA + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  使い方:");
        player.sendMessage(ChatColor.GREEN + "    チャットで相談内容を入力" + ChatColor.GRAY + "（例: " + ChatColor.WHITE + "悩みがある" + ChatColor.GRAY + "）");
        player.sendMessage("");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[モデル変更]", "/gemini model", "クリックでモデル変更", net.md_5.bungee.api.ChatColor.YELLOW),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "[" + npcName + "] " + ChatColor.WHITE +
            "こんにちは！何か困っていることはありますか？気軽に話してくださいね。");
        player.sendMessage("");
    }

    private void startSearchMode(Player player) {
        UUID playerId = player.getUniqueId();
        playerSessionMode.put(playerId, SessionMode.SEARCH);
        String model = getModelDisplayName(getPlayerModel(playerId));

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GREEN + "║  " + ChatColor.WHITE + "検索モード" + ChatColor.GREEN + "                                 ║");
        player.sendMessage(ChatColor.GREEN + "╠═══════════════════════════════════════════════╣");
        player.sendMessage(ChatColor.GREEN + "║ " + ChatColor.GRAY + "モデル: " + ChatColor.WHITE + model + ChatColor.GREEN + "               ║");
        player.sendMessage(ChatColor.GREEN + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  使い方:");
        player.sendMessage(ChatColor.GREEN + "    チャットで検索ワードを入力" + ChatColor.GRAY + " → 検索する");
        player.sendMessage("");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[モデル変更]", "/gemini model", "クリックでモデル変更", net.md_5.bungee.api.ChatColor.YELLOW),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  例: " + ChatColor.WHITE + "Minecraft 建築 コツ");
        player.sendMessage(ChatColor.GRAY + "  例: " + ChatColor.WHITE + "レッドストーン回路 初心者");
        player.sendMessage("");
    }

    private void startImageMode(Player player) {
        UUID playerId = player.getUniqueId();
        playerSessionMode.put(playerId, SessionMode.IMAGE);
        String imageModel = getImageModelDisplayName(getPlayerImageModel(playerId));
        String ratio = getPlayerAspectRatio(playerId);
        String res = getPlayerResolution(playerId);

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "║  " + ChatColor.WHITE + "画像生成モード" + ChatColor.LIGHT_PURPLE + "                             ║");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "╠═══════════════════════════════════════════════╣");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "║ " + ChatColor.GRAY + "モデル: " + ChatColor.WHITE + imageModel + ChatColor.LIGHT_PURPLE + "    ║");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "║ " + ChatColor.GRAY + "比率: " + ChatColor.WHITE + ratio + " " + ChatColor.GRAY + getAspectRatioDescription(ratio) + ChatColor.LIGHT_PURPLE + "               ║");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "║ " + ChatColor.GRAY + "解像度: " + ChatColor.WHITE + getResolutionDisplayName(res) + ChatColor.LIGHT_PURPLE + "                  ║");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  画像を生成:");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "    チャットで画像の説明を入力" + ChatColor.GRAY + " → 画像を生成");
        player.sendMessage(ChatColor.GRAY + "      例: " + ChatColor.WHITE + "夕焼けの海");
        player.sendMessage(ChatColor.GRAY + "      例: " + ChatColor.WHITE + "かわいい猫がMinecraftで遊んでいる");
        player.sendMessage(ChatColor.GRAY + "      ※ローマ字OK: yuuyakenoumi → 夕焼けの海");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  画像から画像を生成" + ChatColor.GRAY + " (Image-to-Image):");
        player.sendMessage(ChatColor.AQUA + "    https://画像URL プロンプト" + ChatColor.GRAY + " → 画像を変換");
        player.sendMessage(ChatColor.GRAY + "      例: " + ChatColor.WHITE + "https://example.com/img.png animefuunisite");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  操作" + ChatColor.GRAY + " (クリック):");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[設定一覧]", "/gemini image settings", "クリックで設定を表示", net.md_5.bungee.api.ChatColor.YELLOW),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[モデル変更]", "/gemini imagemodel", "クリックでモデル選択", net.md_5.bungee.api.ChatColor.AQUA),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[比率変更]", "/gemini image settings", "クリックで比率を変更", net.md_5.bungee.api.ChatColor.GREEN));
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[ライブラリ]", "/gemini library", "クリックで画像ライブラリを表示", net.md_5.bungee.api.ChatColor.GOLD),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
    }

    private void startCommandMode(Player player) {
        UUID playerId = player.getUniqueId();
        playerSessionMode.put(playerId, SessionMode.COMMAND);
        String model = getModelDisplayName(getPlayerModel(playerId));

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "コマンド生成モード" + ChatColor.GOLD + "                         ║");
        player.sendMessage(ChatColor.GOLD + "╠═══════════════════════════════════════════════╣");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "モデル: " + ChatColor.WHITE + model + ChatColor.GOLD + "               ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  使い方:");
        player.sendMessage(ChatColor.GRAY + "    チャットでやりたいことを入力 → AIがコマンドを生成");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  例:");
        player.sendMessage(ChatColor.GRAY + "    最強の剣を作って");
        player.sendMessage(ChatColor.GRAY + "    ダイヤの装備を全部ください");
        player.sendMessage(ChatColor.GRAY + "    saikyo no ken wo tukutte");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "─────────────────────────────────");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[モデル変更]", "/gemini model", "クリックでモデル変更", net.md_5.bungee.api.ChatColor.YELLOW),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
    }

    private void processCommandGeneration(Player player, String userRequest) {
        UUID playerId = player.getUniqueId();
        String playerModel = getPlayerModel(playerId);

        JsonArray contents = new JsonArray();
        JsonObject userPart = new JsonObject();
        userPart.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", userRequest);
        userParts.add(textPart);
        userPart.add("parts", userParts);
        contents.add(userPart);

        String response = callGeminiAPIInternal(contents, playerModel, getCommandGenerationSystemPrompt());

        Bukkit.getScheduler().runTask(this, () -> {
            if (response == null || response.isEmpty()) {
                player.sendMessage(ChatColor.RED + "コマンドを生成できませんでした。別の表現で試してください。");
                return;
            }
            displayCommandResult(player, response, userRequest);
        });
    }

    private void displayCommandResult(Player player, String response, String originalRequest) {
        String cleanResponse = stripMarkdown(response);
        List<String> commands = new ArrayList<>();
        String explanation = null;

        for (String line : cleanResponse.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("COMMAND:")) {
                commands.add(trimmed.substring("COMMAND:".length()).trim());
            } else if (trimmed.startsWith("EXPLAIN:")) {
                explanation = trimmed.substring("EXPLAIN:".length()).trim();
            }
        }

        // Fallback: if format not matched, look for lines starting with /
        if (commands.isEmpty()) {
            for (String line : cleanResponse.split("\n")) {
                if (line.trim().startsWith("/")) {
                    commands.add(line.trim());
                    break;
                }
            }
            if (explanation == null) {
                String cmdStr = commands.isEmpty() ? "" : commands.get(0);
                explanation = cleanResponse.replace(cmdStr, "").trim();
            }
        }

        if (commands.isEmpty()) {
            player.sendMessage(ChatColor.RED + "コマンドを生成できませんでした。");
            player.sendMessage(ChatColor.GRAY + cleanResponse);
            return;
        }

        // Display result UI
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "コマンド生成結果" + ChatColor.GOLD + "                           ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  リクエスト: " + ChatColor.WHITE + originalRequest);
        player.sendMessage("");
        player.sendMessage(ChatColor.YELLOW + "  コマンド:");

        // Auto-fix snake_case and syntax issues in AI-generated commands
        for (int i = 0; i < commands.size(); i++) {
            commands.set(i, fixCommandSyntax(commands.get(i)));
        }

        for (int i = 0; i < commands.size(); i++) {
            String cmd = commands.get(i);
            if (commands.size() > 1) {
                player.sendMessage(ChatColor.AQUA + "  " + (i + 1) + ". " + cmd);
            } else {
                player.sendMessage(ChatColor.AQUA + "  " + cmd);
            }
            sendClickableLine(player,
                text("     ", net.md_5.bungee.api.ChatColor.WHITE),
                createClickableButton("[実行]", cmd, "クリックでコマンドを実行", net.md_5.bungee.api.ChatColor.GREEN, true),
                text(" ", net.md_5.bungee.api.ChatColor.GRAY),
                createSuggestButton("[コピー]", cmd, "クリックでチャットバーにコピー", net.md_5.bungee.api.ChatColor.YELLOW, false));
        }

        player.sendMessage("");

        if (explanation != null && !explanation.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "  説明:");
            for (String wl : wrapText(explanation, 45)) {
                player.sendMessage(ChatColor.WHITE + "  " + wl);
            }
            player.sendMessage("");
        }

        sendModeFooter(player);

        // Log to spreadsheet
        String cmdList = String.join("\n", commands);
        logToSpreadsheet(player, "コマンド生成", getPlayerModel(player.getUniqueId()),
            originalRequest, cmdList + (explanation != null ? "\n" + explanation : ""));
    }

    private String fixCommandSyntax(String command) {
        if (command == null || command.isEmpty()) return command;

        // Fix item IDs (missing underscores)
        command = command.replace("diamondsword", "diamond_sword");
        command = command.replace("netheritesword", "netherite_sword");
        command = command.replace("ironsword", "iron_sword");
        command = command.replace("stonesword", "stone_sword");
        command = command.replace("goldensword", "golden_sword");
        command = command.replace("woodensword", "wooden_sword");
        command = command.replace("diamondpickaxe", "diamond_pickaxe");
        command = command.replace("netheritepickaxe", "netherite_pickaxe");
        command = command.replace("ironpickaxe", "iron_pickaxe");
        command = command.replace("diamondaxe", "diamond_axe");
        command = command.replace("netheriteaxe", "netherite_axe");
        command = command.replace("diamondshovel", "diamond_shovel");
        command = command.replace("netheriteshovel", "netherite_shovel");
        command = command.replace("diamondhoe", "diamond_hoe");
        command = command.replace("netheritehoe", "netherite_hoe");
        command = command.replace("diamondhelmet", "diamond_helmet");
        command = command.replace("netheritehelmet", "netherite_helmet");
        command = command.replace("ironhelmet", "iron_helmet");
        command = command.replace("diamondchestplate", "diamond_chestplate");
        command = command.replace("netheritechestplate", "netherite_chestplate");
        command = command.replace("ironchestplate", "iron_chestplate");
        command = command.replace("diamondleggings", "diamond_leggings");
        command = command.replace("netheriteleggings", "netherite_leggings");
        command = command.replace("ironleggings", "iron_leggings");
        command = command.replace("diamondboots", "diamond_boots");
        command = command.replace("netheriteboots", "netherite_boots");
        command = command.replace("ironboots", "iron_boots");
        command = command.replace("goldenapple", "golden_apple");
        command = command.replace("enchantedgoldenapple", "enchanted_golden_apple");
        command = command.replace("enderpearl", "ender_pearl");
        command = command.replace("splashpotion", "splash_potion");
        command = command.replace("lingeringpotion", "lingering_potion");
        command = command.replace("totemofundying", "totem_of_undying");
        command = command.replace("nametag", "name_tag");
        command = command.replace("lightningbolt", "lightning_bolt");
        command = command.replace("irongolem", "iron_golem");
        // Fix spear IDs (1.21.11)
        command = command.replace("woodenspear", "wooden_spear");
        command = command.replace("stonespear", "stone_spear");
        command = command.replace("copperspear", "copper_spear");
        command = command.replace("ironspear", "iron_spear");
        command = command.replace("goldenspear", "golden_spear");
        command = command.replace("diamondspear", "diamond_spear");
        command = command.replace("netheritespear", "netherite_spear");

        // Fix component names (missing underscores)
        command = command.replace("customname=", "custom_name=");
        command = command.replace("customname =", "custom_name =");
        command = command.replace("dyedcolor=", "dyed_color=");
        command = command.replace("potioncontents=", "potion_contents=");
        command = command.replace("customeffects", "custom_effects");

        // Fix enchantment IDs (missing underscores)
        command = command.replace("fireaspect", "fire_aspect");
        command = command.replace("baneofarthropods", "bane_of_arthropods");
        command = command.replace("sweepingedge", "sweeping_edge");
        command = command.replace("silktouch", "silk_touch");
        command = command.replace("fireprotection", "fire_protection");
        command = command.replace("blastprotection", "blast_protection");
        command = command.replace("projectileprotection", "projectile_protection");
        command = command.replace("featherfalling", "feather_falling");
        command = command.replace("depthstrider", "depth_strider");
        command = command.replace("frostwalker", "frost_walker");
        command = command.replace("soulspeed", "soul_speed");
        command = command.replace("swiftsneak", "swift_sneak");
        command = command.replace("quickcharge", "quick_charge");
        command = command.replace("aquaaffinity", "aqua_affinity");
        command = command.replace("windcharge", "wind_charge");
        command = command.replace("windburst", "wind_burst");
        command = command.replace("miningfatigue", "mining_fatigue");

        // Fix effect IDs (missing underscores)
        command = command.replace("jumpboost", "jump_boost");
        command = command.replace("instanthealth", "instant_health");
        command = command.replace("instantdamage", "instant_damage");
        command = command.replace("fireresistance", "fire_resistance");
        command = command.replace("waterbreathing", "water_breathing");
        command = command.replace("nightvision", "night_vision");
        command = command.replace("slowfalling", "slow_falling");
        command = command.replace("conduitpower", "conduit_power");
        command = command.replace("dolphinsgrace", "dolphins_grace");
        command = command.replace("windcharged", "wind_charged");
        command = command.replace("trialomen", "trial_omen");
        command = command.replace("raidomen", "raid_omen");
        command = command.replace("badomen", "bad_omen");

        // Fix enchantments: unwrap levels: key if present (MC 1.21.5+ uses flat format)
        // Pattern: enchantments={levels:{sharpness:5,...}} -> enchantments={sharpness:5,...}
        java.util.regex.Pattern levelsPattern = java.util.regex.Pattern.compile(
            "enchantments=\\{levels:\\{([^}]+)\\}\\}");
        java.util.regex.Matcher levelsMatcher = levelsPattern.matcher(command);
        StringBuffer sb = new StringBuffer();
        while (levelsMatcher.find()) {
            String inner = levelsMatcher.group(1);
            // Remove double quotes from SNBT keys
            inner = inner.replaceAll("\"([a-z_]+)\":", "$1:");
            levelsMatcher.appendReplacement(sb, "enchantments={" + inner + "}");
        }
        levelsMatcher.appendTail(sb);
        command = sb.toString();

        // Also remove double quotes from SNBT keys in flat enchantments format
        java.util.regex.Pattern enchQuotePattern = java.util.regex.Pattern.compile(
            "(enchantments=\\{)([^}]+)(\\})");
        java.util.regex.Matcher enchQuoteMatcher = enchQuotePattern.matcher(command);
        sb = new StringBuffer();
        while (enchQuoteMatcher.find()) {
            String inner = enchQuoteMatcher.group(2);
            inner = inner.replaceAll("\"([a-z_]+)\":", "$1:");
            enchQuoteMatcher.appendReplacement(sb,
                java.util.regex.Matcher.quoteReplacement(enchQuoteMatcher.group(1) + inner + enchQuoteMatcher.group(3)));
        }
        enchQuoteMatcher.appendTail(sb);
        command = sb.toString();

        // Fix custom_name: convert JSON text component to SNBT direct format (MC 1.21.5+)
        // Pattern: custom_name='{"text":"NAME","italic":false}' -> custom_name={text:'NAME',italic:false}
        // Also: custom_name='{"text":"NAME","color":"COLOR","italic":false}'
        java.util.regex.Pattern cnJsonPattern = java.util.regex.Pattern.compile(
            "custom_name='\\{\"text\":\"([^\"]*?)\"(?:,\"color\":\"([^\"]*?)\")?(?:,\"bold\":(true|false))?(?:,\"italic\":(true|false))?\\}'");
        java.util.regex.Matcher cnJsonMatcher = cnJsonPattern.matcher(command);
        sb = new StringBuffer();
        while (cnJsonMatcher.find()) {
            String text = cnJsonMatcher.group(1);
            String color = cnJsonMatcher.group(2);
            String bold = cnJsonMatcher.group(3);
            String italic = cnJsonMatcher.group(4);
            StringBuilder snbt = new StringBuilder("custom_name={text:'");
            snbt.append(text).append("'");
            if (color != null) snbt.append(",color:'").append(color).append("'");
            if (bold != null) snbt.append(",bold:").append(bold);
            if (italic != null) snbt.append(",italic:").append(italic);
            snbt.append("}");
            cnJsonMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(snbt.toString()));
        }
        cnJsonMatcher.appendTail(sb);
        command = sb.toString();

        // Fix lore: convert JSON format to SNBT direct format
        // Pattern: lore=['{"text":"TEXT"}'] -> lore=[{text:'TEXT'}]
        command = command.replaceAll("'\\{\"text\":\"([^\"]*?)\"\\}'", "{text:'$1'}");

        return command;
    }

    private String getCommandGenerationSystemPrompt() {
        return "あなたはMinecraft Java Edition 1.21.5以降 コマンド生成の専門AIです。\n" +
               "ユーザーの自然言語リクエストからMinecraftコマンドを生成してください。\n\n" +
               "【必ず以下のフォーマットで回答】\n" +
               "COMMAND: /生成したコマンド\n" +
               "EXPLAIN: コマンドの説明（1-2文、日本語）\n\n" +
               "【複数コマンドが必要な場合】\n" +
               "COMMAND: /コマンド1\n" +
               "COMMAND: /コマンド2\n" +
               "EXPLAIN: 全体の説明\n\n" +
               "【ローマ字入力の解釈】\n" +
               "ユーザーはMinecraftチャットからローマ字で入力する場合があります:\n" +
               "- 'saikyo no ken' → '最強の剣'\n" +
               "- 'sora wo toberu kutsu' → '空を飛べる靴'\n" +
               "- 'daiya no yoroi zenbu' → 'ダイヤの鎧全部'\n\n" +
               "【★★★ 最重要ルール ★★★】\n\n" +
               "★ ルール1: snake_case 必須 ★\n" +
               "Minecraftの全てのIDはsnake_case（アンダースコア区切り）です。\n" +
               "絶対に単語を繋げて書かないでください。\n" +
               "正: diamond_sword, fire_aspect, bane_of_arthropods, sweeping_edge, custom_name\n" +
               "誤: diamondsword, fireaspect, baneofarthropods, sweepingedge, customname\n\n" +
               "★ ルール2: enchantments はフラット形式（levels: キーは使わない） ★\n" +
               "1.21.5以降では enchantments コンポーネントはフラットマップ形式です。\n" +
               "正: enchantments={sharpness:255}\n" +
               "誤: enchantments={levels:{sharpness:255}}\n" +
               "誤: enchantments={\"sharpness\":255}\n\n" +
               "★ ルール3: SNBT形式ではキーにダブルクォートを付けない ★\n" +
               "正: {sharpness:255,fire_aspect:2}\n" +
               "誤: {\"sharpness\":255,\"fire_aspect\":2}\n\n" +
               "【Minecraft 1.21.5+ コマンド構文 (データコンポーネント形式)】\n\n" +
               "=== /give コマンド ===\n" +
               "/give <対象> <アイテムID>[コンポーネント1=値,コンポーネント2=値] [個数]\n\n" +
               "--- エンチャント (フラット形式・levels: 不要) ---\n" +
               "形式: enchantments={エンチャントID:レベル,エンチャントID:レベル}\n" +
               "例: /give @p netherite_sword[enchantments={sharpness:255,fire_aspect:2,knockback:2,looting:3,sweeping_edge:3,unbreaking:3,mending:1}]\n\n" +
               "--- カスタム名 (SNBT直接形式 ※JSONではない) ---\n" +
               "形式: custom_name={text:'名前',italic:false}\n" +
               "例: /give @p diamond_sword[custom_name={text:'伝説の剣',italic:false}]\n" +
               "色付き: custom_name={text:'炎の剣',color:'red',italic:false}\n" +
               "★絶対に custom_name='{\"text\":...}' のJSON形式は使わない★\n\n" +
               "--- ポーション ---\n" +
               "形式: potion_contents={custom_effects:[{id:エフェクトID,amplifier:N,duration:T}]}\n" +
               "例: /give @p potion[potion_contents={custom_effects:[{id:strength,amplifier:255,duration:999999}]}]\n\n" +
               "--- その他コンポーネント ---\n" +
               "耐久無限: unbreakable={}\n" +
               "染色: dyed_color={rgb:16711680}\n" +
               "説明文: lore=[{text:'説明文'},{text:'2行目'}]\n\n" +
               "--- 最強装備の完全な正しい例 ---\n" +
               "/give @p diamond_sword[custom_name={text:'最強の剣',italic:false},unbreakable={},enchantments={sharpness:255,smite:255,bane_of_arthropods:255,fire_aspect:255,knockback:255,looting:255,sweeping_edge:255,unbreaking:255,mending:1}] 1\n\n" +
               "--- ネザライト全身装備の例 ---\n" +
               "COMMAND: /give @p netherite_helmet[custom_name={text:'最強のヘルメット',italic:false},unbreakable={},enchantments={protection:255,fire_protection:255,blast_protection:255,projectile_protection:255,respiration:3,aqua_affinity:1,thorns:255,unbreaking:255,mending:1}] 1\n" +
               "COMMAND: /give @p netherite_chestplate[custom_name={text:'最強のチェストプレート',italic:false},unbreakable={},enchantments={protection:255,fire_protection:255,blast_protection:255,projectile_protection:255,thorns:255,unbreaking:255,mending:1}] 1\n" +
               "COMMAND: /give @p netherite_leggings[custom_name={text:'最強のレギンス',italic:false},unbreakable={},enchantments={protection:255,fire_protection:255,blast_protection:255,projectile_protection:255,thorns:255,swift_sneak:3,unbreaking:255,mending:1}] 1\n" +
               "COMMAND: /give @p netherite_boots[custom_name={text:'最強のブーツ',italic:false},unbreakable={},enchantments={protection:255,fire_protection:255,blast_protection:255,projectile_protection:255,feather_falling:255,depth_strider:3,soul_speed:3,thorns:255,unbreaking:255,mending:1}] 1\n\n" +
               "=== エンチャントID一覧 (全てsnake_case) ===\n" +
               "剣: sharpness, smite, bane_of_arthropods, fire_aspect, knockback, looting, sweeping_edge\n" +
               "弓: power, punch, flame, infinity\n" +
               "防具: protection, fire_protection, blast_protection, projectile_protection, thorns\n" +
               "ツール: efficiency, silk_touch, fortune, unbreaking, mending\n" +
               "ブーツ: feather_falling, depth_strider, frost_walker, soul_speed, swift_sneak\n" +
               "トライデント: riptide, loyalty, channeling, impaling\n" +
               "クロスボウ: quick_charge, multishot, piercing\n" +
               "槍(spear): lunge, unbreaking, mending\n" +
               "その他: aqua_affinity, respiration, wind_burst, density, breach\n\n" +
               "=== アイテムID例 (全てsnake_case) ===\n" +
               "diamond_sword, netherite_sword, iron_sword, diamond_pickaxe, netherite_pickaxe\n" +
               "diamond_helmet, diamond_chestplate, diamond_leggings, diamond_boots\n" +
               "netherite_helmet, netherite_chestplate, netherite_leggings, netherite_boots\n" +
               "槍: wooden_spear, stone_spear, copper_spear, iron_spear, golden_spear, diamond_spear, netherite_spear\n" +
               "enchanted_golden_apple, ender_pearl, golden_apple, splash_potion, lingering_potion\n" +
               "elytra, shield, bow, crossbow, trident, totem_of_undying, name_tag, mace\n\n" +
               "=== /summon コマンド ===\n" +
               "/summon <エンティティID> [座標] [NBTデータ]\n" +
               "例: /summon zombie ~ ~ ~ {IsBaby:1b,ArmorItems:[{},{},{},{id:\"diamond_helmet\",count:1}]}\n" +
               "例: /summon lightning_bolt ~ ~ ~\n" +
               "例: /summon creeper ~ ~ ~ {Fuse:0,ExplosionRadius:10}\n\n" +
               "主なエンティティ: zombie, skeleton, creeper, spider, enderman, wither,\n" +
               "  ender_dragon, blaze, ghast, phantom, warden, iron_golem, villager,\n" +
               "  wolf, cat, horse, pig, cow, sheep, chicken, bee, axolotl, allay,\n" +
               "  camel, sniffer, armadillo, breeze, bogged\n\n" +
               "=== /effect コマンド ===\n" +
               "/effect give <対象> <エフェクトID> [秒数] [レベル] [パーティクル非表示]\n" +
               "例: /effect give @p speed 999999 255 true\n\n" +
               "主なエフェクト: speed, slowness, haste, mining_fatigue, strength,\n" +
               "  instant_health, instant_damage, jump_boost, nausea, regeneration,\n" +
               "  resistance, fire_resistance, water_breathing, invisibility,\n" +
               "  night_vision, saturation, glowing, levitation, slow_falling,\n" +
               "  conduit_power, dolphins_grace, darkness, wind_charged,\n" +
               "  weaving, oozing, infested, trial_omen, raid_omen, bad_omen\n\n" +
               "=== その他のコマンド ===\n" +
               "/gamemode <モード> [対象] (creative/survival/adventure/spectator)\n" +
               "/tp <対象> <x> <y> <z>\n" +
               "/weather <天候> [秒数] (clear/rain/thunder)\n" +
               "/time set <値> (day/noon/night/midnight/0-24000)\n" +
               "/kill <対象>\n" +
               "/clear <対象> [アイテム] [個数]\n" +
               "/xp add <対象> <量> [levels/points]\n" +
               "/enchant <対象> <エンチャント> [レベル]\n" +
               "/fill <x1> <y1> <z1> <x2> <y2> <z2> <ブロック> [replace/destroy/keep]\n" +
               "/setblock <x> <y> <z> <ブロック>\n" +
               "/clone <x1> <y1> <z1> <x2> <y2> <z2> <dx> <dy> <dz>\n" +
               "/particle <パーティクル> <x> <y> <z>\n" +
               "/playsound <サウンド> <ソース> <対象>\n" +
               "/title <対象> title {\"text\":\"メッセージ\"}\n" +
               "/gamerule <ルール> <値>\n\n" +
               "=== 対象セレクター ===\n" +
               "@p (最寄りプレイヤー), @a (全プレイヤー), @r (ランダム), @e (全エンティティ), @s (実行者)\n" +
               "引数: @e[type=zombie,distance=..10,limit=1,sort=nearest]\n\n" +
               "【重要なルール - 必ず全て守ること】\n" +
               "- コマンドは必ず / で始める\n" +
               "- 全てのIDはsnake_case必須（diamond_sword, fire_aspect）絶対に単語を繋げない\n" +
               "- enchantmentsはフラット形式: enchantments={sharpness:255} (levels: は使わない)\n" +
               "- SNBTのキーにダブルクォートは付けない: {sharpness:255} が正しい\n" +
               "- custom_nameはSNBT直接形式: custom_name={text:'名前',italic:false}\n" +
               "- ユーザーがエンチャントの種類やレベルを具体的に指定した場合は、その指定に正確に従う\n" +
               "- 「最強」等の曖昧な指定の場合のみレベル255で全関連エンチャント付与\n" +
               "- 対象が指定されなければ @p を使用\n" +
               "- 座標が指定されなければ ~ ~ ~ を使用\n" +
               "- 実行不可能なリクエストの場合、最も近い実現可能なコマンドを生成し、制限を説明\n" +
               "- COMMAND:行とEXPLAIN:行以外は出力しない";
    }

    private void showImageModelSelection(Player player) {
        UUID playerId = player.getUniqueId();
        String currentModel = getPlayerImageModel(playerId);

        boolean isNanobanana = MODEL_NANOBANANA.equals(currentModel);
        boolean isNanobananaPro = MODEL_NANOBANANA_PRO.equals(currentModel);

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "画像AIモデル選択" + ChatColor.GRAY + " (クリックで変更)" + ChatColor.GOLD + "         ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");

        // Nanobanana
        if (isNanobanana) {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GREEN),
                text("► ", net.md_5.bungee.api.ChatColor.GREEN),
                createClickableButton("[Nanobanana]", "/gemini imagemodel nanobanana", "現在使用中", net.md_5.bungee.api.ChatColor.AQUA, true),
                text(" - 高速  ", net.md_5.bungee.api.ChatColor.WHITE),
                text("[使用中]", net.md_5.bungee.api.ChatColor.GREEN));
        } else {
            sendClickableLine(player,
                text("    ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[Nanobanana]", "/gemini imagemodel nanobanana", "クリックでNanobananaに変更", net.md_5.bungee.api.ChatColor.AQUA),
                text(" - 高速", net.md_5.bungee.api.ChatColor.GRAY));
        }
        player.sendMessage(ChatColor.GRAY + "      速度: ★★★★★ | 高速で軽快な画像生成");
        player.sendMessage(ChatColor.GRAY + "      解像度: 最大1K (1024px) | ~$0.04/枚");
        player.sendMessage("");

        // Nanobanana Pro
        if (isNanobananaPro) {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GREEN),
                text("► ", net.md_5.bungee.api.ChatColor.GREEN),
                createClickableButton("[Nanobanana Pro]", "/gemini imagemodel nanobanana-pro", "現在使用中", net.md_5.bungee.api.ChatColor.LIGHT_PURPLE, true),
                text(" - 高品質  ", net.md_5.bungee.api.ChatColor.WHITE),
                text("[使用中]", net.md_5.bungee.api.ChatColor.GREEN));
        } else {
            sendClickableLine(player,
                text("    ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[Nanobanana Pro]", "/gemini imagemodel nanobanana-pro", "クリックでNanobanana Proに変更", net.md_5.bungee.api.ChatColor.LIGHT_PURPLE),
                text(" - 高品質", net.md_5.bungee.api.ChatColor.GRAY));
        }
        player.sendMessage(ChatColor.GRAY + "      品質: ★★★★★ | 高品質・4K対応");
        player.sendMessage(ChatColor.GRAY + "      解像度: 最大4K (4096px) | ~$0.13-0.24/枚");
        player.sendMessage("");

        player.sendMessage(ChatColor.GRAY + "  ─────────────────────────────────");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[設定に戻る]", "/gemini image settings", "クリックで設定一覧に戻る", net.md_5.bungee.api.ChatColor.YELLOW),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
    }

    private void handleImageModelChange(Player player, String modelArg) {
        UUID playerId = player.getUniqueId();
        String newModel;

        switch (modelArg.toLowerCase()) {
            case "nanobanana":
            case "nano":
            case "banana":
            case "nb":
            case "flash":
            case "1":
                newModel = MODEL_NANOBANANA;
                break;
            case "nanobanana-pro":
            case "nanobananapro":
            case "nb-pro":
            case "nbpro":
            case "pro":
            case "2":
                newModel = MODEL_NANOBANANA_PRO;
                break;
            default:
                player.sendMessage(ChatColor.RED + "不明な画像モデル: " + modelArg);
                player.sendMessage(ChatColor.YELLOW + "利用可能: nanobanana, nanobanana-pro");
                return;
        }

        String oldModel = getPlayerImageModel(playerId);
        if (newModel.equals(oldModel)) {
            player.sendMessage(ChatColor.YELLOW + "既に " + getImageModelDisplayName(newModel) + " を使用中です。");
            return;
        }

        playerImageModels.put(playerId, newModel);

        // Auto-adjust resolution when switching models
        if (MODEL_NANOBANANA.equals(newModel)) {
            // Flash only supports 1K - reset if higher was set
            String currentRes = playerResolutions.getOrDefault(playerId, defaultResolution);
            if (!RESOLUTION_1K.equals(currentRes)) {
                playerResolutions.put(playerId, RESOLUTION_1K);
            }
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "✓ 画像モデルを " + ChatColor.LIGHT_PURPLE + getImageModelDisplayName(newModel) + ChatColor.GREEN + " に変更しました。");

        if (newModel.equals(MODEL_NANOBANANA_PRO)) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "  Nanobanana Proは高品質ですが、生成に時間がかかる場合があります。");
        } else if (newModel.equals(MODEL_NANOBANANA)) {
            player.sendMessage(ChatColor.GRAY + "  Nanobananaは1K解像度固定です。");
        }

        // Auto-refresh settings view if in IMAGE mode
        if (getSessionMode(playerId) == SessionMode.IMAGE) {
            showImageSettings(player);
        }
    }

    private void showImageSettings(Player player) {
        UUID playerId = player.getUniqueId();
        String currentModel = getPlayerImageModel(playerId);
        String currentRatio = getPlayerAspectRatio(playerId);
        String currentRes = getPlayerResolution(playerId);
        boolean isPro = MODEL_NANOBANANA_PRO.equals(currentModel);

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "║  " + ChatColor.WHITE + "画像生成 設定" + ChatColor.GRAY + "  (クリックで変更)" + ChatColor.LIGHT_PURPLE + "          ║");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");

        // === Model selection ===
        player.sendMessage(ChatColor.GREEN + "  【モデル】");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.GRAY),
            MODEL_NANOBANANA.equals(currentModel)
                ? createClickableButton("[Nanobanana ✓]", "/gemini imagemodel nanobanana", "使用中: Nanobanana (高速)", net.md_5.bungee.api.ChatColor.GREEN, true)
                : createClickableButton("[Nanobanana]", "/gemini imagemodel nanobanana", "クリックで変更: 高速・1Kのみ (~$0.04/枚)", net.md_5.bungee.api.ChatColor.AQUA),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            isPro
                ? createClickableButton("[Nanobanana Pro ✓]", "/gemini imagemodel nanobanana-pro", "使用中: Nanobanana Pro (高品質)", net.md_5.bungee.api.ChatColor.GREEN, true)
                : createClickableButton("[Nanobanana Pro]", "/gemini imagemodel nanobanana-pro", "クリックで変更: 高品質・4K対応 (~$0.13-0.24/枚)", net.md_5.bungee.api.ChatColor.LIGHT_PURPLE));
        player.sendMessage("");

        // === Aspect ratio selection ===
        player.sendMessage(ChatColor.GREEN + "  【アスペクト比】");
        // Row 1: first 5 ratios
        TextComponent ratioRow1 = new TextComponent("    ");
        for (int i = 0; i < 5 && i < VALID_ASPECT_RATIOS.size(); i++) {
            String ratio = VALID_ASPECT_RATIOS.get(i);
            boolean active = ratio.equals(currentRatio);
            TextComponent btn;
            if (active) {
                btn = createClickableButton("[" + ratio + "]", "/gemini image ratio " + ratio, "使用中: " + ratio + " " + getAspectRatioDescription(ratio), net.md_5.bungee.api.ChatColor.GREEN, true);
            } else {
                btn = createClickableButton("[" + ratio + "]", "/gemini image ratio " + ratio, "クリックで変更: " + ratio + " " + getAspectRatioDescription(ratio), net.md_5.bungee.api.ChatColor.WHITE);
            }
            ratioRow1.addExtra(btn);
            ratioRow1.addExtra(new TextComponent(" "));
        }
        player.spigot().sendMessage(ratioRow1);
        // Row 2: remaining ratios
        TextComponent ratioRow2 = new TextComponent("    ");
        for (int i = 5; i < VALID_ASPECT_RATIOS.size(); i++) {
            String ratio = VALID_ASPECT_RATIOS.get(i);
            boolean active = ratio.equals(currentRatio);
            TextComponent btn;
            if (active) {
                btn = createClickableButton("[" + ratio + "]", "/gemini image ratio " + ratio, "使用中: " + ratio + " " + getAspectRatioDescription(ratio), net.md_5.bungee.api.ChatColor.GREEN, true);
            } else {
                btn = createClickableButton("[" + ratio + "]", "/gemini image ratio " + ratio, "クリックで変更: " + ratio + " " + getAspectRatioDescription(ratio), net.md_5.bungee.api.ChatColor.WHITE);
            }
            ratioRow2.addExtra(btn);
            ratioRow2.addExtra(new TextComponent(" "));
        }
        player.spigot().sendMessage(ratioRow2);
        player.sendMessage("");

        // === Resolution selection ===
        player.sendMessage(ChatColor.GREEN + "  【解像度】" + ChatColor.GRAY + (isPro ? "" : " (Proのみ変更可)"));
        if (isPro) {
            sendClickableLine(player,
                text("    ", net.md_5.bungee.api.ChatColor.GRAY),
                RESOLUTION_1K.equals(currentRes)
                    ? createClickableButton("[1K ✓]", "/gemini image resolution 1K", "使用中: 1K (1024px ~$0.13/枚)", net.md_5.bungee.api.ChatColor.GREEN, true)
                    : createClickableButton("[1K]", "/gemini image resolution 1K", "クリックで変更: 1K (1024px ~$0.13/枚)", net.md_5.bungee.api.ChatColor.WHITE),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                RESOLUTION_2K.equals(currentRes)
                    ? createClickableButton("[2K ✓]", "/gemini image resolution 2K", "使用中: 2K (2048px ~$0.13/枚)", net.md_5.bungee.api.ChatColor.GREEN, true)
                    : createClickableButton("[2K]", "/gemini image resolution 2K", "クリックで変更: 2K (2048px ~$0.13/枚)", net.md_5.bungee.api.ChatColor.WHITE),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                RESOLUTION_4K.equals(currentRes)
                    ? createClickableButton("[4K ✓]", "/gemini image resolution 4K", "使用中: 4K (4096px ~$0.24/枚 高コスト)", net.md_5.bungee.api.ChatColor.GREEN, true)
                    : createClickableButton("[4K]", "/gemini image resolution 4K", "クリックで変更: 4K (4096px ~$0.24/枚 高コスト!)", net.md_5.bungee.api.ChatColor.RED));
        } else {
            sendClickableLine(player,
                text("    ", net.md_5.bungee.api.ChatColor.GRAY),
                text("[1K] ", net.md_5.bungee.api.ChatColor.GREEN),
                text("1024px (Nanobananaは1K固定)", net.md_5.bungee.api.ChatColor.GRAY));
        }
        player.sendMessage("");

        player.sendMessage(ChatColor.GRAY + "  ─────────────────────────────────");
        sendClickableLine(player,
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createSuggestButton("[画像を生成する]", "", "画像の説明をチャットで入力", net.md_5.bungee.api.ChatColor.LIGHT_PURPLE, true),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
    }

    private void handleAspectRatioChange(Player player, String ratioArg) {
        UUID playerId = player.getUniqueId();
        String normalized = ratioArg.trim();

        if (!VALID_ASPECT_RATIOS.contains(normalized)) {
            player.sendMessage(ChatColor.RED + "無効なアスペクト比: " + ratioArg);
            player.sendMessage(ChatColor.YELLOW + "利用可能: " + String.join(", ", VALID_ASPECT_RATIOS));
            return;
        }

        String old = getPlayerAspectRatio(playerId);
        if (normalized.equals(old)) {
            player.sendMessage(ChatColor.YELLOW + "既にアスペクト比 " + normalized + " を使用中です。");
            return;
        }

        playerAspectRatios.put(playerId, normalized);
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "✓ アスペクト比を " + ChatColor.WHITE + normalized
            + " " + ChatColor.GRAY + getAspectRatioDescription(normalized)
            + ChatColor.GREEN + " に変更しました。");

        // Auto-refresh settings view if in IMAGE mode
        if (getSessionMode(playerId) == SessionMode.IMAGE) {
            showImageSettings(player);
        }
    }

    private void handleResolutionChange(Player player, String resArg) {
        UUID playerId = player.getUniqueId();
        String currentModel = getPlayerImageModel(playerId);

        // Check if using Pro model
        if (!MODEL_NANOBANANA_PRO.equals(currentModel)) {
            player.sendMessage(ChatColor.RED + "解像度の変更は Nanobanana Pro でのみ可能です。");
            player.sendMessage(ChatColor.GRAY + "まず /model nanobanana-pro でモデルを変更してください。");
            return;
        }

        String normalized = resArg.trim().toUpperCase();
        // Handle variations
        switch (normalized) {
            case "1K":
            case "1024":
                normalized = RESOLUTION_1K;
                break;
            case "2K":
            case "2048":
                normalized = RESOLUTION_2K;
                break;
            case "4K":
            case "4096":
                normalized = RESOLUTION_4K;
                break;
            default:
                player.sendMessage(ChatColor.RED + "無効な解像度: " + resArg);
                player.sendMessage(ChatColor.YELLOW + "利用可能: 1K, 2K, 4K");
                return;
        }

        String old = getPlayerResolution(playerId);
        if (normalized.equals(old)) {
            player.sendMessage(ChatColor.YELLOW + "既に解像度 " + getResolutionDisplayName(normalized) + " を使用中です。");
            return;
        }

        playerResolutions.put(playerId, normalized);
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "✓ 解像度を " + ChatColor.WHITE + getResolutionDisplayName(normalized)
            + ChatColor.GREEN + " に変更しました。");

        // Cost warning for 4K
        if (RESOLUTION_4K.equals(normalized)) {
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "  ⚠ 注意: 4K画像は1枚あたり ~$0.24 のコストが発生します。");
        }

        // Auto-refresh settings view if in IMAGE mode
        if (getSessionMode(playerId) == SessionMode.IMAGE) {
            showImageSettings(player);
        }
    }

    private void sendImageLink(Player player, String imageUrl, String prompt, String modelName, String aspectRatio, String resolution) {
        String displayModel = getImageModelDisplayName(modelName);

        // Save to library
        addToLibrary(player.getUniqueId(), prompt, modelName, aspectRatio, resolution, imageUrl);

        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;

            player.sendMessage("");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "╔═══════════════════════════════════════════════╗");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "║  " + ChatColor.WHITE + "画像生成完了！" + ChatColor.LIGHT_PURPLE + "                             ║");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "╚═══════════════════════════════════════════════╝");
            player.sendMessage("");
            player.sendMessage(ChatColor.GRAY + "  プロンプト: " + ChatColor.WHITE + prompt);
            player.sendMessage(ChatColor.GRAY + "  モデル: " + ChatColor.LIGHT_PURPLE + displayModel
                + ChatColor.GRAY + " | 比率: " + ChatColor.WHITE + aspectRatio
                + ChatColor.GRAY + " | 解像度: " + ChatColor.WHITE + resolution);
            player.sendMessage("");

            // Send clickable link using TextComponent (OPEN_URL)
            TextComponent prefix = new TextComponent("  ");

            TextComponent linkText = new TextComponent("[画像を表示する (クリック)]");
            linkText.setColor(net.md_5.bungee.api.ChatColor.GOLD);
            linkText.setBold(true);
            linkText.setUnderlined(true);
            linkText.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, imageUrl));
            linkText.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("クリックしてブラウザで画像を表示")
                    .color(net.md_5.bungee.api.ChatColor.YELLOW).create()));

            prefix.addExtra(linkText);
            player.spigot().sendMessage(prefix);

            // Also show raw URL for copy-paste
            player.sendMessage(ChatColor.GRAY + "  URL: " + ChatColor.GRAY + imageUrl);

            player.sendMessage("");
            player.sendMessage(ChatColor.GRAY + "─────────────────────────────────");
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createSuggestButton("[続けて生成]", "", "画像の説明をチャットで入力", net.md_5.bungee.api.ChatColor.LIGHT_PURPLE, false),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[設定変更]", "/gemini image settings", "クリックで設定を表示", net.md_5.bungee.api.ChatColor.YELLOW));
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[ライブラリ]", "/gemini library", "クリックで画像ライブラリを表示", net.md_5.bungee.api.ChatColor.GOLD),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
            player.sendMessage("");
        });
    }

    private void showStatus(Player player) {
        UUID playerId = player.getUniqueId();
        String model = getModelDisplayName(getPlayerModel(playerId));
        SessionMode mode = getSessionMode(playerId);
        int historySize = conversationHistories.containsKey(playerId) ?
            conversationHistories.get(playerId).size() : 0;

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "ステータス" + ChatColor.GOLD + "                                 ║");
        player.sendMessage(ChatColor.GOLD + "╠═══════════════════════════════════════════════╣");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "システム状態: " + ChatColor.GREEN + "起動中" + ChatColor.GOLD + "                    ║");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "現在のモード: " + ChatColor.AQUA + getModeDisplayName(mode) + ChatColor.GOLD + "                 ║");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "使用モデル: " + ChatColor.AQUA + model + ChatColor.GOLD + "                   ║");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "画像モデル: " + ChatColor.LIGHT_PURPLE + getImageModelDisplayName(getPlayerImageModel(playerId)) + ChatColor.GOLD + " ║");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "アスペクト比: " + ChatColor.WHITE + getPlayerAspectRatio(playerId) + ChatColor.GOLD + "                      ║");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "解像度: " + ChatColor.WHITE + getPlayerResolution(playerId) + ChatColor.GOLD + "                            ║");
        int librarySize = playerImageLibrary.containsKey(playerId) ? playerImageLibrary.get(playerId).size() : 0;
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "会話履歴: " + ChatColor.WHITE + historySize + " メッセージ" + ChatColor.GOLD + "                ║");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "ライブラリ: " + ChatColor.WHITE + librarySize + " 枚" + ChatColor.GOLD + "                       ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[履歴クリア]", "/gemini clear", "クリックで会話履歴をクリア", net.md_5.bungee.api.ChatColor.YELLOW),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
    }

    private void showModelSelection(Player player) {
        UUID playerId = player.getUniqueId();
        String currentModel = getPlayerModel(playerId);

        boolean isFlash = MODEL_FLASH.equals(currentModel);
        boolean isFlashThinking = MODEL_FLASH_THINKING.equals(currentModel);
        boolean isPro = MODEL_PRO.equals(currentModel) || currentModel.contains("pro");

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "AIモデル選択" + ChatColor.GRAY + " (クリックで変更)" + ChatColor.GOLD + "            ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");

        // Flash
        sendClickableLine(player,
            isFlash ? text("  ► ", net.md_5.bungee.api.ChatColor.GREEN) : text("    ", net.md_5.bungee.api.ChatColor.GRAY),
            isFlash
                ? createClickableButton("[Flash ✓]", "/gemini model flash", "使用中: Gemini 3 Flash", net.md_5.bungee.api.ChatColor.GREEN, true)
                : createClickableButton("[Flash]", "/gemini model flash", "クリックで変更: 高速・軽快", net.md_5.bungee.api.ChatColor.AQUA),
            text(" サクサク軽快な応答", net.md_5.bungee.api.ChatColor.GRAY));

        // Flash Thinking
        sendClickableLine(player,
            isFlashThinking ? text("  ► ", net.md_5.bungee.api.ChatColor.GREEN) : text("    ", net.md_5.bungee.api.ChatColor.GRAY),
            isFlashThinking
                ? createClickableButton("[Thinking ✓]", "/gemini model thinking", "使用中: Gemini 3 Flash Thinking", net.md_5.bungee.api.ChatColor.GREEN, true)
                : createClickableButton("[Thinking]", "/gemini model thinking", "クリックで変更: 速度と深い思考の両立", net.md_5.bungee.api.ChatColor.GOLD),
            text(" 速度と深い思考の両立", net.md_5.bungee.api.ChatColor.GRAY));

        // Pro
        sendClickableLine(player,
            isPro ? text("  ► ", net.md_5.bungee.api.ChatColor.GREEN) : text("    ", net.md_5.bungee.api.ChatColor.GRAY),
            isPro
                ? createClickableButton("[Pro ✓]", "/gemini model pro", "使用中: Gemini 3 Pro", net.md_5.bungee.api.ChatColor.GREEN, true)
                : createClickableButton("[Pro]", "/gemini model pro", "クリックで変更: 最高性能・詳しい回答", net.md_5.bungee.api.ChatColor.LIGHT_PURPLE),
            text(" 最高性能・詳しい回答", net.md_5.bungee.api.ChatColor.GRAY));
        player.sendMessage("");

        player.sendMessage(ChatColor.GRAY + "  ─────────────────────────────────");
        sendClickableLine(player,
            text("    ", net.md_5.bungee.api.ChatColor.WHITE),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
    }

    private void handleModelChange(Player player, String modelArg) {
        UUID playerId = player.getUniqueId();
        String newModel;

        switch (modelArg.toLowerCase()) {
            case "flash":
            case "f":
            case "1":
                newModel = MODEL_FLASH;
                break;
            case "thinking":
            case "flash-thinking":
            case "t":
            case "2":
                newModel = MODEL_FLASH_THINKING;
                break;
            case "pro":
            case "p":
            case "3":
                newModel = MODEL_PRO;
                break;
            default:
                player.sendMessage(ChatColor.RED + "不明なモデル: " + modelArg);
                player.sendMessage(ChatColor.YELLOW + "利用可能: flash, thinking, pro");
                return;
        }

        String oldModel = getPlayerModel(playerId);
        if (newModel.equals(oldModel)) {
            player.sendMessage(ChatColor.YELLOW + "既に " + getModelDisplayName(newModel) + " を使用中です。");
            return;
        }

        playerModels.put(playerId, newModel);
        conversationHistories.remove(playerId);

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "✓ AIモデルを " + ChatColor.AQUA + getModelDisplayName(newModel) + ChatColor.GREEN + " に変更しました。");
        player.sendMessage(ChatColor.GRAY + "  (会話履歴をクリアしました)");

        if (newModel.equals(MODEL_PRO)) {
            player.sendMessage("");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "  ℹ Proモデルは深く考えるため、応答に時間がかかる場合があります。");
        } else if (newModel.equals(MODEL_FLASH_THINKING)) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "  ℹ Flash Thinkingは深い思考を行いつつ高速に応答します。");
        }
        player.sendMessage("");
    }

    // ==================== Help System ====================

    private void showHelpPage(CommandSender sender, int page) {
        final int TOTAL_PAGES = 5;

        if (page < 1 || page > TOTAL_PAGES) {
            sender.sendMessage(ChatColor.RED + "ページ番号は1〜" + TOTAL_PAGES + "の間で指定してください。");
            return;
        }

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        sender.sendMessage(ChatColor.GOLD + "║  " + ChatColor.GREEN + "if-Gemini ヘルプ" + ChatColor.GRAY + " - ページ " + page + "/" + TOTAL_PAGES + ChatColor.GOLD + "             ║");
        sender.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");

        switch (page) {
            case 1:
                showHelpPage1(sender);
                break;
            case 2:
                showHelpPage2(sender);
                break;
            case 3:
                showHelpPage3(sender);
                break;
            case 4:
                showHelpPage4(sender);
                break;
            case 5:
                showHelpPage5(sender);
                break;
        }

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Clickable navigation for players
        if (sender instanceof Player) {
            Player p = (Player) sender;
            if (page < TOTAL_PAGES) {
                sendClickableLine(p,
                    text("    ", net.md_5.bungee.api.ChatColor.WHITE),
                    createClickableButton("[次のページ →]", "/gemini help " + (page + 1), "クリックでページ " + (page + 1) + " を表示", net.md_5.bungee.api.ChatColor.YELLOW));
            }
            if (page > 1) {
                sendClickableLine(p,
                    text("    ", net.md_5.bungee.api.ChatColor.WHITE),
                    createClickableButton("[← 前のページ]", "/gemini help " + (page - 1), "クリックでページ " + (page - 1) + " を表示", net.md_5.bungee.api.ChatColor.YELLOW));
            }
            sendClickableLine(p,
                text("    ", net.md_5.bungee.api.ChatColor.WHITE),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        } else {
            if (page < TOTAL_PAGES) {
                sender.sendMessage(ChatColor.YELLOW + "次のページ: " + ChatColor.WHITE + "/gemini help " + (page + 1));
            }
            sender.sendMessage(ChatColor.YELLOW + "トピック別: " + ChatColor.WHITE + "/gemini help <トピック>");
            sender.sendMessage(ChatColor.GRAY + "トピック: chat, search, image, model, commands");
        }
        sender.sendMessage("");
    }

    private void showHelpPage1(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【概要】");
        sender.sendMessage(ChatColor.WHITE + "if-Geminiは、Google Gemini AIを使った");
        sender.sendMessage(ChatColor.WHITE + "相談・検索システムです。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【始め方】");
        sender.sendMessage(ChatColor.YELLOW + "1. " + ChatColor.WHITE + "/gemini でシステムを起動");
        sender.sendMessage(ChatColor.YELLOW + "2. " + ChatColor.WHITE + "メニューから機能を選択");
        sender.sendMessage(ChatColor.YELLOW + "3. " + ChatColor.WHITE + "/exit で前の画面に戻る");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【主な機能】");
        sender.sendMessage(ChatColor.AQUA + "• " + ChatColor.WHITE + "相談モード - AIカウンセラーに相談");
        sender.sendMessage(ChatColor.AQUA + "• " + ChatColor.WHITE + "検索モード - Webから情報を検索");
        sender.sendMessage(ChatColor.AQUA + "• " + ChatColor.WHITE + "コマンド生成 - AIでMCコマンド作成");
        sender.sendMessage(ChatColor.AQUA + "• " + ChatColor.WHITE + "画像生成モード - AIで画像を生成");
    }

    private void showHelpPage2(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【相談モードの使い方】");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.WHITE + "メニューで " + ChatColor.YELLOW + "[1] 相談モード" + ChatColor.WHITE + " をクリック");
        sender.sendMessage(ChatColor.WHITE + "または " + ChatColor.YELLOW + "/gemini chat" + ChatColor.WHITE + " で開始。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【相談の仕方】");
        sender.sendMessage(ChatColor.WHITE + "チャットで相談内容を入力するだけ！");
        sender.sendMessage(ChatColor.GRAY + "例: 最近悩んでいることがある");
        sender.sendMessage(ChatColor.GRAY + "例: 勉強のやる気が出ない");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【終了・戻る】");
        sender.sendMessage(ChatColor.WHITE + "[メニューに戻る]ボタンをクリック");
    }

    private void showHelpPage3(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【検索モードの使い方】");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.WHITE + "メニューで " + ChatColor.YELLOW + "[2] 検索モード" + ChatColor.WHITE + " をクリック");
        sender.sendMessage(ChatColor.WHITE + "または " + ChatColor.YELLOW + "/gemini search" + ChatColor.WHITE + " で開始。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【検索の仕方】");
        sender.sendMessage(ChatColor.WHITE + "チャットで検索ワードを入力するだけ！");
        sender.sendMessage(ChatColor.GRAY + "例: Minecraft 建築 コツ");
        sender.sendMessage(ChatColor.GRAY + "例: レッドストーン回路 初心者");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【特徴】");
        sender.sendMessage(ChatColor.WHITE + "• リアルタイムでWebから情報を取得");
        sender.sendMessage(ChatColor.WHITE + "• 結果を分かりやすくまとめて表示");
    }

    private void showHelpPage4(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【AIモデル（テキスト）】");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.AQUA + "Flash (高速)");
        sender.sendMessage(ChatColor.WHITE + "  素早い応答。軽い相談向け。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.AQUA + "Pro (高性能)");
        sender.sendMessage(ChatColor.WHITE + "  詳しい回答。深い相談向け。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【モデル変更】");
        sender.sendMessage(ChatColor.WHITE + "各モード内の " + ChatColor.YELLOW + "[モデル変更]" + ChatColor.WHITE + " ボタンをクリック。");
        sender.sendMessage(ChatColor.WHITE + "または " + ChatColor.YELLOW + "/gemini model" + ChatColor.WHITE + " でも変更可能。");
    }

    private void showHelpPage5(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【画像生成モード】");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.WHITE + "メニューで " + ChatColor.YELLOW + "[4] 画像生成モード" + ChatColor.WHITE + " をクリック");
        sender.sendMessage(ChatColor.WHITE + "または " + ChatColor.YELLOW + "/gemini image" + ChatColor.WHITE + " で開始。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【使い方】");
        sender.sendMessage(ChatColor.WHITE + "チャットで画像の説明を入力するだけ！");
        sender.sendMessage(ChatColor.GRAY + "設定はボタンから変更できます。");
        sender.sendMessage(ChatColor.YELLOW + "/gemini image settings" + ChatColor.WHITE + " → 設定メニュー");
        sender.sendMessage(ChatColor.YELLOW + "/gemini imagemodel" + ChatColor.WHITE + " → モデル切替");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【画像AIモデル】");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Nanobanana" + ChatColor.WHITE + " - 高速 (1Kのみ)");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Nanobanana Pro" + ChatColor.WHITE + " - 高品質 (1K/2K/4K)");
    }

    private void showTopicHelp(CommandSender sender, String topic) {
        sender.sendMessage("");

        switch (topic.toLowerCase()) {
            case "chat":
            case "相談":
                sender.sendMessage(ChatColor.GOLD + "══════ 相談モード 詳細 ══════");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【開始方法】");
                sender.sendMessage(ChatColor.WHITE + "/gemini → メニューで [1] をクリック");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【使い方】");
                sender.sendMessage(ChatColor.WHITE + "チャットで相談内容を入力するだけ！");
                sender.sendMessage(ChatColor.GRAY + "例: 友達と喧嘩してしまった");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【会話履歴】");
                sender.sendMessage(ChatColor.WHITE + "AIは会話を記憶しています。");
                sender.sendMessage(ChatColor.YELLOW + "/gemini clear" + ChatColor.WHITE + " で履歴クリア");
                break;

            case "search":
            case "検索":
                sender.sendMessage(ChatColor.GOLD + "══════ 検索モード 詳細 ══════");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【開始方法】");
                sender.sendMessage(ChatColor.WHITE + "/gemini → メニューで [2] をクリック");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【使い方】");
                sender.sendMessage(ChatColor.WHITE + "チャットで検索ワードを入力するだけ！");
                sender.sendMessage(ChatColor.GRAY + "例: Minecraft MOD おすすめ");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【コツ】");
                sender.sendMessage(ChatColor.WHITE + "具体的なキーワードを複数使うと");
                sender.sendMessage(ChatColor.WHITE + "より良い結果が得られます。");
                break;

            case "image":
            case "画像":
                sender.sendMessage(ChatColor.GOLD + "══════ 画像生成モード 詳細 ══════");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【開始方法】");
                sender.sendMessage(ChatColor.WHITE + "/gemini → メニューで [4] をクリック");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【使い方】");
                sender.sendMessage(ChatColor.WHITE + "チャットで画像の説明を入力するだけ！");
                sender.sendMessage(ChatColor.GRAY + "例: 宇宙から見た地球");
                sender.sendMessage(ChatColor.GRAY + "例: かわいい猫のイラスト");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【画像モデル】");
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Nanobanana" + ChatColor.WHITE + " - 高速・1Kのみ (~$0.04/枚)");
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Nanobanana Pro" + ChatColor.WHITE + " - 高品質・4K対応 (~$0.13/枚)");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【設定変更】");
                sender.sendMessage(ChatColor.WHITE + "ボタンをクリックして設定変更できます。");
                sender.sendMessage(ChatColor.YELLOW + "/gemini image settings" + ChatColor.WHITE + " - 設定メニュー");
                sender.sendMessage(ChatColor.YELLOW + "/gemini imagemodel" + ChatColor.WHITE + " - モデル切替");
                sender.sendMessage(ChatColor.YELLOW + "/gemini image ratio <値>" + ChatColor.WHITE + " - アスペクト比変更");
                sender.sendMessage(ChatColor.GRAY + "  (1:1, 2:3, 3:2, 3:4, 4:3, 4:5, 5:4, 9:16, 16:9, 21:9)");
                sender.sendMessage(ChatColor.YELLOW + "/gemini image resolution <値>" + ChatColor.WHITE + " - 解像度変更 (Proのみ)");
                sender.sendMessage(ChatColor.GRAY + "  (1K, 2K, 4K ※4Kはコスト約2倍)");
                break;

            case "model":
            case "モデル":
                sender.sendMessage(ChatColor.GOLD + "══════ AIモデル 詳細 ══════");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.AQUA + "■ Flash");
                sender.sendMessage(ChatColor.WHITE + "  速度: ★★★★★ / 詳しさ: ★★★☆☆");
                sender.sendMessage(ChatColor.GRAY + "  サクサク会話したい時に");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.AQUA + "■ Pro");
                sender.sendMessage(ChatColor.WHITE + "  速度: ★★★☆☆ / 詳しさ: ★★★★★");
                sender.sendMessage(ChatColor.GRAY + "  じっくり相談したい時に");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【変更方法】");
                sender.sendMessage(ChatColor.WHITE + "各モード内の " + ChatColor.YELLOW + "[モデル変更]" + ChatColor.WHITE + " ボタンをクリック");
                sender.sendMessage(ChatColor.WHITE + "または " + ChatColor.YELLOW + "/gemini model flash" + ChatColor.WHITE + " / " + ChatColor.YELLOW + "/gemini model pro" + ChatColor.WHITE + " で直接変更");
                break;

            case "commands":
            case "コマンド":
                sender.sendMessage(ChatColor.GOLD + "══════ コマンド一覧 ══════");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【メイン】");
                sender.sendMessage(ChatColor.YELLOW + "/gemini" + ChatColor.WHITE + " - システム起動/メニュー表示");
                sender.sendMessage(ChatColor.YELLOW + "/gemini chat" + ChatColor.WHITE + " - 相談モード開始");
                sender.sendMessage(ChatColor.YELLOW + "/gemini search" + ChatColor.WHITE + " - 検索モード開始");
                sender.sendMessage(ChatColor.YELLOW + "/gemini command" + ChatColor.WHITE + " - コマンド生成モード開始");
                sender.sendMessage(ChatColor.YELLOW + "/gemini image" + ChatColor.WHITE + " - 画像生成モード開始");
                sender.sendMessage(ChatColor.YELLOW + "/gemini model" + ChatColor.WHITE + " - テキストモデル選択");
                sender.sendMessage(ChatColor.YELLOW + "/gemini imagemodel" + ChatColor.WHITE + " - 画像モデル選択");
                sender.sendMessage(ChatColor.YELLOW + "/gemini help" + ChatColor.WHITE + " - ヘルプ表示");
                sender.sendMessage(ChatColor.YELLOW + "/gemini status" + ChatColor.WHITE + " - ステータス表示");
                sender.sendMessage(ChatColor.YELLOW + "/gemini clear" + ChatColor.WHITE + " - 履歴クリア");
                sender.sendMessage(ChatColor.YELLOW + "/gemini exit" + ChatColor.WHITE + " - システム終了");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【モード内の使い方】");
                sender.sendMessage(ChatColor.WHITE + "チャットで内容を入力するだけ！");
                sender.sendMessage(ChatColor.WHITE + "ボタンをクリックして操作できます。");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【画像モード設定】");
                sender.sendMessage(ChatColor.YELLOW + "/gemini image settings" + ChatColor.WHITE + " - 設定メニュー");
                sender.sendMessage(ChatColor.YELLOW + "/gemini imagemodel" + ChatColor.WHITE + " - 画像モデル切替");
                sender.sendMessage(ChatColor.YELLOW + "/gemini image ratio <値>" + ChatColor.WHITE + " - アスペクト比変更");
                sender.sendMessage(ChatColor.YELLOW + "/gemini image resolution <値>" + ChatColor.WHITE + " - 解像度変更(Proのみ)");
                break;

            default:
                sender.sendMessage(ChatColor.RED + "不明なトピック: " + topic);
                sender.sendMessage(ChatColor.YELLOW + "利用可能: chat, search, command, image, model, commands");
                break;
        }
        sender.sendMessage("");

        // Clickable navigation for players
        if (sender instanceof Player) {
            Player p = (Player) sender;
            sendClickableLine(p,
                text("    ", net.md_5.bungee.api.ChatColor.WHITE),
                createClickableButton("[ヘルプ一覧]", "/gemini help 1", "クリックでヘルプ一覧を表示", net.md_5.bungee.api.ChatColor.YELLOW),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
            sender.sendMessage("");
        }
    }

    // ==================== Chat Processing ====================

    private void processGeminiChat(Player player, String userMessage) {
        UUID playerId = player.getUniqueId();

        JsonArray history = conversationHistories.computeIfAbsent(playerId, k -> new JsonArray());

        JsonObject userPart = new JsonObject();
        userPart.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", userMessage);
        userParts.add(textPart);
        userPart.add("parts", userParts);
        history.add(userPart);

        while (history.size() > maxHistorySize) {
            history.remove(0);
        }

        String playerModel = getPlayerModel(playerId);
        String response = callGeminiAPIInternal(history, playerModel, systemPrompt);

        if (response != null && !response.isEmpty()) {
            JsonObject assistantPart = new JsonObject();
            assistantPart.addProperty("role", "model");
            JsonArray assistantParts = new JsonArray();
            JsonObject respTextPart = new JsonObject();
            respTextPart.addProperty("text", response);
            assistantParts.add(respTextPart);
            assistantPart.add("parts", assistantParts);
            history.add(assistantPart);

            final String finalResponse = stripMarkdown(response);
            Bukkit.getScheduler().runTask(this, () -> {
                player.sendMessage("");
                player.sendMessage(ChatColor.AQUA + "[" + npcName + "] " + ChatColor.WHITE + finalResponse);
                player.sendMessage("");
                sendModeFooter(player);
            });
            logToSpreadsheet(player, "相談", getPlayerModel(playerId), userMessage, finalResponse);
        } else {
            Bukkit.getScheduler().runTask(this, () -> {
                player.sendMessage(ChatColor.RED + "[" + npcName + "] " +
                    "ごめんなさい、うまく聞き取れませんでした。もう一度教えてもらえますか？");
            });
        }

        conversationHistories.put(playerId, history);
    }

    private void processWebSearch(Player player, String query) {
        UUID playerId = player.getUniqueId();
        String playerModel = getPlayerModel(playerId);

        JsonArray contents = new JsonArray();
        JsonObject userPart = new JsonObject();
        userPart.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", "以下について検索して、結果を日本語で簡潔にまとめてください: " + query);
        userParts.add(textPart);
        userPart.add("parts", userParts);
        contents.add(userPart);

        WebSearchResult result = callGeminiAPIWithSearch(contents, playerModel);

        Bukkit.getScheduler().runTask(this, () -> {
            player.sendMessage("");
            player.sendMessage(ChatColor.GREEN + "╔═══════════════════════════════════════════════╗");
            player.sendMessage(ChatColor.GREEN + "║  " + ChatColor.WHITE + "検索結果" + ChatColor.GREEN + "                                   ║");
            player.sendMessage(ChatColor.GREEN + "╚═══════════════════════════════════════════════╝");

            if (result != null && result.text != null && !result.text.isEmpty()) {
                String cleanText = stripMarkdown(result.text);
                String[] lines = cleanText.split("\n");
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        List<String> wrappedLines = wrapText(line.trim(), 50);
                        for (String wrappedLine : wrappedLines) {
                            player.sendMessage(ChatColor.WHITE + wrappedLine);
                        }
                    }
                }

                if (result.sources != null && !result.sources.isEmpty()) {
                    player.sendMessage("");
                    player.sendMessage(ChatColor.GRAY + "--- 情報源 ---");
                    int count = 0;
                    for (String source : result.sources) {
                        if (count >= 3) break;
                        player.sendMessage(ChatColor.BLUE + "• " + ChatColor.GRAY + source);
                        count++;
                    }
                }
            } else {
                player.sendMessage(ChatColor.RED + "検索結果を取得できませんでした。");
                player.sendMessage(ChatColor.GRAY + "別のキーワードで試してみてください。");
            }

            player.sendMessage("");
            sendModeFooter(player);
        });

        // Log to spreadsheet
        String resultText = (result != null && result.text != null) ? stripMarkdown(result.text) : "検索失敗";
        logToSpreadsheet(player, "検索", playerModel, query, resultText);
    }

    private List<String> wrapText(String text, int maxLength) {
        List<String> lines = new ArrayList<>();
        while (text.length() > maxLength) {
            int splitIndex = text.lastIndexOf(' ', maxLength);
            if (splitIndex == -1) splitIndex = maxLength;
            lines.add(text.substring(0, splitIndex));
            text = text.substring(splitIndex).trim();
        }
        if (!text.isEmpty()) {
            lines.add(text);
        }
        return lines;
    }

    private String stripMarkdown(String text) {
        if (text == null) return null;
        // Remove bold (**text** or __text__)
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        text = text.replaceAll("__(.+?)__", "$1");
        // Remove italic (*text* or _text_)
        text = text.replaceAll("\\*(.+?)\\*", "$1");
        text = text.replaceAll("_(.+?)_", "$1");
        // Remove headers (# ## ### etc.)
        text = text.replaceAll("^#{1,6}\\s*", "");
        // Remove code blocks
        text = text.replaceAll("```[\\s\\S]*?```", "");
        text = text.replaceAll("`(.+?)`", "$1");
        return text;
    }

    private static class WebSearchResult {
        String text;
        List<String> sources;

        WebSearchResult(String text, List<String> sources) {
            this.text = text;
            this.sources = sources;
        }
    }

    // ==================== Image Generation ====================

    private void processImageGeneration(Player player, String prompt, String imageModel, String aspectRatio, String resolution) {
        try {
            // Step 1: Call Gemini Image API
            byte[] imageData = callGeminiImageAPI(prompt, imageModel, aspectRatio, resolution);

            if (imageData == null || imageData.length == 0) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "[画像生成] 画像の生成に失敗しました。");
                    player.sendMessage(ChatColor.GRAY + "別のプロンプトで試してみてください。");
                });
                return;
            }

            // Step 2: Upload image to hosting service
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "画像をアップロード中...");
            });

            String imageUrl = uploadImage(imageData);

            if (imageUrl == null || imageUrl.isEmpty()) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "[画像生成] 画像のアップロードに失敗しました。");
                    player.sendMessage(ChatColor.GRAY + "全てのホスティングサービスへの接続に失敗しました。");
                    player.sendMessage(ChatColor.GRAY + "しばらく待ってから再度お試しください。");
                    player.sendMessage(ChatColor.GRAY + "サーバーログで詳細を確認できます。");
                });
                return;
            }

            // Validate URL format
            if (!imageUrl.startsWith("https://") && !imageUrl.startsWith("http://")) {
                getLogger().warning("Invalid image URL returned: " + imageUrl);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "[画像生成] 画像URLが不正です。再度お試しください。");
                });
                return;
            }

            // Step 3: Send clickable link to player
            sendImageLink(player, imageUrl, prompt, imageModel, aspectRatio, resolution);

        } catch (Exception e) {
            getLogger().severe("Image generation error: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(ChatColor.RED + "[画像生成] エラーが発生しました: " + e.getMessage());
            });
        }
    }

    private void processI2IGeneration(Player player, String prompt, String sourceUrl, String imageModel, String aspectRatio, String resolution) {
        try {
            // Step 1: Download source image
            DownloadResult downloadResult;
            try {
                getLogger().info("I2I: Downloading image from: " + sourceUrl);
                downloadResult = downloadImageWithMeta(sourceUrl);
                getLogger().info("I2I: Downloaded " + downloadResult.data.length + " bytes, type: " + downloadResult.contentType);
            } catch (Exception e) {
                getLogger().warning("I2I: Download failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "[Image-to-Image] 元画像のダウンロードに失敗しました:");
                    player.sendMessage(ChatColor.RED + "  " + e.getMessage());
                    player.sendMessage(ChatColor.GRAY + "URLが正しいか確認してください。対応形式: JPEG, PNG, WebP");
                    player.sendMessage(ChatColor.GRAY + "ヒント: 画像に直接リンクするURLを使用してください。");
                });
                return;
            }

            byte[] sourceImage = downloadResult.data;
            String mimeType = detectMimeType(sourceUrl, downloadResult.contentType);
            getLogger().info("I2I: Detected MIME type: " + mimeType);

            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "画像を変換中...");
            });

            // Step 2: Call Gemini API with source image
            byte[] imageData = callGeminiImageAPI(prompt, imageModel, aspectRatio, resolution, sourceImage, mimeType);

            if (imageData == null || imageData.length == 0) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "[Image-to-Image] 画像の変換に失敗しました。");
                    player.sendMessage(ChatColor.GRAY + "別のプロンプトで試してみてください。");
                });
                return;
            }

            // Step 3: Upload
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "画像をアップロード中...");
            });

            String imageUrl = uploadImage(imageData);

            if (imageUrl == null || imageUrl.isEmpty()) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "[Image-to-Image] 画像のアップロードに失敗しました。");
                });
                return;
            }

            if (!imageUrl.startsWith("https://") && !imageUrl.startsWith("http://")) {
                getLogger().warning("Invalid I2I image URL returned: " + imageUrl);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "[Image-to-Image] 画像URLが不正です。再度お試しください。");
                });
                return;
            }

            // Step 4: Send link (also saves to library)
            sendImageLink(player, imageUrl, prompt + " (i2i)", imageModel, aspectRatio, resolution);

        } catch (Exception e) {
            getLogger().severe("I2I generation error: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(ChatColor.RED + "[Image-to-Image] エラーが発生しました: " + e.getMessage());
            });
        }
    }

    // Image-to-Image: Download source image from URL with manual redirect following
    private static class DownloadResult {
        byte[] data;
        String contentType;
        DownloadResult(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }
    }

    private DownloadResult downloadImageWithMeta(String imageUrl) throws Exception {
        String currentUrl = imageUrl;
        int maxRedirects = 5;

        for (int i = 0; i < maxRedirects; i++) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "image/*, */*");
            conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false); // Handle redirects manually for cross-protocol

            int responseCode = conn.getResponseCode();

            // Handle redirects manually (including HTTP->HTTPS and HTTPS->HTTP)
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == 307 || responseCode == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new Exception("リダイレクト先が見つかりません");
                }
                // Handle relative URLs
                if (location.startsWith("/")) {
                    URL base = new URL(currentUrl);
                    location = base.getProtocol() + "://" + base.getHost() + location;
                }
                currentUrl = location;
                getLogger().info("Image download redirect: " + currentUrl);
                continue;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new Exception("HTTP " + responseCode + " (" + currentUrl + ")");
            }

            // Check content length
            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_IMAGE_DOWNLOAD_SIZE) {
                conn.disconnect();
                throw new Exception("画像が大きすぎます (" + (contentLength / 1024 / 1024) + "MB, 最大10MB)");
            }

            String contentType = conn.getContentType();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (java.io.InputStream is = conn.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                long totalRead = 0;
                while ((read = is.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead > MAX_IMAGE_DOWNLOAD_SIZE) {
                        throw new Exception("画像が大きすぎます (最大10MB)");
                    }
                    baos.write(buffer, 0, read);
                }
            } finally {
                conn.disconnect();
            }

            byte[] data = baos.toByteArray();
            if (data.length == 0) {
                throw new Exception("ダウンロードしたデータが空です");
            }

            return new DownloadResult(data, contentType);
        }

        throw new Exception("リダイレクトが多すぎます");
    }

    private String detectMimeType(String url, String contentType) {
        // First try Content-Type header
        if (contentType != null && !contentType.isEmpty()) {
            String ct = contentType.toLowerCase();
            if (ct.contains("image/png")) return "image/png";
            if (ct.contains("image/jpeg") || ct.contains("image/jpg")) return "image/jpeg";
            if (ct.contains("image/webp")) return "image/webp";
            if (ct.contains("image/gif")) return "image/gif";
        }
        // Fall back to URL extension
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return "image/jpeg";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".gif")) return "image/gif";
        // Default: check magic bytes would be ideal, but fall back to png
        return "image/png";
    }

    // Image-to-Image: callGeminiImageAPI with source image
    private byte[] callGeminiImageAPI(String prompt, String modelName, String aspectRatio, String resolution, byte[] sourceImage, String sourceMimeType) {
        HttpURLConnection connection = null;
        try {
            String urlString = String.format(GEMINI_API_URL, modelName, apiKey);
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);

            JsonObject requestBody = new JsonObject();

            // System instruction for romaji support
            JsonObject systemInstruction = new JsonObject();
            JsonArray systemParts = new JsonArray();
            JsonObject systemText = new JsonObject();
            systemText.addProperty("text", getImageGenerationSystemPrompt());
            systemParts.add(systemText);
            systemInstruction.add("parts", systemParts);
            requestBody.add("systemInstruction", systemInstruction);

            // Contents with text + inline_data
            JsonArray contents = new JsonArray();
            JsonObject userPart = new JsonObject();
            userPart.addProperty("role", "user");
            JsonArray userParts = new JsonArray();

            // Text part
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", prompt);
            userParts.add(textPart);

            // Inline image data part
            JsonObject imagePart = new JsonObject();
            JsonObject inlineData = new JsonObject();
            inlineData.addProperty("mime_type", sourceMimeType);
            inlineData.addProperty("data", Base64.getEncoder().encodeToString(sourceImage));
            imagePart.add("inline_data", inlineData);
            userParts.add(imagePart);

            userPart.add("parts", userParts);
            contents.add(userPart);
            requestBody.add("contents", contents);

            // Generation config
            JsonObject generationConfig = new JsonObject();
            JsonArray responseModalities = new JsonArray();
            responseModalities.add("TEXT");
            responseModalities.add("IMAGE");
            generationConfig.add("responseModalities", responseModalities);

            JsonObject imageConfig = new JsonObject();
            imageConfig.addProperty("aspectRatio", aspectRatio);
            if (MODEL_NANOBANANA_PRO.equals(modelName)) {
                imageConfig.addProperty("imageSize", resolution);
            }
            generationConfig.add("imageConfig", imageConfig);
            requestBody.add("generationConfig", generationConfig);

            // Safety settings
            JsonArray safetySettings = new JsonArray();
            String[] categories = {
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            };
            for (String category : categories) {
                JsonObject setting = new JsonObject();
                setting.addProperty("category", category);
                setting.addProperty("threshold", "BLOCK_MEDIUM_AND_ABOVE");
                safetySettings.add(setting);
            }
            requestBody.add("safetySettings", safetySettings);

            String jsonBody = new Gson().toJson(requestBody);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                }

                JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();

                if (jsonResponse.has("candidates")) {
                    JsonArray candidates = jsonResponse.getAsJsonArray("candidates");
                    if (candidates.size() > 0) {
                        JsonObject candidate = candidates.get(0).getAsJsonObject();
                        if (candidate.has("content")) {
                            JsonObject content = candidate.getAsJsonObject("content");
                            if (content.has("parts")) {
                                JsonArray parts = content.getAsJsonArray("parts");
                                for (int i = 0; i < parts.size(); i++) {
                                    JsonObject part = parts.get(i).getAsJsonObject();
                                    if (part.has("inlineData") || part.has("inline_data")) {
                                        JsonObject inData = part.has("inlineData")
                                            ? part.getAsJsonObject("inlineData")
                                            : part.getAsJsonObject("inline_data");
                                        if (inData.has("data")) {
                                            String base64Data = inData.get("data").getAsString();
                                            return Base64.getDecoder().decode(base64Data);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                getLogger().warning("No image data in I2I API response");
            } else {
                StringBuilder errorResponse = new StringBuilder();
                java.io.InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            errorResponse.append(responseLine.trim());
                        }
                    }
                }
                getLogger().severe("Gemini I2I API Error: " + responseCode + " - " + errorResponse.toString());
            }

        } catch (Exception e) {
            getLogger().severe("Error calling Gemini I2I API: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return null;
    }

    // Text-to-Image: original callGeminiImageAPI
    private byte[] callGeminiImageAPI(String prompt, String modelName, String aspectRatio, String resolution) {
        HttpURLConnection connection = null;
        try {
            String urlString = String.format(GEMINI_API_URL, modelName, apiKey);
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000); // 3 minutes for image generation

            JsonObject requestBody = new JsonObject();

            // System instruction for romaji support
            JsonObject systemInstruction = new JsonObject();
            JsonArray systemParts = new JsonArray();
            JsonObject systemText = new JsonObject();
            systemText.addProperty("text", getImageGenerationSystemPrompt());
            systemParts.add(systemText);
            systemInstruction.add("parts", systemParts);
            requestBody.add("systemInstruction", systemInstruction);

            // Contents
            JsonArray contents = new JsonArray();
            JsonObject userPart = new JsonObject();
            userPart.addProperty("role", "user");
            JsonArray userParts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", prompt);
            userParts.add(textPart);
            userPart.add("parts", userParts);
            contents.add(userPart);
            requestBody.add("contents", contents);

            // Generation config with image response modality and imageConfig
            JsonObject generationConfig = new JsonObject();
            JsonArray responseModalities = new JsonArray();
            responseModalities.add("TEXT");
            responseModalities.add("IMAGE");
            generationConfig.add("responseModalities", responseModalities);

            // imageConfig with aspectRatio and imageSize
            JsonObject imageConfig = new JsonObject();
            imageConfig.addProperty("aspectRatio", aspectRatio);
            if (MODEL_NANOBANANA_PRO.equals(modelName)) {
                imageConfig.addProperty("imageSize", resolution);
            }
            generationConfig.add("imageConfig", imageConfig);

            requestBody.add("generationConfig", generationConfig);

            // Safety settings
            JsonArray safetySettings = new JsonArray();
            String[] categories = {
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            };
            for (String category : categories) {
                JsonObject setting = new JsonObject();
                setting.addProperty("category", category);
                setting.addProperty("threshold", "BLOCK_MEDIUM_AND_ABOVE");
                safetySettings.add(setting);
            }
            requestBody.add("safetySettings", safetySettings);

            String jsonBody = new Gson().toJson(requestBody);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                }

                JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();

                if (jsonResponse.has("candidates")) {
                    JsonArray candidates = jsonResponse.getAsJsonArray("candidates");
                    if (candidates.size() > 0) {
                        JsonObject candidate = candidates.get(0).getAsJsonObject();
                        if (candidate.has("content")) {
                            JsonObject content = candidate.getAsJsonObject("content");
                            if (content.has("parts")) {
                                JsonArray parts = content.getAsJsonArray("parts");
                                for (int i = 0; i < parts.size(); i++) {
                                    JsonObject part = parts.get(i).getAsJsonObject();
                                    if (part.has("inlineData") || part.has("inline_data")) {
                                        JsonObject inlineData = part.has("inlineData")
                                            ? part.getAsJsonObject("inlineData")
                                            : part.getAsJsonObject("inline_data");
                                        if (inlineData.has("data")) {
                                            String base64Data = inlineData.get("data").getAsString();
                                            return Base64.getDecoder().decode(base64Data);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                getLogger().warning("No image data in API response");
            } else {
                StringBuilder errorResponse = new StringBuilder();
                java.io.InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            errorResponse.append(responseLine.trim());
                        }
                    }
                }
                getLogger().severe("Gemini Image API Error: " + responseCode + " - " + errorResponse.toString());
            }

        } catch (Exception e) {
            getLogger().severe("Error calling Gemini Image API: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return null;
    }

    private String uploadImage(byte[] imageData) {
        if ("imgbb".equalsIgnoreCase(imageHosting) && !imgbbApiKey.equals("YOUR_IMGBB_API_KEY_HERE")) {
            return uploadToImgBB(imageData);
        }
        return uploadToCatbox(imageData);
    }

    private String uploadToImgBB(byte[] imageData) {
        HttpURLConnection conn = null;
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageData);
            String endpoint = "https://api.imgbb.com/1/upload?key=" + java.net.URLEncoder.encode(imgbbApiKey, "UTF-8");
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            String body = "image=" + java.net.URLEncoder.encode(base64Image, "UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder resp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }
                JsonObject json = JsonParser.parseString(resp.toString()).getAsJsonObject();
                if (json.has("success") && json.get("success").getAsBoolean()) {
                    return json.getAsJsonObject("data").get("display_url").getAsString();
                }
                getLogger().warning("ImgBB response indicates failure: " + resp.toString().substring(0, Math.min(resp.length(), 200)));
            } else {
                getLogger().severe("ImgBB upload error (HTTP " + responseCode + ")");
                // エラー詳細をログに出力
                try (java.io.InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        String err = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8)).readLine();
                        if (err != null) {
                            getLogger().severe("ImgBB error detail: " + (err.length() > 200 ? err.substring(0, 200) + "..." : err));
                        }
                    }
                }
            }

        } catch (Exception e) {
            getLogger().severe("ImgBB upload failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // Fallback to catbox if imgbb fails
        getLogger().info("Falling back to Catbox for image upload");
        return uploadToCatbox(imageData);
    }

    private String uploadToCatbox(byte[] imageData) {
        HttpURLConnection conn = null;
        try {
            String boundary = "----GeminiNPCBoundary" + System.currentTimeMillis();
            URL url = new URL("https://catbox.moe/user/api.php");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            // Cloudflare対策: ブラウザに偽装し、Origin/Refererヘッダーを追加
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/plain, */*");
            conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8");
            conn.setRequestProperty("Origin", "https://catbox.moe");
            conn.setRequestProperty("Referer", "https://catbox.moe/");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream()) {
                writeMultipartField(os, boundary, "reqtype", "fileupload");
                writeMultipartField(os, boundary, "userhash", "");
                writeMultipartFile(os, boundary, "fileToUpload", "generated_image.png", imageData, "image/png");
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder resp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }
                String result = resp.toString().trim();
                if (result.startsWith("https://") || result.startsWith("http://")) {
                    return result;
                }
                getLogger().warning("Catbox returned unexpected response: " + result);
            } else {
                getLogger().severe("Catbox upload error (HTTP " + responseCode + ")");
                // Cloudflareのブロック(403/503)等のエラー詳細をログに出力
                try (java.io.InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        String err = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8)).readLine();
                        if (err != null) {
                            getLogger().severe("Error detail: " + (err.length() > 200 ? err.substring(0, 200) + "..." : err));
                        }
                    }
                }
            }

        } catch (Exception e) {
            getLogger().severe("Catbox upload failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // フォールバック: Catboxが失敗した場合はLitterbox(一時ファイル)を試行
        getLogger().warning("Catbox upload failed, trying fallback to Litterbox...");
        return uploadToLitterbox(imageData);
    }

    private String uploadToLitterbox(byte[] imageData) {
        HttpURLConnection conn = null;
        try {
            String boundary = "----GeminiNPCFallback" + System.currentTimeMillis();
            URL url = new URL("https://litterbox.catbox.moe/resources/internals/api.php");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/plain, */*");
            conn.setRequestProperty("Origin", "https://litterbox.catbox.moe");
            conn.setRequestProperty("Referer", "https://litterbox.catbox.moe/");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream()) {
                writeMultipartField(os, boundary, "reqtype", "fileupload");
                writeMultipartField(os, boundary, "time", "72h");
                writeMultipartFile(os, boundary, "fileToUpload", "generated_image.png", imageData, "image/png");
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder resp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }
                String result = resp.toString().trim();
                if (result.startsWith("https://") || result.startsWith("http://")) {
                    getLogger().info("Litterbox fallback succeeded: " + result);
                    return result;
                }
                getLogger().warning("Litterbox returned unexpected response: " + result);
            } else {
                getLogger().severe("Litterbox upload error (HTTP " + responseCode + ")");
            }

        } catch (Exception e) {
            getLogger().severe("Litterbox fallback failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        return null;
    }

    private void writeMultipartField(OutputStream os, String boundary, String name, String value) throws java.io.IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeMultipartFile(OutputStream os, String boundary, String fieldName, String fileName, byte[] data, String mimeType) throws java.io.IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // ==================== Library Persistence ====================

    private void addToLibrary(UUID playerId, String prompt, String modelName, String aspectRatio, String resolution, String imageUrl) {
        List<ImageRecord> library = playerImageLibrary.computeIfAbsent(playerId, k -> Collections.synchronizedList(new ArrayList<>()));
        ImageRecord record = new ImageRecord(prompt, modelName, aspectRatio, resolution, imageUrl, System.currentTimeMillis());
        library.add(record);
        // Trim to max size (remove oldest)
        while (library.size() > maxLibrarySize) {
            library.remove(0);
        }
        // Save async
        final List<ImageRecord> snapshot = new ArrayList<>(library);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> saveLibrary(playerId, snapshot));
    }

    private void saveLibrary(UUID playerId, List<ImageRecord> library) {
        try {
            File libraryDir = new File(getDataFolder(), "library");
            if (!libraryDir.exists()) libraryDir.mkdirs();
            File file = new File(libraryDir, playerId.toString() + ".json");
            String json = new Gson().toJson(library);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(json);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to save library for " + playerId + ": " + e.getMessage());
        }
    }

    private List<ImageRecord> loadLibrary(UUID playerId) {
        try {
            File file = new File(new File(getDataFolder(), "library"), playerId.toString() + ".json");
            if (!file.exists()) return null;
            try (FileReader reader = new FileReader(file)) {
                Type listType = new TypeToken<ArrayList<ImageRecord>>(){}.getType();
                List<ImageRecord> library = new Gson().fromJson(reader, listType);
                return library != null ? Collections.synchronizedList(new ArrayList<>(library)) : null;
            }
        } catch (Exception e) {
            getLogger().warning("Failed to load library for " + playerId + ": " + e.getMessage());
            return null;
        }
    }

    private String getRelativeTimeString(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        if (seconds < 60) return seconds + "秒前";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "分前";
        long hours = minutes / 60;
        if (hours < 24) return hours + "時間前";
        long days = hours / 24;
        if (days < 30) return days + "日前";
        long months = days / 30;
        return months + "ヶ月前";
    }

    // ==================== Library UI ====================

    private void showLibrary(Player player, int page) {
        UUID playerId = player.getUniqueId();
        List<ImageRecord> library = playerImageLibrary.get(playerId);
        int totalItems = (library != null) ? library.size() : 0;
        int itemsPerPage = 5;
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / itemsPerPage));

        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "画像ライブラリ (" + totalItems + "枚)" +
            ChatColor.GRAY + "  ページ " + page + "/" + totalPages + ChatColor.GOLD + "           ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");

        if (totalItems == 0) {
            player.sendMessage(ChatColor.GRAY + "  まだ画像がありません。");
            player.sendMessage(ChatColor.GRAY + "  画像生成モードで画像を生成すると自動で保存されます。");
        } else {
            int startIndex = totalItems - 1 - (page - 1) * itemsPerPage; // newest first
            int endIndex = Math.max(startIndex - itemsPerPage + 1, 0);

            int displayNum = (page - 1) * itemsPerPage + 1;
            for (int i = startIndex; i >= endIndex; i--) {
                ImageRecord record = library.get(i);
                String shortPrompt = record.prompt.length() > 25 ? record.prompt.substring(0, 25) + "..." : record.prompt;
                String shortModel = getImageModelShortName(record.modelName);
                String timeAgo = getRelativeTimeString(record.timestamp);

                // Line 1: Number + [表示] + prompt
                sendClickableLine(player,
                    text("  " + displayNum + ". ", net.md_5.bungee.api.ChatColor.WHITE),
                    createClickableButton("[表示]", "/gemini library view " + i, "クリックでブラウザで画像を表示", net.md_5.bungee.api.ChatColor.GOLD),
                    text(" ", net.md_5.bungee.api.ChatColor.GRAY),
                    createClickableButton("[変換]", "/gemini library i2i " + i, "クリックでこの画像をImage-to-Image変換", net.md_5.bungee.api.ChatColor.AQUA),
                    text(" " + shortPrompt, net.md_5.bungee.api.ChatColor.WHITE));
                player.sendMessage(ChatColor.GRAY + "     " + shortModel + " | " + record.aspectRatio + " | " + record.resolution + " | " + timeAgo);
                displayNum++;
            }
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  ─────────────────────────────────");

        // Navigation buttons
        final int currentPage = page;
        if (totalPages > 1) {
            TextComponent navLine = new TextComponent("  ");
            if (currentPage > 1) {
                navLine.addExtra(createClickableButton("[← 前]", "/gemini library page " + (currentPage - 1), "前のページ", net.md_5.bungee.api.ChatColor.YELLOW));
                navLine.addExtra(new TextComponent("  "));
            }
            if (currentPage < totalPages) {
                navLine.addExtra(createClickableButton("[次 →]", "/gemini library page " + (currentPage + 1), "次のページ", net.md_5.bungee.api.ChatColor.YELLOW));
                navLine.addExtra(new TextComponent("  "));
            }
            navLine.addExtra(createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
            player.spigot().sendMessage(navLine);
        } else {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        }
        player.sendMessage("");
    }

    private String getImageModelShortName(String modelName) {
        if (MODEL_NANOBANANA_PRO.equals(modelName) || (modelName != null && modelName.contains("pro-image"))) {
            return "Nanobanana Pro";
        }
        return "Nanobanana";
    }

    private void handleLibraryView(Player player, int index) {
        UUID playerId = player.getUniqueId();
        List<ImageRecord> library = playerImageLibrary.get(playerId);
        if (library == null || index < 0 || index >= library.size()) {
            player.sendMessage(ChatColor.RED + "画像が見つかりません。");
            return;
        }
        ImageRecord record = library.get(index);

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "画像詳細" + ChatColor.GOLD + "                                   ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  プロンプト: " + ChatColor.WHITE + record.prompt);
        player.sendMessage(ChatColor.GRAY + "  モデル: " + ChatColor.LIGHT_PURPLE + getImageModelDisplayName(record.modelName)
            + ChatColor.GRAY + " | 比率: " + ChatColor.WHITE + record.aspectRatio
            + ChatColor.GRAY + " | 解像度: " + ChatColor.WHITE + record.resolution);
        player.sendMessage(ChatColor.GRAY + "  生成: " + ChatColor.WHITE + getRelativeTimeString(record.timestamp));
        player.sendMessage("");

        // Clickable link
        TextComponent prefix = new TextComponent("  ");
        TextComponent linkText = new TextComponent("[画像を表示する (クリック)]");
        linkText.setColor(net.md_5.bungee.api.ChatColor.GOLD);
        linkText.setBold(true);
        linkText.setUnderlined(true);
        linkText.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, record.imageUrl));
        linkText.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new ComponentBuilder("クリックしてブラウザで画像を表示")
                .color(net.md_5.bungee.api.ChatColor.YELLOW).create()));
        prefix.addExtra(linkText);
        player.spigot().sendMessage(prefix);

        player.sendMessage(ChatColor.GRAY + "  URL: " + ChatColor.GRAY + record.imageUrl);
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  ─────────────────────────────────");
        sendClickableLine(player,
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[変換]", "/gemini library i2i " + index, "この画像をImage-to-Image変換", net.md_5.bungee.api.ChatColor.AQUA),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[ライブラリに戻る]", "/gemini library", "クリックでライブラリに戻る", net.md_5.bungee.api.ChatColor.YELLOW),
            text("  ", net.md_5.bungee.api.ChatColor.GRAY),
            createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        player.sendMessage("");
    }

    private void handleLibraryI2I(Player player, int index) {
        UUID playerId = player.getUniqueId();
        List<ImageRecord> library = playerImageLibrary.get(playerId);
        if (library == null || index < 0 || index >= library.size()) {
            player.sendMessage(ChatColor.RED + "画像が見つかりません。");
            return;
        }
        ImageRecord record = library.get(index);

        // Switch to IMAGE mode and suggest the i2i command
        playerSessionMode.put(playerId, SessionMode.IMAGE);
        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "✦ " + ChatColor.WHITE + "Image-to-Imageモードに切り替えました。");
        player.sendMessage(ChatColor.GRAY + "  元画像: " + ChatColor.WHITE + record.prompt);
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  プロンプトを入力してください:");
        player.sendMessage(ChatColor.GRAY + "  例: " + ChatColor.WHITE + record.imageUrl + " アニメ風にして");
        player.sendMessage("");

        // Use suggest command to pre-fill the URL
        TextComponent line = new TextComponent("  ");
        line.addExtra(createSuggestButton("[プロンプトを入力]", record.imageUrl + " ", "URLが入力されます。続けてプロンプトを入力してください", net.md_5.bungee.api.ChatColor.LIGHT_PURPLE, true));
        line.addExtra(new TextComponent("  "));
        line.addExtra(createClickableButton("[ライブラリに戻る]", "/gemini library", "クリックでライブラリに戻る", net.md_5.bungee.api.ChatColor.YELLOW));
        player.spigot().sendMessage(line);
        player.sendMessage("");
    }

    // ==================== Spreadsheet Logging ====================

    private void logToSpreadsheet(Player player, String mode, String model, String userInput, String aiResponse) {
        if (!spreadsheetEnabled || gasUrl == null || gasUrl.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                java.net.URL url = new java.net.URL(gasUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                // Follow redirects (GAS always redirects)
                conn.setInstanceFollowRedirects(false);

                // Build JSON payload
                com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
                payload.addProperty("spreadsheetId", spreadsheetId);
                payload.addProperty("playerName", player.getName());
                payload.addProperty("uuid", player.getUniqueId().toString());
                payload.addProperty("mode", mode);
                payload.addProperty("model", model);
                payload.addProperty("userInput", userInput != null ? userInput : "");
                // Truncate AI response to prevent payload too large
                String truncatedResponse = aiResponse != null ?
                    (aiResponse.length() > 5000 ? aiResponse.substring(0, 5000) + "..." : aiResponse) : "";
                payload.addProperty("aiResponse", truncatedResponse);
                payload.addProperty("server", getServer().getName());

                byte[] postData = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(postData.length));

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(postData);
                }

                int responseCode = conn.getResponseCode();
                // GAS returns 302 redirect on POST, follow it
                if (responseCode == 302 || responseCode == 301) {
                    String redirectUrl = conn.getHeaderField("Location");
                    if (redirectUrl != null) {
                        java.net.URL redirect = new java.net.URL(redirectUrl);
                        java.net.HttpURLConnection conn2 = (java.net.HttpURLConnection) redirect.openConnection();
                        conn2.setRequestMethod("POST");
                        conn2.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                        conn2.setDoOutput(true);
                        conn2.setConnectTimeout(10000);
                        conn2.setReadTimeout(10000);
                        conn2.setRequestProperty("Content-Length", String.valueOf(postData.length));
                        try (java.io.OutputStream os2 = conn2.getOutputStream()) {
                            os2.write(postData);
                        }
                        conn2.getResponseCode();
                        conn2.disconnect();
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("Failed to log to spreadsheet: " + e.getMessage());
            }
        });
    }

    // ==================== Spreadsheet History Viewer ====================

    private void showSpreadsheetHistory(Player player, int page) {
        if (!spreadsheetEnabled || gasUrl == null || gasUrl.isEmpty() || gasUrl.equals("YOUR_GAS_WEB_APP_URL_HERE")) {
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "スプレッドシート連携が設定されていません。");
            player.sendMessage(ChatColor.GRAY + "config.yml の spreadsheet セクションを設定してください。");
            player.sendMessage("");
            return;
        }

        player.sendMessage(ChatColor.DARK_AQUA + "✦ " + ChatColor.GRAY + "履歴を取得中...");

        final int requestPage = Math.max(1, page);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // GAS doGet にUUID・スプレッドシートIDパラメータを付けてリクエスト
                String uuid = player.getUniqueId().toString();
                String fetchUrl = gasUrl + "?uuid=" + java.net.URLEncoder.encode(uuid, "UTF-8")
                    + "&spreadsheetId=" + java.net.URLEncoder.encode(spreadsheetId, "UTF-8")
                    + "&limit=100";

                java.net.URL url = new java.net.URL(fetchUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);

                int responseCode = conn.getResponseCode();

                // GAS GET may also redirect
                if (responseCode == 302 || responseCode == 301) {
                    String redirectUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (redirectUrl != null) {
                        url = new java.net.URL(redirectUrl);
                        conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(15000);
                        conn.setInstanceFollowRedirects(true);
                        responseCode = conn.getResponseCode();
                    }
                }

                StringBuilder responseBody = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseBody.append(line);
                    }
                }
                conn.disconnect();

                // Parse JSON response
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(responseBody.toString()).getAsJsonObject();
                String status = json.has("status") ? json.get("status").getAsString() : "";

                if (!"ok".equals(status)) {
                    String errorMsg = json.has("message") ? json.get("message").getAsString() : "不明なエラー";
                    Bukkit.getScheduler().runTask(GeminiNPC.this, () -> {
                        player.sendMessage(ChatColor.RED + "履歴の取得に失敗しました: " + errorMsg);
                    });
                    return;
                }

                com.google.gson.JsonArray entries = json.has("entries") ? json.getAsJsonArray("entries") : new com.google.gson.JsonArray();

                Bukkit.getScheduler().runTask(GeminiNPC.this, () -> {
                    displaySpreadsheetHistory(player, entries, requestPage);
                });

            } catch (Exception e) {
                getLogger().warning("Failed to fetch spreadsheet history: " + e.getMessage());
                Bukkit.getScheduler().runTask(GeminiNPC.this, () -> {
                    player.sendMessage(ChatColor.RED + "履歴の取得に失敗しました。接続エラー。");
                });
            }
        });
    }

    private void displaySpreadsheetHistory(Player player, com.google.gson.JsonArray entries, int page) {
        int totalItems = entries.size();
        int itemsPerPage = 5;
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / itemsPerPage));

        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_AQUA + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.DARK_AQUA + "║  " + ChatColor.WHITE + "会話履歴 (" + totalItems + "件)" +
            ChatColor.GRAY + "  ページ " + page + "/" + totalPages + ChatColor.DARK_AQUA + "              ║");
        player.sendMessage(ChatColor.DARK_AQUA + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");

        if (totalItems == 0) {
            player.sendMessage(ChatColor.GRAY + "  まだ履歴がありません。");
            player.sendMessage(ChatColor.GRAY + "  相談・検索・コマンド生成を使うと自動で記録されます。");
        } else {
            int startIndex = (page - 1) * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, totalItems);

            for (int i = startIndex; i < endIndex; i++) {
                com.google.gson.JsonObject entry = entries.get(i).getAsJsonObject();
                String timestamp = entry.has("timestamp") ? entry.get("timestamp").getAsString() : "";
                String mode = entry.has("mode") ? entry.get("mode").getAsString() : "";
                String model = entry.has("model") ? entry.get("model").getAsString() : "";
                String userInput = entry.has("userInput") ? entry.get("userInput").getAsString() : "";
                String aiResponse = entry.has("aiResponse") ? entry.get("aiResponse").getAsString() : "";

                // モード色の設定
                ChatColor modeColor;
                switch (mode) {
                    case "相談": modeColor = ChatColor.AQUA; break;
                    case "検索": modeColor = ChatColor.GREEN; break;
                    case "コマンド生成": modeColor = ChatColor.GOLD; break;
                    default: modeColor = ChatColor.GRAY; break;
                }

                // 入力を短縮
                String shortInput = userInput.length() > 35 ? userInput.substring(0, 35) + "..." : userInput;
                // AI応答を短縮
                String shortResponse = aiResponse.length() > 45 ? aiResponse.substring(0, 45) + "..." : aiResponse;
                // タイムスタンプを短縮（日付部分のみ）
                String shortTime = timestamp.length() > 16 ? timestamp.substring(0, 16) : timestamp;

                int displayNum = i + 1;
                player.sendMessage(ChatColor.WHITE + "  " + displayNum + ". " + modeColor + "[" + mode + "] " +
                    ChatColor.GRAY + shortTime);
                player.sendMessage(ChatColor.YELLOW + "     あなた: " + ChatColor.WHITE + shortInput);
                player.sendMessage(ChatColor.AQUA + "     AI: " + ChatColor.GRAY + shortResponse);
                player.sendMessage("");
            }
        }

        player.sendMessage(ChatColor.GRAY + "  ─────────────────────────────────");

        // ナビゲーションボタン
        final int currentPage = page;
        if (totalPages > 1) {
            TextComponent navLine = new TextComponent("  ");
            if (currentPage > 1) {
                navLine.addExtra(createClickableButton("[← 前]", "/gemini history page " + (currentPage - 1), "前のページ", net.md_5.bungee.api.ChatColor.YELLOW));
                navLine.addExtra(new TextComponent("  "));
            }
            if (currentPage < totalPages) {
                navLine.addExtra(createClickableButton("[次 →]", "/gemini history page " + (currentPage + 1), "次のページ", net.md_5.bungee.api.ChatColor.YELLOW));
                navLine.addExtra(new TextComponent("  "));
            }
            navLine.addExtra(createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
            player.spigot().sendMessage(navLine);
        } else {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        }
        player.sendMessage("");
    }

    // ==================== Mode Navigation Footer ====================

    private void sendModeFooter(Player player) {
        UUID playerId = player.getUniqueId();
        SessionMode mode = getSessionMode(playerId);

        player.sendMessage(ChatColor.GRAY + "─────────────────────────────────");

        if (mode == SessionMode.CHAT) {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[モデル変更]", "/gemini model", "クリックでモデル変更", net.md_5.bungee.api.ChatColor.YELLOW),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[履歴クリア]", "/gemini clear", "クリックで会話履歴をクリア", net.md_5.bungee.api.ChatColor.GRAY),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        } else if (mode == SessionMode.SEARCH) {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[モデル変更]", "/gemini model", "クリックでモデル変更", net.md_5.bungee.api.ChatColor.YELLOW),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        } else if (mode == SessionMode.COMMAND) {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[モデル変更]", "/gemini model", "クリックでモデル変更", net.md_5.bungee.api.ChatColor.YELLOW),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        } else if (mode == SessionMode.IMAGE) {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[設定変更]", "/gemini image settings", "クリックで設定を表示", net.md_5.bungee.api.ChatColor.YELLOW),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[ライブラリ]", "/gemini library", "クリックで画像ライブラリを表示", net.md_5.bungee.api.ChatColor.GOLD),
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        } else {
            sendClickableLine(player,
                text("  ", net.md_5.bungee.api.ChatColor.GRAY),
                createClickableButton("[メニューに戻る]", "/gemini menu", "クリックでメインメニューに戻る", net.md_5.bungee.api.ChatColor.RED));
        }
        player.sendMessage("");
    }

    // ==================== Clickable UI Helpers ====================

    private TextComponent createClickableButton(String label, String command, String hoverText, net.md_5.bungee.api.ChatColor color) {
        TextComponent btn = new TextComponent(label);
        btn.setColor(color);
        btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new ComponentBuilder(hoverText).color(net.md_5.bungee.api.ChatColor.YELLOW).create()));
        return btn;
    }

    private TextComponent createClickableButton(String label, String command, String hoverText, net.md_5.bungee.api.ChatColor color, boolean bold) {
        TextComponent btn = createClickableButton(label, command, hoverText, color);
        btn.setBold(bold);
        return btn;
    }

    private TextComponent createSuggestButton(String label, String suggestion, String hoverText, net.md_5.bungee.api.ChatColor color, boolean bold) {
        TextComponent btn = new TextComponent(label);
        btn.setColor(color);
        btn.setBold(bold);
        btn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggestion));
        btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new ComponentBuilder(hoverText).color(net.md_5.bungee.api.ChatColor.YELLOW).create()));
        return btn;
    }

    private void sendClickableLine(Player player, BaseComponent... components) {
        TextComponent line = new TextComponent("");
        for (BaseComponent c : components) {
            line.addExtra(c);
        }
        player.spigot().sendMessage(line);
    }

    private TextComponent text(String content, net.md_5.bungee.api.ChatColor color) {
        TextComponent t = new TextComponent(content);
        t.setColor(color);
        return t;
    }

    // ==================== API Methods ====================

    private WebSearchResult callGeminiAPIWithSearch(JsonArray conversationHistory, String modelName) {
        try {
            String actualModel = getActualModelName(modelName);
            String thinkingLevel = getThinkingLevel(modelName);
            String urlString = String.format(GEMINI_API_URL, actualModel, apiKey);
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(90000);

            JsonObject requestBody = new JsonObject();

            JsonObject systemInstruction = new JsonObject();
            JsonArray systemParts = new JsonArray();
            JsonObject systemText = new JsonObject();
            systemText.addProperty("text", getWebSearchSystemPrompt());
            systemParts.add(systemText);
            systemInstruction.add("parts", systemParts);
            requestBody.add("systemInstruction", systemInstruction);

            requestBody.add("contents", conversationHistory);

            JsonArray tools = new JsonArray();
            JsonObject googleSearchTool = new JsonObject();
            googleSearchTool.add("google_search", new JsonObject());
            tools.add(googleSearchTool);
            requestBody.add("tools", tools);

            JsonObject generationConfig = new JsonObject();
            generationConfig.addProperty("temperature", 0.7);
            generationConfig.addProperty("topK", 40);
            generationConfig.addProperty("topP", 0.95);
            generationConfig.addProperty("maxOutputTokens", 65536);

            // Add thinking config only for models that support it (Flash Thinking, Pro)
            if (supportsThinking(modelName)) {
                JsonObject thinkingConfig = new JsonObject();
                thinkingConfig.addProperty("thinkingLevel", thinkingLevel);
                generationConfig.add("thinkingConfig", thinkingConfig);
            }

            requestBody.add("generationConfig", generationConfig);

            JsonArray safetySettings = new JsonArray();
            String[] categories = {
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            };
            for (String category : categories) {
                JsonObject setting = new JsonObject();
                setting.addProperty("category", category);
                setting.addProperty("threshold", "BLOCK_MEDIUM_AND_ABOVE");
                safetySettings.add(setting);
            }
            requestBody.add("safetySettings", safetySettings);

            String jsonBody = new Gson().toJson(requestBody);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                }

                JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
                String text = null;
                List<String> sources = new ArrayList<>();

                if (jsonResponse.has("candidates")) {
                    JsonArray candidates = jsonResponse.getAsJsonArray("candidates");
                    if (candidates.size() > 0) {
                        JsonObject candidate = candidates.get(0).getAsJsonObject();
                        if (candidate.has("content")) {
                            JsonObject content = candidate.getAsJsonObject("content");
                            if (content.has("parts")) {
                                JsonArray parts = content.getAsJsonArray("parts");
                                // Concatenate all text parts (important for thinking mode which may return multiple parts)
                                StringBuilder textBuilder = new StringBuilder();
                                for (int i = 0; i < parts.size(); i++) {
                                    JsonObject part = parts.get(i).getAsJsonObject();
                                    if (part.has("text")) {
                                        textBuilder.append(part.get("text").getAsString());
                                    }
                                }
                                if (textBuilder.length() > 0) {
                                    text = textBuilder.toString();
                                }
                            }
                        }

                        if (candidate.has("groundingMetadata")) {
                            JsonObject groundingMetadata = candidate.getAsJsonObject("groundingMetadata");
                            if (groundingMetadata.has("groundingChunks")) {
                                JsonArray chunks = groundingMetadata.getAsJsonArray("groundingChunks");
                                for (int i = 0; i < chunks.size() && i < 5; i++) {
                                    JsonObject chunk = chunks.get(i).getAsJsonObject();
                                    if (chunk.has("web")) {
                                        JsonObject web = chunk.getAsJsonObject("web");
                                        if (web.has("title")) {
                                            sources.add(web.get("title").getAsString());
                                        } else if (web.has("uri")) {
                                            sources.add(web.get("uri").getAsString());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                connection.disconnect();
                return new WebSearchResult(text, sources);

            } else {
                StringBuilder errorResponse = new StringBuilder();
                java.io.InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            errorResponse.append(responseLine.trim());
                        }
                    }
                }
                getLogger().severe("Gemini API Error: " + responseCode + " - " + errorResponse.toString());
            }

            connection.disconnect();

        } catch (Exception e) {
            getLogger().severe("Error calling Gemini API with search: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    private String callGeminiAPIInternal(JsonArray conversationHistory, String modelName, String sysPrompt) {
        try {
            String actualModel = getActualModelName(modelName);
            String thinkingLevel = getThinkingLevel(modelName);
            String urlString = String.format(GEMINI_API_URL, actualModel, apiKey);
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);  // Increased timeout for thinking models

            JsonObject requestBody = new JsonObject();

            JsonObject systemInstruction = new JsonObject();
            JsonArray systemParts = new JsonArray();
            JsonObject systemText = new JsonObject();
            systemText.addProperty("text", sysPrompt);
            systemParts.add(systemText);
            systemInstruction.add("parts", systemParts);
            requestBody.add("systemInstruction", systemInstruction);

            requestBody.add("contents", conversationHistory);

            JsonObject generationConfig = new JsonObject();
            generationConfig.addProperty("temperature", 0.7);
            generationConfig.addProperty("topK", 40);
            generationConfig.addProperty("topP", 0.95);
            generationConfig.addProperty("maxOutputTokens", 65536);

            // Add thinking config only for models that support it (Flash Thinking, Pro)
            if (supportsThinking(modelName)) {
                JsonObject thinkingConfig = new JsonObject();
                thinkingConfig.addProperty("thinkingLevel", thinkingLevel);
                generationConfig.add("thinkingConfig", thinkingConfig);
            }

            requestBody.add("generationConfig", generationConfig);

            JsonArray safetySettings = new JsonArray();
            String[] categories = {
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            };
            for (String category : categories) {
                JsonObject setting = new JsonObject();
                setting.addProperty("category", category);
                setting.addProperty("threshold", "BLOCK_MEDIUM_AND_ABOVE");
                safetySettings.add(setting);
            }
            requestBody.add("safetySettings", safetySettings);

            String jsonBody = new Gson().toJson(requestBody);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                }

                JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();

                if (jsonResponse.has("candidates")) {
                    JsonArray candidates = jsonResponse.getAsJsonArray("candidates");
                    if (candidates.size() > 0) {
                        JsonObject candidate = candidates.get(0).getAsJsonObject();
                        if (candidate.has("content")) {
                            JsonObject content = candidate.getAsJsonObject("content");
                            if (content.has("parts")) {
                                JsonArray parts = content.getAsJsonArray("parts");
                                // Concatenate all text parts (important for thinking mode which may return multiple parts)
                                StringBuilder textBuilder = new StringBuilder();
                                for (int i = 0; i < parts.size(); i++) {
                                    JsonObject part = parts.get(i).getAsJsonObject();
                                    if (part.has("text")) {
                                        textBuilder.append(part.get("text").getAsString());
                                    }
                                }
                                if (textBuilder.length() > 0) {
                                    return textBuilder.toString();
                                }
                            }
                        }
                    }
                }

                getLogger().warning("Unexpected API response format: " + response.toString());
            } else {
                StringBuilder errorResponse = new StringBuilder();
                java.io.InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            errorResponse.append(responseLine.trim());
                        }
                    }
                }
                getLogger().severe("Gemini API Error: " + responseCode + " - " + errorResponse.toString());
            }

            connection.disconnect();

        } catch (Exception e) {
            getLogger().severe("Error calling Gemini API: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }
}
