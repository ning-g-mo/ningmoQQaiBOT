package cn.ningmo;

import cn.ningmo.bot.OneBotClient;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NingmoAIBot {
    private static final Logger logger = LoggerFactory.getLogger(NingmoAIBot.class);
    
    private ConfigLoader configLoader;
    private DataManager dataManager;
    private OneBotClient botClient;
    
    public static void main(String[] args) {
        // 设置默认编码为UTF-8，解决乱码问题
        System.setProperty("file.encoding", "UTF-8");
        
        // 确保日志目录存在
        CommonUtils.ensureDirectoryExists("logs");
        
        NingmoAIBot bot = new NingmoAIBot();
        bot.start();
    }
    
    public void start() {
        logger.info("柠枺AI机器人启动中...");
        logger.info("当前Java版本: {}", System.getProperty("java.version"));
        
        // 加载配置
        configLoader = new ConfigLoader();
        configLoader.loadConfig();
        
        // 加载数据
        dataManager = new DataManager(configLoader);
        dataManager.loadData();
        
        // 启动机器人
        String wsUrl = configLoader.getConfigString("bot.ws_url");
        logger.info("正在连接OneBot服务器: {}", wsUrl);
        
        botClient = new OneBotClient(wsUrl, configLoader, dataManager);
        botClient.connect();
        
        logger.info("柠枺AI机器人启动完成！");
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("正在关闭柠枺AI机器人...");
            botClient.close();
            dataManager.saveData();
            logger.info("柠枺AI机器人已关闭");
        }));
    }
} 