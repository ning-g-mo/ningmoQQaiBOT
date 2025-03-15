package cn.ningmo.ai;

import cn.ningmo.ai.model.ModelManager;
import cn.ningmo.ai.persona.PersonaManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIService {
    private static final Logger logger = LoggerFactory.getLogger(AIService.class);
    
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final ModelManager modelManager;
    private final PersonaManager personaManager;
    
    // 用户会话: userId -> 消息历史
    private final Map<String, List<Map<String, String>>> conversations;
    
    // 多段消息分隔符
    private static final String MESSAGE_SEPARATOR = "\\n---\\n";
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(MESSAGE_SEPARATOR);
    
    public AIService(ConfigLoader configLoader, DataManager dataManager) {
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.modelManager = new ModelManager(configLoader);
        this.personaManager = new PersonaManager(configLoader);
        this.conversations = new HashMap<>();
    }
    
    /**
     * 与AI聊天并获取回复（单条消息）
     */
    public String chat(String userId, String message) {
        List<String> replies = chatMultipart(userId, message);
        if (replies.isEmpty()) {
            return "";
        }
        return String.join("\n\n", replies);
    }
    
    /**
     * 与AI聊天并获取多段回复
     * @return 多段消息列表，如果AI选择不回复，返回空列表
     */
    public List<String> chatMultipart(String userId, String message) {
        // 获取用户当前的模型
        String modelName = dataManager.getUserModel(userId);
        // 如果用户模型是默认的gpt-3.5-turbo，检查配置文件中的默认设置
        if (modelName.equals("gpt-3.5-turbo")) {
            String configDefault = configLoader.getConfigString("ai.default_model", "");
            if (!configDefault.isEmpty() && modelManager.hasModel(configDefault)) {
                modelName = configDefault;
            }
        }
        
        // 获取用户当前的人设
        String personaName = dataManager.getUserPersona(userId);
        
        // 获取或创建用户会话
        List<Map<String, String>> conversation = getOrCreateConversation(userId);
        
        // 获取人设系统提示
        String systemPrompt = personaManager.getPersonaPrompt(personaName);
        
        // 添加多段消息提示
        systemPrompt += "\n\n如果你想发送多条消息，请在消息之间使用 \\n---\\n 作为分隔符。这样系统会将你的回复拆分成多条单独发送。" +
                      "如果认为不需要回复，可以回复 [NO_RESPONSE] 表示不发送任何消息。";
        
        // 将用户消息添加到会话
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", message);
        conversation.add(userMessage);
        
        try {
            // 调用AI模型生成回复
            logger.info("用户 {} 使用模型 {} 和人设 {} 生成回复", userId, modelName, personaName);
            String aiReply = modelManager.generateReply(modelName, systemPrompt, conversation);
            
            // 检查是否是不回复的指令
            if (aiReply.trim().equals("[NO_RESPONSE]")) {
                logger.info("AI选择不回复消息");
                
                // 将AI回复添加到会话，但不发送
                Map<String, String> assistantMessage = new HashMap<>();
                assistantMessage.put("role", "assistant");
                assistantMessage.put("content", "");
                conversation.add(assistantMessage);
                
                return new ArrayList<>();
            }
            
            // 将AI回复添加到会话
            Map<String, String> assistantMessage = new HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", aiReply);
            conversation.add(assistantMessage);
            
            // 限制会话长度，防止过长
            int maxConversationLength = configLoader.getConfig("ai.max_conversation_length", 20);
            while (conversation.size() > maxConversationLength) {
                conversation.remove(0);
            }
            
            // 拆分回复为多段消息
            List<String> messageParts = splitMessage(aiReply);
            
            // 获取配置中最大连续消息数量
            int maxConsecutive = configLoader.getConfig("bot.messages.max_consecutive", 3);
            if (maxConsecutive > 0 && messageParts.size() > maxConsecutive) {
                logger.info("AI生成的消息数量({})超过了最大限制({}), 将被截断", messageParts.size(), maxConsecutive);
                messageParts = messageParts.subList(0, maxConsecutive);
            }
            
            return messageParts;
        } catch (Exception e) {
            logger.error("生成AI回复时发生错误", e);
            // 从会话中移除失败的请求，避免影响后续对话
            if (!conversation.isEmpty()) {
                conversation.remove(conversation.size() - 1);
            }
            
            // 返回错误消息
            List<String> errorMessage = new ArrayList<>();
            errorMessage.add("抱歉，AI生成回复时出现错误：" + e.getMessage());
            return errorMessage;
        }
    }
    
    /**
     * 将消息按分隔符拆分为多段
     */
    private List<String> splitMessage(String message) {
        // 使用分隔符拆分消息
        String[] parts = SEPARATOR_PATTERN.split(message);
        
        // 过滤并整理消息段
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        
        // 如果拆分后为空，则至少保留一条消息
        if (result.isEmpty() && !message.trim().isEmpty()) {
            result.add(message.trim());
        }
        
        return result;
    }
    
    private List<Map<String, String>> getOrCreateConversation(String userId) {
        return conversations.computeIfAbsent(userId, k -> new ArrayList<>());
    }
    
    public void clearConversation(String userId) {
        conversations.computeIfPresent(userId, (id, conversation) -> {
            conversation.clear();
            return conversation;
        });
        logger.info("已清除用户 {} 的对话历史", userId);
    }
    
    public String getConversationSummary(String userId) {
        List<Map<String, String>> conversation = getOrCreateConversation(userId);
        if (conversation.isEmpty()) {
            return "无对话历史";
        }
        
        StringBuilder sb = new StringBuilder("当前对话历史：\n");
        int count = Math.min(conversation.size(), 3);  // 最多显示最近3条
        for (int i = conversation.size() - count; i < conversation.size(); i++) {
            Map<String, String> message = conversation.get(i);
            sb.append(message.get("role")).append(": ")
              .append(CommonUtils.truncateText(message.get("content"), 50))
              .append("\n");
        }
        
        return sb.toString();
    }
} 