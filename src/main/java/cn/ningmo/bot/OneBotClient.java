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

public class OneBotClient extends WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(OneBotClient.class);
    
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final BlacklistManager blacklistManager;
    private final FilterWordManager filterWordManager;
    private final MessageHandler messageHandler;
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
    
    public OneBotClient(String serverUri, ConfigLoader configLoader, DataManager dataManager, BlacklistManager blacklistManager, FilterWordManager filterWordManager) {
        super(createURI(serverUri));
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
    
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("WebSocket连接已建立，状态码：{}", handshakedata.getHttpStatus());
        
        // 停止重连定时器
        stopReconnectTimer();
        
        // 发送获取机器人信息的请求
        sendGetLoginInfo();
        
        // 启动心跳
        startHeartbeat();
    }
    
    @Override
    public void onMessage(String message) {
        try {
            // 记录收到的消息原始内容（仅在debug级别）
            if (logger.isDebugEnabled()) {
                logger.debug("收到WebSocket消息: {}", message);
            }
            
            JSONObject json = new JSONObject(message);
            
            // 处理群成员列表响应
            if (json.has("post_type") && "message_sent".equals(json.getString("post_type")) && 
                json.has("echo") && json.getString("echo").startsWith("get_group_member_list:")) {
                
                if (json.has("data") && json.get("data") instanceof JSONArray) {
                    String groupId = json.getString("echo").substring("get_group_member_list:".length());
                    JSONArray members = json.getJSONArray("data");
                    parseAndCacheGroupMembers(groupId, members);
                }
                return;
            }
            
            // 处理心跳响应
            if (json.has("echo") && "heartbeat".equals(json.optString("echo"))) {
                logger.debug("收到心跳响应");
                return;
            }
            
            // 如果是元事件，不做处理
            if (json.has("post_type") && "meta_event".equals(json.getString("post_type"))) {
                return;
            }
            
            // 如果消息类型有问题，增加日志记录
            if (!json.has("post_type") || !json.has("message_type")) {
                logger.warn("收到格式异常的消息: {}", message);
                return;
            }
            
            // 消息处理
            if (json.has("post_type") && "message".equals(json.getString("post_type"))) {
                messageHandler.handleMessage(json);
            }
            
            // 处理群成员列表响应
            if (json.has("data") && json.has("echo") && json.getString("echo").startsWith("get_group_member_list")) {
                String groupId = json.getString("echo").substring("get_group_member_list:".length());
                JSONArray members = json.getJSONArray("data");
                parseAndCacheGroupMembers(groupId, members);
                return;
            }
            
            // 其他响应
            logger.debug("收到其他事件: {}", message);
        } catch (Exception e) {
            logger.error("处理WebSocket消息时出错", e);
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
        
        // 如果消息长度小于最大长度，直接发送
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            sendSingleGroupMessage(groupId, message);
            return;
        }
        
        // 消息过长，需要分段发送
        List<String> segments = splitMessage(message);
        for (String segment : segments) {
            sendSingleGroupMessage(groupId, segment);
            
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
    
    @Override
    public void close() {
        // 停止定时器
        stopHeartbeat();
        stopReconnectTimer();
        
        // 调用父类方法关闭连接
        super.close();
    }
} 