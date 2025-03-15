package cn.ningmo.bot;

import cn.ningmo.ai.AIService;
import cn.ningmo.ai.model.ModelManager;
import cn.ningmo.ai.persona.PersonaManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.ningmo.utils.CommonUtils;

public class MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(MessageHandler.class);
    
    private final OneBotClient botClient;
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final AIService aiService;
    private final ModelManager modelManager;
    private final PersonaManager personaManager;
    
    private final ExecutorService executor;
    
    public MessageHandler(OneBotClient botClient, ConfigLoader configLoader, DataManager dataManager, AIService aiService) {
        this.botClient = botClient;
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.aiService = aiService;
        this.modelManager = new ModelManager(configLoader);
        this.personaManager = new PersonaManager(configLoader);
        this.executor = Executors.newFixedThreadPool(10);
    }
    
    /**
     * 处理消息事件
     */
    public void handleMessage(JSONObject message) {
        try {
            String messageType = message.getString("message_type");
            
            if ("group".equals(messageType)) {
                // 处理群消息
                handleGroupMessage(message);
            } else if ("private".equals(messageType)) {
                // 处理私聊消息（如果启用）
                if (botClient.isPrivateMessageEnabled()) {
                    handlePrivateMessage(message);
                } else {
                    // 记录私聊尝试（可选择是否回复"私聊功能已关闭"的消息）
                    String userId = CommonUtils.safeGetString(message, "user_id");
                    logger.debug("收到私聊消息，但私聊功能已关闭。用户ID: {}", userId);
                    
                    // 可选：对管理员启用私聊（即使全局私聊关闭）
                    Object adminsObj = configLoader.getConfigMap("bot").getOrDefault("admins", new ArrayList<>());
                    List<String> admins = new ArrayList<>();
                    
                    // 处理类型转换
                    if (adminsObj instanceof List<?>) {
                        for (Object item : (List<?>) adminsObj) {
                            if (item instanceof String) {
                                admins.add((String) item);
                            }
                        }
                    }
                    
                    if (admins.contains(userId)) {
                        logger.info("管理员私聊消息，允许处理。用户ID: {}", userId);
                        handlePrivateMessage(message);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("处理消息时出错", e);
        }
    }
    
    /**
     * 处理群消息并生成AI回复
     * @param groupId 群ID
     * @param userId 用户ID
     * @param content 消息内容
     * @param atSender 是否@发送者
     */
    private void processGroupAiReply(String groupId, String userId, String content, boolean atSender) {
        CompletableFuture.runAsync(() -> {
            try {
                // 获取多段回复
                List<String> replies = aiService.chatMultipart(userId, content);
                
                // 如果AI选择不回复
                if (replies.isEmpty()) {
                    logger.info("AI选择不回复消息");
                    return;
                }
                
                // 获取消息间隔时间（毫秒）
                int messageInterval = configLoader.getConfig("bot.messages.interval", 300);
                
                // 发送首条消息
                String firstReply = replies.get(0);
                if (atSender) {
                    firstReply = "[CQ:at,qq=" + userId + "] " + firstReply;
                }
                botClient.sendGroupMessage(groupId, firstReply);
                
                // 发送后续消息
                if (replies.size() > 1) {
                    // 添加延迟，避免消息发送过快
                    try {
                        Thread.sleep(messageInterval);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    // 发送剩余消息
                    for (int i = 1; i < replies.size(); i++) {
                        botClient.sendGroupMessage(groupId, replies.get(i));
                        
                        // 如果还有下一条消息，等待一段时间
                        if (i < replies.size() - 1) {
                            try {
                                Thread.sleep(messageInterval);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("处理AI回复时出错", e);
                botClient.sendGroupMessage(groupId, "抱歉，处理回复时出现错误：" + e.getMessage());
            }
        }, executor);
    }
    
    /**
     * 处理私聊消息并生成AI回复
     * @param userId 用户ID
     * @param content 消息内容
     */
    private void processPrivateAiReply(String userId, String content) {
        CompletableFuture.runAsync(() -> {
            try {
                // 获取多段回复
                List<String> replies = aiService.chatMultipart(userId, content);
                
                // 如果AI选择不回复
                if (replies.isEmpty()) {
                    logger.info("AI选择不回复私聊消息");
                    return;
                }
                
                // 获取消息间隔时间（毫秒）
                int messageInterval = configLoader.getConfig("bot.messages.interval", 300);
                
                // 发送所有消息
                for (int i = 0; i < replies.size(); i++) {
                    botClient.sendPrivateMessage(userId, replies.get(i));
                    
                    // 如果还有下一条消息，等待一段时间
                    if (i < replies.size() - 1) {
                        try {
                            Thread.sleep(messageInterval);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("处理私聊AI回复时出错", e);
                botClient.sendPrivateMessage(userId, "抱歉，处理回复时出现错误：" + e.getMessage());
            }
        }, executor);
    }
    
    private void handleGroupMessage(JSONObject message) {
        try {
            String groupId = CommonUtils.safeGetString(message, "group_id");
            String userId = CommonUtils.safeGetString(message, "user_id");
            String rawMessage = message.optString("raw_message", "");
            
            // 添加日志，帮助调试消息格式
            logger.debug("收到群消息: groupId={}, userId={}, rawMessage={}", groupId, userId, 
                         CommonUtils.truncateText(rawMessage, 100));
            
            // 检查群是否启用AI，如果未启用且不是管理员，直接返回
            boolean isAdmin;
            try {
                isAdmin = isGroupAdmin(userId, groupId);
            } catch (Exception e) {
                logger.error("检查用户权限时出错: ", e);
                isAdmin = false; // 出错时保守处理，当作非管理员
            }
            
            boolean isGroupEnabled;
            try {
                isGroupEnabled = dataManager.isGroupAIEnabled(groupId);
            } catch (Exception e) {
                logger.error("检查群AI状态时出错: ", e);
                isGroupEnabled = false; // 出错时保守处理，当作禁用
            }
            
            if (!isGroupEnabled && !isAdmin) {
                return; // 群未启用，且不是管理员，无视所有消息
            }
            
            // 处理命令
            if (rawMessage.startsWith("/")) {
                try {
                    handleGroupCommand(groupId, userId, rawMessage);
                } catch (Exception e) {
                    logger.error("处理群命令时出错: ", e);
                    botClient.sendGroupMessage(groupId, "处理命令时出现错误，请稍后再试。");
                }
                return;
            }
            
            // 群已启用AI功能，处理对话触发条件
            String selfId = configLoader.getConfigString("bot.self_id");
            String botName = configLoader.getConfigString("bot.name", "柠檬");
            
            // 更强大的@检测：检查消息中所有的CQ:at代码
            boolean isAtBot = false;
            
            // 方法1：使用正则表达式检查所有@标记
            if (rawMessage.contains("[CQ:at")) {
                Pattern pattern = Pattern.compile("\\[CQ:at,qq=(\\d+)\\]");
                Matcher matcher = pattern.matcher(rawMessage);
                while (matcher.find()) {
                    String atQQ = matcher.group(1);
                    if (selfId.equals(atQQ)) {
                        isAtBot = true;
                        break;
                    }
                }
            }
            
            // 方法2：备用检测，防止格式差异
            if (!isAtBot) {
                isAtBot = rawMessage.contains("@" + selfId) || 
                          rawMessage.contains("[CQ:at,qq=" + selfId + "]") ||
                          rawMessage.contains("@" + botName);
            }
            
            // 触发条件2：消息包含机器人名字
            boolean containsBotName = !CommonUtils.isEmpty(botName) && rawMessage.contains(botName);
            
            // 记录触发状态
            logger.debug("消息触发检测: isAtBot={}, containsBotName={}, isEnabled={}", 
                        isAtBot, containsBotName, dataManager.isGroupAIEnabled(groupId));
            
            // 如果触发了任一条件，则进行回复
            if (dataManager.isGroupAIEnabled(groupId) && (isAtBot || containsBotName)) {
                // 清理消息内容，去掉@标记
                String cleanedContent = rawMessage
                    .replaceAll("@" + selfId, "")
                    .replaceAll("\\[CQ:at,qq=" + selfId + "\\]", "")
                    .trim();
                
                logger.debug("处理AI回复: userId={}, cleanedContent={}", userId, cleanedContent);
                
                // 检查是否需要@发送者
                boolean alwaysAtSender = configLoader.getConfigBoolean("bot.always_at_sender");
                
                // 使用新的方法处理AI回复
                processGroupAiReply(groupId, userId, cleanedContent, alwaysAtSender);
            }
        } catch (Exception e) {
            logger.error("处理群消息时出现未预期错误: ", e);
        }
    }
    
    private void handlePrivateMessage(JSONObject message) {
        String userId = CommonUtils.safeGetString(message, "user_id");
        String rawMessage = message.optString("raw_message", "");
        
        // 处理命令
        if (rawMessage.startsWith("/")) {
            handlePrivateCommand(userId, rawMessage);
            return;
        }
        
        // 使用新的方法处理AI回复
        processPrivateAiReply(userId, rawMessage);
    }
    
    private void handleGroupCommand(String groupId, String userId, String command) {
        logger.debug("处理群命令: groupId={}, userId={}, command={}", groupId, userId, command);
        
        // 去掉命令前后的空格
        command = command.trim();
        
        // 使用正则表达式将多个空格替换为单个空格
        command = command.replaceAll("\\s+", " ");
        
        // 保留原始命令用于精确匹配
        String originalCommand = command;
        
        // 转为小写用于非精确匹配
        String cmdLower = command.toLowerCase();
        
        // 首先检查是否是启用/禁用AI的命令 - 使用精确匹配和更多可能的命令形式
        if (originalCommand.equals("/启用ai") || originalCommand.equals("/开启ai") || 
            cmdLower.equals("/启用ai") || cmdLower.equals("/开启ai") ||
            originalCommand.equals("/AI启用") || originalCommand.equals("/AI开启")) {
            
            logger.info("收到启用AI命令: {}, 用户: {}, 群: {}", command, userId, groupId);
            
            if (isGroupAdmin(userId, groupId)) {
                logger.info("用户 {} 是管理员，正在启用群 {} 的AI", userId, groupId);
                dataManager.setGroupAIEnabled(groupId, true);
                botClient.sendGroupMessage(groupId, "AI已启用！现在可以在群内与我对话了~");
            } else {
                logger.info("用户 {} 不是管理员，拒绝启用AI请求", userId);
                botClient.sendGroupMessage(groupId, "只有群主或管理员才能启用AI聊天功能。");
            }
            return;
        } else if (originalCommand.equals("/禁用ai") || originalCommand.equals("/关闭ai") || 
                   cmdLower.equals("/禁用ai") || cmdLower.equals("/关闭ai") ||
                   originalCommand.equals("/AI禁用") || originalCommand.equals("/AI关闭")) {
            
            logger.info("收到禁用AI命令: {}, 用户: {}, 群: {}", command, userId, groupId);
            
            if (isGroupAdmin(userId, groupId)) {
                logger.info("用户 {} 是管理员，正在禁用群 {} 的AI", userId, groupId);
                dataManager.setGroupAIEnabled(groupId, false);
                botClient.sendGroupMessage(groupId, "AI已禁用！");
            } else {
                logger.info("用户 {} 不是管理员，拒绝禁用AI请求", userId);
                botClient.sendGroupMessage(groupId, "只有群主或管理员才能禁用AI聊天功能。");
            }
            return;
        }
        
        // 处理人设相关命令
        if (command.startsWith("/新增人设 ") || command.startsWith("/添加人设 ")) {
            handleAddPersonaCommand(groupId, userId, command, true);
            return;
        } else if (command.startsWith("/删除人设 ")) {
            handleDeletePersonaCommand(groupId, userId, command, true);
            return;
        } else if (command.equals("/刷新人设")) {
            if (isGroupAdmin(userId, groupId)) {
                personaManager.refreshPersonas();
                botClient.sendGroupMessage(groupId, "人设列表已刷新！");
            } else {
                botClient.sendGroupMessage(groupId, "只有管理员才能刷新人设列表。");
            }
            return;
        }
        
        // 处理其他命令...
        switch (cmdLower) {
            case "/查看人设", "/人设列表" -> {
                List<String> personas = personaManager.listPersonas();
                StringBuilder sb = new StringBuilder("当前可用人设：\n");
                for (String persona : personas) {
                    sb.append("- ").append(persona).append("\n");
                }
                String currentPersona = dataManager.getUserPersona(userId);
                sb.append("\n您当前使用的人设：").append(currentPersona);
                sb.append("\n\n使用 /切换人设 [人设名] 来切换人设");
                botClient.sendGroupMessage(groupId, sb.toString());
            }
            case "/查看模型", "/模型列表" -> {
                List<String> models = modelManager.listModels();
                StringBuilder sb = new StringBuilder("当前可用模型：\n");
                for (String model : models) {
                    sb.append("- ").append(model).append("\n");
                }
                String currentModel = dataManager.getUserModel(userId);
                sb.append("\n您当前使用的模型：").append(currentModel);
                botClient.sendGroupMessage(groupId, sb.toString());
            }
            case "/模型详情" -> {
                // 检查是否是管理员
                if (isGroupAdmin(userId, groupId)) {
                    Map<String, Object> modelsConfig = configLoader.getConfigMap("ai.models");
                    StringBuilder sb = new StringBuilder("模型详细信息：\n\n");
                    
                    for (Map.Entry<String, Object> entry : modelsConfig.entrySet()) {
                        sb.append("模型名称: ").append(entry.getKey()).append("\n");
                        
                        if (entry.getValue() instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> modelConfig = (Map<String, Object>) entry.getValue();
                            
                            sb.append("  类型: ").append(modelConfig.getOrDefault("type", "openai")).append("\n");
                            sb.append("  描述: ").append(modelConfig.getOrDefault("description", "")).append("\n");
                            
                            // 只对管理员显示API地址信息
                            String apiBaseUrl = (String) modelConfig.getOrDefault("api_base_url", "");
                            if (apiBaseUrl.isEmpty()) {
                                apiBaseUrl = configLoader.getConfigString("ai.openai.api_base_url");
                                if (apiBaseUrl.isEmpty()) {
                                    apiBaseUrl = "https://api.openai.com";
                                }
                            }
                            sb.append("  API地址: ").append(apiBaseUrl).append("\n\n");
                        }
                    }
                    
                    botClient.sendGroupMessage(groupId, sb.toString());
                } else {
                    botClient.sendGroupMessage(groupId, "只有管理员才能查看模型详情。");
                }
            }
            case "/ai状态", "/查询ai" -> {
                boolean enabled = dataManager.isGroupAIEnabled(groupId);
                botClient.sendGroupMessage(groupId, "当前群AI功能状态: " + (enabled ? "已启用" : "已禁用"));
            }
            case "/清除对话" -> {
                aiService.clearConversation(userId);
                botClient.sendGroupMessage(groupId, "已清除您的对话历史。");
            }
            case "/组合" -> {
                String[] parts = command.substring("/组合 ".length()).trim().split(" ");
                if (parts.length == 2) {
                    String model = parts[0];
                    String persona = parts[1];
                    
                    if (!modelManager.hasModel(model)) {
                        botClient.sendGroupMessage(groupId, "模型不存在，请使用 /查看模型 查看可用模型。");
                        return;
                    }
                    
                    if (!personaManager.hasPersona(persona)) {
                        botClient.sendGroupMessage(groupId, "人设不存在，请使用 /查看人设 查看可用人设。");
                        return;
                    }
                    
                    // 设置模型和人设
                    dataManager.setUserModel(userId, model);
                    dataManager.setUserPersona(userId, persona);
                    
                    // 清除历史对话
                    aiService.clearConversation(userId);
                    
                    botClient.sendGroupMessage(groupId, "已设置组合：模型=" + model + "，人设=" + persona + "\n已重置对话历史。");
                } else {
                    botClient.sendGroupMessage(groupId, "格式错误。正确格式：/组合 模型名 人设名");
                }
                return;
            }
            case "/人设详情" -> {
                List<String> personas = personaManager.listPersonas();
                StringBuilder sb = new StringBuilder("人设详情：\n\n");
                
                for (String personaName : personas) {
                    sb.append("【").append(personaName).append("】\n");
                    String description = personaManager.getPersonaPrompt(personaName);
                    // 截断过长的描述
                    if (description.length() > 100) {
                        description = description.substring(0, 97) + "...";
                    }
                    sb.append(description).append("\n\n");
                }
                
                botClient.sendGroupMessage(groupId, sb.toString());
            }
            case "/模型信息" -> {
                // 获取指定模型的详细信息
                if (command.length() > 6) {
                    String modelName = command.substring(6).trim();
                    if (modelManager.hasModel(modelName)) {
                        Map<String, String> details = modelManager.getModelDetails(modelName);
                        StringBuilder sb = new StringBuilder("【" + modelName + "】\n");
                        sb.append("类型: ").append(details.get("type")).append("\n");
                        sb.append("描述: ").append(details.get("description")).append("\n");
                        botClient.sendGroupMessage(groupId, sb.toString());
                    } else {
                        botClient.sendGroupMessage(groupId, "模型不存在，请使用 /查看模型 查看可用模型。");
                    }
                } else {
                    botClient.sendGroupMessage(groupId, "请指定模型名称，例如: /模型信息 gpt-3.5-turbo");
                }
            }
            case "/对话历史" -> {
                String summary = aiService.getConversationSummary(userId);
                botClient.sendGroupMessage(groupId, summary);
            }
            case "/帮助", "/help" -> {
                StringBuilder sb = new StringBuilder("柠枺AI机器人命令列表：\n\n");
                sb.append("基本命令：\n");
                sb.append("/查看人设 - 查看可用人设列表\n");
                sb.append("/切换人设 [人设名] - 切换到指定人设\n");
                sb.append("/查看模型 - 查看可用模型列表\n");
                sb.append("/切换模型 [模型名] - 切换到指定模型\n");
                sb.append("/组合 [模型名] [人设名] - 同时设置模型和人设\n");
                sb.append("/清除对话 - 清除当前对话历史\n");
                sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                sb.append("/模型信息 [模型名] - 查看指定模型的详细信息\n");
                sb.append("/对话历史 - 显示当前对话的最近几条消息\n");
                
                // 管理员命令
                if (isGroupAdmin(userId, groupId)) {
                    sb.append("\n管理员命令：\n");
                    sb.append("/启用ai - 启用群AI功能\n");
                    sb.append("/禁用ai - 禁用群AI功能\n");
                    sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                    sb.append("/模型详情 - 查看模型详细信息\n");
                    sb.append("\n人设管理命令：\n");
                    sb.append("/新增人设 [人设名] [人设内容] - 创建新的人设\n");
                    sb.append("/删除人设 [人设名] - 删除指定人设\n");
                    sb.append("/刷新人设 - 刷新人设列表（重新加载人设文件）\n");
                }
                
                botClient.sendGroupMessage(groupId, sb.toString());
            }
            default -> {
                if (command.startsWith("/切换人设 ")) {
                    String persona = command.substring("/切换人设 ".length()).trim();
                    handlePersonaSwitch(userId, persona, true, groupId);
                } else if (command.startsWith("/切换模型 ")) {
                    String model = command.substring("/切换模型 ".length()).trim();
                    if (modelManager.hasModel(model)) {
                        dataManager.setUserModel(userId, model);
                        botClient.sendGroupMessage(groupId, "模型已切换为：" + model);
                    } else {
                        botClient.sendGroupMessage(groupId, "模型不存在，请使用 /查看模型 查看可用模型。");
                    }
                } else {
                    if (command.equals("/帮助") || command.equals("/help")) {
                        StringBuilder sb = new StringBuilder("柠枺AI机器人命令列表：\n\n");
                        sb.append("基本命令：\n");
                        sb.append("/查看人设 - 查看可用人设列表\n");
                        sb.append("/切换人设 [人设名] - 切换到指定人设\n");
                        sb.append("/查看模型 - 查看可用模型列表\n");
                        sb.append("/切换模型 [模型名] - 切换到指定模型\n");
                        sb.append("/组合 [模型名] [人设名] - 同时设置模型和人设\n");
                        sb.append("/清除对话 - 清除当前对话历史\n");
                        sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                        sb.append("/模型信息 [模型名] - 查看指定模型的详细信息\n");
                        sb.append("/对话历史 - 显示当前对话的最近几条消息\n");
                        
                        // 管理员命令
                        if (isGroupAdmin(userId, groupId)) {
                            sb.append("\n管理员命令：\n");
                            sb.append("/启用ai - 启用群AI功能\n");
                            sb.append("/禁用ai - 禁用群AI功能\n");
                            sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                            sb.append("/模型详情 - 查看模型详细信息\n");
                            sb.append("\n人设管理命令：\n");
                            sb.append("/新增人设 [人设名] [人设内容] - 创建新的人设\n");
                            sb.append("/删除人设 [人设名] - 删除指定人设\n");
                            sb.append("/刷新人设 - 刷新人设列表（重新加载人设文件）\n");
                        }
                        
                        botClient.sendGroupMessage(groupId, sb.toString());
                    } else {
                        botClient.sendGroupMessage(groupId, "未知命令。使用 /帮助 查看可用命令。");
                    }
                }
            }
        }
    }
    
    private void handlePrivateCommand(String userId, String command) {
        // 去掉命令前后的空格
        command = command.trim();
        
        if (command.equals("/帮助") || command.equals("/help")) {
            StringBuilder sb = new StringBuilder("柠枺AI机器人命令列表：\n\n");
            sb.append("基本命令：\n");
            sb.append("/查看人设 - 查看可用人设列表\n");
            sb.append("/切换人设 [人设名] - 切换到指定人设\n");
            sb.append("/查看模型 - 查看可用模型列表\n");
            sb.append("/切换模型 [模型名] - 切换到指定模型\n");
            sb.append("/组合 [模型名] [人设名] - 同时设置模型和人设\n");
            sb.append("/清除对话 - 清除当前对话历史\n");
            sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
            sb.append("/模型信息 [模型名] - 查看指定模型的详细信息\n");
            sb.append("/对话历史 - 显示当前对话的最近几条消息\n");
            
            // 管理员命令
            if (isGroupAdmin(userId, "")) {
                sb.append("\n管理员命令：\n");
                sb.append("/启用ai - 启用群AI功能\n");
                sb.append("/禁用ai - 禁用群AI功能\n");
                sb.append("/模型详情 - 查看模型详细信息\n");
                sb.append("\n人设管理命令：\n");
                sb.append("/新增人设 [人设名] [人设内容] - 创建新的人设\n");
                sb.append("/删除人设 [人设名] - 删除指定人设\n");
                sb.append("/刷新人设 - 刷新人设列表（重新加载人设文件）\n");
            }
            
            botClient.sendPrivateMessage(userId, sb.toString());
        } else if (command.startsWith("/切换人设 ")) {
            String persona = command.substring("/切换人设 ".length()).trim();
            handlePersonaSwitch(userId, persona, false, null);
        } else if (command.startsWith("/切换模型 ")) {
            String model = command.substring("/切换模型 ".length()).trim();
            if (modelManager.hasModel(model)) {
                dataManager.setUserModel(userId, model);
                botClient.sendPrivateMessage(userId, "模型已切换为：" + model);
            } else {
                botClient.sendPrivateMessage(userId, "模型不存在，请使用 /查看模型 查看可用模型。");
            }
        } else {
            // 处理人设相关命令
            if (command.startsWith("/新增人设 ") || command.startsWith("/添加人设 ")) {
                handleAddPersonaCommand("", userId, command, false);
                return;
            } else if (command.startsWith("/删除人设 ")) {
                handleDeletePersonaCommand("", userId, command, false);
                return;
            } else if (command.equals("/刷新人设")) {
                // 检查是否是管理员
                if (isGroupAdmin(userId, "")) {
                    personaManager.refreshPersonas();
                    botClient.sendPrivateMessage(userId, "人设列表已刷新！");
                } else {
                    botClient.sendPrivateMessage(userId, "只有管理员才能刷新人设列表。");
                }
                return;
            }
            
            if (command.equals("/帮助") || command.equals("/help")) {
                StringBuilder sb = new StringBuilder("柠枺AI机器人命令列表：\n\n");
                sb.append("基本命令：\n");
                sb.append("/查看人设 - 查看可用人设列表\n");
                sb.append("/切换人设 [人设名] - 切换到指定人设\n");
                sb.append("/查看模型 - 查看可用模型列表\n");
                sb.append("/切换模型 [模型名] - 切换到指定模型\n");
                sb.append("/组合 [模型名] [人设名] - 同时设置模型和人设\n");
                sb.append("/清除对话 - 清除当前对话历史\n");
                sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                sb.append("/模型信息 [模型名] - 查看指定模型的详细信息\n");
                sb.append("/对话历史 - 显示当前对话的最近几条消息\n");
                
                // 管理员命令
                if (isGroupAdmin(userId, "")) {
                    sb.append("\n管理员命令：\n");
                    sb.append("/启用ai - 启用群AI功能\n");
                    sb.append("/禁用ai - 禁用群AI功能\n");
                    sb.append("/模型详情 - 查看模型详细信息\n");
                    sb.append("\n人设管理命令：\n");
                    sb.append("/新增人设 [人设名] [人设内容] - 创建新的人设\n");
                    sb.append("/删除人设 [人设名] - 删除指定人设\n");
                    sb.append("/刷新人设 - 刷新人设列表（重新加载人设文件）\n");
                }
                
                botClient.sendPrivateMessage(userId, sb.toString());
            } else {
                botClient.sendPrivateMessage(userId, "未知命令。使用 /帮助 查看可用命令。");
            }
        }
    }
    
    private void handlePersonaSwitch(String userId, String persona, boolean isGroup, String groupId) {
        if (personaManager.hasPersona(persona)) {
            // 获取旧人设
            String oldPersona = dataManager.getUserPersona(userId);
            
            // 设置新人设
            dataManager.setUserPersona(userId, persona);
            
            // 构建提示消息
            String message = "人设已切换为：" + persona;
            
            // 如果是不同人设，增加记忆提示
            if (!oldPersona.equals(persona)) {
                // 清除用户的对话历史，开始新对话
                aiService.clearConversation(userId);
                message += "\n已重置对话历史。新对话将以" + persona + "人设开始。";
            }
            
            // 发送消息
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
        } else {
            String errorMsg = "人设不存在，请使用 /查看人设 查看可用人设。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, errorMsg);
            } else {
                botClient.sendPrivateMessage(userId, errorMsg);
            }
        }
    }
    
    /**
     * 检查用户是否为群管理员或机器人管理员
     */
    private boolean isGroupAdmin(String userId, String groupId) {
        try {
            // 首先检查是否是机器人的全局管理员
            Object adminsObj = configLoader.getConfigMap("bot").getOrDefault("admins", new ArrayList<>());
            List<String> admins = new ArrayList<>();
            
            if (adminsObj instanceof List<?>) {
                for (Object item : (List<?>) adminsObj) {
                    if (item instanceof String) {
                        admins.add((String) item);
                    }
                }
            }
            
            // 打印管理员列表，帮助调试
            logger.debug("机器人管理员列表: {}", admins);
            
            // 如果是全局管理员，直接返回true
            if (admins.contains(userId)) {
                logger.debug("用户 {} 是机器人全局管理员", userId);
                return true;
            }
            
            // 这里可以添加群管理员检查
            // OneBot的API暂未实现，后续可以通过botClient.getGroupMemberInfo方法获取用户在群中的角色
            
            logger.debug("用户 {} 不是群 {} 的管理员", userId, groupId);
            return false;
        } catch (Exception e) {
            logger.error("检查管理员权限时发生错误", e);
            return false; // 出错时保守处理，当作非管理员
        }
    }
    
    /**
     * 处理添加人设命令
     */
    private void handleAddPersonaCommand(String groupId, String userId, String command, boolean isGroup) {
        // 只有管理员可以添加人设
        if (!isGroupAdmin(userId, groupId)) {
            String message = "只有管理员才能添加人设。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 解析命令
        String content;
        if (command.startsWith("/新增人设 ")) {
            content = command.substring("/新增人设 ".length());
        } else {
            content = command.substring("/添加人设 ".length());
        }
        
        // 分割人设名和内容
        int spaceIndex = content.indexOf(" ");
        if (spaceIndex <= 0) {
            String message = "命令格式错误，正确格式：/新增人设 人设名 人设内容";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        String personaName = content.substring(0, spaceIndex).trim();
        String personaContent = content.substring(spaceIndex + 1).trim();
        
        if (personaName.isEmpty() || personaContent.isEmpty()) {
            String message = "人设名和人设内容不能为空。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 创建人设
        boolean success = personaManager.createPersona(personaName, personaContent);
        String message;
        if (success) {
            message = "人设「" + personaName + "」创建成功！\n使用 /切换人设 " + personaName + " 来使用此人设。";
        } else {
            message = "人设创建失败，请检查人设名是否合法或联系管理员。";
        }
        
        if (isGroup) {
            botClient.sendGroupMessage(groupId, message);
        } else {
            botClient.sendPrivateMessage(userId, message);
        }
    }
    
    /**
     * 处理删除人设命令
     */
    private void handleDeletePersonaCommand(String groupId, String userId, String command, boolean isGroup) {
        // 只有管理员可以删除人设
        if (!isGroupAdmin(userId, groupId)) {
            String message = "只有管理员才能删除人设。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 解析命令
        String personaName = command.substring("/删除人设 ".length()).trim();
        
        if (personaName.isEmpty()) {
            String message = "命令格式错误，正确格式：/删除人设 人设名";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 不允许删除默认人设
        if ("default".equalsIgnoreCase(personaName)) {
            String message = "不允许删除默认人设。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 删除人设
        boolean success = personaManager.deletePersona(personaName);
        String message;
        if (success) {
            message = "人设「" + personaName + "」已删除。";
        } else {
            message = "删除人设失败，该人设可能不存在或无法删除。";
        }
        
        if (isGroup) {
            botClient.sendGroupMessage(groupId, message);
        } else {
            botClient.sendPrivateMessage(userId, message);
        }
    }
} 