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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.bukkit.command.TabCompleter;

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
        IMAGE        // Image generation mode
    }

    private String imageHosting;
    private String imgbbApiKey;
    private String defaultImageModel;
    private String defaultAspectRatio;
    private String defaultResolution;

    private final Map<UUID, JsonArray> conversationHistories = new ConcurrentHashMap<>();
    private final Map<UUID, SessionMode> playerSessionMode = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerModels = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerImageModels = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerAspectRatios = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerResolutions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> imageGenerationCooldown = new ConcurrentHashMap<>();
    private static final long IMAGE_COOLDOWN_MS = 10000; // 10 seconds cooldown

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

        getLogger().info("========================================");
        getLogger().info("GeminiNPC Plugin v1.6.0 Enabled!");
        getLogger().info("Default Model: " + defaultModelName);
        getLogger().info("Default Image Model: " + defaultImageModel);
        getLogger().info("Image Hosting: " + imageHosting);
        getLogger().info("Available Models: Flash, Flash Thinking, Pro");
        getLogger().info("Image Models: Nanobanana, Nanobanana Pro");
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        conversationHistories.clear();
        playerSessionMode.clear();
        playerModels.clear();
        playerImageModels.clear();
        playerAspectRatios.clear();
        playerResolutions.clear();
        imageGenerationCooldown.clear();
        getLogger().info("GeminiNPC Plugin Disabled!");
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

        getLogger().info("Model configured: " + defaultModelName);
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
        Bukkit.getScheduler().runTaskLater(this, () -> {
            showWelcomeMessage(player);
        }, 40L);
    }

    private void showWelcomeMessage(Player player) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.GREEN + "GeminiNPC AI相談システム" + ChatColor.GOLD + "                   ║");
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
        conversationHistories.remove(playerId);
        playerSessionMode.remove(playerId);
        playerModels.remove(playerId);
        playerImageModels.remove(playerId);
        playerAspectRatios.remove(playerId);
        playerResolutions.remove(playerId);
        imageGenerationCooldown.remove(playerId);
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

        String command = event.getMessage().toLowerCase();

        // Always allow these commands
        if (command.equals("/gemini") || command.startsWith("/gemini ")) {
            return; // Let the command handler process it
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
                case "image":
                case "画像":
                    startImageMode(player);
                    return;
                case "4":
                case "model":
                case "モデル":
                    showModelSelection(player);
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
                default:
                    player.sendMessage(ChatColor.RED + "無効な選択です。1〜6の数字を入力してください。");
                    return;
            }
        }

        // Handle chat mode
        if (mode == SessionMode.CHAT) {
            event.setCancelled(true);
            String message = command.substring(1).trim();

            if (message.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "/<相談内容> で相談できます。/exit でメニューに戻ります。");
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
                player.sendMessage(ChatColor.GRAY + "/<検索ワード> で検索できます。/exit でメニューに戻ります。");
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
            String input = command.substring(1).trim();

            // Handle image model change within image mode
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

            if (input.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "/<画像の説明> で画像を生成できます。/settings で設定表示。/exit でメニューに戻ります。");
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
            player.sendMessage(ChatColor.LIGHT_PURPLE + "[画像生成] " + ChatColor.WHITE + input);
            player.sendMessage(ChatColor.DARK_GRAY + "  比率: " + capturedRatio + " | 解像度: " + capturedRes);
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
                processImageGeneration(player, input, capturedModel, capturedRatio, capturedRes);
            });
            return;
        }
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
                    case "3":
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
                    case "4":
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
                        player.sendMessage(ChatColor.YELLOW + "使用方法: /gemini [chat|search|image|model|help|status|menu|exit]");
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
            sender.sendMessage(ChatColor.GREEN + "GeminiNPC設定をリロードしました。");
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
                    "chat", "search", "image", "model", "help", "status", "menu", "exit", "clear", "imagemodel"
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
        player.sendMessage(ChatColor.GREEN + "  ✓ " + ChatColor.WHITE + "GeminiNPC システムを起動しました！");
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
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.GREEN + "GeminiNPC メインメニュー" + ChatColor.GOLD + "                   ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  機能を選んでください:");
        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "    /1" + ChatColor.GRAY + " → " + ChatColor.WHITE + "相談モード" + ChatColor.GRAY + " (AIに相談)");
        player.sendMessage(ChatColor.GREEN + "    /2" + ChatColor.GRAY + " → " + ChatColor.WHITE + "検索モード" + ChatColor.GRAY + " (Web検索)");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "    /3" + ChatColor.GRAY + " → " + ChatColor.WHITE + "画像生成モード" + ChatColor.GRAY + " (AI画像生成)");
        player.sendMessage(ChatColor.YELLOW + "    /4" + ChatColor.GRAY + " → " + ChatColor.WHITE + "モデル選択" + ChatColor.GRAY + " (AI切り替え)");
        player.sendMessage(ChatColor.BLUE + "    /5" + ChatColor.GRAY + " → " + ChatColor.WHITE + "ヘルプ");
        player.sendMessage(ChatColor.GRAY + "    /6" + ChatColor.GRAY + " → " + ChatColor.WHITE + "ステータス");
        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GRAY + "  ─────────────────────────────────");
        player.sendMessage(ChatColor.RED + "    /exit" + ChatColor.GRAY + " → システム終了");
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
        player.sendMessage(ChatColor.GREEN + "    /<相談内容>" + ChatColor.GRAY + " → 相談する（例: " + ChatColor.WHITE + "/悩みがある" + ChatColor.GRAY + "）");
        player.sendMessage(ChatColor.YELLOW + "    /exit" + ChatColor.GRAY + "      → メニューに戻る");
        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "[" + npcName + "] " + ChatColor.WHITE +
            "こんにちは！何か困っていることはありますか？気軽に話してくださいね。");
        player.sendMessage("");
    }

    private void startSearchMode(Player player) {
        UUID playerId = player.getUniqueId();
        playerSessionMode.put(playerId, SessionMode.SEARCH);

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GREEN + "║  " + ChatColor.WHITE + "検索モード" + ChatColor.GREEN + "                                 ║");
        player.sendMessage(ChatColor.GREEN + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  使い方:");
        player.sendMessage(ChatColor.GREEN + "    /<検索ワード>" + ChatColor.GRAY + " → 検索する");
        player.sendMessage(ChatColor.YELLOW + "    /exit" + ChatColor.GRAY + "        → メニューに戻る");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  例: " + ChatColor.WHITE + "/Minecraft 建築 コツ");
        player.sendMessage(ChatColor.GRAY + "  例: " + ChatColor.WHITE + "/レッドストーン回路 初心者");
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
        player.sendMessage(ChatColor.LIGHT_PURPLE + "║ " + ChatColor.GRAY + "比率: " + ChatColor.WHITE + ratio + " " + ChatColor.DARK_GRAY + getAspectRatioDescription(ratio) + ChatColor.LIGHT_PURPLE + "               ║");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "║ " + ChatColor.GRAY + "解像度: " + ChatColor.WHITE + getResolutionDisplayName(res) + ChatColor.LIGHT_PURPLE + "                  ║");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  画像を生成:");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "    /<画像の説明>" + ChatColor.GRAY + " → 画像を生成");
        player.sendMessage(ChatColor.GRAY + "      例: " + ChatColor.WHITE + "/夕焼けの海");
        player.sendMessage(ChatColor.GRAY + "      例: " + ChatColor.WHITE + "/かわいい猫がMinecraftで遊んでいる");
        player.sendMessage("");
        player.sendMessage(ChatColor.WHITE + "  設定変更:");
        player.sendMessage(ChatColor.YELLOW + "    /settings" + ChatColor.GRAY + "              → 設定一覧を表示");
        player.sendMessage(ChatColor.YELLOW + "    /model <モデル名>" + ChatColor.GRAY + "     → モデル変更");
        player.sendMessage(ChatColor.YELLOW + "    /ratio <比率>" + ChatColor.GRAY + "        → 比率変更 (例: 16:9)");
        if (MODEL_NANOBANANA_PRO.equals(getPlayerImageModel(playerId))) {
            player.sendMessage(ChatColor.YELLOW + "    /resolution <解像度>" + ChatColor.GRAY + " → 解像度変更 (1K/2K/4K)");
        }
        player.sendMessage(ChatColor.RED + "    /exit" + ChatColor.GRAY + "               → メニューに戻る");
        player.sendMessage("");
    }

    private void showImageModelSelection(Player player) {
        UUID playerId = player.getUniqueId();
        String currentModel = getPlayerImageModel(playerId);

        boolean isNanobanana = MODEL_NANOBANANA.equals(currentModel);
        boolean isNanobananaPro = MODEL_NANOBANANA_PRO.equals(currentModel);

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "画像AIモデル選択" + ChatColor.GOLD + "                           ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  現在: " + ChatColor.WHITE + getImageModelDisplayName(currentModel));
        player.sendMessage("");

        // Nanobanana
        if (isNanobanana) {
            player.sendMessage(ChatColor.GREEN + "  ► " + ChatColor.AQUA + "nanobanana" + ChatColor.WHITE + " - Nanobanana" + ChatColor.GREEN + " [使用中]");
        } else {
            player.sendMessage(ChatColor.GRAY + "    " + ChatColor.AQUA + "nanobanana" + ChatColor.WHITE + " - Nanobanana");
        }
        player.sendMessage(ChatColor.GRAY + "      速度: ★★★★★ | 高速で軽快な画像生成");
        player.sendMessage(ChatColor.GRAY + "      解像度: 最大1K (1024x1024)");
        player.sendMessage("");

        // Nanobanana Pro
        if (isNanobananaPro) {
            player.sendMessage(ChatColor.GREEN + "  ► " + ChatColor.LIGHT_PURPLE + "nanobanana-pro" + ChatColor.WHITE + " - Nanobanana Pro" + ChatColor.GREEN + " [使用中]");
        } else {
            player.sendMessage(ChatColor.GRAY + "    " + ChatColor.LIGHT_PURPLE + "nanobanana-pro" + ChatColor.WHITE + " - Nanobanana Pro");
        }
        player.sendMessage(ChatColor.GRAY + "      品質: ★★★★★ | 高品質・4K対応");
        player.sendMessage(ChatColor.GRAY + "      解像度: 最大4K (4096x4096)");
        player.sendMessage(ChatColor.DARK_GRAY + "      ※生成に時間がかかる場合があります");
        player.sendMessage("");

        player.sendMessage(ChatColor.DARK_GRAY + "  ─────────────────────────────────");
        player.sendMessage(ChatColor.YELLOW + "    /gemini imagemodel nanobanana" + ChatColor.GRAY + "     → 高速");
        player.sendMessage(ChatColor.YELLOW + "    /gemini imagemodel nanobanana-pro" + ChatColor.GRAY + " → 高品質");
        player.sendMessage(ChatColor.RED + "    /exit" + ChatColor.GRAY + " → メニューに戻る");
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
            player.sendMessage(ChatColor.LIGHT_PURPLE + "  ℹ Nanobanana Proは高品質ですが、生成に時間がかかる場合があります。");
            player.sendMessage(ChatColor.GRAY + "  ℹ /resolution 2K or 4K で解像度を上げられます。");
        } else if (newModel.equals(MODEL_NANOBANANA)) {
            player.sendMessage(ChatColor.GRAY + "  ℹ Nanobananaは1K解像度固定です。");
        }
        player.sendMessage("");
    }

    private void showImageSettings(Player player) {
        UUID playerId = player.getUniqueId();
        String currentModel = getPlayerImageModel(playerId);
        String currentRatio = getPlayerAspectRatio(playerId);
        String currentRes = getPlayerResolution(playerId);
        boolean isPro = MODEL_NANOBANANA_PRO.equals(currentModel);

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "╔═══════════════════════════════════════════════╗");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "║  " + ChatColor.WHITE + "画像生成 設定" + ChatColor.LIGHT_PURPLE + "                              ║");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");

        // Current settings
        player.sendMessage(ChatColor.GREEN + "  【現在の設定】");
        player.sendMessage(ChatColor.GRAY + "  モデル:  " + ChatColor.WHITE + getImageModelDisplayName(currentModel));
        player.sendMessage(ChatColor.GRAY + "  比率:    " + ChatColor.WHITE + currentRatio + " " + ChatColor.DARK_GRAY + getAspectRatioDescription(currentRatio));
        player.sendMessage(ChatColor.GRAY + "  解像度:  " + ChatColor.WHITE + getResolutionDisplayName(currentRes));
        player.sendMessage("");

        // Model selection
        player.sendMessage(ChatColor.GREEN + "  【モデル】" + ChatColor.GRAY + " /model <名前>");
        player.sendMessage((MODEL_NANOBANANA.equals(currentModel) ? ChatColor.GREEN + "  ► " : ChatColor.GRAY + "    ")
            + ChatColor.AQUA + "nanobanana" + ChatColor.GRAY + " - 高速 (1Kのみ, ~$0.04/枚)");
        player.sendMessage((isPro ? ChatColor.GREEN + "  ► " : ChatColor.GRAY + "    ")
            + ChatColor.LIGHT_PURPLE + "nanobanana-pro" + ChatColor.GRAY + " - 高品質 (4K対応, ~$0.13-0.24/枚)");
        player.sendMessage("");

        // Aspect ratio selection
        player.sendMessage(ChatColor.GREEN + "  【アスペクト比】" + ChatColor.GRAY + " /ratio <比率>");
        StringBuilder ratioLine1 = new StringBuilder(ChatColor.GRAY + "    ");
        StringBuilder ratioLine2 = new StringBuilder(ChatColor.GRAY + "    ");
        int count = 0;
        for (String ratio : VALID_ASPECT_RATIOS) {
            String display;
            if (ratio.equals(currentRatio)) {
                display = ChatColor.GREEN + "[" + ratio + "]" + ChatColor.GRAY;
            } else {
                display = ChatColor.WHITE + ratio + ChatColor.GRAY;
            }
            if (count < 5) {
                ratioLine1.append(display).append("  ");
            } else {
                ratioLine2.append(display).append("  ");
            }
            count++;
        }
        player.sendMessage(ratioLine1.toString());
        player.sendMessage(ratioLine2.toString());
        player.sendMessage("");

        // Resolution selection (Pro only)
        player.sendMessage(ChatColor.GREEN + "  【解像度】" + ChatColor.GRAY + (isPro ? " /resolution <解像度>" : " (Proのみ変更可)"));
        if (isPro) {
            player.sendMessage(ChatColor.GRAY + "    "
                + (RESOLUTION_1K.equals(currentRes) ? ChatColor.GREEN + "[1K]" : ChatColor.WHITE + "1K")
                + ChatColor.GRAY + " 1024px - ~$0.13/枚");
            player.sendMessage(ChatColor.GRAY + "    "
                + (RESOLUTION_2K.equals(currentRes) ? ChatColor.GREEN + "[2K]" : ChatColor.WHITE + "2K")
                + ChatColor.GRAY + " 2048px - ~$0.13/枚");
            player.sendMessage(ChatColor.GRAY + "    "
                + (RESOLUTION_4K.equals(currentRes) ? ChatColor.GREEN + "[4K]" : ChatColor.RED + "4K")
                + ChatColor.GRAY + " 4096px - " + ChatColor.RED + "~$0.24/枚 (高コスト)");
        } else {
            player.sendMessage(ChatColor.GRAY + "    " + ChatColor.GREEN + "[1K]" + ChatColor.GRAY + " 1024px (Nanobananaは1K固定)");
        }
        player.sendMessage("");

        player.sendMessage(ChatColor.DARK_GRAY + "  ─────────────────────────────────");
        player.sendMessage(ChatColor.GRAY + "  " + ChatColor.YELLOW + "/<画像の説明>" + ChatColor.GRAY + " → 画像を生成");
        player.sendMessage(ChatColor.GRAY + "  " + ChatColor.YELLOW + "/exit" + ChatColor.GRAY + " → メニューに戻る");
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
            + " " + ChatColor.DARK_GRAY + getAspectRatioDescription(normalized)
            + ChatColor.GREEN + " に変更しました。");
        player.sendMessage("");
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
            player.sendMessage(ChatColor.RED + "  1K/2K (~$0.13/枚) の約1.8倍のコストです。");
            player.sendMessage(ChatColor.GRAY + "  コストを抑えたい場合は /resolution 1K で戻せます。");
        }
        player.sendMessage("");
    }

    private void sendImageLink(Player player, String imageUrl, String prompt, String modelName, String aspectRatio, String resolution) {
        String displayModel = getImageModelDisplayName(modelName);

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
            player.sendMessage(ChatColor.DARK_GRAY + "  URL: " + ChatColor.GRAY + imageUrl);

            player.sendMessage("");
            player.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────────────");
            player.sendMessage(ChatColor.GRAY + "  続けて /<説明> で別の画像を生成");
            player.sendMessage(ChatColor.GRAY + "  " + ChatColor.YELLOW + "/exit" + ChatColor.GRAY + " → メニューに戻る");
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
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.GRAY + "会話履歴: " + ChatColor.WHITE + historySize + " メッセージ" + ChatColor.GOLD + "                ║");
        player.sendMessage(ChatColor.GOLD + "╠═══════════════════════════════════════════════╣");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.YELLOW + "/gemini clear" + ChatColor.GRAY + " で履歴クリア" + ChatColor.GOLD + "              ║");
        player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.YELLOW + "/gemini menu" + ChatColor.GRAY + " でメニューに戻る" + ChatColor.GOLD + "            ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
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
        player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "AIモデル選択" + ChatColor.GOLD + "                               ║");
        player.sendMessage(ChatColor.GOLD + "╚═══════════════════════════════════════════════╝");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  現在: " + ChatColor.WHITE + getModelDisplayName(currentModel));
        player.sendMessage("");

        // Flash
        if (isFlash) {
            player.sendMessage(ChatColor.GREEN + "  ► " + ChatColor.AQUA + "flash" + ChatColor.WHITE + " - Gemini 3 Flash" + ChatColor.GREEN + " [使用中]");
        } else {
            player.sendMessage(ChatColor.GRAY + "    " + ChatColor.AQUA + "flash" + ChatColor.WHITE + " - Gemini 3 Flash");
        }
        player.sendMessage(ChatColor.GRAY + "      速度: ★★★★★ | サクサク軽快な応答");
        player.sendMessage("");

        // Flash Thinking
        if (isFlashThinking) {
            player.sendMessage(ChatColor.GREEN + "  ► " + ChatColor.GOLD + "thinking" + ChatColor.WHITE + " - Gemini 3 Flash Thinking" + ChatColor.GREEN + " [使用中]");
        } else {
            player.sendMessage(ChatColor.GRAY + "    " + ChatColor.GOLD + "thinking" + ChatColor.WHITE + " - Gemini 3 Flash Thinking");
        }
        player.sendMessage(ChatColor.GRAY + "      バランス: ★★★★☆ | 速度と深い思考の両立");
        player.sendMessage("");

        // Pro
        if (isPro) {
            player.sendMessage(ChatColor.GREEN + "  ► " + ChatColor.LIGHT_PURPLE + "pro" + ChatColor.WHITE + " - Gemini 3 Pro" + ChatColor.GREEN + " [使用中]");
        } else {
            player.sendMessage(ChatColor.GRAY + "    " + ChatColor.LIGHT_PURPLE + "pro" + ChatColor.WHITE + " - Gemini 3 Pro");
        }
        player.sendMessage(ChatColor.GRAY + "      品質: ★★★★★ | 最高性能・詳しい回答");
        player.sendMessage(ChatColor.DARK_GRAY + "      ※推論に時間がかかる場合があります");
        player.sendMessage("");

        player.sendMessage(ChatColor.DARK_GRAY + "  ─────────────────────────────────");
        player.sendMessage(ChatColor.YELLOW + "    /gemini model flash" + ChatColor.GRAY + "    → Flash");
        player.sendMessage(ChatColor.YELLOW + "    /gemini model thinking" + ChatColor.GRAY + " → Flash Thinking");
        player.sendMessage(ChatColor.YELLOW + "    /gemini model pro" + ChatColor.GRAY + "      → Pro");
        player.sendMessage(ChatColor.RED + "    /exit" + ChatColor.GRAY + " → メニューに戻る");
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
        sender.sendMessage(ChatColor.GOLD + "║  " + ChatColor.GREEN + "GeminiNPC ヘルプ" + ChatColor.GRAY + " - ページ " + page + "/" + TOTAL_PAGES + ChatColor.GOLD + "             ║");
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
        if (page < TOTAL_PAGES) {
            sender.sendMessage(ChatColor.YELLOW + "次のページ: " + ChatColor.WHITE + "/gemini help " + (page + 1));
        }
        sender.sendMessage(ChatColor.YELLOW + "トピック別: " + ChatColor.WHITE + "/gemini help <トピック>");
        sender.sendMessage(ChatColor.GRAY + "トピック: chat, search, image, model, commands");
        sender.sendMessage("");
    }

    private void showHelpPage1(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【概要】");
        sender.sendMessage(ChatColor.WHITE + "GeminiNPCは、Google Gemini AIを使った");
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
        sender.sendMessage(ChatColor.AQUA + "• " + ChatColor.WHITE + "画像生成モード - AIで画像を生成");
    }

    private void showHelpPage2(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【相談モードの使い方】");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.WHITE + "メインメニューで " + ChatColor.YELLOW + "/1" + ChatColor.WHITE + " または " + ChatColor.YELLOW + "/chat");
        sender.sendMessage(ChatColor.WHITE + "を入力して相談モードを開始。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【相談の仕方】");
        sender.sendMessage(ChatColor.YELLOW + "/<相談内容>" + ChatColor.WHITE + " で相談できます");
        sender.sendMessage(ChatColor.GRAY + "例: /最近悩んでいることがある");
        sender.sendMessage(ChatColor.GRAY + "例: /勉強のやる気が出ない");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【終了・戻る】");
        sender.sendMessage(ChatColor.YELLOW + "/exit" + ChatColor.WHITE + " でメインメニューに戻ります");
    }

    private void showHelpPage3(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【検索モードの使い方】");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.WHITE + "メインメニューで " + ChatColor.YELLOW + "/2" + ChatColor.WHITE + " または " + ChatColor.YELLOW + "/search");
        sender.sendMessage(ChatColor.WHITE + "を入力して検索モードを開始。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【検索の仕方】");
        sender.sendMessage(ChatColor.YELLOW + "/<検索ワード>" + ChatColor.WHITE + " で検索できます");
        sender.sendMessage(ChatColor.GRAY + "例: /Minecraft 建築 コツ");
        sender.sendMessage(ChatColor.GRAY + "例: /レッドストーン回路 初心者");
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
        sender.sendMessage(ChatColor.WHITE + "メインメニューで " + ChatColor.YELLOW + "/4" + ChatColor.WHITE + " または " + ChatColor.YELLOW + "/model");
        sender.sendMessage(ChatColor.WHITE + "を入力してモデル選択画面へ。");
    }

    private void showHelpPage5(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【画像生成モード】");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.WHITE + "メインメニューで " + ChatColor.YELLOW + "/3" + ChatColor.WHITE + " または " + ChatColor.YELLOW + "/image");
        sender.sendMessage(ChatColor.WHITE + "を入力して画像生成モードを開始。");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GREEN + "【画像モード内コマンド】");
        sender.sendMessage(ChatColor.YELLOW + "/<画像の説明>" + ChatColor.WHITE + " → 画像を生成");
        sender.sendMessage(ChatColor.YELLOW + "/settings" + ChatColor.WHITE + " → 設定メニュー表示");
        sender.sendMessage(ChatColor.YELLOW + "/model" + ChatColor.WHITE + " → モデル切替");
        sender.sendMessage(ChatColor.YELLOW + "/ratio <値>" + ChatColor.WHITE + " → アスペクト比変更");
        sender.sendMessage(ChatColor.YELLOW + "/resolution <値>" + ChatColor.WHITE + " → 解像度変更(Pro)");
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
                sender.sendMessage(ChatColor.WHITE + "/gemini → メニューで /1 または /chat");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【使い方】");
                sender.sendMessage(ChatColor.WHITE + "/<相談内容> で直接相談");
                sender.sendMessage(ChatColor.GRAY + "例: /友達と喧嘩してしまった");
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
                sender.sendMessage(ChatColor.WHITE + "/gemini → メニューで /2 または /search");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【使い方】");
                sender.sendMessage(ChatColor.WHITE + "/<検索ワード> で検索");
                sender.sendMessage(ChatColor.GRAY + "例: /Minecraft MOD おすすめ");
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
                sender.sendMessage(ChatColor.WHITE + "/gemini → メニューで /3 または /image");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【使い方】");
                sender.sendMessage(ChatColor.WHITE + "/<画像の説明> で画像を生成");
                sender.sendMessage(ChatColor.GRAY + "例: /宇宙から見た地球");
                sender.sendMessage(ChatColor.GRAY + "例: /かわいい猫のイラスト");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【画像モデル】");
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Nanobanana" + ChatColor.WHITE + " - 高速・1Kのみ (~$0.04/枚)");
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Nanobanana Pro" + ChatColor.WHITE + " - 高品質・4K対応 (~$0.13/枚)");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【画像モード内コマンド】");
                sender.sendMessage(ChatColor.YELLOW + "/settings" + ChatColor.WHITE + " - 設定メニュー表示");
                sender.sendMessage(ChatColor.YELLOW + "/model" + ChatColor.WHITE + " - モデル切替");
                sender.sendMessage(ChatColor.YELLOW + "/ratio <値>" + ChatColor.WHITE + " - アスペクト比変更");
                sender.sendMessage(ChatColor.GRAY + "  (1:1, 2:3, 3:2, 3:4, 4:3, 4:5, 5:4, 9:16, 16:9, 21:9)");
                sender.sendMessage(ChatColor.YELLOW + "/resolution <値>" + ChatColor.WHITE + " - 解像度変更 (Proのみ)");
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
                sender.sendMessage(ChatColor.YELLOW + "/gemini model flash");
                sender.sendMessage(ChatColor.YELLOW + "/gemini model pro");
                break;

            case "commands":
            case "コマンド":
                sender.sendMessage(ChatColor.GOLD + "══════ コマンド一覧 ══════");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【メイン】");
                sender.sendMessage(ChatColor.YELLOW + "/gemini" + ChatColor.WHITE + " - システム起動/メニュー表示");
                sender.sendMessage(ChatColor.YELLOW + "/gemini chat" + ChatColor.WHITE + " - 相談モード開始");
                sender.sendMessage(ChatColor.YELLOW + "/gemini search" + ChatColor.WHITE + " - 検索モード開始");
                sender.sendMessage(ChatColor.YELLOW + "/gemini image" + ChatColor.WHITE + " - 画像生成モード開始");
                sender.sendMessage(ChatColor.YELLOW + "/gemini model" + ChatColor.WHITE + " - テキストモデル選択");
                sender.sendMessage(ChatColor.YELLOW + "/gemini imagemodel" + ChatColor.WHITE + " - 画像モデル選択");
                sender.sendMessage(ChatColor.YELLOW + "/gemini help" + ChatColor.WHITE + " - ヘルプ表示");
                sender.sendMessage(ChatColor.YELLOW + "/gemini status" + ChatColor.WHITE + " - ステータス表示");
                sender.sendMessage(ChatColor.YELLOW + "/gemini clear" + ChatColor.WHITE + " - 履歴クリア");
                sender.sendMessage(ChatColor.YELLOW + "/gemini exit" + ChatColor.WHITE + " - システム終了");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【モード内共通】");
                sender.sendMessage(ChatColor.YELLOW + "/<内容>" + ChatColor.WHITE + " - 相談/検索/画像生成を実行");
                sender.sendMessage(ChatColor.YELLOW + "/exit" + ChatColor.WHITE + " - メニューに戻る");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.GREEN + "【画像モード内】");
                sender.sendMessage(ChatColor.YELLOW + "/settings" + ChatColor.WHITE + " - 設定メニュー");
                sender.sendMessage(ChatColor.YELLOW + "/model" + ChatColor.WHITE + " - 画像モデル切替");
                sender.sendMessage(ChatColor.YELLOW + "/ratio <値>" + ChatColor.WHITE + " - アスペクト比変更");
                sender.sendMessage(ChatColor.YELLOW + "/resolution <値>" + ChatColor.WHITE + " - 解像度変更(Proのみ)");
                break;

            default:
                sender.sendMessage(ChatColor.RED + "不明なトピック: " + topic);
                sender.sendMessage(ChatColor.YELLOW + "利用可能: chat, search, image, model, commands");
                break;
        }
        sender.sendMessage("");
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
                player.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────────────");
                player.sendMessage(ChatColor.GRAY + "  " + ChatColor.YELLOW + "/exit" + ChatColor.GRAY + " → メニューに戻る");
                player.sendMessage("");
            });
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
            player.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────────────");
            player.sendMessage(ChatColor.GRAY + "  " + ChatColor.YELLOW + "/exit" + ChatColor.GRAY + " → メニューに戻る");
            player.sendMessage("");
        });
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
                    player.sendMessage(ChatColor.GRAY + "しばらく待ってから再度お試しください。");
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
            } else {
                getLogger().severe("ImgBB upload error: " + responseCode);
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
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "GeminiNPC/1.6.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            try (OutputStream os = conn.getOutputStream()) {
                writeMultipartField(os, boundary, "reqtype", "fileupload");
                writeMultipartFile(os, boundary, "fileToUpload", "generated_image.png", imageData, "image/png");
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
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
                if (result.startsWith("https://")) {
                    return result;
                }
                getLogger().warning("Catbox returned unexpected response: " + result);
            } else {
                getLogger().severe("Catbox upload error: " + responseCode);
            }

        } catch (Exception e) {
            getLogger().severe("Catbox upload failed: " + e.getMessage());
            e.printStackTrace();
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
