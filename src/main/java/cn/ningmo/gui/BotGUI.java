package cn.ningmo.gui;

import cn.ningmo.ai.AIService;
import cn.ningmo.ai.model.ModelManager;
import cn.ningmo.ai.persona.PersonaManager;
import cn.ningmo.bot.OneBotClient;
import cn.ningmo.config.BlacklistManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.config.FilterWordManager;
import cn.ningmo.console.ConsoleCommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 机器人GUI主界面
 */
public class BotGUI {
    private static final Logger logger = LoggerFactory.getLogger(BotGUI.class);
    
    // 主窗口
    private JFrame mainFrame;
    // 状态标签
    private JLabel statusLabel;
    // 状态面板
    private JPanel statusPanel;
    // 日志文本区域
    private JTextArea logArea;
    // 标签页
    private JTabbedPane tabbedPane;
    
    // 当前状态
    private boolean connected = false;
    // 更新定时器
    private Timer updateTimer;
    
    // 系统服务
    private final OneBotClient botClient;
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final AIService aiService;
    private final BlacklistManager blacklistManager;
    private final FilterWordManager filterWordManager;
    private final ModelManager modelManager;
    private final PersonaManager personaManager;
    
    // 子面板
    private GroupPanel groupPanel;
    private MessagePanel messagePanel;
    private BlacklistPanel blacklistPanel;
    private FilterWordPanel filterWordPanel;
    private ModelPanel modelPanel;
    private PersonaPanel personaPanel;
    private ConsolePanel consolePanel;
    
    // 日志队列
    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    // 日志处理线程运行标记
    private final AtomicBoolean logProcessorRunning = new AtomicBoolean(false);
    // 完整日志缓存，用于过滤功能
    private final List<String> completeLogHistory = new ArrayList<>();
    
    /**
     * 构造函数
     */
    public BotGUI(OneBotClient botClient, ConfigLoader configLoader, DataManager dataManager, 
                  AIService aiService, BlacklistManager blacklistManager, FilterWordManager filterWordManager,
                  ModelManager modelManager, PersonaManager personaManager) {
        this.botClient = botClient;
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.aiService = aiService;
        this.blacklistManager = blacklistManager;
        this.filterWordManager = filterWordManager;
        this.modelManager = modelManager;
        this.personaManager = personaManager;
        
        // 在事件调度线程中初始化GUI
        SwingUtilities.invokeLater(this::initGUI);
    }
    
