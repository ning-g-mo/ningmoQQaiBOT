package cn.ningmo.ai.model;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GenericAPIModel implements AIModel {
    private static final Logger logger = LoggerFactory.getLogger(GenericAPIModel.class);
    
    private final String name;
    private final String description;
    private final ConfigLoader configLoader;
    private final Map<String, Object> modelConfig;
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    public GenericAPIModel(ConfigLoader configLoader, String name, Map<String, Object> modelConfig) {
        this.configLoader = configLoader;
        this.name = name;
        this.modelConfig = modelConfig;
        this.description = (String) modelConfig.getOrDefault("description", "通用API模型");
    }
    
    @Override
    public String generateReply(String systemPrompt, List<Map<String, String>> conversation) {
        try {
            String apiUrl = (String) modelConfig.get("api_url");
            if (apiUrl == null || apiUrl.isEmpty()) {
                return "模型配置错误：未指定API URL";
            }
            
            JSONObject requestBody = new JSONObject();
            
            // 从模型配置中获取请求数据模板
            Map<String, Object> requestTemplate = getRequestTemplate();
            
            // 将模板应用到请求体
            for (Map.Entry<String, Object> entry : requestTemplate.entrySet()) {
                requestBody.put(entry.getKey(), entry.getValue());
            }
            
            // 添加系统提示和对话历史
            if (requestBody.has("messages")) {
                JSONArray messages = requestBody.getJSONArray("messages");
                
                // 添加系统消息
                JSONObject systemMessage = new JSONObject();
                systemMessage.put("role", "system");
                systemMessage.put("content", systemPrompt);
                messages.put(systemMessage);
                
                // 添加对话历史
                for (Map<String, String> message : conversation) {
                    JSONObject jsonMessage = new JSONObject();
                    jsonMessage.put("role", message.get("role"));
                    jsonMessage.put("content", message.get("content"));
                    messages.put(jsonMessage);
                }
            } else {
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
                    jsonMessage.put("content", message.get("content"));
                    messages.put(jsonMessage);
                }
                
                requestBody.put("messages", messages);
            }
            
            // 获取HTTP请求头
            Map<String, String> headers = getHeaders();
            
            // 构建HTTP请求
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json");
            
            // 添加请求头
            for (Map.Entry<String, String> header : headers.entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }
            
            // 添加请求体
            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();
            
            // 发送请求
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return extractContent(response.body());
            } else {
                logger.error("API调用失败: {}, 状态码: {}", response.body(), response.statusCode());
                return "抱歉，API调用失败，错误代码: " + response.statusCode();
            }
        } catch (IOException | InterruptedException e) {
            logger.error("API调用异常", e);
            return "抱歉，API服务暂时不可用，请稍后再试。";
        }
    }
    
    private String extractContent(String responseBody) {
        try {
            JSONObject response = new JSONObject(responseBody);
            
            // 从配置中获取内容提取路径
            String contentPath = (String) modelConfig.getOrDefault("response_content_path", "choices.0.message.content");
            
            // 解析路径并提取内容
            String[] pathParts = contentPath.split("\\.");
            Object current = response;
            
            for (String part : pathParts) {
                if (current instanceof JSONObject) {
                    // 如果是对象，按键获取值
                    current = ((JSONObject) current).get(part);
                } else if (current instanceof JSONArray) {
                    // 如果是数组，按索引获取值
                    try {
                        int index = Integer.parseInt(part);
                        current = ((JSONArray) current).get(index);
                    } catch (NumberFormatException e) {
                        logger.error("无法解析数组索引: " + part, e);
                        return "API响应格式错误，无法提取内容。";
                    }
                } else {
                    logger.error("无法解析响应路径: " + contentPath);
                    return "API响应格式错误，无法提取内容。";
                }
            }
            
            return current.toString();
        } catch (Exception e) {
            logger.error("提取API响应内容失败", e);
            return "解析API响应时出错: " + e.getMessage();
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> getRequestTemplate() {
        Object template = modelConfig.get("request_template");
        if (template instanceof Map) {
            return (Map<String, Object>) template;
        }
        return new HashMap<>();
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, String> getHeaders() {
        Object headersObj = modelConfig.get("headers");
        Map<String, String> headers = new HashMap<>();
        
        if (headersObj instanceof Map) {
            Map<String, Object> headersMap = (Map<String, Object>) headersObj;
            for (Map.Entry<String, Object> entry : headersMap.entrySet()) {
                headers.put(entry.getKey(), entry.getValue().toString());
            }
        }
        
        // 如果配置了API密钥，添加到请求头
        String apiKey = (String) modelConfig.getOrDefault("api_key", "");
        if (!apiKey.isEmpty()) {
            String authHeader = (String) modelConfig.getOrDefault("auth_header", "Authorization");
            String authFormat = (String) modelConfig.getOrDefault("auth_format", "Bearer {api_key}");
            headers.put(authHeader, authFormat.replace("{api_key}", apiKey));
        }
        
        return headers;
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
        return "api";
    }
} 