package cn.ningmo.ai;

import cn.ningmo.ai.model.ModelManager;
import cn.ningmo.ai.persona.PersonaManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI服务
 * 用于处理AI对话请求
 */
public class AIService {
    private static final Logger logger = LoggerFactory.getLogger(AIService.class);
    
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private ModelManager modelManager;
    private PersonaManager personaManager;
    
    // 对话历史缓存，使用ConcurrentHashMap保证线程安全
    private final Map<String, List<Map<String, String>>> conversations = new ConcurrentHashMap<>();
    
    // 用于限制每个用户请求频率的时间戳记录
    private final Map<String, Long> userLastRequestTime = new ConcurrentHashMap<>();
    
    // 用于缓存AI响应的结果，避免重复计算
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    
    // 消息分隔符
    private static final String MESSAGE_SEPARATOR = "\\n---\\n";
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(MESSAGE_SEPARATOR);
    
    // 执行AI请求的线程池，使用有界队列避免积压过多请求
    private final ExecutorService aiExecutor;
    
    // 限制每个用户的请求频率（毫秒）
    private final long minRequestInterval;
    
    public AIService(ConfigLoader configLoader, DataManager dataManager) {
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.modelManager = new ModelManager(configLoader);
        this.personaManager = new PersonaManager(configLoader);
        
        // 创建AI执行线程池，避免过多线程争抢资源
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.aiExecutor = new ThreadPoolExecutor(
            corePoolSize, corePoolSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20), // 有界队列，避免OOM
            r -> {
                Thread t = new Thread(r, "AI-Worker-" + System.currentTimeMillis());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时，在调用线程执行
        );
        
        // 设置最小请求间隔，默认500毫秒，防止用户过于频繁请求
        this.minRequestInterval = configLoader.getConfig("ai.min_request_interval", 500L);
        
        logger.info("AI服务初始化完成，工作线程数: {}, 最小请求间隔: {}ms", corePoolSize, minRequestInterval);
        
        // 定期清理缓存
        startCacheCleanupScheduler();
    }
    
    /**
     * 处理AI聊天请求
     * @param userId 用户ID
     * @param message 消息内容
     * @return AI回复
     */
    public String chat(String userId, String message) {
        return chat(userId, message, new ArrayList<>());
    }
    