    /**
     * 初始化GUI
     */
    private void initGUI() {
        try {
            // 设置系统外观
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.error("设置系统外观失败", e);
        }
        
        // 创建主窗口
        mainFrame = new JFrame("柠檬AI机器人控制面板");
        mainFrame.setSize(900, 600);
        mainFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        mainFrame.setLocationRelativeTo(null); // 居中显示
        
        // 添加窗口关闭事件
        mainFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int result = JOptionPane.showConfirmDialog(
                    mainFrame,
                    "关闭窗口将只会隐藏界面，不会退出程序。\n如需完全退出程序，请使用「系统」->「退出」菜单。\n确定要关闭窗口吗？",
                    "关闭确认",
                    JOptionPane.YES_NO_OPTION
                );
                
                if (result == JOptionPane.YES_OPTION) {
                    mainFrame.setVisible(false);
                }
            }
        });
        
        // 创建菜单栏
        JMenuBar menuBar = createMenuBar();
        mainFrame.setJMenuBar(menuBar);
        
        // 创建主面板
        JPanel mainPanel = new JPanel(new BorderLayout());
        
        // 创建状态面板
        statusPanel = createStatusPanel();
        mainPanel.add(statusPanel, BorderLayout.NORTH);
        
        // 创建标签页面板
        tabbedPane = new JTabbedPane();
        
        // 添加各功能标签页
        createFunctionalPanels();
        
        // 添加日志面板
        JPanel logPanel = createLogPanel();
        
        // 创建分割面板，上部是标签页，下部是日志
        JSplitPane splitPane = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            tabbedPane,
            logPanel
        );
        splitPane.setResizeWeight(0.7); // 设置分割比例
        
        mainPanel.add(splitPane, BorderLayout.CENTER);
        
        // 添加主面板到窗口
        mainFrame.add(mainPanel);
        
        // 启动状态更新定时器
        startUpdateTimer();
        
        // 显示窗口
        mainFrame.setVisible(true);
    }
    
    /**
     * 创建菜单栏
     */
    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        // 系统菜单
        JMenu systemMenu = new JMenu("系统");
        
        // 退出菜单项
        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(
                mainFrame,
                "确定要退出程序吗？",
                "退出确认",
                JOptionPane.YES_NO_OPTION
            );
            
            if (result == JOptionPane.YES_OPTION) {
                // 保存数据
                dataManager.saveData();
                blacklistManager.saveBlacklist();
                filterWordManager.saveFilterWords();
                
                // 退出程序
                System.exit(0);
            }
        });
        systemMenu.add(exitItem);
        
        // 保存菜单项
        JMenuItem saveItem = new JMenuItem("保存所有数据");
        saveItem.addActionListener(e -> {
            dataManager.saveData();
            blacklistManager.saveBlacklist();
            filterWordManager.saveFilterWords();
            JOptionPane.showMessageDialog(mainFrame, "所有数据已保存", "保存成功", JOptionPane.INFORMATION_MESSAGE);
        });
        systemMenu.add(saveItem);
        
        // 添加系统菜单
        menuBar.add(systemMenu);
        
        // 帮助菜单
        JMenu helpMenu = new JMenu("帮助");
        
        // 关于菜单项
        JMenuItem aboutItem = new JMenuItem("关于");
        aboutItem.addActionListener(e -> {
            JOptionPane.showMessageDialog(
                mainFrame,
                "柠檬AI机器人 GUI控制面板\n" +
                "版本：1.0.0\n" +
                "基于OneBot协议的QQ AI聊天机器人\n",
                "关于",
                JOptionPane.INFORMATION_MESSAGE
            );
        });
        helpMenu.add(aboutItem);
        
        // 添加帮助菜单
        menuBar.add(helpMenu);
        
        return menuBar;
    }
    
    /**
     * 创建状态面板
     */
    private JPanel createStatusPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.setLayout(new FlowLayout(FlowLayout.LEFT));
        
        // 添加状态标签
        panel.add(new JLabel("状态："));
        statusLabel = new JLabel("正在连接...");
        panel.add(statusLabel);
        
        // 添加内存使用标签
        panel.add(new JLabel("  |  内存："));
        JLabel memoryLabel = new JLabel("0MB / 0MB");
        panel.add(memoryLabel);
        
        // 添加运行时间标签
        panel.add(new JLabel("  |  运行时间："));
        JLabel uptimeLabel = new JLabel("0秒");
        panel.add(uptimeLabel);
        
        // 添加更新定时器，定期更新状态
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> {
                    // 更新连接状态
                    boolean isConnected = botClient.isOpen();
                    statusLabel.setText(isConnected ? "已连接" : "未连接");
                    statusLabel.setForeground(isConnected ? Color.GREEN : Color.RED);
                    
                    // 更新内存使用
                    Runtime runtime = Runtime.getRuntime();
                    long totalMemory = runtime.totalMemory() / (1024 * 1024);
                    long freeMemory = runtime.freeMemory() / (1024 * 1024);
                    long usedMemory = totalMemory - freeMemory;
                    memoryLabel.setText(usedMemory + "MB / " + totalMemory + "MB");
                    
                    // 更新运行时间
                    long uptime = (System.currentTimeMillis() - botClient.getStartupTime()) / 1000;
                    uptimeLabel.setText(formatUptime(uptime));
                });
            }
        }, 0, 1000); // 每秒更新一次
        
        return panel;
    }
    
    /**
     * 创建日志面板
     */
    private JPanel createLogPanel() {
        JPanel logPanel = new JPanel(new BorderLayout());
        
        // 创建日志文本区域
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        // 设置字符编码和字体，解决乱码问题
        logArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        
        // 确保文本区域使用UTF-8编码
        ((DefaultCaret) logArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        
        // 创建滚动面板
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        
        // 创建工具栏
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        // 添加清除按钮
        JButton clearButton = new JButton("清除日志");
        clearButton.addActionListener(e -> {
            logArea.setText("");
        });
        toolBar.add(clearButton);
        
        // 添加日志级别过滤器
        toolBar.add(new JLabel("日志级别:"));
        JComboBox<String> logLevelComboBox = new JComboBox<>(new String[]{"全部", "ERROR", "WARN", "INFO", "DEBUG"});
        logLevelComboBox.addActionListener(e -> {
            String level = (String) logLevelComboBox.getSelectedItem();
            if (!"全部".equals(level)) {
                filterLogByLevel(level);
            } else {
                // 重置过滤器
                refreshLogArea();
            }
        });
        toolBar.add(logLevelComboBox);
        
        // 添加文本搜索
        toolBar.add(new JLabel("搜索:"));
        JTextField searchTextField = new JTextField(15);
        searchTextField.addActionListener(e -> {
            String text = searchTextField.getText().trim();
            if (!text.isEmpty()) {
                filterLogByText(text);
            } else {
                // 重置过滤器
                refreshLogArea();
            }
        });
        toolBar.add(searchTextField);
        
        JButton searchButton = new JButton("搜索");
        searchButton.addActionListener(e -> {
            String text = searchTextField.getText().trim();
            if (!text.isEmpty()) {
                filterLogByText(text);
            } else {
                // 重置过滤器
                refreshLogArea();
            }
        });
        toolBar.add(searchButton);
        
        // 添加导出按钮
        JButton exportButton = new JButton("导出日志");
        exportButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("导出日志");
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setAcceptAllFileFilterUsed(false);
            fileChooser.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("文本文件(*.txt)", "txt"));
            
            int result = fileChooser.showSaveDialog(mainFrame);
            if (result == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                if (!file.getName().toLowerCase().endsWith(".txt")) {
                    file = new File(file.getAbsolutePath() + ".txt");
                }
                
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(file), StandardCharsets.UTF_8))) {
                    writer.write(logArea.getText());
                    JOptionPane.showMessageDialog(mainFrame, "日志已导出到: " + file.getAbsolutePath(), "导出成功", JOptionPane.INFORMATION_MESSAGE);
                } catch (IOException ex) {
                    logger.error("导出日志失败", ex);
                    JOptionPane.showMessageDialog(mainFrame, "导出日志失败: " + ex.getMessage(), "导出失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        toolBar.add(exportButton);
        
        // 添加组件
        logPanel.add(toolBar, BorderLayout.NORTH);
        logPanel.add(scrollPane, BorderLayout.CENTER);
        
        return logPanel;
    }
    
    /**
     * 创建功能面板
     */
    private void createFunctionalPanels() {
        // 使用懒加载，延迟初始化面板
        // 创建群组管理面板
        groupPanel = createGroupPanel();
        tabbedPane.addTab("群组管理", null, new JPanel(), "管理群组和AI开关状态");
        
        // 创建消息发送面板
        messagePanel = createMessagePanel();
        tabbedPane.addTab("消息发送", null, new JPanel(), "发送群消息和私聊消息");
        
        // 创建黑名单管理面板
        blacklistPanel = createBlacklistPanel();
        tabbedPane.addTab("黑名单管理", null, new JPanel(), "管理被禁止使用AI的用户");
        
        // 创建屏蔽词管理面板
        filterWordPanel = createFilterWordPanel();
        tabbedPane.addTab("屏蔽词管理", null, new JPanel(), "管理消息过滤词");
        
        // 创建模型管理面板
        modelPanel = createModelPanel();
        tabbedPane.addTab("模型管理", null, new JPanel(), "查看可用的AI模型");
        
        // 创建人设管理面板
        personaPanel = createPersonaPanel();
        tabbedPane.addTab("人设管理", null, new JPanel(), "管理AI人设");
        
        // 获取控制台命令管理器
        ConsoleCommandManager consoleCommandManager = new ConsoleCommandManager(
            botClient, configLoader, dataManager, aiService, 
            blacklistManager, filterWordManager, modelManager, personaManager
        );
        consolePanel = new ConsolePanel(consoleCommandManager);
        
        // 添加面板到选项卡
        tabbedPane.addTab("控制台", null, consolePanel, "执行命令行命令");
        
        // 启动控制台命令管理器
        Thread.ofVirtual().name("console-command-manager").start(consoleCommandManager::start);
        
        // 添加标签页切换监听器，只在打开对应标签页时更新数据并加载实际面板
        tabbedPane.addChangeListener(e -> {
            try {
                int selectedIndex = tabbedPane.getSelectedIndex();
                if (selectedIndex >= 0) {
                    String tabTitle = tabbedPane.getTitleAt(selectedIndex);
                    logger.debug("切换到标签页: {}", tabTitle);
                    
                    // 延迟加载面板内容
                    loadTabContent(selectedIndex, tabTitle);
                }
            } catch (Exception ex) {
                logger.error("标签页切换更新数据时出错", ex);
            }
        });
        
        // 立即加载第一个标签页内容
        if (tabbedPane.getTabCount() > 0) {
            SwingUtilities.invokeLater(() -> {
                try {
                    String firstTabTitle = tabbedPane.getTitleAt(0);
                    loadTabContent(0, firstTabTitle);
                } catch (Exception ex) {
                    logger.error("加载第一个标签页内容时出错", ex);
                }
            });
        }
    }
    
    /**
     * 加载标签页内容 - 延迟初始化
     */
    private void loadTabContent(int tabIndex, String tabTitle) {
        try {
            Component currentComponent = tabbedPane.getComponentAt(tabIndex);
            
            // 检查该标签页是否已经加载了实际内容
            if (!(currentComponent instanceof JPanel) || 
                ((currentComponent instanceof GroupPanel) || 
                 (currentComponent instanceof MessagePanel) || 
                 (currentComponent instanceof BlacklistPanel) || 
                 (currentComponent instanceof FilterWordPanel) || 
                 (currentComponent instanceof ModelPanel) || 
                 (currentComponent instanceof PersonaPanel) || 
                 (currentComponent instanceof ConsolePanel))) {
                // 已经加载了实际内容，只需要更新数据
                updateVisiblePanelOnly(tabTitle);
                return;
            }
            
            // 标签页尚未加载实际内容，加载对应的面板
            Component panelToLoad = null;
            
            switch (tabTitle) {
                case "群组管理":
                    if (groupPanel == null) {
                        groupPanel = createGroupPanel();
                    }
                    groupPanel.updateData();
                    panelToLoad = groupPanel;
                    break;
                    
                case "消息发送":
                    if (messagePanel == null) {
                        messagePanel = createMessagePanel();
                    }
                    panelToLoad = messagePanel;
                    break;
                    
                case "黑名单管理":
                    if (blacklistPanel == null) {
                        blacklistPanel = createBlacklistPanel();
                    }
                    blacklistPanel.updateData();
                    panelToLoad = blacklistPanel;
                    break;
                    
                case "屏蔽词管理":
                    if (filterWordPanel == null) {
                        filterWordPanel = createFilterWordPanel();
                    }
                    filterWordPanel.updateData();
                    panelToLoad = filterWordPanel;
                    break;
                    
                case "模型管理":
                    if (modelPanel == null) {
                        modelPanel = createModelPanel();
                    }
                    modelPanel.updateData();
                    panelToLoad = modelPanel;
                    break;
                    
                case "人设管理":
                    if (personaPanel == null) {
                        personaPanel = createPersonaPanel();
                    }
                    personaPanel.updateData();
                    panelToLoad = personaPanel;
                    break;
                    
                case "控制台":
                    // 控制台面板已在创建时加载
                    if (consolePanel == null) {
                        ConsoleCommandManager cmdManager = new ConsoleCommandManager(
                            botClient, configLoader, dataManager, aiService, 
                            blacklistManager, filterWordManager, modelManager, personaManager
                        );
                        consolePanel = new ConsolePanel(cmdManager);
                        Thread.ofVirtual().name("console-command-manager").start(cmdManager::start);
                    }
                    panelToLoad = consolePanel;
                    break;
            }
            
            // 替换占位面板
            if (panelToLoad != null) {
                tabbedPane.setComponentAt(tabIndex, panelToLoad);
            }
        } catch (Exception e) {
            logger.error("加载标签页内容时出错: {}", tabTitle, e);
        }
    }
    
    /**
     * 创建群组管理面板
     */
    private GroupPanel createGroupPanel() {
        return new GroupPanel(dataManager, botClient);
    }
    
    /**
     * 创建消息发送面板
     */
    private MessagePanel createMessagePanel() {
        return new MessagePanel(botClient);
    }
    
    /**
     * 创建黑名单管理面板
     */
    private BlacklistPanel createBlacklistPanel() {
        return new BlacklistPanel(blacklistManager);
    }
    
    /**
     * 创建屏蔽词管理面板
     */
    private FilterWordPanel createFilterWordPanel() {
        return new FilterWordPanel(filterWordManager);
    }
    
    /**
     * 创建模型管理面板
     */
    private ModelPanel createModelPanel() {
        return new ModelPanel(modelManager);
    }
    
    /**
     * 创建人设管理面板
     */
    private PersonaPanel createPersonaPanel() {
        return new PersonaPanel(personaManager);
    }
    
    /**
     * 启动状态更新定时器
     */
    private void startUpdateTimer() {
        if (updateTimer != null) {
            updateTimer.cancel();
            updateTimer.purge();
        }
        
        updateTimer = new Timer("GUI-UpdateTimer", true); // 使用守护线程
        updateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    // 检查GUI是否可见，如果不可见则不更新
                    if (!isVisible()) {
                        return;
                    }
                    
                    // 使用invokeLater确保UI更新在EDT线程上执行
                    SwingUtilities.invokeLater(() -> {
                        try {
                            // 更新连接状态 - 这是轻量级操作
                            boolean isConnected = botClient.isOpen();
                            if (isConnected != connected) {
                                connected = isConnected;
                                statusLabel.setText(isConnected ? "已连接" : "未连接");
                                statusLabel.setForeground(isConnected ? new Color(0, 150, 0) : Color.RED);
                            }
                        } catch (Exception e) {
                            logger.error("更新GUI状态数据时出错", e);
                        }
                    });
                    
                    // 其他面板更新放在单独的线程中异步准备数据
                    // 使用计数器来控制不同面板的更新频率
                    int updateCycle = (int)(System.currentTimeMillis() / 1000) % 60; // 60秒一个完整周期
                    
                    if (updateCycle % 60 == 0 && isVisible()) {
                        // 每分钟更新一次所有数据 - 异步准备数据
                        CompletableFuture.runAsync(() -> {
                            try {
                                // 收集需要更新的数据
                                Map<String, Object> updateData = new HashMap<>();
                                
                                // 准备各种数据...
                                
                                // 数据准备好后，在EDT中更新UI
                                SwingUtilities.invokeLater(() -> {
                                    try {
                                        // 根据当前显示的标签页选择性更新
                                        int selectedIndex = tabbedPane.getSelectedIndex();
                                        if (selectedIndex >= 0) {
                                            String tabTitle = tabbedPane.getTitleAt(selectedIndex);
                                            updateVisiblePanelOnly(tabTitle);
                                        }
                                    } catch (Exception e) {
                                        logger.error("更新GUI面板时出错", e);
                                    }
                                });
                            } catch (Exception e) {
                                logger.error("异步准备GUI更新数据时出错", e);
                            }
                        });
                    }
                } catch (Exception e) {
                    logger.error("GUI定时更新任务出错", e);
                }
            }
        }, 0, 5000); // 每5秒轮询一次状态，不再每2秒更新
    }
    
    /**
     * 仅更新当前可见的面板
     */
    private void updateVisiblePanelOnly(String tabTitle) {
        try {
            logger.debug("更新当前可见标签页: {}", tabTitle);
            
            switch (tabTitle) {
                case "群组管理":
                    if (groupPanel != null) {
                        try {
                            groupPanel.updateData();
                        } catch (Exception e) {
                            logger.error("更新群组管理面板出错", e);
                        }
                    }
                    break;
                case "黑名单管理":
                    if (blacklistPanel != null) {
                        try {
                            blacklistPanel.updateData();
                        } catch (Exception e) {
                            logger.error("更新黑名单管理面板出错", e);
                        }
                    }
                    break;
                case "屏蔽词管理":
                    if (filterWordPanel != null) {
                        try {
                            filterWordPanel.updateData();
                        } catch (Exception e) {
                            logger.error("更新屏蔽词管理面板出错", e);
                        }
                    }
                    break;
                case "模型管理":
                    if (modelPanel != null) {
                        try {
                            modelPanel.updateData();
                        } catch (Exception e) {
                            logger.error("更新模型管理面板出错", e);
                        }
                    }
                    break;
                case "人设管理":
                    if (personaPanel != null) {
                        try {
                            personaPanel.updateData();
                        } catch (Exception e) {
                            logger.error("更新人设管理面板出错", e);
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            logger.error("更新可见面板时出错", e);
        }
    }
    
    /**
     * 添加日志记录
     */
    public void addLog(String log) {
        // 添加到日志队列
        logQueue.add(log);
        
        // 如果队列已满，处理一批日志
        if (logQueue.size() >= MAX_LOG_QUEUE_SIZE) {
            processLogQueue();
        } else if (!logProcessorRunning.get()) {
            // 如果处理器没有运行，启动一个新的处理器
            logProcessorRunning.set(true);
            SwingUtilities.invokeLater(this::processLogQueue);
        }
    }
    
    /**
     * 处理日志队列中的日志
     */
    private void processLogQueue() {
        // 确保只有一个线程在处理
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::processLogQueue);
            return;
        }
        
        // 处理队列中的所有日志
        StringBuilder sb = new StringBuilder();
        String log;
        int count = 0;
        List<String> newEntries = new ArrayList<>();
        
        while ((log = logQueue.poll()) != null && count < MAX_LOG_QUEUE_SIZE) {
            sb.append(log).append("\n");
            newEntries.add(log);
            count++;
        }
        
        if (sb.length() > 0) {
            // 设置UTF-8编码处理日志内容
            String newText = new String(sb.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            logArea.append(newText);
            
            // 同时更新完整日志历史
            completeLogHistory.addAll(newEntries);
            
            // 如果完整日志历史过长，也需要限制
            while (completeLogHistory.size() > MAX_LOG_LINES * 2) {
                completeLogHistory.remove(0);
            }
            
            // 限制日志行数
            limitLogLines();
        }
        
        // 如果队列还有日志，继续处理
        if (!logQueue.isEmpty()) {
            SwingUtilities.invokeLater(this::processLogQueue);
        } else {
            logProcessorRunning.set(false);
        }
    }
    
    /**
     * 限制日志行数
     */
    private void limitLogLines() {
        try {
            if (logArea.getLineCount() > MAX_LOG_LINES) {
                int endOffset = logArea.getLineEndOffset(logArea.getLineCount() - MAX_LOG_LINES - 1);
                logArea.getDocument().remove(0, endOffset);
            }
        } catch (Exception e) {
            // 忽略异常，不让日志管理本身导致问题
        }
    }
    
    /**
     * 按级别过滤日志
     */
    private void filterLogByLevel(String level) {
        if ("全部".equals(level)) {
            // 重置过滤，显示所有日志
            refreshLogArea();
            return;
        }
        
        // 实现按级别过滤的逻辑
        SwingUtilities.invokeLater(() -> {
            try {
                // 清空当前日志区域
                logArea.setText("");
                
                // 遍历完整日志历史，添加匹配的行
                for (String logLine : completeLogHistory) {
                    if (logLine.contains(" " + level + " ")) {
                        logArea.append(logLine + "\n");
                    }
                }
                
                // 滚动到底部
                logArea.setCaretPosition(logArea.getDocument().getLength());
            } catch (Exception e) {
                logger.error("过滤日志时出错", e);
            }
        });
    }
    
    /**
     * 按文本过滤日志
     */
    private void filterLogByText(String text) {
        if (text == null || text.trim().isEmpty()) {
            // 清除过滤
            refreshLogArea();
            return;
        }
        
        // 实现文本搜索逻辑
        SwingUtilities.invokeLater(() -> {
            try {
                // 清空当前日志区域
                logArea.setText("");
                
                String searchText = text.toLowerCase();
                
                // 遍历完整日志历史，添加匹配的行
                for (String logLine : completeLogHistory) {
                    if (logLine.toLowerCase().contains(searchText)) {
                        logArea.append(logLine + "\n");
                    }
                }
                
                // 滚动到底部
                logArea.setCaretPosition(logArea.getDocument().getLength());
            } catch (Exception e) {
                logger.error("搜索日志时出错", e);
            }
        });
    }
    
    /**
     * 刷新日志区域，显示所有日志
     */
    private void refreshLogArea() {
        // 清除所有过滤器，显示完整日志
        if (logArea != null) {
            SwingUtilities.invokeLater(() -> {
                try {
                    // 清空当前内容
                    logArea.setText("");
                    
                    // 重新加载完整的日志历史
                    for (String logLine : completeLogHistory) {
                        logArea.append(logLine + "\n");
                    }
                    
                    // 确保光标滚动到最底部
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                } catch (Exception e) {
                    logger.error("刷新日志区域时出错", e);
                }
            });
        }
    }
    
    /**
     * 格式化运行时间
     */
    private String formatUptime(long seconds) {
        long days = seconds / (24 * 3600);
        seconds %= (24 * 3600);
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        
        if (days > 0) {
            return days + "天 " + hours + "小时 " + minutes + "分 " + seconds + "秒";
        } else if (hours > 0) {
            return hours + "小时 " + minutes + "分 " + seconds + "秒";
        } else if (minutes > 0) {
            return minutes + "分 " + seconds + "秒";
        } else {
            return seconds + "秒";
        }
    }
    
    /**
     * 关闭GUI
     */
    public void shutdown() {
        // 取消所有定时器
        if (updateTimer != null) {
            updateTimer.cancel();
            updateTimer.purge();
            updateTimer = null;
        }
        
        // 关闭子面板资源
        if (groupPanel != null) {
            // 清理资源...
        }
        
        // 关闭主窗口
        if (mainFrame != null) {
            mainFrame.dispose();
            mainFrame = null;
        }
    }
    
    /**
     * 显示GUI
     */
    public void show() {
        if (mainFrame != null && !mainFrame.isVisible()) {
            mainFrame.setVisible(true);
        }
    }
    
    /**
     * 隐藏GUI
     */
    public void hide() {
        if (mainFrame != null && mainFrame.isVisible()) {
            mainFrame.setVisible(false);
        }
    }
    
    /**
     * 是否显示GUI
     */
    public boolean isVisible() {
        return mainFrame != null && mainFrame.isVisible();
    }
    
    // 最大日志行数
    private static final int MAX_LOG_LINES = 1000;
    // 最大日志队列大小
    private static final int MAX_LOG_QUEUE_SIZE = 500;
} 