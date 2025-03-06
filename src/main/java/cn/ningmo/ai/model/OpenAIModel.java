package cn.ningmo.ai.model;

import cn.ningmo.config.ConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAIModel implements AIModel {
    private static final Logger logger = LoggerFactory.getLogger(OpenAIModel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String modelName;
    private final ConfigLoader configLoader;
    private final Map<String, Object> modelConfig;
    private final HttpClient httpClient;
    
    public OpenAIModel(String modelName, ConfigLoader configLoader, Map<String, Object> modelConfig) {
        this.modelName = modelName;
        this.configLoader = configLoader;
        this.modelConfig = modelConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }
    
    @Override
    public String generateReply(String systemPrompt, List<Map<String, String>> conversation) {
        String apiKey = getModelConfigValue("api_key", configLoader.getConfigString("ai.openai.api_key"));
        String apiBaseUrl = getModelConfigValue("api_base_url", configLoader.getConfigString("ai.openai.api_base_url"));
        
        if (apiKey.isEmpty()) {
            logger.error("模型{}的API密钥未配置", modelName);
            return "AI服务暂时不可用，请联系管理员配置API密钥。";
        }
        
        if (apiBaseUrl.isEmpty()) {
            apiBaseUrl = "https://api.openai.com/v1/chat/completions";
        } else {
            if (!apiBaseUrl.endsWith("/")) {
                apiBaseUrl += "/";
            }
            if (!apiBaseUrl.endsWith("v1/chat/completions")) {
                apiBaseUrl += "v1/chat/completions";
            }
        }
        
        logger.debug("使用模型{}，API地址：{}", modelName, apiBaseUrl);
        
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            
            List<Map<String, String>> messages = new ArrayList<>();
            
            // 添加系统提示
            Map<String, String> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
            
            // 添加对话历史
            messages.addAll(conversation);
            
            requestBody.put("messages", messages);
            
            // 添加其他参数，优先使用模型特定配置
            requestBody.put("temperature", getModelConfigValue("temperature", 
                    configLoader.getConfig("ai.openai.temperature", 0.7)));
            requestBody.put("max_tokens", getModelConfigValue("max_tokens", 
                    configLoader.getConfig("ai.openai.max_tokens", 2000)));
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                Map<String, Object> responseBody = objectMapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    Map<String, String> message = (Map<String, String>) choice.get("message");
                    return message.get("content");
                }
            } else if (response.statusCode() == 401) {
                logger.error("API密钥无效或已过期，模型：{}", modelName);
                return "AI服务认证失败，请联系管理员检查API密钥配置。";
            } else if (response.statusCode() == 429) {
                logger.error("API请求频率超限或余额不足，模型：{}", modelName);
                return "AI服务暂时达到使用限制，请稍后再试。";
            } else if (response.statusCode() >= 500) {
                logger.error("API服务器错误，模型：{}, 状态码：{}", modelName, response.statusCode());
                return "AI服务器暂时出现故障，请稍后再试。";
            } else {
                logger.error("API调用失败，模型：{}，状态码：{}，响应：{}", 
                        modelName, response.statusCode(), response.body());
                return "AI服务暂时出现故障，请稍后再试。";
            }
            
            return "AI服务暂时出现故障，请稍后再试。";
            
        } catch (IOException e) {
            logger.error("API网络请求异常，模型：{}", modelName, e);
            return "AI服务网络连接异常，请稍后再试。";
        } catch (InterruptedException e) {
            logger.error("API请求被中断，模型：{}", modelName, e);
            Thread.currentThread().interrupt();
            return "AI请求被中断，请稍后再试。";
        } catch (Exception e) {
            logger.error("API调用过程中发生未预期异常，模型：{}", modelName, e);
            return "AI服务发生未知错误，请稍后再试。";
        }
    }
    
    @Override
    public String getName() {
        return modelName;
    }
    
    @Override
    public String getDescription() {
        return "OpenAI模型";
    }
    
    @Override
    public String getType() {
        return "openai";
    }
    
    private String getModelConfigValue(String key, String defaultValue) {
        if (modelConfig == null) return defaultValue;
        
        Object value = modelConfig.get(key);
        if (value instanceof String) {
            String strValue = (String) value;
            return strValue.isEmpty() ? defaultValue : strValue;
        }
        return defaultValue;
    }
    
    @SuppressWarnings("unchecked")
    private <T> T getModelConfigValue(String key, T defaultValue) {
        if (modelConfig == null) return defaultValue;
        
        Object value = modelConfig.get(key);
        if (value == null) return defaultValue;
        
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }
} 