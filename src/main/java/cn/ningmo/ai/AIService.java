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

public class AIService {
    private static final Logger logger = LoggerFactory.getLogger(AIService.class);
    
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final ModelManager modelManager;
    private final PersonaManager personaManager;
    
    // 用户会话: userId -> 消息历史
    private final Map<String, List<Map<String, String>>> conversations;
    
    public AIService(ConfigLoader configLoader, DataManager dataManager) {
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.modelManager = new ModelManager(configLoader);
        this.personaManager = new PersonaManager(configLoader);
        this.conversations = new HashMap<>();
    }
    
    public String chat(String userId, String message) {
        // 获取用户当前的模型
        String modelName = dataManager.getUserModel(userId);
        // 获取用户当前的人设
        String personaName = dataManager.getUserPersona(userId);
        
        // 获取或创建用户会话
        List<Map<String, String>> conversation = getOrCreateConversation(userId);
        
        // 获取人设系统提示
        String systemPrompt = personaManager.getPersonaPrompt(personaName);
        
        // 将用户消息添加到会话
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", message);
        conversation.add(userMessage);
        
        // 调用AI模型生成回复
        String aiReply = modelManager.generateReply(modelName, systemPrompt, conversation);
        
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
        
        return aiReply;
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