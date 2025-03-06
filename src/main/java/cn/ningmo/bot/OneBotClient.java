package cn.ningmo.bot;

import cn.ningmo.ai.AIService;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.utils.CommonUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

public class OneBotClient extends WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(OneBotClient.class);
    
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
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
    
    public OneBotClient(String serverUri, ConfigLoader configLoader, DataManager dataManager) {
        super(createURI(serverUri));
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.aiService = new AIService(configLoader, dataManager);
        this.messageHandler = new MessageHandler(this, configLoader, dataManager, aiService);
        
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
            JSONObject jsonObject = new JSONObject(message);
            
            // 只处理消息事件
            if (jsonObject.has("post_type") && "message".equals(jsonObject.getString("post_type"))) {
                logger.debug("收到消息事件: {}", CommonUtils.truncateText(message, 200));
                messageHandler.handleMessage(jsonObject);
            }
            // 处理心跳响应
            else if (jsonObject.has("echo") && jsonObject.getString("echo").startsWith("heartbeat")) {
                logger.trace("收到心跳响应");
            }
            // 处理登录信息响应
            else if (jsonObject.has("echo") && "get_login_info".equals(jsonObject.getString("echo"))
                    && jsonObject.has("data")) {
                JSONObject data = jsonObject.getJSONObject("data");
                if (data.has("user_id")) {
                    String userId = String.valueOf(data.get("user_id"));
                    String nickname = data.optString("nickname", "机器人");
                    logger.info("机器人登录信息: QQ={}, 昵称={}", userId, nickname);
                }
            }
            // 其他事件
            else if (jsonObject.has("post_type")) {
                logger.debug("收到其他事件: {}", jsonObject.getString("post_type"));
            }
            // API响应
            else if (jsonObject.has("status") && jsonObject.has("retcode")) {
                int retcode = jsonObject.getInt("retcode");
                if (retcode != 0) {
                    logger.warn("API调用返回错误: code={}, status={}",
                            retcode, jsonObject.getString("status"));
                }
            }
        } catch (Exception e) {
            logger.error("处理消息时出错: {}", message, e);
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
     * 发送群消息
     */
    public void sendGroupMessage(String groupId, String message) {
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
     * 发送私聊消息
     */
    public void sendPrivateMessage(String userId, String message) {
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
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("action", "get_group_member_list");
        
        JSONObject params = new JSONObject();
        params.put("group_id", groupId);
        
        jsonObject.put("params", params);
        jsonObject.put("echo", "get_group_member_list_" + messageIdCounter.incrementAndGet());
        
        send(jsonObject.toString());
        logger.debug("获取群成员列表: groupId={}", groupId);
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