package cn.ningmo;

import cn.ningmo.bot.OneBotClient;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NingmoAIBot {
    private static final Logger logger = LoggerFactory.getLogger(NingmoAIBot.class);
    
    private ConfigLoader configLoader;
    private DataManager dataManager;
    private OneBotClient botClient;
    
    public static void main(String[] args) {
        NingmoAIBot bot = new NingmoAIBot();
        bot.start();
    }
    
    public void start() {
        logger.info("宁默AI机器人启动中...");
        
        // 加载配置
        configLoader = new ConfigLoader();
        configLoader.loadConfig();
        
        // 加载数据
        dataManager = new DataManager();
        dataManager.loadData();
        
        // 启动机器人
        String wsUrl = configLoader.getConfigString("bot.ws_url");
        botClient = new OneBotClient(wsUrl, configLoader, dataManager);
        botClient.connect();
        
        logger.info("宁默AI机器人启动完成！");
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("正在关闭宁默AI机器人...");
            botClient.close();
            dataManager.saveData();
            logger.info("宁默AI机器人已关闭");
        }));
    }
} 