package cn.ningmo.bot;

import cn.ningmo.ai.AIService;
import cn.ningmo.config.BlacklistManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.config.FilterWordManager;
import cn.ningmo.utils.CommonUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public class OneBotClient extends WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(OneBotClient.class);
    
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final BlacklistManager blacklistManager;
    private final FilterWordManager filterWordManager;
    private MessageHandler messageHandler;
    private final AIService aiService;
    
    // 心跳计时器
    private Timer heartbeatTimer;
    // 重连计时器
    private Timer reconnectTimer;
    // 消息ID计数器
    private final AtomicInteger messageIdCounter = new AtomicInteger(0);
    // 连接状态
    private boolean isReconnecting = false;
    // 最大单条消息长度
    private static final int MAX_MESSAGE_LENGTH = 3000;
    
    // 群成员信息缓存
    private final Map<String, List<Map<String, String>>> groupMembersCache = new ConcurrentHashMap<>();
    
    private final long startupTime = System.currentTimeMillis();
    
    // 消息发送频率限制 - 时间窗口内允许的最大消息数
    private static final int MAX_MESSAGES_PER_WINDOW = 10;
    // 时间窗口大小（毫秒）
    private static final long RATE_LIMIT_WINDOW = 10000; // 10秒
    
    // 存储每个目标的消息发送记录
    private final Map<String, Deque<Long>> messageSendTimes = new ConcurrentHashMap<>();
    
    public OneBotClient(String serverUri, ConfigLoader configLoader, DataManager dataManager, BlacklistManager blacklistManager, FilterWordManager filterWordManager) {
        super(createURI(serverUri), createHeaders(configLoader));
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.blacklistManager = blacklistManager;
        this.filterWordManager = filterWordManager;
        this.aiService = new AIService(configLoader, dataManager);
        this.messageHandler = new MessageHandler(this, configLoader, dataManager, aiService, blacklistManager, filterWordManager);
        
        // 设置连接超时
        this.setConnectionLostTimeout(60); // 60秒
    }
    
    private static URI createURI(String serverUri) {
        try {
            return new URI(serverUri);
        } catch (URISyntaxException e) {
            logger.error("无效的WebSocket URI: {}", serverUri, e);
            throw new RuntimeException("无效的WebSocket URI", e);
        }
    }
    
    /**
     * 创建WebSocket连接头，包含认证信息
     */
    private static Map<String, String> createHeaders(ConfigLoader configLoader) {
        Map<String, String> headers = new HashMap<>();
        
        // 获取访问令牌
        String accessToken = configLoader.getConfigString("bot.access_token", "");
        
        if (!accessToken.isEmpty()) {
            // 添加Authorization头，使用Bearer认证
            headers.put("Authorization", "Bearer " + accessToken);
            logger.debug("已添加OneBot访问令牌到WebSocket连接头");
        } else {
            logger.debug("未配置OneBot访问令牌，使用匿名连接");
        }
        
        // 添加User-Agent头
        headers.put("User-Agent", "NingmoAIBot/1.0");
        
        return headers;
    }
    
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        String accessToken = configLoader.getConfigString("bot.access_token", "");
        if (!accessToken.isEmpty()) {
            logger.info("WebSocket连接已建立（使用访问令牌认证），状态码：{}", handshakedata.getHttpStatus());
        } else {
            logger.info("WebSocket连接已建立（匿名连接），状态码：{}", handshakedata.getHttpStatus());
        }
        
        // 停止重连定时器
        stopReconnectTimer();
        
        // 发送获取机器人信息的请求
        sendGetLoginInfo();
        
        // 启动心跳
        startHeartbeat();
        
        // 启动消息频率限制清理任务
        startRateLimitCleanup();
    }
    
    @Override
    public void onMessage(String message) {
        try {
            // 快速过滤心跳响应，不需要进行完整JSON解析
            if (message.contains("\"echo\":\"heartbeat")) {
                logger.trace("收到心跳响应");
                return;
            }
            
            // 解析JSON消息
            JSONObject json = new JSONObject(message);
            
            // 处理心跳响应 - 冗余检查，保证兼容性
            if (json.has("echo") && json.getString("echo").startsWith("heartbeat")) {
                logger.trace("收到心跳响应");
                return;
            }
            
            // 异步处理消息，避免阻塞WebSocket线程
            if (json.has("post_type")) {
                // 创建消息的副本，避免线程间共享可变对象
                final JSONObject jsonCopy = new JSONObject(json.toString());
                
                // 使用虚拟线程池处理消息
                final Thread virtualThread = Thread.ofVirtual()
                    .name("message-handler-" + System.currentTimeMillis())
                    .start(() -> {
                        try {
                            String postType = jsonCopy.getString("post_type");
                            
                            switch (postType) {
                                case "message":
                                    messageHandler.handleMessage(jsonCopy);
                                    break;
                                case "meta_event":
                                    handleMetaEvent(jsonCopy);
                                    break;
                                default:
                                    // 记录其他类型的事件
                                    logger.debug("收到其他类型事件: {}", postType);
                                    break;
                            }
                        } catch (Exception e) {
                            logger.error("处理消息事件时出错", e);
                        }
                    });
            }
        } catch (Exception e) {
            logger.warn("解析WebSocket消息时出错: {}", e.getMessage());
            logger.debug("问题消息内容: {}", message.length() > 200 ? message.substring(0, 200) + "..." : message);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("WebSocket连接已关闭: code={}, reason={}, remote={}", code, reason, remote);
        
        // 停止心跳
        stopHeartbeat();
        
        // 尝试重新连接
        if (!isReconnecting) {
            startReconnectTimer();
        }
    }
    
    @Override
    public void onError(Exception ex) {
        logger.error("WebSocket错误", ex);
    }
    
    /**
     * 启动心跳定时器
     */
    private void startHeartbeat() {
        stopHeartbeat(); // 先停止已有的定时器
        
        heartbeatTimer = new Timer("HeartbeatTimer");
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (isOpen()) {
                    sendHeartbeat();
                }
            }
        }, 0, 30000); // 30秒一次心跳
        
        logger.debug("心跳定时器已启动");
    }
    
    /**
     * 停止心跳定时器
     */
    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
            logger.debug("心跳定时器已停止");
        }
    }
    
    /**
     * 发送心跳包
     */
    private void sendHeartbeat() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("action", "get_status");
        jsonObject.put("echo", "heartbeat" + System.currentTimeMillis());
        
        send(jsonObject.toString());
        logger.trace("发送心跳包");
    }
    
    /**
     * 获取登录信息
     */
    private void sendGetLoginInfo() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("action", "get_login_info");
        jsonObject.put("echo", "get_login_info");
        
        send(jsonObject.toString());
        logger.debug("发送获取登录信息请求");
    }
    
    /**
     * 启动重连定时器
     */
    private void startReconnectTimer() {
        stopReconnectTimer(); // 先停止已有的定时器
        
        isReconnecting = true;
        reconnectTimer = new Timer("ReconnectTimer");
        reconnectTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                logger.info("尝试重新连接...");
                try {
                    reconnect();
                } catch (Exception e) {
                    logger.error("重连失败", e);
                    // 继续尝试
                    startReconnectTimer();
                }
            }
        }, 5000); // 5秒后尝试重连
    }
    
    /**
     * 停止重连定时器
     */
    private void stopReconnectTimer() {
        if (reconnectTimer != null) {
            reconnectTimer.cancel();
            reconnectTimer = null;
            isReconnecting = false;
            logger.debug("重连定时器已停止");
        }
    }
    
    /**
     * 检查私聊功能是否启用
     */
    public boolean isPrivateMessageEnabled() {
        return configLoader.getConfigBoolean("bot.enable_private_message");
    }
    
    /**
     * 发送群消息（支持自动分段）
     * 如果消息过长，会自动分成多段发送
     */
    public void sendGroupMessage(String groupId, String message) {
        if (message == null || message.trim().isEmpty()) {
            logger.warn("尝试发送空消息到群: {}", groupId);
            return;
        }
        
        // 检查消息发送频率限制
        if (!checkMessageRateLimit("group:" + groupId)) {
            logger.warn("群{}消息发送频率过高，消息已丢弃", groupId);
            return;
        }
        
        // 如果消息长度小于最大长度，直接发送
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            sendSingleGroupMessage(groupId, message);
            return;
        }
        
        // 消息过长，需要分段发送
        List<String> segments = splitMessage(message);
        
        // 限制分段数量，防止消息轰炸
        int maxSegments = Math.min(segments.size(), 3);
        
        for (int i = 0; i < maxSegments; i++) {
            String segment = segments.get(i);
            sendSingleGroupMessage(groupId, segment);
            
            // 添加短暂延迟，避免频繁发送导致的风控
            if (i < maxSegments - 1) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("发送消息线程被中断");
                    break;
                }
            }
        }
        
        // 如果有更多分段未发送，添加提示
        if (segments.size() > maxSegments) {
            logger.info("消息过长，只发送了前{}段，共{}段", maxSegments, segments.size());
            sendSingleGroupMessage(groupId, "……(余下内容过长，已省略)");
        }
    }
    
    /**
     * 发送单条群消息
     */
    private void sendSingleGroupMessage(String groupId, String message) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("action", "send_group_msg");
        
        JSONObject params = new JSONObject();
        params.put("group_id", groupId);
        params.put("message", message);
        
        jsonObject.put("params", params);
        jsonObject.put("echo", "send_group_msg_" + messageIdCounter.incrementAndGet());
        
        send(jsonObject.toString());
        logger.debug("发送群消息: groupId={}, message={}", groupId, CommonUtils.truncateText(message, 100));
    }
    
    /**
     * 发送私聊消息（如果启用）
     */
    public void sendPrivateMessage(String userId, String message) {
        // 检查私聊功能是否启用
        if (!isPrivateMessageEnabled()) {
            logger.info("私聊功能已关闭，不发送消息给用户: {}", userId);
            return;
        }
        
        if (message == null || message.trim().isEmpty()) {
            logger.warn("尝试发送空消息到用户: {}", userId);
            return;
        }
        
        // 如果消息长度小于最大长度，直接发送
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            sendSinglePrivateMessage(userId, message);
            return;
        }
        
        // 消息过长，需要分段发送
        List<String> segments = splitMessage(message);
        for (String segment : segments) {
            sendSinglePrivateMessage(userId, segment);
            
            // 添加短暂延迟，避免频繁发送导致的风控
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("发送消息线程被中断");
            }
        }
    }
    
    /**
     * 发送单条私聊消息
     */
    private void sendSinglePrivateMessage(String userId, String message) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("action", "send_private_msg");
        
        JSONObject params = new JSONObject();
        params.put("user_id", userId);
        params.put("message", message);
        
        jsonObject.put("params", params);
        jsonObject.put("echo", "send_private_msg_" + messageIdCounter.incrementAndGet());
        
        send(jsonObject.toString());
        logger.debug("发送私聊消息: userId={}, message={}", userId, CommonUtils.truncateText(message, 100));
    }
    
    /**
     * 将长消息分割成多段
     */
    private List<String> splitMessage(String message) {
        List<String> segments = new ArrayList<>();
        
        // 尝试在段落或句子处分割消息
        int startIndex = 0;
        while (startIndex < message.length()) {
            int endIndex = Math.min(startIndex + MAX_MESSAGE_LENGTH, message.length());
            
            // 如果没有到达消息末尾，尝试在适当位置分割
            if (endIndex < message.length()) {
                // 尝试在段落处分割
                int paragraphBreak = message.lastIndexOf("\n\n", endIndex);
                if (paragraphBreak > startIndex && paragraphBreak > endIndex - 500) {
                    endIndex = paragraphBreak;
                } else {
                    // 尝试在句子处分割
                    int sentenceBreak = findLastSentenceBreak(message, startIndex, endIndex);
                    if (sentenceBreak > startIndex) {
                        endIndex = sentenceBreak;
                    }
                }
            }
            
            segments.add(message.substring(startIndex, endIndex).trim());
            startIndex = endIndex;
        }
        
        return segments;
    }
    
    /**
     * 查找最后一个句子分隔符的位置
     */
    private int findLastSentenceBreak(String message, int startIndex, int endIndex) {
        // 常见的句子分隔符
        String[] separators = {"。", "！", "？", "…", ".", "!", "?"};
        
        int lastBreak = startIndex;
        for (String separator : separators) {
            int pos = message.lastIndexOf(separator, endIndex);
            if (pos > lastBreak && pos > startIndex) {
                lastBreak = pos + separator.length();
            }
        }
        
        return lastBreak > startIndex ? lastBreak : -1;
    }
    
    /**
     * 获取群成员信息
     */
    public void getGroupMemberInfo(String groupId, String userId) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("action", "get_group_member_info");
        
        JSONObject params = new JSONObject();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        
        jsonObject.put("params", params);
        jsonObject.put("echo", "get_group_member_info_" + messageIdCounter.incrementAndGet());
        
        send(jsonObject.toString());
        logger.debug("获取群成员信息: groupId={}, userId={}", groupId, userId);
    }
    
    /**
     * 获取群成员列表
     */
    public void getGroupMemberList(String groupId) {
        try {
            JSONObject request = new JSONObject();
            request.put("action", "get_group_member_list");
            request.put("params", new JSONObject().put("group_id", Long.parseLong(groupId)));
            request.put("echo", "get_group_member_list:" + groupId);
            
            send(request.toString());
            logger.debug("已发送获取群 {} 成员列表请求", groupId);
        } catch (Exception e) {
            logger.error("发送获取群成员列表请求失败", e);
        }
    }
    
    // 解析并缓存群成员信息
    private void parseAndCacheGroupMembers(String groupId, JSONArray members) {
        try {
            List<Map<String, String>> memberList = new ArrayList<>();
            
            for (int i = 0; i < members.length(); i++) {
                JSONObject member = members.getJSONObject(i);
                Map<String, String> memberInfo = new HashMap<>();
                
                memberInfo.put("user_id", CommonUtils.safeGetString(member, "user_id"));
                memberInfo.put("nickname", CommonUtils.safeGetString(member, "nickname"));
                memberInfo.put("card", CommonUtils.safeGetString(member, "card"));  // 群名片
                memberInfo.put("role", CommonUtils.safeGetString(member, "role"));  // 角色: owner, admin, member
                
                memberList.add(memberInfo);
            }
            
            groupMembersCache.put(groupId, memberList);
            logger.debug("已缓存群 {} 的成员列表，共 {} 人", groupId, memberList.size());
        } catch (Exception e) {
            logger.error("解析群成员信息失败", e);
        }
    }
    
    // 添加获取群成员列表的方法
    public List<Map<String, String>> getGroupMembers(String groupId) {
        return groupMembersCache.getOrDefault(groupId, new ArrayList<>());
    }
    
    /**
     * 同步获取群成员信息
     * @param groupId 群ID
     * @param userId 用户ID
     * @return 群成员信息，如果失败则返回null
     */
    public JSONObject getGroupMemberInfoSync(String groupId, String userId) {
        try {
            // 构建API请求
            JSONObject params = new JSONObject();
            params.put("group_id", groupId);
            params.put("user_id", userId);
            params.put("no_cache", true);
            
            String echo = "get_group_member_info_sync_" + System.currentTimeMillis();
            JSONObject result = sendApiRequestSync("get_group_member_info", params, echo, 3000);
            
            if (result != null && result.has("data")) {
                return result.getJSONObject("data");
            }
            return null;
        } catch (Exception e) {
            logger.error("同步获取群成员信息失败: groupId={}, userId={}", groupId, userId, e);
            return null;
        }
    }
    
    /**
     * 发送同步API请求并等待响应
     * @param action API动作
     * @param params 参数
     * @param echo 请求标识
     * @param timeoutMs 超时时间(毫秒)
     * @return 响应对象，如果超时或失败则返回null
     */
    private JSONObject sendApiRequestSync(String action, JSONObject params, String echo, long timeoutMs) {
        try {
            final JSONObject[] response = {null};
            final Object lock = new Object();
            
            // 创建临时的消息监听器
            Runnable messageCallback = () -> {
                // 在指定时间内接收消息，寻找匹配的echo
                long endTime = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < endTime) {
                    try {
                        synchronized (lock) {
                            // 如果已经收到响应，退出循环
                            if (response[0] != null) {
                                break;
                            }
                            // 等待一小段时间
                            lock.wait(100);
                        }
                    } catch (InterruptedException e) {
                        logger.warn("等待API响应被中断", e);
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            };

            // 添加一个临时监听器用于此次请求
            Thread listenerThread = new Thread(messageCallback);
            listenerThread.setDaemon(true); // 设置为守护线程，不阻止JVM退出
            
            // 发送请求
            JSONObject request = new JSONObject();
            request.put("action", action);
            request.put("params", params);
            request.put("echo", echo);
            
            String requestJson = request.toString();
            if (isOpen()) {
                // 在发送请求前启动监听线程
                listenerThread.start();
                
                // 发送请求
                send(requestJson);
                logger.debug("发送同步API请求: {}, echo: {}", action, echo);
                
                // 等待响应到达
                try {
                    listenerThread.join(timeoutMs);
                } catch (InterruptedException e) {
                    logger.warn("等待响应线程被中断", e);
                    Thread.currentThread().interrupt();
                }
                
                if (response[0] == null) {
                    logger.warn("API请求超时: {}, echo: {}", action, echo);
                }
            } else {
                logger.error("WebSocket连接未建立，无法发送同步请求: {}", action);
                return null;
            }
            
            return response[0];
        } catch (Exception e) {
            logger.error("发送同步API请求失败: {}", action, e);
            return null;
        }
    }
    
    /**
     * 处理接收到的消息，用于监听API响应
     * 
     * @param message 接收到的消息
     * @param echo 请求标识
     * @param response 响应容器
     * @param lock 同步锁
     * @return 是否是匹配的响应
     */
    private boolean handleApiResponse(String message, String echo, JSONObject[] response, Object lock) {
        try {
            JSONObject json = new JSONObject(message);
            if (json.has("echo") && json.getString("echo").equals(echo)) {
                synchronized (lock) {
                    response[0] = json;
                    lock.notify();
                    return true;
                }
            }
        } catch (Exception e) {
            // 忽略非JSON消息
        }
        return false;
    }
    
    /**
     * 获取机器人启动时间
     * @return 启动时间（毫秒）
     */
    public long getStartupTime() {
        return startupTime;
    }
    
    @Override
    public void close() {
        // 停止定时器
        stopHeartbeat();
        stopReconnectTimer();
        
        // 调用父类方法关闭连接
        super.close();
    }
    
    /**
     * 处理元事件消息
     * @param metaEvent 元事件消息
     */
    private void handleMetaEvent(JSONObject metaEvent) {
        if (!metaEvent.has("meta_event_type")) {
            logger.warn("接收到元事件但缺少meta_event_type字段: {}", metaEvent);
            return;
        }
        
        String metaEventType = metaEvent.getString("meta_event_type");
        logger.debug("处理元事件: {}", metaEventType);
        
        switch (metaEventType) {
            case "heartbeat":
                // 心跳事件，可以用来更新机器人状态
                if (metaEvent.has("status")) {
                    // 可以在这里处理状态信息
                    logger.trace("收到心跳包，机器人状态正常");
                }
                break;
                
            case "lifecycle":
                // 生命周期事件，如连接成功
                logger.info("收到生命周期事件: {}", metaEvent.optString("sub_type", "unknown"));
                break;
                
            default:
                logger.debug("收到未处理的元事件类型: {}", metaEventType);
                break;
        }
    }
    
    /**
     * 设置消息处理器
     * @param messageHandler 消息处理器
     */
    public void setMessageHandler(MessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }
    
    /**
     * 检查消息发送频率是否超过限制
     * @param target 消息目标标识（群ID或用户ID）
     * @return 是否允许发送
     */
    private boolean checkMessageRateLimit(String target) {
        long now = System.currentTimeMillis();
        
        // 获取或创建消息发送时间队列
        Deque<Long> sendTimes = messageSendTimes.computeIfAbsent(target, k -> new ConcurrentLinkedDeque<>());
        
        // 清理过期的时间戳
        while (!sendTimes.isEmpty() && now - sendTimes.peekFirst() > RATE_LIMIT_WINDOW) {
            sendTimes.pollFirst();
        }
        
        // 检查是否超过频率限制
        if (sendTimes.size() >= MAX_MESSAGES_PER_WINDOW) {
            return false;
        }
        
        // 添加当前时间戳
        sendTimes.addLast(now);
        return true;
    }
    
    /**
     * 定期清理过期的消息发送记录
     */
    private void startRateLimitCleanup() {
        Timer timer = new Timer("RateLimit-Cleanup", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    long now = System.currentTimeMillis();
                    
                    // 清理过期的发送记录
                    messageSendTimes.entrySet().removeIf(entry -> {
                        Deque<Long> times = entry.getValue();
                        // 清理过期的时间戳
                        while (!times.isEmpty() && now - times.peekFirst() > RATE_LIMIT_WINDOW) {
                            times.pollFirst();
                        }
                        // 如果队列为空，移除整个条目
                        return times.isEmpty();
                    });
                } catch (Exception e) {
                    logger.error("清理消息频率限制记录时出错", e);
                }
            }
        }, RATE_LIMIT_WINDOW, RATE_LIMIT_WINDOW);
    }
} 