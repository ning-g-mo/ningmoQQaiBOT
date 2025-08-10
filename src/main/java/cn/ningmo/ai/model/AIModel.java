package cn.ningmo.ai.model;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * AI模型接口，所有模型实现类都应该实现此接口
 */
public interface AIModel {
    /**
     * 生成回复
     * @param systemPrompt 系统提示词
     * @param conversation 对话历史
     * @param personaAsSystemPrompt 是否将人设作为系统提示词
     * @return 模型回复的内容
     */
    default String generateReply(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt) {
        return generateReply(systemPrompt, conversation, personaAsSystemPrompt, new ArrayList<>());
    }
    
    /**
     * 生成回复（支持图片）
     * @param systemPrompt 系统提示词
     * @param conversation 对话历史
     * @param personaAsSystemPrompt 是否将人设作为系统提示词
     * @param imageBase64List 图片base64编码列表
     * @return 模型回复的内容
     */
    String generateReply(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt, List<String> imageBase64List);
    
    /**
     * 获取模型名称
     */
    String getName();
    
    /**
     * 获取模型描述
     */
    String getDescription();
    
    /**
     * 获取模型类型
     */
    String getType();
} 