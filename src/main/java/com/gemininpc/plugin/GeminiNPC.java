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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GeminiNPC extends JavaPlugin implements Listener {

    private String apiKey;
    private String modelName;
    private String systemPrompt;
    private String npcName;
    private int maxHistorySize;

    private final Map<UUID, JsonArray> conversationHistories = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> chatModeActive = new ConcurrentHashMap<>();

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("========================================");
        getLogger().info("GeminiNPC Plugin Enabled!");
        getLogger().info("Model: " + modelName);
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        conversationHistories.clear();
        chatModeActive.clear();
        getLogger().info("GeminiNPC Plugin Disabled!");
    }

    private void loadConfiguration() {
        reloadConfig();
        FileConfiguration config = getConfig();

        apiKey = config.getString("gemini.api-key", "YOUR_API_KEY_HERE");
        modelName = config.getString("gemini.model", "gemini-2.0-flash");
        systemPrompt = config.getString("gemini.system-prompt", getDefaultSystemPrompt());
        npcName = config.getString("npc.name", "相談員");
        maxHistorySize = config.getInt("conversation.max-history", 20);

        if (apiKey.equals("YOUR_API_KEY_HERE")) {
            getLogger().warning("Please set your Gemini API key in config.yml!");
        }
    }

    private String getDefaultSystemPrompt() {
        return "あなたはMinecraftの世界で生徒の相談に乗るカウンセラーです。\n" +
               "以下のルールを必ず守ってください：\n" +
               "1. 回答は必ず200文字以内で簡潔に\n" +
               "2. まず相手の言葉を受け止め、共感を示す\n" +
               "3. 相手が言ったことを分かりやすく言い換えてまとめる\n" +
               "4. 次に何をすればいいか、具体的なアクションを1つ提案する\n" +
               "5. 優しく励ますトーンで話す\n" +
               "6. 質問で終わり、対話を促す";
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.sendMessage(ChatColor.GOLD + "[" + npcName + "] " + ChatColor.GREEN +
            "困ったことがあれば /gemini で相談モードを開始できます。");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        conversationHistories.remove(playerId);
        chatModeActive.remove(playerId);
    }

    // Intercept ALL commands while in chat mode
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Check if player is in chat mode
        if (!chatModeActive.getOrDefault(playerId, false)) {
            return; // Not in chat mode, let command proceed normally
        }

        String command = event.getMessage(); // includes the /

        // Allow /exit to end chat mode
        if (command.equalsIgnoreCase("/exit") ||
            command.equalsIgnoreCase("/quit") ||
            command.equalsIgnoreCase("/end")) {
            event.setCancelled(true);
            chatModeActive.put(playerId, false);
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "[" + npcName + "] " + ChatColor.YELLOW +
                "相談モードを終了しました。また何かあればいつでも /gemini で相談してね！");
            player.sendMessage("");
            return;
        }

        // Allow /gemini to show status
        if (command.equalsIgnoreCase("/gemini")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.YELLOW + "現在、相談モード中です。");
            player.sendMessage(ChatColor.GRAY + "相談: " + ChatColor.WHITE + "/<相談内容>");
            player.sendMessage(ChatColor.GRAY + "終了: " + ChatColor.WHITE + "/exit");
            return;
        }

        // Cancel the original command
        event.setCancelled(true);

        // Extract message (remove the leading /)
        String message = command.substring(1).trim();

        if (message.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "/<相談内容> で相談できます。/exit で終了。");
            return;
        }

        // Show user's message
        player.sendMessage(ChatColor.GRAY + "[あなた] " + ChatColor.WHITE + message);

        // Process with Gemini asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            processGeminiChat(player, message);
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /gemini command - start chat mode
        if (command.getName().equalsIgnoreCase("gemini")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();
            boolean isActive = chatModeActive.getOrDefault(playerId, false);

            if (!isActive) {
                // Start chat mode
                chatModeActive.put(playerId, true);
                player.sendMessage("");
                player.sendMessage(ChatColor.GOLD + "╔════════════════════════════════════╗");
                player.sendMessage(ChatColor.GOLD + "║  " + ChatColor.GREEN + "相談モードを開始しました！" + ChatColor.GOLD + "       ║");
                player.sendMessage(ChatColor.GOLD + "╠════════════════════════════════════╣");
                player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.WHITE + "/<相談内容>" + ChatColor.GRAY + " で相談できます" + ChatColor.GOLD + "     ║");
                player.sendMessage(ChatColor.GOLD + "║ " + ChatColor.WHITE + "/exit" + ChatColor.GRAY + " で終了" + ChatColor.GOLD + "                  ║");
                player.sendMessage(ChatColor.GOLD + "╚════════════════════════════════════╝");
                player.sendMessage("");
                player.sendMessage(ChatColor.AQUA + "[" + npcName + "] " + ChatColor.WHITE +
                    "こんにちは！何か困っていることはありますか？気軽に話してくださいね。");
                player.sendMessage("");
            } else {
                player.sendMessage(ChatColor.YELLOW + "既に相談モード中です。");
                player.sendMessage(ChatColor.GRAY + "相談: " + ChatColor.WHITE + "/<相談内容>");
                player.sendMessage(ChatColor.GRAY + "終了: " + ChatColor.WHITE + "/exit");
            }
            return true;
        }

        // /exit command (fallback for when not in chat mode)
        if (command.getName().equalsIgnoreCase("exit")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            Player player = (Player) sender;
            player.sendMessage(ChatColor.GRAY + "現在、相談モードではありません。/gemini で開始できます。");
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

        // /geminiclear command
        if (command.getName().equalsIgnoreCase("geminiclear")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            Player player = (Player) sender;
            conversationHistories.remove(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "会話履歴をクリアしました。");
            return true;
        }

        // /geminihelp command
        if (command.getName().equalsIgnoreCase("geminihelp")) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.GOLD + "=== 相談システム ヘルプ ===");
            sender.sendMessage(ChatColor.YELLOW + "/gemini" + ChatColor.WHITE + " - 相談モードを開始");
            sender.sendMessage(ChatColor.GRAY + "  相談モード中:");
            sender.sendMessage(ChatColor.YELLOW + "  /<相談内容>" + ChatColor.WHITE + " - 相談する");
            sender.sendMessage(ChatColor.YELLOW + "  /exit" + ChatColor.WHITE + " - 相談モードを終了");
            sender.sendMessage("");
            return true;
        }

        return false;
    }

    private void processGeminiChat(Player player, String userMessage) {
        UUID playerId = player.getUniqueId();

        JsonArray history = conversationHistories.computeIfAbsent(playerId, k -> new JsonArray());

        // Add user message
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

        String response = callGeminiAPI(history);

        if (response != null && !response.isEmpty()) {
            // Add response to history
            JsonObject assistantPart = new JsonObject();
            assistantPart.addProperty("role", "model");
            JsonArray assistantParts = new JsonArray();
            JsonObject respTextPart = new JsonObject();
            respTextPart.addProperty("text", response);
            assistantParts.add(respTextPart);
            assistantPart.add("parts", assistantParts);
            history.add(assistantPart);

            final String finalResponse = response;
            Bukkit.getScheduler().runTask(this, () -> {
                player.sendMessage("");
                player.sendMessage(ChatColor.AQUA + "[" + npcName + "] " + ChatColor.WHITE + finalResponse);
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

    private String callGeminiAPI(JsonArray conversationHistory) {
        try {
            String urlString = String.format(GEMINI_API_URL, modelName, apiKey);
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);

            JsonObject requestBody = new JsonObject();

            // System instruction
            JsonObject systemInstruction = new JsonObject();
            JsonArray systemParts = new JsonArray();
            JsonObject systemText = new JsonObject();
            systemText.addProperty("text", systemPrompt);
            systemParts.add(systemText);
            systemInstruction.add("parts", systemParts);
            requestBody.add("systemInstruction", systemInstruction);

            requestBody.add("contents", conversationHistory);

            // Generation config
            JsonObject generationConfig = new JsonObject();
            generationConfig.addProperty("temperature", 0.7);
            generationConfig.addProperty("topK", 40);
            generationConfig.addProperty("topP", 0.95);
            generationConfig.addProperty("maxOutputTokens", 256);
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
                                if (parts.size() > 0) {
                                    JsonObject part = parts.get(0).getAsJsonObject();
                                    if (part.has("text")) {
                                        return part.get("text").getAsString();
                                    }
                                }
                            }
                        }
                    }
                }

                getLogger().warning("Unexpected API response format: " + response.toString());
            } else {
                StringBuilder errorResponse = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        errorResponse.append(responseLine.trim());
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
