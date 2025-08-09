package cn.ningmo.ai.model;

import cn.ningmo.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ModelManager {
    private static final Logger logger = LoggerFactory.getLogger(ModelManager.class);
    
    private final ConfigLoader configLoader;
    private final Map<String, AIModel> models = new ConcurrentHashMap<>();
    
    // 跟踪模型失败次数
    private final Map<String, AtomicInteger> modelFailureCount = new ConcurrentHashMap<>();
    // 跟踪模型冷却期，记录模型应该冷却到什么时间点
    private final Map<String, Long> modelCooldownUntil = new ConcurrentHashMap<>();
    // 最大失败次数，超过这个次数会触发冷却
    private static final int MAX_FAILURE_THRESHOLD = 5;
    // 冷却时间（毫秒）
    private static final long COOLDOWN_PERIOD_MS = 5 * 60 * 1000; // 5分钟
    // 重试间隔基数（毫秒）
    private static final long RETRY_INTERVAL_BASE_MS = 1000; // 1秒
    // 最大重试次数
    private static final int MAX_RETRY_COUNT = 2;
    
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
        
        // 初次启动时检查至少有一个可用模型
        if (models.isEmpty()) {
            logger.warn("没有成功加载任何模型，系统可能无法正常工作");
        } else {
            logger.info("共加载了 {} 个模型", models.size());
        }
    }
    
    /**
     * 刷新模型列表
     */
    public void refreshModels() {
        models.clear();
        modelFailureCount.clear();
        modelCooldownUntil.clear();
        loadModels();
        logger.info("模型列表已刷新");
    }
    
    private AIModel createModel(String modelName, String type, Map<String, Object> modelConfig) {
        try {
            return switch (type.toLowerCase()) {
                case "openai" -> new OpenAIModel(modelName, configLoader, modelConfig);
                case "anthropic" -> new AnthropicModel(configLoader, modelName, modelConfig);
                case "local" -> new LocalLLMModel(configLoader, modelName, modelConfig);
                case "api" -> new GenericAPIModel(configLoader, modelName, modelConfig);
                case "deepseek" -> new DeepSeekModel(configLoader, modelName, modelConfig);
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
    
    /**
     * 生成回复
     * @param modelName 模型名称
     * @param systemPrompt 系统提示词
     * @param conversation 对话历史
     * @param personaAsSystemPrompt 是否将人设作为系统提示词
     * @return 模型回复的内容
     */
    public String generateReply(String modelName, String systemPrompt, List<Map<String, String>> conversation, boolean personaAsSystemPrompt) {
        long startTime = System.currentTimeMillis();
        
        // 记录请求开始信息
        logger.info("开始生成回复: 模型={}, 对话长度={}, 系统提示长度={}", 
                  modelName, conversation.size(), systemPrompt.length());
        
        // 检查模型是否在冷却期
        if (isModelInCooldown(modelName)) {
            logger.warn("模型 {} 处于冷却期，尝试使用备用模型", modelName);
            String fallbackModel = findAvailableFallbackModel(modelName);
            
            if (fallbackModel != null && !fallbackModel.equals(modelName)) {
                logger.info("使用备用模型 {} 代替 {}", fallbackModel, modelName);
                modelName = fallbackModel;
            } else {
                logger.warn("没有可用的备用模型，尝试使用原模型 {}", modelName);
                // 即使在冷却期，也尝试使用，避免无法回复
                // 重置冷却状态，给出一次机会
                resetModelStatus(modelName);
            }
        }
        
        // 获取模型实例
        AIModel model = getModelForName(modelName);
        if (model == null) {
            logger.error("找不到模型: {}，尝试使用默认模型", modelName);
            String defaultModel = configLoader.getConfigString("ai.default_model", "");
            model = getModelForName(defaultModel);
            
            if (model == null) {
                logger.error("找不到默认模型，无法生成回复");
                return "抱歉，AI模型配置错误，请联系管理员。";
            }
        }
        
        // 记录正在使用的模型
        logger.info("使用模型 {} (类型: {}) 生成回复", model.getName(), model.getType());
        
        // 实现重试机制
        for (int attempt = 0; attempt <= MAX_RETRY_COUNT; attempt++) {
            try {
                // 记录是第几次尝试
                if (attempt > 0) {
                    logger.info("第{}次重试模型 {}", attempt, modelName);
                }
                
                // 添加超时检测
                long attemptStart = System.currentTimeMillis();
                
                // 调用模型生成回复
                logger.debug("调用模型 {} 生成回复...", modelName);
                String result = model.generateReply(systemPrompt, conversation, personaAsSystemPrompt);
                
                long attemptDuration = System.currentTimeMillis() - attemptStart;
                logger.info("模型 {} 响应耗时: {}毫秒", modelName, attemptDuration);
                
                // 如果结果为空或包含错误标识，进行重试
                if (result == null || result.trim().isEmpty() || 
                    result.contains("服务暂时不可用") || 
                    result.contains("服务器出现故障") ||
                    result.contains("请稍后再试") ||
                    result.contains("API调用失败") ||
                    result.contains("错误") ||
                    result.contains("失败")) {
                    
                    logger.warn("模型 {} 返回错误或空结果: {}", modelName, result);
                    
                    // 增加失败计数
                    incrementModelFailureCount(modelName);
                    
                    // 检查是否需要进入冷却期
                    if (checkAndSetModelCooldown(modelName)) {
                        logger.warn("模型 {} 已进入冷却期", modelName);
                        String fallbackModel = findAvailableFallbackModel(modelName);
                        
                        if (fallbackModel != null && !fallbackModel.equals(modelName)) {
                            logger.info("切换到备用模型 {}", fallbackModel);
                            AIModel fallbackModelObj = getModelForName(fallbackModel);
                            if (fallbackModelObj != null) {
                                // 使用备用模型
                                return fallbackModelObj.generateReply(systemPrompt, conversation, personaAsSystemPrompt);
                            }
                        }
                    }
                    
                    // 如果不是最后一次尝试，则等待一段时间后重试
                    if (attempt < MAX_RETRY_COUNT) {
                        long retryDelay = calculateRetryDelay(attempt);
                        logger.info("将在 {} 毫秒后重试", retryDelay);
                        try {
                            Thread.sleep(retryDelay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logger.error("重试等待被中断", e);
                        }
                        continue;
                    }
                } else {
                    // 成功获取结果，重置失败计数
                    resetModelFailureCount(modelName);
                    
                    // 记录成功信息和响应时间
                    long totalTime = System.currentTimeMillis() - startTime;
                    logger.info("模型 {} 成功生成回复，总用时: {}毫秒，回复长度: {}", 
                              modelName, totalTime, result.length());
                    
                    return result;
                }
            } catch (Exception e) {
                // 捕获所有异常，增加失败计数
                logger.error("模型 {} 调用异常: {}", modelName, e.getMessage(), e);
                incrementModelFailureCount(modelName);
                
                // 如果不是最后一次尝试，则重试
                if (attempt < MAX_RETRY_COUNT) {
                    long retryDelay = calculateRetryDelay(attempt);
                    logger.info("将在 {} 毫秒后重试", retryDelay);
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.error("重试等待被中断", ie);
                    }
                    continue;
                }
            }
        }
        
        // 所有重试都失败，尝试使用备用模型
        logger.error("模型 {} 在 {} 次尝试后仍然失败", modelName, MAX_RETRY_COUNT + 1);
        String fallbackModel = findAvailableFallbackModel(modelName);
        
        if (fallbackModel != null && !fallbackModel.equals(modelName)) {
            logger.info("所有重试失败，切换到备用模型 {}", fallbackModel);
            AIModel fallbackModelObj = getModelForName(fallbackModel);
            if (fallbackModelObj != null) {
                try {
                    return fallbackModelObj.generateReply(systemPrompt, conversation, personaAsSystemPrompt);
                } catch (Exception e) {
                    logger.error("备用模型 {} 也失败: {}", fallbackModel, e.getMessage(), e);
                }
            }
        }
        
        // 记录总处理时间
        long totalTime = System.currentTimeMillis() - startTime;
        logger.error("所有模型都失败，总耗时 {} 毫秒", totalTime);
        
        return "抱歉，AI服务暂时不可用，请稍后再试。我们的技术团队已收到此问题通知。";
    }

    /**
     * 计算重试延迟时间（指数退避策略）
     */
    private long calculateRetryDelay(int attempt) {
        // 使用指数退避策略：基础延迟 * 2^尝试次数 + 随机抖动
        return RETRY_INTERVAL_BASE_MS * (long)Math.pow(2, attempt) + 
               (long)(Math.random() * 1000); // 添加0-1000ms的随机抖动
    }
    
    /**
     * 获取模型对象，处理默认模型和备用模型
     */
    private AIModel getModelForName(String modelName) {
        AIModel model = models.get(modelName);
        if (model == null) {
            // 从配置中获取默认模型，如果未配置则使用gpt-3.5-turbo
            String defaultModel = configLoader.getConfigString("ai.default_model", "gpt-3.5-turbo");
            logger.warn("模型不存在: {}, 尝试使用默认模型: {}", modelName, defaultModel);
            model = models.get(defaultModel);
            
            // 如果默认模型也不存在，尝试使用第一个可用的模型
            if (model == null && !models.isEmpty()) {
                String firstModel = models.keySet().iterator().next();
                logger.warn("默认模型{}不存在，使用第一个可用模型: {}", defaultModel, firstModel);
                model = models.get(firstModel);
            }
        }
        return model;
    }
    
    /**
     * 找到一个可用的备用模型
     */
    private String findAvailableFallbackModel(String currentModel) {
        // 获取配置中指定的备用模型
        String configuredFallback = configLoader.getConfigString("ai.fallback_model", "");
        
        // 检查配置的备用模型是否可用且不是当前模型
        if (!configuredFallback.isEmpty() && 
            !configuredFallback.equals(currentModel) && 
            models.containsKey(configuredFallback) && 
            !isModelInCooldown(configuredFallback)) {
            return configuredFallback;
        }
        
        // 否则，查找第一个不在冷却期的可用模型
        for (String modelName : models.keySet()) {
            if (!modelName.equals(currentModel) && !isModelInCooldown(modelName)) {
                return modelName;
            }
        }
        
        // 如果所有模型都不可用，返回默认模型
        String defaultModel = configLoader.getConfigString("ai.default_model", "gpt-3.5-turbo");
        if (models.containsKey(defaultModel) && !defaultModel.equals(currentModel)) {
            return defaultModel;
        }
        
        // 如果没有其他选择，返回原模型
        return currentModel;
    }
    
    /**
     * 增加模型失败计数
     */
    private void incrementModelFailureCount(String modelName) {
        modelFailureCount.computeIfAbsent(modelName, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * 重置模型失败计数
     */
    private void resetModelFailureCount(String modelName) {
        AtomicInteger counter = modelFailureCount.get(modelName);
        if (counter != null) {
            counter.set(0);
        }
    }
    
    /**
     * 检查模型是否超过失败阈值，如果是则设置冷却期
     * @return 是否进入冷却期
     */
    private boolean checkAndSetModelCooldown(String modelName) {
        AtomicInteger counter = modelFailureCount.get(modelName);
        if (counter != null && counter.get() >= MAX_FAILURE_THRESHOLD) {
            long cooldownUntil = System.currentTimeMillis() + COOLDOWN_PERIOD_MS;
            modelCooldownUntil.put(modelName, cooldownUntil);
            logger.warn("模型 {} 已达到失败阈值 {}，进入冷却期直到: {}", 
                      modelName, MAX_FAILURE_THRESHOLD, new Date(cooldownUntil));
            return true;
        }
        return false;
    }
    
    /**
     * 检查模型是否在冷却期
     */
    private boolean isModelInCooldown(String modelName) {
        Long cooldownUntil = modelCooldownUntil.get(modelName);
        if (cooldownUntil != null) {
            if (System.currentTimeMillis() < cooldownUntil) {
                return true;
            } else {
                // 冷却期已过，清除
                modelCooldownUntil.remove(modelName);
                // 重置失败计数
                resetModelFailureCount(modelName);
            }
        }
        return false;
    }
    
    /**
     * 检查模型是否存在
     */
    public boolean hasModel(String modelName) {
        return models.containsKey(modelName);
    }
    
    /**
     * 获取所有模型列表
     */
    public List<String> listModels() {
        return new ArrayList<>(models.keySet());
    }
    
    /**
     * 获取模型详情
     */
    public Map<String, String> getModelDetails(String modelName) {
        AIModel model = models.get(modelName);
        if (model == null) {
            return Map.of();
        }
        
        Map<String, String> details = new HashMap<>();
        details.put("name", model.getName());
        details.put("type", model.getType());
        details.put("description", model.getDescription());
        
        // 添加状态信息
        AtomicInteger failCount = modelFailureCount.get(modelName);
        if (failCount != null) {
            details.put("failure_count", String.valueOf(failCount.get()));
        }
        
        Long cooldownTime = modelCooldownUntil.get(modelName);
        if (cooldownTime != null) {
            long now = System.currentTimeMillis();
            if (now < cooldownTime) {
                details.put("status", "冷却中");
                details.put("available_in", String.format("%.1f分钟", (cooldownTime - now) / 60000.0));
            } else {
                details.put("status", "可用");
            }
        } else {
            details.put("status", "可用");
        }
        
        return details;
    }
    
    /**
     * 手动将模型设置为可用状态（解除冷却）
     */
    public boolean resetModelStatus(String modelName) {
        if (!models.containsKey(modelName)) {
            return false;
        }
        
        modelCooldownUntil.remove(modelName);
        resetModelFailureCount(modelName);
        logger.info("已手动重置模型 {} 的状态", modelName);
        return true;
    }
}
