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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeepSeekModel implements AIModel {
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekModel.class);
    
    private final String name;
    private final String description;
    private final ConfigLoader configLoader;
    private final Map<String, Object> modelConfig;
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    public DeepSeekModel(ConfigLoader configLoader, String name, Map<String, Object> modelConfig) {
        this.configLoader = configLoader;
        this.name = name;
        this.modelConfig = modelConfig;
        this.description = (String) modelConfig.getOrDefault("description", "DeepSeek AI Model");
    }
    
    @Override
    public String generateReply(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt) {
        try {
            String apiKey = getApiKey();
            String apiBaseUrl = getApiBaseUrl();
            
            if (apiKey.isEmpty()) {
                logger.error("DeepSeek模型{}的API密钥未配置", name);
                return "DeepSeek AI服务暂时不可用，请联系管理员配置API密钥。";
            }
            
            JSONObject requestBody = buildRequestBody(systemPrompt, conversation, personaAsSystemPrompt);
            String requestJson = requestBody.toString(2); // 格式化的JSON
            logger.debug("DeepSeek API请求体: {}", requestJson);
            
            String endpoint = apiBaseUrl + "/v1/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();
            
            logger.debug("DeepSeek API请求地址: {}", endpoint);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            // 记录完整响应内容用于调试
            logger.debug("DeepSeek API响应: 状态码={}, 内容={}", response.statusCode(), response.body());
            
            if (response.statusCode() == 200) {
                return ResponseParser.parseResponse(response.body(), "DeepSeek");
            } else {
                // 更详细地记录错误信息
                logger.error("DeepSeek API调用失败: {}, 状态码: {}", response.body(), response.statusCode());
                
                // 尝试从错误响应中提取更有用的信息
                String detailedError = "未知错误";
                try {
                    JSONObject errorObj = new JSONObject(response.body());
                    if (errorObj.has("error")) {
                        JSONObject error = errorObj.getJSONObject("error");
                        String message = error.optString("message", "未提供错误信息");
                        String type = error.optString("type", "");
                        String code = error.optString("code", "");
                        detailedError = message;
                        
                        logger.error("DeepSeek错误详情: 消息={}, 类型={}, 代码={}", message, type, code);
                        
                        if (!type.isEmpty()) {
                            detailedError += " (" + type + ")";
                        }
                        
                        // 处理模型不存在的情况，提供更具体的建议
                        if ("Model Not Exist".equals(message) || message.contains("model") && message.contains("exist")) {
                            String modelName = requestBody.optString("model", "未知");
                            logger.error("DeepSeek API不支持模型: {}，尝试降级到默认模型", modelName);
                            
                            // 尝试使用fallback模型重试一次
                            requestBody.put("model", "deepseek-chat");
                            logger.info("尝试使用fallback模型deepseek-chat重试");
                            
                            request = HttpRequest.newBuilder()
                                    .uri(URI.create(endpoint))
                                    .header("Content-Type", "application/json")
                                    .header("Authorization", "Bearer " + apiKey)
                                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                                    .build();
                            
                            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                            
                            if (response.statusCode() == 200) {
                                logger.info("使用fallback模型成功");
                                return ResponseParser.parseResponse(response.body(), "DeepSeek");
                            } else {
                                logger.error("使用fallback模型仍然失败: {}", response.body());
                                return "DeepSeek API不支持请求的模型，并且降级尝试也失败。请联系管理员配置正确的模型。";
                            }
                        }
                    }
                } catch (Exception e) {
                    // 解析错误响应失败，使用原始错误响应
                    logger.warn("无法解析DeepSeek错误响应", e);
                }
                
                return switch (response.statusCode()) {
                    case 400 -> "DeepSeek API请求错误: " + detailedError;
                    case 401 -> "DeepSeek API认证失败，请检查API密钥。";
                    case 429 -> "DeepSeek API请求频率超限或余额不足，请稍后再试。";
                    default -> "DeepSeek API调用失败: " + detailedError;
                };
            }
        } catch (IOException | InterruptedException e) {
            logger.error("DeepSeek API调用异常", e);
            return "DeepSeek AI服务暂时不可用，请稍后再试。";
        }
    }
    
    private JSONObject buildRequestBody(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt) {
        JSONObject requestBody = new JSONObject();
        
        // 设置模型名称 - 修复模型名称映射
        String modelName = getModelConfigValue("model_name", "deepseek-chat");
        // 将内部模型名称映射到DeepSeek API实际接受的模型名称
        String apiModelName = mapToAPIModelName(modelName);
        requestBody.put("model", apiModelName);
        logger.debug("使用DeepSeek模型: {}", apiModelName);
        
        // 声明变量在外部，避免作用域问题
        JSONArray messages = new JSONArray();
        JSONObject systemMessage = null;
        
        // 根据配置决定如何处理人设
        if (personaAsSystemPrompt) {
            // 人设作为系统提示词
            // 添加系统消息
            systemMessage = new JSONObject();
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
            // 人设作为对话历史的一部分
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
        }
        
        requestBody.put("messages", messages);
        
        // 设置温度参数
        double temperature = getTemperature();
        requestBody.put("temperature", temperature);
        
        // 设置最大tokens
        int maxTokens = getMaxTokens();
        requestBody.put("max_tokens", maxTokens);
        
        // 添加DeepSeek特有参数
        
        // 搜索增强功能
        boolean enableSearch = getModelConfigValue("enable_search", false);
        if (enableSearch) {
            requestBody.put("enable_search", true);
        }
        
        // R系列模型特有参数
        if (modelName.contains("r1") || modelName.contains("r3")) {
            // 添加top_p参数 (对R系列模型很有用)
            double topP = getModelConfigValue("top_p", 0.8);
            requestBody.put("top_p", topP);
            
            // 添加停止序列，如果配置了的话
            Object stopSequences = modelConfig.get("stop_sequences");
            if (stopSequences instanceof List) {
                JSONArray stopArray = new JSONArray();
                for (Object stop : (List<?>) stopSequences) {
                    stopArray.put(stop.toString());
                }
                if (stopArray.length() > 0) {
                    requestBody.put("stop", stopArray);
                }
            }
            
            // 如果是R3模型，添加默认语言设置为中文
            if (modelName.contains("r3") && systemMessage != null) {
                String language = getModelConfigValue("language", "zh");
                // 在系统提示中添加语言指令
                if (!systemPrompt.toLowerCase().contains("use chinese") && 
                    !systemPrompt.toLowerCase().contains("用中文") &&
                    "zh".equals(language)) {
                    logger.debug("为R3模型添加默认中文回复设置");
                    systemMessage.put("content", systemPrompt + "\n请用中文回复。");
                }
            }
        }
        
        return requestBody;
    }
    
    /**
     * 将内部模型名称映射到DeepSeek API实际接受的模型标识符
     * 根据官方文档，当前仅支持deepseek-chat和deepseek-reasoner两种模型
     * https://api-docs.deepseek.com/zh-cn/
     */
    private String mapToAPIModelName(String internalModelName) {
        return switch(internalModelName) {
            // 根据DeepSeek API官方文档，仅支持以下两种模型
            case "deepseek-reasoner" -> "deepseek-reasoner"; // 对应DeepSeek-R1推理模型
            case "deepseek-chat" -> "deepseek-chat";  // 对应DeepSeek-V3基础模型
            case "deepseek-coder" -> "deepseek-coder"; // 支持编程的特定模型
            
            // 所有其他模型名称都映射到默认的deepseek-chat
            default -> "deepseek-chat";  // 默认使用DeepSeek-V3模型
        };
    }
    

    
    private String getApiKey() {
        String apiKey = (String) modelConfig.getOrDefault("api_key", "");
        if (apiKey.isEmpty()) {
            apiKey = configLoader.getConfigString("ai.deepseek.api_key", "");
        }
        return apiKey;
    }
    
    private String getApiBaseUrl() {
        String apiBaseUrl = (String) modelConfig.getOrDefault("api_base_url", "");
        if (apiBaseUrl.isEmpty()) {
            apiBaseUrl = configLoader.getConfigString("ai.deepseek.api_base_url", "https://api.deepseek.com");
        }
        return apiBaseUrl;
    }
    
    private double getTemperature() {
        Object temp = modelConfig.getOrDefault("temperature", configLoader.getConfig("ai.deepseek.temperature", 0.7));
        if (temp instanceof Number) {
            return ((Number) temp).doubleValue();
        }
        return 0.7;
    }
    
    private int getMaxTokens() {
        Object tokens = modelConfig.getOrDefault("max_tokens", configLoader.getConfig("ai.deepseek.max_tokens", 2000));
        if (tokens instanceof Number) {
            return ((Number) tokens).intValue();
        }
        return 2000;
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
        return "deepseek";
    }
} 