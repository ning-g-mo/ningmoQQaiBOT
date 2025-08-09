package cn.ningmo.ai.model;

import cn.ningmo.ai.response.ResponseParser;
import cn.ningmo.config.ConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
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

public class OpenAIModel implements AIModel {
    private static final Logger logger = LoggerFactory.getLogger(OpenAIModel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String modelName;
    private final ConfigLoader configLoader;
    private final Map<String, Object> modelConfig;
    private final HttpClient httpClient;
    
    // 默认超时设置
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
    
    public OpenAIModel(String modelName, ConfigLoader configLoader, Map<String, Object> modelConfig) {
        this.modelName = modelName;
        this.configLoader = configLoader;
        this.modelConfig = modelConfig;
        
        // 从配置中获取超时设置
        int connectTimeout = getModelConfigValue("connect_timeout_seconds", DEFAULT_CONNECT_TIMEOUT_SECONDS);
        int requestTimeout = getModelConfigValue("request_timeout_seconds", DEFAULT_REQUEST_TIMEOUT_SECONDS);
        
        // 创建HttpClient，设置超时
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();
        
        logger.info("初始化OpenAI模型: {}, 连接超时: {}秒, 请求超时: {}秒", 
                    modelName, connectTimeout, requestTimeout);
    }
    
    @Override
    public String generateReply(String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt) {
        // 记录开始处理请求的时间
        long startTime = System.currentTimeMillis();
        logger.info("开始生成AI回复，使用模型: {}, 对话长度: {}", modelName, conversation.size());
        
        try {
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            
            // 添加模型名称
            requestBody.put("model", mapToApiModelName());
            
            // 构建消息数组
            JSONArray messages = new JSONArray();
            
            // 根据配置决定如何处理人设
            if (personaAsSystemPrompt) {
                // 人设作为系统提示词
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
            double temperature = getModelConfigValue("temperature", 0.7);
            requestBody.put("temperature", temperature);
            
            // 设置最大生成Token数
            int maxTokens = getModelConfigValue("max_tokens", 2000);
            requestBody.put("max_tokens", maxTokens);
            
            // 记录请求的基本信息
            logger.debug("API请求: 模型={}, 温度={}, 最大Token={}", 
                       mapToApiModelName(), temperature, maxTokens);
            
            // 从配置中获取API密钥
            String apiKey = getApiKey();
            if (apiKey.isEmpty()) {
                logger.error("未配置API密钥，无法调用OpenAI API");
                return "API配置错误：未提供有效的API密钥";
            }
            
            // 从配置中获取API基础URL
            String apiBaseUrl = getApiBaseUrl();
            
            // 构建完整URL
            String completeUrl = apiBaseUrl + "/v1/chat/completions";
            logger.debug("API调用URL: {}", completeUrl);
            
            // 获取请求超时设置
            int requestTimeout = getModelConfigValue("request_timeout_seconds", DEFAULT_REQUEST_TIMEOUT_SECONDS);
            
            // 构建HTTP请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(completeUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .timeout(Duration.ofSeconds(requestTimeout))
                    .build();
            
            // 发送请求
            logger.debug("发送API请求...");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            // 记录响应时间
            long responseTime = System.currentTimeMillis() - startTime;
            logger.info("收到API响应，状态码: {}, 响应时间: {}毫秒", response.statusCode(), responseTime);
            
            // 处理响应
            if (response.statusCode() == 200) {
                String content = ResponseParser.parseResponse(response.body(), "OpenAI");
                
                // 记录成功
                long totalTime = System.currentTimeMillis() - startTime;
                logger.info("AI回复生成成功，总耗时: {}毫秒，回复长度: {}", totalTime, content.length());
                
                return content;
            } else {
                // 记录不同的错误状态码
                String errorMessage = "OpenAI API调用失败，状态码: " + response.statusCode();
                
                // 尝试从响应中提取更详细的错误信息
                try {
                    JSONObject errorJson = new JSONObject(response.body());
                    if (errorJson.has("error")) {
                        JSONObject error = errorJson.getJSONObject("error");
                        String message = error.optString("message", "未知错误");
                        String type = error.optString("type", "unknown");
                        errorMessage += ", 错误类型: " + type + ", 错误信息: " + message;
                    }
                } catch (Exception e) {
                    // 如果解析失败，使用原始响应体
                    errorMessage += ", 响应: " + response.body();
                }
                
                // 针对不同状态码给出更具体的错误信息
                if (response.statusCode() == 401) {
                    logger.error("API密钥无效或过期: {}", errorMessage);
                    return "API认证失败，请联系管理员更新API密钥";
                } else if (response.statusCode() == 429) {
                    logger.error("API请求超出限制: {}", errorMessage);
                    return "API请求太频繁或已达到配额限制，请稍后再试";
                } else if (response.statusCode() >= 500) {
                    logger.error("OpenAI服务器错误: {}", errorMessage);
                    return "OpenAI服务器暂时不可用，请稍后再试";
                } else {
                    logger.error(errorMessage);
                    return "AI服务调用失败，请稍后再试";
                }
            }
        } catch (IOException e) {
            logger.error("API请求IO异常", e);
            return "网络连接错误，无法连接到AI服务：" + e.getMessage();
        } catch (InterruptedException e) {
            logger.error("API请求被中断", e);
            Thread.currentThread().interrupt();
            return "请求被中断，请稍后再试";
        } catch (Exception e) {
            logger.error("调用OpenAI API时发生未知错误", e);
            return "生成回复时发生错误: " + e.getMessage();
        }
    }
    
    /**
     * 将内部模型名映射到API请求中使用的模型名
     */
    private String mapToApiModelName() {
        // 从模型配置中获取API模型名称，如果没有配置则使用内部模型名
        String apiModelName = getModelConfigValue("api_model_name", "");
        if (!apiModelName.isEmpty()) {
            return apiModelName;
        }
        
        // 默认内部模型名可能与API模型名相同
        return modelName;
    }
    
    /**
     * 获取API密钥
     */
    private String getApiKey() {
        // 首先尝试从模型配置中获取
        String apiKey = getModelConfigValue("api_key", "");
        
        // 如果模型配置中没有，从全局配置中获取
        if (apiKey.isEmpty()) {
            apiKey = configLoader.getConfigString("ai.openai.api_key", "");
        }
        
        return apiKey;
    }
    
    /**
     * 获取API基础URL
     */
    private String getApiBaseUrl() {
        // 首先尝试从模型配置中获取
        String apiBaseUrl = getModelConfigValue("api_base_url", "");
        
        // 如果模型配置中没有，从全局配置中获取，默认为官方API
        if (apiBaseUrl.isEmpty()) {
            apiBaseUrl = configLoader.getConfigString("ai.openai.api_base_url", "https://api.openai.com");
        }
        
        return apiBaseUrl;
    }
    
    /**
     * 从模型配置中获取字符串值
     */
    private String getModelConfigValue(String key, String defaultValue) {
        Object value = modelConfig.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }
    
    /**
     * 从模型配置中获取泛型值
     */
    @SuppressWarnings("unchecked")
    private <T> T getModelConfigValue(String key, T defaultValue) {
        Object value = modelConfig.get(key);
        if (value == null) {
            return defaultValue;
        }
        
        try {
            // 尝试类型转换
            if (defaultValue instanceof Number && value instanceof Number) {
                if (defaultValue instanceof Integer) {
                    return (T) Integer.valueOf(((Number) value).intValue());
                } else if (defaultValue instanceof Double) {
                    return (T) Double.valueOf(((Number) value).doubleValue());
                }
            }
            return (T) value;
        } catch (ClassCastException e) {
            logger.warn("配置值类型转换失败, key: {}, value: {}", key, value, e);
            return defaultValue;
        }
    }
    
    @Override
    public String getName() {
        return modelName;
    }
    
    @Override
    public String getDescription() {
        return (String) modelConfig.getOrDefault("description", "OpenAI模型");
    }
    
    @Override
    public String getType() {
        return "openai";
    }
}