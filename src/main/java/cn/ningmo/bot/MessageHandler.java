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
        
        // 处理@机器人消息
        String selfId = configLoader.getConfigString("bot.self_id");
        if (rawMessage.contains("@" + selfId) || rawMessage.contains("[CQ:at,qq=" + selfId + "]")) {
            String cleanedContent = rawMessage.replaceAll("@" + selfId, "").replaceAll("\\[CQ:at,qq=" + selfId + "\\]", "").trim();
            
            // 异步处理AI回复
            CompletableFuture.runAsync(() -> {
                try {
                    String reply = aiService.chat(userId, cleanedContent);
                    botClient.sendGroupMessage(groupId, reply);
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
            default -> {
                if (command.startsWith("/切换人设 ")) {
                    String persona = command.substring("/切换人设 ".length()).trim();
                    if (personaManager.hasPersona(persona)) {
                        dataManager.setUserPersona(userId, persona);
                        botClient.sendGroupMessage(groupId, "人设已切换为：" + persona);
                    } else {
                        botClient.sendGroupMessage(groupId, "人设不存在，请使用 /查看人设 查看可用人设。");
                    }
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
                        sb.append("/切换人设 [人设名] - 切换到指定人设\n");
                        sb.append("/查看模型 - 查看可用模型列表\n");
                        sb.append("/切换模型 [模型名] - 切换到指定模型\n");
                        sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                        
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
            sb.append("/切换人设 [人设名] - 切换到指定人设\n");
            sb.append("/查看模型 - 查看可用模型列表\n");
            sb.append("/切换模型 [模型名] - 切换到指定模型\n");
            
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
            if (personaManager.hasPersona(persona)) {
                dataManager.setUserPersona(userId, persona);
                botClient.sendPrivateMessage(userId, "人设已切换为：" + persona);
            } else {
                botClient.sendPrivateMessage(userId, "人设不存在，请使用 /查看人设 查看可用人设。");
            }
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
                sb.append("/切换人设 [人设名] - 切换到指定人设\n");
                sb.append("/查看模型 - 查看可用模型列表\n");
                sb.append("/切换模型 [模型名] - 切换到指定模型\n");
                sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                
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