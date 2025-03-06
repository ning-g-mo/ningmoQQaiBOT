package cn.ningmo.ai.model;

import cn.ningmo.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModelManager {
    private static final Logger logger = LoggerFactory.getLogger(ModelManager.class);
    
    private final ConfigLoader configLoader;
    private final Map<String, OpenAIModel> models;
    
    public ModelManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.models = new HashMap<>();
        initModels();
    }
    
    private void initModels() {
        Map<String, Object> modelConfigs = configLoader.getConfigMap("ai.models");
        
        // 如果配置为空，添加默认模型
        if (modelConfigs.isEmpty()) {
            models.put("gpt-3.5-turbo", new OpenAIModel("gpt-3.5-turbo", configLoader, null));
            models.put("gpt-4", new OpenAIModel("gpt-4", configLoader, null));
            return;
        }
        
        // 加载配置的所有模型
        for (Map.Entry<String, Object> entry : modelConfigs.entrySet()) {
            String modelName = entry.getKey();
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> modelConfig = (Map<String, Object>) entry.getValue();
                
                String type = (String) modelConfig.getOrDefault("type", "openai");
                
                if ("openai".equals(type)) {
                    models.put(modelName, new OpenAIModel(modelName, configLoader, modelConfig));
                }
                // 可以在这里添加其他类型的模型支持
            }
        }
        
        logger.info("已加载{}个AI模型", models.size());
    }
    
    public List<String> listModels() {
        return new ArrayList<>(models.keySet());
    }
    
    public boolean hasModel(String modelName) {
        return models.containsKey(modelName);
    }
    
    public String generateReply(String modelName, String systemPrompt, List<Map<String, String>> conversation) {
        if (!models.containsKey(modelName)) {
            logger.warn("模型{}不存在，使用默认模型gpt-3.5-turbo", modelName);
            modelName = "gpt-3.5-turbo";
        }
        
        OpenAIModel model = models.get(modelName);
        return model.generateReply(systemPrompt, conversation);
    }
}
