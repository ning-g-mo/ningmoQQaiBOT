package cn.ningmo.ai.response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * 统一的AI模型响应解析器
 * 提供多种格式的响应解析和错误处理
 */
public class ResponseParser {
    private static final Logger logger = LoggerFactory.getLogger(ResponseParser.class);
    
    /**
     * 通用响应解析方法
     * @param responseBody 原始响应体
     * @param modelName 模型名称（用于日志）
     * @return 解析后的内容，如果失败返回错误信息
     */
    public static String parseResponse(String responseBody, String modelName) {
        return parseResponse(responseBody, modelName, null);
    }
    
    /**
     * 使用自定义路径解析响应
     * @param responseBody 原始响应体
     * @param modelName 模型名称（用于日志）
     * @param customPath 自定义解析路径，如 "choices.0.message.content"
     * @return 解析后的内容，如果失败返回错误信息
     */
    public static String parseResponse(String responseBody, String modelName, String customPath) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            logger.error("{}模型返回空响应", modelName);
            return "AI服务返回空响应";
        }
        
        try {
            logger.debug("解析{}模型API响应: {}", modelName, responseBody);
            
            // 尝试解析JSON
            JSONObject response;
            try {
                response = new JSONObject(responseBody);
            } catch (JSONException e) {
                // 如果不是有效JSON，可能是纯文本响应
                logger.warn("{}模型返回非JSON格式响应，当作纯文本处理", modelName);
                String trimmed = responseBody.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
                logger.error("{}模型返回的纯文本响应为空", modelName);
                return "AI服务返回空内容";
            }
            
            // 检查是否有错误信息
            String errorResult = checkForErrors(response, modelName);
            if (errorResult != null) {
                return errorResult;
            }
            
            // 如果指定了自定义路径，优先使用
            if (customPath != null && !customPath.trim().isEmpty()) {
                String pathResult = parseByPath(response, customPath, modelName);
                if (pathResult != null) {
                    return pathResult;
                }
                logger.warn("{}模型自定义路径解析失败，尝试标准格式", modelName);
            }
            
            // 尝试标准格式解析
            String standardResult = parseStandardFormats(response, modelName);
            if (standardResult != null) {
                return standardResult;
            }
            
            // 如果所有解析都失败，返回详细错误信息
            logger.warn("{}模型响应格式不匹配任何已知格式，完整响应: {}", modelName, responseBody);
            return String.format("%s模型响应格式不支持。响应内容: %s", 
                               modelName, 
                               responseBody.substring(0, Math.min(200, responseBody.length())));
                               
        } catch (Exception e) {
            logger.error("解析{}模型响应时发生未知错误", modelName, e);
            logger.error("原始响应内容: {}", responseBody);
            return String.format("解析%s模型响应时出错: %s", modelName, e.getMessage());
        }
    }
    
    /**
     * 检查响应中的错误信息
     */
    private static String checkForErrors(JSONObject response, String modelName) {
        if (response.has("error")) {
            try {
                JSONObject error = response.getJSONObject("error");
                String errorMessage = error.optString("message", "未知错误");
                String errorType = error.optString("type", "");
                String errorCode = error.optString("code", "");
                
                logger.error("{}模型返回错误: {}, 类型: {}, 代码: {}", modelName, errorMessage, errorType, errorCode);
                
                // 组合错误信息
                StringBuilder errorInfo = new StringBuilder("API返回错误: ").append(errorMessage);
                if (!errorType.isEmpty()) {
                    errorInfo.append(" (类型: ").append(errorType).append(")");
                }
                if (!errorCode.isEmpty()) {
                    errorInfo.append(" (代码: ").append(errorCode).append(")");
                }
                
                return errorInfo.toString();
            } catch (Exception e) {
                logger.error("解析{}模型错误信息失败", modelName, e);
                return "API返回错误，但无法解析错误详情";
            }
        }
        return null;
    }
    
    /**
     * 根据路径解析响应
     */
    private static String parseByPath(JSONObject response, String path, String modelName) {
        try {
            String[] pathParts = path.split("\\.");
            Object current = response;
            
            logger.debug("{}模型使用路径解析: {}", modelName, path);
            
            for (String part : pathParts) {
                if (current == null) {
                    logger.warn("{}模型路径解析中途遇到null值，路径: {}", modelName, path);
                    return null;
                }
                
                if (current instanceof JSONObject) {
                    JSONObject jsonObj = (JSONObject) current;
                    if (!jsonObj.has(part)) {
                        logger.debug("{}模型JSON对象中不存在键: {}, 路径: {}", modelName, part, path);
                        return null;
                    }
                    current = jsonObj.get(part);
                } else if (current instanceof JSONArray) {
                    JSONArray jsonArray = (JSONArray) current;
                    try {
                        int index = Integer.parseInt(part);
                        if (index >= 0 && index < jsonArray.length()) {
                            current = jsonArray.get(index);
                        } else {
                            logger.warn("{}模型数组索引超出范围: {}, 数组长度: {}, 路径: {}", 
                                      modelName, index, jsonArray.length(), path);
                            return null;
                        }
                    } catch (NumberFormatException e) {
                        logger.warn("{}模型无法解析数组索引: {}, 路径: {}", modelName, part, path);
                        return null;
                    }
                } else {
                    logger.warn("{}模型路径解析遇到非JSON对象/数组: {}, 路径: {}", 
                              modelName, current.getClass().getSimpleName(), path);
                    return null;
                }
            }
            
            if (current != null) {
                String content = current.toString();
                if (content != null && !content.trim().isEmpty()) {
                    return content.trim();
                }
            }
        } catch (Exception e) {
            logger.warn("{}模型路径解析异常: {}", modelName, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 尝试解析标准格式
     */
    private static String parseStandardFormats(JSONObject response, String modelName) {
        // 定义要尝试的解析方法
        List<FormatParser> parsers = Arrays.asList(
            // OpenAI格式
            r -> parseOpenAIFormat(r),
            // Anthropic格式
            r -> parseAnthropicFormat(r),
            // 直接文本格式
            r -> parseDirectTextFormat(r),
            // Ollama格式
            r -> parseOllamaFormat(r),
            // LM Studio格式
            r -> parseLMStudioFormat(r)
        );
        
        for (FormatParser parser : parsers) {
            try {
                String result = parser.parse(response);
                if (result != null && !result.trim().isEmpty()) {
                    logger.debug("{}模型使用{}格式解析成功", modelName, parser.getClass().getSimpleName());
                    return result.trim();
                }
            } catch (Exception e) {
                logger.debug("{}模型{}格式解析失败: {}", modelName, parser.getClass().getSimpleName(), e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 解析OpenAI格式
     */
    private static String parseOpenAIFormat(JSONObject response) {
        if (response.has("choices") && response.getJSONArray("choices").length() > 0) {
            JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
            if (choice.has("message")) {
                JSONObject message = choice.getJSONObject("message");
                if (message.has("content")) {
                    return message.getString("content");
                }
            }
            // 支持流式响应
            if (choice.has("delta")) {
                JSONObject delta = choice.getJSONObject("delta");
                if (delta.has("content")) {
                    return delta.getString("content");
                }
            }
        }
        return null;
    }
    
    /**
     * 解析Anthropic格式
     */
    private static String parseAnthropicFormat(JSONObject response) {
        if (response.has("content") && response.getJSONArray("content").length() > 0) {
            JSONObject content = response.getJSONArray("content").getJSONObject(0);
            if (content.has("text")) {
                return content.getString("text");
            }
        }
        return null;
    }
    
    /**
     * 解析直接文本格式
     */
    private static String parseDirectTextFormat(JSONObject response) {
        // 尝试常见的直接文本字段
        String[] textFields = {"text", "result", "output", "answer", "reply", "message"};
        
        for (String field : textFields) {
            if (response.has(field)) {
                return response.getString(field);
            }
        }
        return null;
    }
    
    /**
     * 解析Ollama格式
     */
    private static String parseOllamaFormat(JSONObject response) {
        if (response.has("response")) {
            return response.getString("response");
        }
        return null;
    }
    
    /**
     * 解析LM Studio格式
     */
    private static String parseLMStudioFormat(JSONObject response) {
        if (response.has("data") && response.getJSONArray("data").length() > 0) {
            JSONObject data = response.getJSONArray("data").getJSONObject(0);
            if (data.has("text")) {
                return data.getString("text");
            }
        }
        return null;
    }
    
    /**
     * 格式解析器接口
     */
    @FunctionalInterface
    private interface FormatParser {
        String parse(JSONObject response) throws Exception;
    }
}
