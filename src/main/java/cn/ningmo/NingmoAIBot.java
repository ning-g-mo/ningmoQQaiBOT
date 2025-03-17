package cn.ningmo;

import cn.ningmo.ai.AIService;
import cn.ningmo.ai.model.ModelManager;
import cn.ningmo.ai.persona.PersonaManager;
import cn.ningmo.bot.MessageHandler;
import cn.ningmo.bot.OneBotClient;
import cn.ningmo.config.BlacklistManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.config.FilterWordManager;
import cn.ningmo.console.ConsoleCommandManager;
import cn.ningmo.gui.BotGUI;
import cn.ningmo.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class NingmoAIBot {
    private static final Logger logger = LoggerFactory.getLogger(NingmoAIBot.class);
    
    private ConfigLoader configLoader;
    private DataManager dataManager;
    private BlacklistManager blacklistManager;
    private FilterWordManager filterWordManager;
    private OneBotClient botClient;
    private AIService aiService;
    private ModelManager modelManager;
    private PersonaManager personaManager;
    private ConsoleCommandManager consoleCommandManager;
    private BotGUI botGUI;
    
    // 自动保存定时器
    private ScheduledExecutorService autoSaveScheduler;
    
    // 程序启动时间
    private final long startupTime = System.currentTimeMillis();
    
    // 内存监控服务
    private ScheduledExecutorService memoryMonitor;
    
    // 是否正在进行内存紧急回收
    private final AtomicBoolean emergencyCleanupActive = new AtomicBoolean(false);
    
    public static void main(String[] args) {
        // 设置默认编码为UTF-8，解决乱码问题
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
        
        // 设置全局异常处理
        setupUncaughtExceptionHandler();
        
        // 确保日志目录存在
        CommonUtils.ensureDirectoryExists("logs");
        
        // 创建备份目录
        CommonUtils.ensureDirectoryExists("backups");
        
        // 备份关键文件
        backupConfigFiles();
        
        // 设置系统托盘图标（如果支持）
        if (SystemTray.isSupported()) {
            setupSystemTray();
        }
        
        NingmoAIBot bot = new NingmoAIBot();
        bot.start();
    }
    
    /**
     * 设置全局未捕获异常处理器
     */
    private static void setupUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logger.error("未捕获的异常发生在线程: " + thread.getName(), throwable);
            showErrorDialog("发生未预期的错误: " + throwable.getMessage() + "\n请检查日志了解详情。", throwable);
        });
    }
    
    /**
     * 显示错误对话框
     */
    private static void showErrorDialog(String message, Throwable throwable) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(message);
            throwable.printStackTrace();
        } else {
            SwingUtilities.invokeLater(() -> {
                StringBuffer detailMessage = new StringBuffer(message + "\n\n");
                
                // 添加异常堆栈
                StackTraceElement[] stackTraces = throwable.getStackTrace();
                for (int i = 0; i < Math.min(10, stackTraces.length); i++) {
                    detailMessage.append(stackTraces[i].toString()).append("\n");
                }
                if (stackTraces.length > 10) {
                    detailMessage.append("...(更多堆栈信息请查看日志)");
                }
                
                JOptionPane.showMessageDialog(
                    null,
                    detailMessage.toString(),
                    "程序错误",
                    JOptionPane.ERROR_MESSAGE
                );
            });
        }
    }
    
    /**
     * 备份配置文件
     */
    private static void backupConfigFiles() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String timestamp = sdf.format(new Date());
            
            // 备份配置文件
            Path configFile = Paths.get("config.yml");
            if (Files.exists(configFile)) {
                Path backupPath = Paths.get("backups/config_" + timestamp + ".yml");
                Files.copy(configFile, backupPath);
                logger.info("配置文件已备份到: {}", backupPath);
            }
            
            // 备份数据文件
            Path dataFile = Paths.get("data.yml");
            if (Files.exists(dataFile)) {
                Path backupPath = Paths.get("backups/data_" + timestamp + ".yml");
                Files.copy(dataFile, backupPath);
                logger.info("数据文件已备份到: {}", backupPath);
            }
            
            // 限制备份文件数量
            limitBackupFiles("backups", 10);
            
        } catch (IOException e) {
            logger.warn("备份配置文件失败", e);
        }
    }
    
    /**
     * 限制备份文件数量
     */
    private static void limitBackupFiles(String dirPath, int maxFiles) {
        try {
            File dir = new File(dirPath);
            File[] files = dir.listFiles((d, name) -> name.startsWith("config_") || name.startsWith("data_"));
            
            if (files != null && files.length > maxFiles) {
                // 按修改时间排序
                java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));
                
                // 删除最旧的文件
                for (int i = 0; i < files.length - maxFiles; i++) {
                    if (files[i].delete()) {
                        logger.debug("删除旧备份文件: {}", files[i].getName());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("限制备份文件数量失败", e);
        }
    }
    
    private static void setupSystemTray() {
        try {
            SystemTray tray = SystemTray.getSystemTray();
            
            // 创建默认图标
            Image image = Toolkit.getDefaultToolkit().createImage(NingmoAIBot.class.getResource("/icon.png"));
            if (image == null) {
                // 如果找不到图标，使用默认的Java咖啡杯图标
                image = Toolkit.getDefaultToolkit().createImage(NingmoAIBot.class.getResource("/java.png"));
                if (image == null) {
                    logger.warn("无法加载系统托盘图标");
                    return;
                }
            }
            
            final NingmoAIBot[] botRef = new NingmoAIBot[1]; // 用于在匿名类中引用bot实例
            
            // 创建托盘图标
            TrayIcon trayIcon = new TrayIcon(image, "柠檬AI机器人");
            trayIcon.setImageAutoSize(true);
            
            // 创建弹出菜单
            PopupMenu popup = new PopupMenu();
            
            // 显示界面
            MenuItem showItem = new MenuItem("显示界面");
            showItem.addActionListener(e -> {
                if (botRef[0] != null && botRef[0].botGUI != null) {
                    botRef[0].botGUI.show();
                }
            });
            popup.add(showItem);
            
            // 退出
            MenuItem exitItem = new MenuItem("退出");
            exitItem.addActionListener(e -> {
                if (botRef[0] != null) {
                    // 保存数据
                    if (botRef[0].dataManager != null) {
                        botRef[0].dataManager.saveData();
                    }
                    if (botRef[0].blacklistManager != null) {
                        botRef[0].blacklistManager.saveBlacklist();
                    }
                    if (botRef[0].filterWordManager != null) {
                        botRef[0].filterWordManager.saveFilterWords();
                    }
                }
                
                // 退出程序
                System.exit(0);
            });
            popup.add(exitItem);
            
            // 设置托盘图标的弹出菜单
            trayIcon.setPopupMenu(popup);
            
            // 双击托盘图标打开界面
            trayIcon.addActionListener(e -> {
                if (botRef[0] != null && botRef[0].botGUI != null) {
                    botRef[0].botGUI.show();
                }
            });
            
            // 添加托盘图标
            tray.add(trayIcon);
            
            // 设置引用
            botRef[0] = null; // 将在start()方法中设置
            
            // 保存引用，便于后续管理
            NingmoAIBot.botRef = botRef;
            NingmoAIBot.trayIcon = trayIcon;
        } catch (Exception e) {
            logger.error("设置系统托盘图标失败", e);
        }
    }
    
    // 保存静态引用，便于后续管理
    private static NingmoAIBot[] botRef;
    private static TrayIcon trayIcon;
    
    public void start() {
        try {
            logger.info("正在启动柠枺AI机器人...");
            
            // 设置系统属性
            setupSystemProperties();
            
            // 确保配置目录存在
            ensureDirectoriesExist();
            
            // 加载配置和初始化服务
            initializeServices();
            
            // 启动内存监控
            startMemoryMonitor();
            
            // 连接WebSocket
            connectWebSocket();
            
            // 启动GUI界面（如果启用）
            startGUI();
            
            // 注册关闭钩子
            registerShutdownHook();
            
            // 更新系统托盘引用
            if (botRef != null) {
                botRef[0] = this;
            }
            
            // 应用启动完成
            logger.info("柠枺AI机器人已启动");
            
        } catch (Exception e) {
            logger.error("启动失败", e);
            // 清理资源
            cleanup();
            System.exit(1);
        }
    }
    
    /**
     * 设置系统属性
     */
    private void setupSystemProperties() {
        // 设置文件编码
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
        
        // 设置虚拟线程并发数上限，避免创建过多线程
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", 
                          String.valueOf(Runtime.getRuntime().availableProcessors() * 4));
        
        // 设置默认的未捕获异常处理器
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logger.error("线程 {} 发生未捕获异常", thread.getName(), throwable);
        });
    }
    
    /**
     * 确保必要的目录存在
     */
    private void ensureDirectoriesExist() {
        File configDir = new File("config");
        if (!configDir.exists() && !configDir.mkdirs()) {
            logger.warn("无法创建配置目录: {}", configDir.getAbsolutePath());
        }
        
        File dataDir = new File("data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            logger.warn("无法创建数据目录: {}", dataDir.getAbsolutePath());
        }
    }
    
    /**
     * 初始化服务
     */
    private void initializeServices() {
        try {
            // 先初始化配置加载器
            configLoader = new ConfigLoader();
            configLoader.loadConfig();
            logger.info("配置加载完成");
            
            // 初始化数据管理器
            dataManager = new DataManager(configLoader);
            dataManager.loadData();
            
            // 初始化黑名单和屏蔽词管理器
            blacklistManager = new BlacklistManager(configLoader);
            // 黑名单管理器在构造函数中会自动加载黑名单数据
            
            filterWordManager = new FilterWordManager(configLoader);
            // 屏蔽词管理器在构造函数中会自动加载屏蔽词数据
            
            // 初始化AI服务
            aiService = new AIService(configLoader, dataManager);
            
            // 初始化模型和人设管理器
            modelManager = new ModelManager(configLoader);
            personaManager = new PersonaManager(configLoader);
            
            // 设置AI服务的依赖
            aiService.setModelManager(modelManager);
            aiService.setPersonaManager(personaManager);

            // 初始化机器人客户端
            botClient = new OneBotClient(
                configLoader.getConfigString("bot.ws_url"),
                configLoader,
                dataManager,
                blacklistManager,
                filterWordManager
            );
            
            // 初始化消息处理器
            MessageHandler messageHandler = new MessageHandler(
                botClient,
                configLoader,
                dataManager,
                aiService,
                blacklistManager,
                filterWordManager
            );
            messageHandler.setModelManager(modelManager);
            messageHandler.setPersonaManager(personaManager);
            
            // 设置机器人客户端的消息处理器
            botClient.setMessageHandler(messageHandler);
            
            // 初始化控制台命令管理器
            consoleCommandManager = new ConsoleCommandManager(
                botClient,
                configLoader,
                dataManager,
                aiService,
                blacklistManager,
                filterWordManager,
                modelManager,
                personaManager
            );
            
            logger.info("服务初始化完成");
        } catch (Exception e) {
            logger.error("服务初始化失败", e);
            throw new RuntimeException("服务初始化失败", e);
        }
    }
    
    /**
     * 连接WebSocket
     */
    private void connectWebSocket() {
        try {
            // 连接WebSocket
            botClient.connect();
            logger.info("WebSocket客户端已连接");
        } catch (Exception e) {
            logger.error("连接WebSocket失败", e);
            throw new RuntimeException("连接WebSocket失败", e);
        }
    }
    
    /**
     * 启动GUI界面
     */
    private void startGUI() {
        // 判断是否启用GUI
        boolean enableGUI = configLoader.getConfigBoolean("gui.enabled");
        
        if (enableGUI) {
            // 创建并启动GUI
            botGUI = new BotGUI(botClient, configLoader, dataManager, aiService, 
                              blacklistManager, filterWordManager, modelManager, personaManager);
            
            logger.info("GUI界面已启动");
        } else {
            logger.info("GUI界面已禁用");
            
            // 如果GUI禁用，启动控制台命令管理器
            Thread.ofVirtual().name("console-command-manager").start(consoleCommandManager::start);
            
            logger.info("控制台命令管理器已启动");
        }
    }
    
    /**
     * 注册关闭钩子
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("应用正在关闭...");
            cleanup();
            logger.info("应用已安全关闭");
        }, "Shutdown-Hook"));
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        // 保存数据
        if (dataManager != null) {
            try {
                dataManager.saveData();
                logger.info("数据已保存");
            } catch (Exception e) {
                logger.error("保存数据时出错", e);
            }
        }
        
        // 保存黑名单
        if (blacklistManager != null) {
            try {
                blacklistManager.saveBlacklist();
                logger.info("黑名单已保存");
            } catch (Exception e) {
                logger.error("保存黑名单时出错", e);
            }
        }
        
        // 保存屏蔽词
        if (filterWordManager != null) {
            try {
                filterWordManager.saveFilterWords();
                logger.info("屏蔽词已保存");
            } catch (Exception e) {
                logger.error("保存屏蔽词时出错", e);
            }
        }
        
        // 关闭AI服务
        if (aiService != null) {
            try {
                aiService.shutdown();
                logger.info("AI服务已关闭");
            } catch (Exception e) {
                logger.error("关闭AI服务时出错", e);
            }
        }
        
        // 关闭消息处理器
        if (consoleCommandManager != null) {
            try {
                consoleCommandManager.shutdown();
                logger.info("消息处理器已关闭");
            } catch (Exception e) {
                logger.error("关闭消息处理器时出错", e);
            }
        }
        
        // 关闭WebSocket客户端
        if (botClient != null) {
            try {
                botClient.close();
                logger.info("WebSocket客户端已关闭");
            } catch (Exception e) {
                logger.error("关闭WebSocket客户端时出错", e);
            }
        }
        
        // 关闭GUI界面
        if (botGUI != null) {
            try {
                botGUI.shutdown();
                logger.info("GUI界面已关闭");
            } catch (Exception e) {
                logger.error("关闭GUI界面时出错", e);
            }
        }
        
        // 关闭内存监控
        if (memoryMonitor != null) {
            try {
                memoryMonitor.shutdown();
                logger.info("内存监控已关闭");
            } catch (Exception e) {
                logger.error("关闭内存监控时出错", e);
            }
        }
    }
    
    /**
     * 显示GUI界面
     */
    public void showGUI() {
        if (botGUI != null) {
            botGUI.show();
        }
    }
    
    /**
     * 获取程序运行时间（毫秒）
     */
    public long getUptime() {
        return System.currentTimeMillis() - startupTime;
    }
    
    /**
     * 启动内存监控
     */
    private void startMemoryMonitor() {
        // 创建单线程调度器
        memoryMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Memory-Monitor");
            t.setDaemon(true);
            return t;
        });
        
        // 定期监控内存使用情况
        memoryMonitor.scheduleAtFixedRate(() -> {
            try {
                monitorMemory();
            } catch (Exception e) {
                logger.error("内存监控异常", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        logger.info("内存监控服务已启动");
    }
    
    /**
     * 监控内存使用情况
     */
    private void monitorMemory() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        long availableMemory = maxMemory - usedMemory;
        
        // 计算内存使用百分比
        double memoryUsagePercent = ((double) usedMemory / maxMemory) * 100;
        
        logger.debug("内存使用情况: 已用 {}MB / 最大 {}MB ({}%)", 
                   usedMemory, maxMemory, String.format("%.1f", memoryUsagePercent));
        
        // 内存警告阈值
        if (memoryUsagePercent > 75) {
            logger.warn("内存使用率较高: {}%, 剩余: {}MB", 
                       String.format("%.1f", memoryUsagePercent), availableMemory);
        }
        
        // 内存紧急情况
        if (memoryUsagePercent > 85 && !emergencyCleanupActive.get()) {
            // 标记正在进行紧急清理
            if (emergencyCleanupActive.compareAndSet(false, true)) {
                logger.error("内存使用率过高: {}%, 剩余: {}MB, 开始紧急资源释放", 
                           String.format("%.1f", memoryUsagePercent), availableMemory);
                
                // 执行紧急资源释放
                performEmergencyCleanup();
                
                // 完成紧急清理
                emergencyCleanupActive.set(false);
            }
        }
    }
    
    /**
     * 执行紧急资源释放
     */
    private void performEmergencyCleanup() {
        try {
            // 请求垃圾回收
            System.gc();
            
            // 清理服务缓存
            if (aiService != null) {
                // 清理AIService的对话历史等缓存
                // 如果AIService有提供缓存清理方法，可以在这里调用
                // 例如：aiService.clearCache();
            }
            
            // 记录清理后的内存情况
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory() / (1024 * 1024);
            long freeMemory = runtime.freeMemory() / (1024 * 1024);
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory() / (1024 * 1024);
            
            double memoryUsagePercent = ((double) usedMemory / maxMemory) * 100;
            
            logger.info("紧急资源释放完成，当前内存使用率: {}%, 已用: {}MB / 最大: {}MB", 
                       String.format("%.1f", memoryUsagePercent), usedMemory, maxMemory);
            
        } catch (Exception e) {
            logger.error("执行紧急资源释放时出错", e);
        }
    }
} 