    /**
     * 处理AI聊天请求（支持图片）
     * @param userId 用户ID
     * @param message 消息内容
     * @param imageBase64List 图片base64编码列表
     * @return AI回复
     */
    public String chat(String userId, String message, List<String> imageBase64List) {
        // 频率限制检查
        if (!checkRequestLimit(userId)) {
            logger.debug("用户{}请求过于频繁", userId);
            return "请求过于频繁，请稍后再试";
        }
        
        // 构建请求唯一标识，用于缓存和去重
        String requestKey = userId + ":" + message.hashCode();
        
        // 检查是否有相同请求正在处理中
        CompletableFuture<String> pendingRequest = pendingRequests.get(requestKey);
        if (pendingRequest != null && !pendingRequest.isDone()) {
            try {
                // 等待已有请求完成，最多等待10秒
                return pendingRequest.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("等待已有请求超时，将重新处理", e);
                // 超时则继续处理
            }
        }
        
        // 创建新的异步请求
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                // 获取用户设置的个性化模型和人设
                String modelName = dataManager.getUserModel(userId);
                String persona = dataManager.getUserPersona(userId);
                
                // 获取AI回复
                return generateAIReply(userId, message, modelName, persona, imageBase64List);
            } catch (Exception e) {
                logger.error("生成AI回复时出错", e);
                return "AI服务暂时出现问题，请稍后再试。错误：" + e.getMessage();
            }
        }, aiExecutor);
        
        // 缓存请求
        pendingRequests.put(requestKey, future);
        
        try {
            // 等待请求完成，设置超时
            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.error("AI请求超时", e);
            return "AI服务响应超时，请稍后再试";
        } catch (Exception e) {
            logger.error("等待AI回复时出错", e);
            return "处理请求时出错：" + e.getMessage();
        } finally {
            // 无论成功与否，标记请求完成
            future.whenComplete((result, ex) -> pendingRequests.remove(requestKey));
        }
    }
    
    /**
     * 处理AI聊天请求并返回可能的多段回复
     * @param userId 用户ID
     * @param message 消息内容
     * @return AI回复列表
     */
    public List<String> chatMultipart(String userId, String message) {
        // 获取单个回复
        String reply = chat(userId, message);
        
        // 切分多段回复
        return splitMessage(reply);
    }
    
    /**
     * 生成AI回复
     */
    private String generateAIReply(String userId, String message, String modelName, String persona) {
        return generateAIReply(userId, message, modelName, persona, new ArrayList<>());
    }
    
    /**
     * 生成AI回复（支持图片）
     */
    private String generateAIReply(String userId, String message, String modelName, String persona, List<String> imageBase64List) {
        // 获取对话历史
        List<Map<String, String>> conversation = getOrCreateConversation(userId);
        
        // 添加用户消息到对话历史
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", message);
        conversation.add(userMessage);
        
        // 获取系统提示（人设）
        String systemPrompt = personaManager.getPersonaPrompt(persona);
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            systemPrompt = "你是一个友好、有帮助的AI助手。请用中文回答问题。";
        }
        
        // 获取人设配置：是否作为系统提示词
        boolean personaAsSystemPrompt = configLoader.getConfig("ai.persona.as_system_prompt", true);
        
        // 生成AI回复
        long startTime = System.currentTimeMillis();
        String aiReply = modelManager.generateReply(modelName, systemPrompt, conversation, personaAsSystemPrompt, imageBase64List);
        long endTime = System.currentTimeMillis();
        logger.debug("AI响应生成耗时: {}ms", (endTime - startTime));
        
        // 添加AI回复到对话历史
        Map<String, String> assistantMessage = new HashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", aiReply);
        conversation.add(assistantMessage);
        
        // 裁剪对话历史，保持在配置的长度以内
        int maxConversationLength = configLoader.getConfig("ai.max_conversation_length", 20);
        while (conversation.size() > maxConversationLength) {
            conversation.remove(0);
        }
        
        return aiReply;
    }
    
    /**
     * 切分多段消息
     */
    private List<String> splitMessage(String message) {
        if (message == null || message.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 检查是否包含分隔符
        if (!message.contains(MESSAGE_SEPARATOR.replace("\\", ""))) {
            return Collections.singletonList(message);
        }
        
        // 使用分隔符切分消息
        List<String> parts = new ArrayList<>();
        Matcher matcher = SEPARATOR_PATTERN.matcher(message);
        int lastEnd = 0;
        
        while (matcher.find()) {
            parts.add(message.substring(lastEnd, matcher.start()).trim());
            lastEnd = matcher.end();
        }
        
        // 添加最后一部分
        if (lastEnd < message.length()) {
            parts.add(message.substring(lastEnd).trim());
        }
        
        // 过滤空消息段
        parts.removeIf(String::isEmpty);
        
        // 限制最大段数，避免消息洪水
        int maxParts = configLoader.getConfig("bot.messages.max_consecutive", 3);
        if (maxParts > 0 && parts.size() > maxParts) {
            return parts.subList(0, maxParts);
        }
        
        return parts;
    }
    
    /**
     * 获取或创建用户对话历史
     */
    private List<Map<String, String>> getOrCreateConversation(String userId) {
        return conversations.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
    }
    
    /**
     * 清除用户对话历史
     */
    public void clearConversation(String userId) {
        conversations.remove(userId);
        logger.info("已清除用户{}的对话历史", userId);
    }
    
    /**
     * 获取用户对话历史摘要
     */
    public String getConversationSummary(String userId) {
        List<Map<String, String>> conversation = conversations.get(userId);
        
        if (conversation == null || conversation.isEmpty()) {
            return "没有对话历史";
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append("对话历史（").append(conversation.size()).append("条消息）：\n\n");
        
        int i = 1;
        for (Map<String, String> message : conversation) {
            String role = message.get("role");
            String content = message.get("content");
            
            summary.append(i).append(". ");
            summary.append(role.equals("user") ? "用户: " : "AI: ");
            
            // 截断过长的消息
            if (content.length() > 100) {
                content = content.substring(0, 100) + "...";
            }
            
            summary.append(content).append("\n");
            i++;
        }
        
        return summary.toString();
    }
    
    /**
     * 检查请求限制
     * @return 是否允许请求
     */
    private boolean checkRequestLimit(String userId) {
        long now = System.currentTimeMillis();
        Long lastRequestTime = userLastRequestTime.get(userId);
        
        if (lastRequestTime != null) {
            if (now - lastRequestTime < minRequestInterval) {
                return false;
            }
        }
        
        userLastRequestTime.put(userId, now);
        return true;
    }
    
    /**
     * 启动缓存清理任务
     */
    private void startCacheCleanupScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AIService-Cache-Cleaner");
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 清理过期的请求记录
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<String, Long>> it = userLastRequestTime.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Long> entry = it.next();
                    // 超过30分钟的记录清除
                    if (now - entry.getValue() > 30 * 60 * 1000) {
                        it.remove();
                    }
                }
                
                // 清理已完成但未移除的pendingRequests
                Iterator<Map.Entry<String, CompletableFuture<String>>> it2 = pendingRequests.entrySet().iterator();
                while (it2.hasNext()) {
                    Map.Entry<String, CompletableFuture<String>> entry = it2.next();
                    if (entry.getValue().isDone()) {
                        it2.remove();
                    }
                }
                
                // 限制对话历史占用内存
                if (conversations.size() > 1000) { // 如果超过1000个用户的对话
                    // 找出最旧的对话并移除
                    List<String> oldestUsers = new ArrayList<>(conversations.keySet());
                    // 保留最新的500个
                    if (oldestUsers.size() > 500) {
                        oldestUsers.subList(0, oldestUsers.size() - 500).forEach(conversations::remove);
                    }
                }
            } catch (Exception e) {
                logger.error("缓存清理任务异常", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
    }
    
    /**
     * 获取模型管理器
     */
    public ModelManager getModelManager() {
        return modelManager;
    }
    
    /**
     * 设置模型管理器
     */
    public void setModelManager(ModelManager modelManager) {
        this.modelManager = modelManager;
    }
    
    /**
     * 获取人设管理器
     */
    public PersonaManager getPersonaManager() {
        return personaManager;
    }
    
    /**
     * 设置人设管理器
     */
    public void setPersonaManager(PersonaManager personaManager) {
        this.personaManager = personaManager;
    }
    
    /**
     * 关闭资源
     */
    public void shutdown() {
        if (aiExecutor != null && !aiExecutor.isShutdown()) {
            logger.info("正在关闭AI服务线程池...");
            aiExecutor.shutdown();
            try {
                if (!aiExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    aiExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                aiExecutor.shutdownNow();
            }
            logger.info("AI服务线程池已关闭");
        }
    }
} 