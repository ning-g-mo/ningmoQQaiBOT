package cn.ningmo.ai.model;

import java.util.List;
import java.util.Map;

/**
 * AI模型接口，所有模型实现类都应该实现此接口
 */
public interface AIModel {
    /**
     * 生成回复
     * @param systemPrompt 系统提示词
     * @param conversation 对话历史
     * @return 模型回复的内容
     */
    String generateReply(String systemPrompt, List<Map<String, String>> conversation);
    
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