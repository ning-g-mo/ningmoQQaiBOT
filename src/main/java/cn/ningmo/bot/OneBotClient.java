package cn.ningmo.bot;

import cn.ningmo.ai.AIService;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;

public class OneBotClient extends WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(OneBotClient.class);
    
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final MessageHandler messageHandler;
    private final AIService aiService;
    
    public OneBotClient(String serverUri, ConfigLoader configLoader, DataManager dataManager) {
        super(createURI(serverUri));
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.aiService = new AIService(configLoader, dataManager);
        this.messageHandler = new MessageHandler(this, configLoader, dataManager, aiService);
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
        logger.info("WebSocket连接已建立");
    }
    
    @Override
    public void onMessage(String message) {
        try {
            JSONObject jsonObject = new JSONObject(message);
            
            // 只处理消息事件
            if (jsonObject.has("post_type") && "message".equals(jsonObject.getString("post_type"))) {
                messageHandler.handleMessage(jsonObject);
            }
        } catch (Exception e) {
            logger.error("处理消息时出错", e);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("WebSocket连接已关闭: code={}, reason={}, remote={}", code, reason, remote);
        
        // 尝试重新连接
        if (remote) {
            logger.info("尝试重新连接...");
            reconnect();
        }
    }
    
    @Override
    public void onError(Exception ex) {
        logger.error("WebSocket错误", ex);
    }
    
    public void sendGroupMessage(String groupId, String message) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("action", "send_group_msg");
        
        JSONObject params = new JSONObject();
        params.put("group_id", groupId);
        params.put("message", message);
        
        jsonObject.put("params", params);
        
        send(jsonObject.toString());
        logger.debug("发送群消息: groupId={}, message={}", groupId, message);
    }
    
    public void sendPrivateMessage(String userId, String message) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("action", "send_private_msg");
        
        JSONObject params = new JSONObject();
        params.put("user_id", userId);
        params.put("message", message);
        
        jsonObject.put("params", params);
        
        send(jsonObject.toString());
        logger.debug("发送私聊消息: userId={}, message={}", userId, message);
    }
} 