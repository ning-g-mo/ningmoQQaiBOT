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
import java.util.ArrayList;

public class LocalLLMModel implements AIModel {
    private static final Logger logger = LoggerFactory.getLogger(LocalLLMModel.class);
    
    private final String name;
    private final String description;
    private final ConfigLoader configLoader;
    private final Map<String, Object> modelConfig;
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    public LocalLLMModel(ConfigLoader configLoader, String name, Map<String, Object> modelConfig) {
        this.configLoader = configLoader;
        this.name = name;
        this.modelConfig = modelConfig;
        this.description = (String) modelConfig.getOrDefault("description", "本地大语言模型");
    }
    
    @Override
    public String generateReply(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt) {
        return generateReply(systemPrompt, conversation, personaAsSystemPrompt, new ArrayList<>());
    }

    @Override
    public String generateReply(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt, List<String> imageBase64List) {
        try {
            String endpoint = getApiEndpoint();
            JSONObject requestBody = buildRequestBody(systemPrompt, conversation, personaAsSystemPrompt, imageBase64List);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return ResponseParser.parseResponse(response.body(), "LocalLLM");
            } else {
                logger.error("本地模型API调用失败: {}, 状态码: {}", response.body(), response.statusCode());
                return "抱歉，本地AI模型响应出错，请检查服务是否正常运行。错误代码: " + response.statusCode();
            }
        } catch (IOException | InterruptedException e) {
            logger.error("本地模型API调用异常", e);
            return "抱歉，本地AI服务暂时不可用，请检查服务是否启动。";
        }
    }
    
    private JSONObject buildRequestBody(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt, List<String> imageBase64List) {
        JSONObject requestBody = new JSONObject();
        
        // 大多数本地LLM服务器都支持OpenAI兼容的API格式
        requestBody.put("model", getLocalModelName());
        
        // 根据配置决定如何处理人设
        if (personaAsSystemPrompt) {
            // 人设作为系统提示词
            JSONArray messages = new JSONArray();
            
            // 添加系统消息
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.put(systemMessage);
            
            // 添加对话历史
            for (Map<String, String> message : conversation) {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("role", message.get("role"));
                
                // 检查是否是最后一条用户消息且包含图片
                boolean isLastUserMessage = message.get("role").equals("user") && 
                                          conversation.indexOf(message) == conversation.size() - 1;
                
                if (isLastUserMessage && !imageBase64List.isEmpty()) {
                    // 构建包含图片的内容
                    JSONArray contentArray = new JSONArray();
                    
                    // 添加文本内容
                    JSONObject textContent = new JSONObject();
                    textContent.put("type", "text");
                    textContent.put("text", message.get("content"));
                    contentArray.put(textContent);
                    
                    // 添加图片内容
                    for (String imageBase64 : imageBase64List) {
                        JSONObject imageContent = new JSONObject();
                        imageContent.put("type", "image_url");
                        
                        JSONObject imageUrl = new JSONObject();
                        imageUrl.put("url", "data:image/jpeg;base64," + imageBase64);
                        imageContent.put("image_url", imageUrl);
                        
                        contentArray.put(imageContent);
                    }
                    
                    jsonMessage.put("content", contentArray);
                } else {
                    jsonMessage.put("content", message.get("content"));
                }
                
                messages.put(jsonMessage);
            }
            
            requestBody.put("messages", messages);
        } else {
            // 人设作为对话历史的一部分
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
                
                // 检查是否是最后一条用户消息且包含图片
                boolean isLastUserMessage = message.get("role").equals("user") && 
                                          conversation.indexOf(message) == conversation.size() - 1;
                
                if (isLastUserMessage && !imageBase64List.isEmpty()) {
                    // 构建包含图片的内容
                    JSONArray contentArray = new JSONArray();
                    
                    // 添加文本内容
                    JSONObject textContent = new JSONObject();
                    textContent.put("type", "text");
                    textContent.put("text", message.get("content"));
                    contentArray.put(textContent);
                    
                    // 添加图片内容
                    for (String imageBase64 : imageBase64List) {
                        JSONObject imageContent = new JSONObject();
                        imageContent.put("type", "image_url");
                        
                        JSONObject imageUrl = new JSONObject();
                        imageUrl.put("url", "data:image/jpeg;base64," + imageBase64);
                        imageContent.put("image_url", imageUrl);
                        
                        contentArray.put(imageContent);
                    }
                    
                    jsonMessage.put("content", contentArray);
                } else {
                    jsonMessage.put("content", message.get("content"));
                }
                
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
    

    
    private String getApiEndpoint() {
        String endpoint = (String) modelConfig.getOrDefault("api_endpoint", "");
        if (endpoint.isEmpty()) {
            // 尝试读取全局本地模型API endpoint
            endpoint = configLoader.getConfigString("ai.local.api_endpoint", "http://localhost:1234/v1/chat/completions");
        }
        return endpoint;
    }
    
    private String getLocalModelName() {
        return (String) modelConfig.getOrDefault("local_model_name", name);
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
        return "local";
    }
} 