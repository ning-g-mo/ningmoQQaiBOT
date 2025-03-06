package cn.ningmo.bot;

import cn.ningmo.ai.AIService;
import cn.ningmo.ai.model.ModelManager;
import cn.ningmo.ai.persona.PersonaManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    
    public void handleMessage(JSONObject message) {
        String messageType = message.getString("message_type");
        
        if ("group".equals(messageType)) {
            handleGroupMessage(message);
        } else if ("private".equals(messageType)) {
            handlePrivateMessage(message);
        }
    }
    
    private void handleGroupMessage(JSONObject message) {
        String groupId = CommonUtils.safeGetString(message, "group_id");
        String userId = CommonUtils.safeGetString(message, "user_id");
        String rawMessage = message.optString("raw_message", "");
        
        // 添加日志，帮助调试消息格式
        logger.debug("收到群消息: groupId={}, userId={}, rawMessage={}", groupId, userId, rawMessage);
        
        // 检查群是否启用AI，如果未启用且不是管理员，直接返回
        boolean isAdmin = isGroupAdmin(userId, groupId);
        if (!dataManager.isGroupAIEnabled(groupId) && !isAdmin) {
            return; // 群未启用，且不是管理员，无视所有消息
        }
        
        // 处理命令
        if (rawMessage.startsWith("/")) {
            handleGroupCommand(groupId, userId, rawMessage);
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
            
            // 异步处理AI回复
            CompletableFuture.runAsync(() -> {
                try {
                    String reply = aiService.chat(userId, cleanedContent);
                    
                    // 检查是否需要@发送者
                    boolean alwaysAtSender = configLoader.getConfigBoolean("bot.always_at_sender");
                    String replyMessage = reply;
                    
                    if (alwaysAtSender) {
                        String atSender = "[CQ:at,qq=" + userId + "] ";
                        replyMessage = atSender + reply;
                    }
                    
                    botClient.sendGroupMessage(groupId, replyMessage);
                } catch (Exception e) {
                    logger.error("AI处理消息出错", e);
                    botClient.sendGroupMessage(groupId, "AI处理消息时出错，请稍后再试。");
                }
            }, executor);
        }
    }
    
    private void handlePrivateMessage(JSONObject message) {
        String userId = String.valueOf(message.get("user_id"));
        String rawMessage = message.getString("raw_message");
        
        // 处理命令
        if (rawMessage.startsWith("/")) {
            handlePrivateCommand(userId, rawMessage);
            return;
        }
        
        // 异步处理AI回复
        CompletableFuture.runAsync(() -> {
            try {
                String reply = aiService.chat(userId, rawMessage);
                botClient.sendPrivateMessage(userId, reply);
            } catch (Exception e) {
                logger.error("AI处理消息出错", e);
                botClient.sendPrivateMessage(userId, "AI处理消息时出错，请稍后再试。");
            }
        }, executor);
    }
    
    private void handleGroupCommand(String groupId, String userId, String command) {
        logger.debug("处理群命令: groupId={}, userId={}, command={}", groupId, userId, command);
        
        // 去掉命令前后的空格
        command = command.trim();
        
        // 使用正则表达式将多个空格替换为单个空格
        command = command.replaceAll("\\s+", " ");
        
        // 统一转为小写后比较命令
        String cmdLower = command.toLowerCase();
        
        // 首先检查是否是启用/禁用AI的命令
        if (cmdLower.equals("/启用ai") || cmdLower.equals("/开启ai")) {
            if (isGroupAdmin(userId, groupId)) {
                dataManager.setGroupAIEnabled(groupId, true);
                botClient.sendGroupMessage(groupId, "AI已启用！");
            } else {
                botClient.sendGroupMessage(groupId, "只有群主或管理员才能启用AI聊天功能。");
            }
            return;  // 已处理命令，退出
        } else if (cmdLower.equals("/禁用ai") || cmdLower.equals("/关闭ai")) {
            if (isGroupAdmin(userId, groupId)) {
                dataManager.setGroupAIEnabled(groupId, false);
                botClient.sendGroupMessage(groupId, "AI已禁用！");
            } else {
                botClient.sendGroupMessage(groupId, "只有群主或管理员才能禁用AI聊天功能。");
            }
            return;  // 已处理命令，退出
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
                        StringBuilder sb = new StringBuilder("可用命令：\n");
                        sb.append("/查看人设 - 查看可用人设列表\n");
                        sb.append("/人设详情 - 查看人设详细描述\n");
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
            StringBuilder sb = new StringBuilder("可用命令：\n");
            sb.append("/查看人设 - 查看可用人设列表\n");
            sb.append("/人设详情 - 查看人设详细描述\n");
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
                sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                sb.append("/模型详情 - 查看模型详细信息\n");
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
            if (command.equals("/帮助") || command.equals("/help")) {
                StringBuilder sb = new StringBuilder("可用命令：\n");
                sb.append("/查看人设 - 查看可用人设列表\n");
                sb.append("/人设详情 - 查看人设详细描述\n");
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
                    sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                    sb.append("/模型详情 - 查看模型详细信息\n");
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
    
    private boolean isGroupAdmin(String userId, String groupId) {
        // 首先检查全局管理员列表
        List<String> admins = configLoader.getConfig("bot.admins", List.of());
        boolean isAdmin = admins.contains(userId);
        logger.debug("检查用户是否为管理员: userId={}, groupId={}, isAdmin={}", userId, groupId, isAdmin);
        
        // 如果不是全局管理员且提供了群ID，则可以考虑通过API获取群角色
        // 此处为简化处理，仅使用全局管理员列表
        // 实际使用时可以通过OneBot API获取群成员信息判断角色
        
        return isAdmin;
    }
} 