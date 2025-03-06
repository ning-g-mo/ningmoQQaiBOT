package cn.ningmo.ai.model;

import cn.ningmo.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ModelManager {
    private static final Logger logger = LoggerFactory.getLogger(ModelManager.class);
    
    private final ConfigLoader configLoader;
    private final Map<String, AIModel> models = new ConcurrentHashMap<>();
    
    public ModelManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        loadModels();
    }
    
    @SuppressWarnings("unchecked")
    private void loadModels() {
        Map<String, Object> modelsConfig = configLoader.getConfigMap("ai.models");
        
        for (Map.Entry<String, Object> entry : modelsConfig.entrySet()) {
            String modelName = entry.getKey();
            
            try {
                if (entry.getValue() instanceof Map) {
                    Map<String, Object> modelConfig = (Map<String, Object>) entry.getValue();
                    String type = (String) modelConfig.getOrDefault("type", "openai");
                    
                    AIModel model = createModel(modelName, type, modelConfig);
                    if (model != null) {
                        models.put(modelName, model);
                        logger.info("加载模型成功: {}, 类型: {}", modelName, type);
                    }
                }
            } catch (Exception e) {
                logger.error("加载模型失败: " + modelName, e);
            }
        }
    }
    
    private AIModel createModel(String modelName, String type, Map<String, Object> modelConfig) {
        try {
            return switch (type.toLowerCase()) {
                case "openai" -> new OpenAIModel(modelName, configLoader, modelConfig);
                case "anthropic" -> new AnthropicModel(configLoader, modelName, modelConfig);
                case "local" -> new LocalLLMModel(configLoader, modelName, modelConfig);
                case "api" -> new GenericAPIModel(configLoader, modelName, modelConfig);
                default -> {
                    logger.warn("不支持的模型类型: {}", type);
                    yield null;
                }
            };
        } catch (Exception e) {
            logger.error("创建模型实例失败: " + modelName + ", 类型: " + type, e);
            return null;
        }
    }
    
    public String generateReply(String modelName, String systemPrompt, List<Map<String, String>> conversation) {
        AIModel model = models.get(modelName);
        if (model == null) {
            // 从配置中获取默认模型，如果未配置则使用gpt-3.5-turbo
            String defaultModel = configLoader.getConfigString("ai.default_model", "gpt-3.5-turbo");
            logger.warn("模型不存在: {}, 使用默认模型: {}", modelName, defaultModel);
            model = models.get(defaultModel);
            
            // 如果默认模型也不存在，尝试使用第一个可用的模型
            if (model == null && !models.isEmpty()) {
                String firstModel = models.keySet().iterator().next();
                logger.warn("默认模型{}不存在，使用第一个可用模型: {}", defaultModel, firstModel);
                model = models.get(firstModel);
            }
            
            // 如果实在没有可用模型，返回错误信息
            if (model == null) {
                return "抱歉，请求的模型不存在，且没有可用的默认模型，请联系管理员设置可用模型。";
            }
        }
        
        try {
            return model.generateReply(systemPrompt, conversation);
        } catch (Exception e) {
            logger.error("模型生成回复失败: " + modelName, e);
            return "抱歉，AI生成回复时出错，请稍后再试。错误: " + e.getMessage();
        }
    }
    
    public boolean hasModel(String modelName) {
        return models.containsKey(modelName);
    }
    
    public List<String> listModels() {
        return new ArrayList<>(models.keySet());
    }
    
    public Map<String, String> getModelDetails(String modelName) {
        AIModel model = models.get(modelName);
        if (model == null) {
            return Map.of();
        }
        
        return Map.of(
            "name", model.getName(),
            "type", model.getType(),
            "description", model.getDescription()
        );
    }
}
