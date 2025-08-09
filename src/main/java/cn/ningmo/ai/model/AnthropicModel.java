package cn.ningmo.ai.model;

import cn.ningmo.ai.response.ResponseParser;
import cn.ningmo.config.ConfigLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class AnthropicModel implements AIModel {
    private static final Logger logger = LoggerFactory.getLogger(AnthropicModel.class);
    
    private final String name;
    private final String description;
    private final ConfigLoader configLoader;
    private final Map<String, Object> modelConfig;
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    public AnthropicModel(ConfigLoader configLoader, String name, Map<String, Object> modelConfig) {
        this.configLoader = configLoader;
        this.name = name;
        this.modelConfig = modelConfig;
        this.description = (String) modelConfig.getOrDefault("description", "Claude AI Assistant");
    }
    
    @Override
    public String generateReply(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt) {
        try {
            String apiKey = getApiKey();
            String apiBaseUrl = getApiBaseUrl();
            
            JSONObject requestBody = buildRequestBody(systemPrompt, conversation, personaAsSystemPrompt);
            
            String endpoint = apiBaseUrl + "/v1/messages";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return ResponseParser.parseResponse(response.body(), "Anthropic");
            } else {
                logger.error("Claude API调用失败: {}, 状态码: {}", response.body(), response.statusCode());
                return "抱歉，AI响应出错，请稍后再试。错误代码: " + response.statusCode();
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Claude API调用异常", e);
            return "抱歉，AI服务暂时不可用，请稍后再试。";
        }
    }
    
    private JSONObject buildRequestBody(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt) {
        JSONObject requestBody = new JSONObject();
        
        // 设置模型名称，例如 "claude-3-opus-20240229"
        requestBody.put("model", name);
        
        // 根据配置决定如何处理人设
        if (personaAsSystemPrompt) {
            // 人设作为系统提示词
            requestBody.put("system", systemPrompt);
            
            // 构建消息数组
            JSONArray messages = new JSONArray();
            for (Map<String, String> message : conversation) {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("role", message.get("role"));
                jsonMessage.put("content", message.get("content"));
                messages.put(jsonMessage);
            }
            requestBody.put("messages", messages);
        } else {
            // 人设作为对话历史的一部分
            // 构建消息数组，将人设作为第一条用户消息
            JSONArray messages = new JSONArray();
            
            // 添加人设作为第一条用户消息
            JSONObject personaMessage = new JSONObject();
            personaMessage.put("role", "user");
            personaMessage.put("content", systemPrompt);
            messages.put(personaMessage);
            
            // 添加对话历史
            for (Map<String, String> message : conversation) {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("role", message.get("role"));
                jsonMessage.put("content", message.get("content"));
                messages.put(jsonMessage);
            }
            requestBody.put("messages", messages);
        }
        
        // 设置温度参数
        double temperature = getTemperature();
        requestBody.put("temperature", temperature);
        
        // 设置最大tokens
        int maxTokens = getMaxTokens();
        requestBody.put("max_tokens", maxTokens);
        
        return requestBody;
    }
    

    
    private String getApiKey() {
        String apiKey = (String) modelConfig.getOrDefault("api_key", "");
        if (apiKey.isEmpty()) {
            apiKey = configLoader.getConfigString("ai.anthropic.api_key");
        }
        return apiKey;
    }
    
    private String getApiBaseUrl() {
        String apiBaseUrl = (String) modelConfig.getOrDefault("api_base_url", "");
        if (apiBaseUrl.isEmpty()) {
            apiBaseUrl = configLoader.getConfigString("ai.anthropic.api_base_url", "https://api.anthropic.com");
        }
        return apiBaseUrl;
    }
    
    private double getTemperature() {
        Object temp = modelConfig.getOrDefault("temperature", 0.7);
        if (temp instanceof Number) {
            return ((Number) temp).doubleValue();
        }
        return 0.7;
    }
    
    private int getMaxTokens() {
        Object tokens = modelConfig.getOrDefault("max_tokens", 2000);
        if (tokens instanceof Number) {
            return ((Number) tokens).intValue();
        }
        return 2000;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    @Override
    public String getType() {
        return "anthropic";
    }
} 