package cn.ningmo.console;

import cn.ningmo.ai.AIService;
import cn.ningmo.ai.model.ModelManager;
import cn.ningmo.ai.persona.PersonaManager;
import cn.ningmo.bot.OneBotClient;
import cn.ningmo.config.BlacklistManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.config.FilterWordManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;

/**
 * 控制台命令管理器
 * 用于处理控制台输入的命令
 */
public class ConsoleCommandManager {
    private static final Logger logger = LoggerFactory.getLogger(ConsoleCommandManager.class);
    
    private final OneBotClient botClient;
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final AIService aiService;
    private final BlacklistManager blacklistManager;
    private final FilterWordManager filterWordManager;
    private final ModelManager modelManager;
    private final PersonaManager personaManager;
    
    private final ExecutorService executor;
    private boolean running = true;
    
    private final Map<String, ConsoleCommand> commandMap = new HashMap<>();
    
    public ConsoleCommandManager(OneBotClient botClient, ConfigLoader configLoader, DataManager dataManager, 
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
        
        this.executor = Executors.newSingleThreadExecutor();
        
        // 注册命令
        registerCommands();
    }
    
    /**
     * 注册所有命令
     */
    private void registerCommands() {
        // 帮助命令
        commandMap.put("help", new ConsoleCommand(
            "显示帮助信息",
            "help [命令名]",
            (args) -> {
                if (args.length > 0) {
                    showCommandHelp(args[0]);
                } else {
                    showAllCommands();
                }
                return true;
            }
        ));
        
        // 退出命令
        commandMap.put("exit", new ConsoleCommand(
            "退出程序",
            "exit",
            (args) -> {
                System.out.println("正在退出...");
                running = false;
                System.exit(0);
                return true;
            }
        ));
        
        // 查看群组状态
        commandMap.put("group", new ConsoleCommand(
            "查看或设置群组状态",
            "group list - 列出所有群组\n" +
            "group enable <群号> - 启用指定群的AI功能\n" +
            "group disable <群号> - 禁用指定群的AI功能\n" +
            "group status <群号> - 查看指定群的状态",
            (args) -> {
                if (args.length == 0) {
                    System.out.println("请指定操作: list, enable, disable, status");
                    return true;
                }
                
                switch (args[0].toLowerCase()) {
                    case "list":
                        // 列出所有群组
                        System.out.println("群组列表:");
                        Map<String, Object> groupData = dataManager.getDataMap("groups");
                        if (groupData.isEmpty()) {
                            System.out.println("  没有群组数据");
                        } else {
                            for (Map.Entry<String, Object> entry : groupData.entrySet()) {
                                String groupId = entry.getKey();
                                boolean enabled = dataManager.isGroupAIEnabled(groupId);
                                System.out.println("  群: " + groupId + " - AI状态: " + (enabled ? "已启用" : "已禁用"));
                            }
                        }
                        break;
                    case "enable":
                        if (args.length < 2) {
                            System.out.println("请指定群号");
                            return true;
                        }
                        String enableGroupId = args[1];
                        dataManager.setGroupAIEnabled(enableGroupId, true);
                        System.out.println("已启用群 " + enableGroupId + " 的AI功能");
                        break;
                    case "disable":
                        if (args.length < 2) {
                            System.out.println("请指定群号");
                            return true;
                        }
                        String disableGroupId = args[1];
                        dataManager.setGroupAIEnabled(disableGroupId, false);
                        System.out.println("已禁用群 " + disableGroupId + " 的AI功能");
                        break;
                    case "status":
                        if (args.length < 2) {
                            System.out.println("请指定群号");
                            return true;
                        }
                        String statusGroupId = args[1];
                        boolean enabled = dataManager.isGroupAIEnabled(statusGroupId);
                        System.out.println("群 " + statusGroupId + " 的AI状态: " + (enabled ? "已启用" : "已禁用"));
                        break;
                    default:
                        System.out.println("未知操作: " + args[0]);
                        break;
                }
                return true;
            }
        ));
        
        // 消息发送命令
        commandMap.put("send", new ConsoleCommand(
            "发送消息",
            "send group <群号> <消息内容> - 向指定群发送消息\n" +
            "send private <QQ号> <消息内容> - 向指定用户发送私聊消息",
            (args) -> {
                if (args.length < 3) {
                    System.out.println("参数不足，使用方法: send [group|private] [群号/QQ号] [消息内容]");
                    return true;
                }
                
                String type = args[0].toLowerCase();
                String target = args[1];
                // 将剩余参数拼接为消息内容
                String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                
                if (type.equals("group")) {
                    botClient.sendGroupMessage(target, message);
                    System.out.println("已向群 " + target + " 发送消息: " + message);
                } else if (type.equals("private")) {
                    botClient.sendPrivateMessage(target, message);
                    System.out.println("已向用户 " + target + " 发送私聊消息: " + message);
                } else {
                    System.out.println("未知消息类型: " + type + "，应为 group 或 private");
                }
                
                return true;
            }
        ));
        
        // 黑名单管理
        commandMap.put("blacklist", new ConsoleCommand(
            "黑名单管理",
            "blacklist list - 列出所有黑名单用户\n" +
            "blacklist add <QQ号> - 添加用户到黑名单\n" +
            "blacklist remove <QQ号> - 从黑名单中移除用户",
            (args) -> {
                if (args.length == 0) {
                    System.out.println("请指定操作: list, add, remove");
                    return true;
                }
                
                switch (args[0].toLowerCase()) {
                    case "list":
                        System.out.println("黑名单用户列表:");
                        for (String userId : blacklistManager.getBlacklistedUsers()) {
                            System.out.println("  " + userId);
                        }
                        if (blacklistManager.getBlacklistedUsers().isEmpty()) {
                            System.out.println("  黑名单为空");
                        }
                        break;
                    case "add":
                        if (args.length < 2) {
                            System.out.println("请指定用户QQ号");
                            return true;
                        }
                        String addUserId = args[1];
                        if (blacklistManager.addToBlacklist(addUserId)) {
                            System.out.println("已将用户 " + addUserId + " 添加到黑名单");
                        } else {
                            System.out.println("用户 " + addUserId + " 已在黑名单中");
                        }
                        break;
                    case "remove":
                        if (args.length < 2) {
                            System.out.println("请指定用户QQ号");
                            return true;
                        }
                        String removeUserId = args[1];
                        if (blacklistManager.removeFromBlacklist(removeUserId)) {
                            System.out.println("已将用户 " + removeUserId + " 从黑名单中移除");
                        } else {
                            System.out.println("用户 " + removeUserId + " 不在黑名单中");
                        }
                        break;
                    default:
                        System.out.println("未知操作: " + args[0]);
                        break;
                }
                return true;
            }
        ));
        
        // 屏蔽词管理
        commandMap.put("filter", new ConsoleCommand(
            "屏蔽词管理",
            "filter list - 列出所有屏蔽词\n" +
            "filter add <词语> - 添加屏蔽词\n" +
            "filter remove <词语> - 删除屏蔽词\n" +
            "filter enable - 启用屏蔽词功能\n" +
            "filter disable - 禁用屏蔽词功能",
            (args) -> {
                if (args.length == 0) {
                    System.out.println("请指定操作: list, add, remove, enable, disable");
                    return true;
                }
                
                switch (args[0].toLowerCase()) {
                    case "list":
                        System.out.println("屏蔽词列表:");
                        for (String word : filterWordManager.getFilterWords()) {
                            System.out.println("  " + word);
                        }
                        if (filterWordManager.getFilterWords().isEmpty()) {
                            System.out.println("  屏蔽词列表为空");
                        }
                        System.out.println("屏蔽词功能状态: " + (filterWordManager.isFilterEnabled() ? "已启用" : "已禁用"));
                        break;
                    case "add":
                        if (args.length < 2) {
                            System.out.println("请指定要添加的屏蔽词");
                            return true;
                        }
                        String addWord = args[1];
                        if (filterWordManager.addFilterWord(addWord)) {
                            System.out.println("已添加屏蔽词: " + addWord);
                        } else {
                            System.out.println("屏蔽词已存在: " + addWord);
                        }
                        break;
                    case "remove":
                        if (args.length < 2) {
                            System.out.println("请指定要删除的屏蔽词");
                            return true;
                        }
                        String removeWord = args[1];
                        if (filterWordManager.removeFilterWord(removeWord)) {
                            System.out.println("已删除屏蔽词: " + removeWord);
                        } else {
                            System.out.println("屏蔽词不存在: " + removeWord);
                        }
                        break;
                    case "enable":
                        // 需要通过配置加载器来更新配置
                        System.out.println("暂不支持通过命令行启用屏蔽词功能，请在配置文件中设置");
                        break;
                    case "disable":
                        // 需要通过配置加载器来更新配置
                        System.out.println("暂不支持通过命令行禁用屏蔽词功能，请在配置文件中设置");
                        break;
                    default:
                        System.out.println("未知操作: " + args[0]);
                        break;
                }
                return true;
            }
        ));
        
        // 模型管理
        commandMap.put("model", new ConsoleCommand(
            "模型管理",
            "model list - 列出所有可用的模型",
            (args) -> {
                if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                    System.out.println("可用的AI模型:");
                    for (String modelName : modelManager.listModels()) {
                        Map<String, String> details = modelManager.getModelDetails(modelName);
                        System.out.println("  " + modelName + ": " + details.getOrDefault("description", "无描述"));
                    }
                } else {
                    System.out.println("未知操作: " + args[0]);
                }
                return true;
            }
        ));
        
        // 人设管理
        commandMap.put("persona", new ConsoleCommand(
            "人设管理",
            "persona list - 列出所有可用的人设\n" +
            "persona refresh - 重新加载所有人设文件",
            (args) -> {
                if (args.length == 0) {
                    System.out.println("请指定操作: list, refresh");
                    return true;
                }
                
                switch (args[0].toLowerCase()) {
                    case "list":
                        System.out.println("可用的人设:");
                        for (String personaName : personaManager.listPersonas()) {
                            System.out.println("  " + personaName);
                        }
                        break;
                    case "refresh":
                        personaManager.refreshPersonas();
                        System.out.println("已重新加载所有人设文件");
                        break;
                    default:
                        System.out.println("未知操作: " + args[0]);
                        break;
                }
                return true;
            }
        ));
        
        // 保存数据命令
        commandMap.put("save", new ConsoleCommand(
            "保存数据",
            "save - 保存所有数据到文件",
            (args) -> {
                dataManager.saveData();
                blacklistManager.saveBlacklist();
                filterWordManager.saveFilterWords();
                System.out.println("所有数据已保存");
                return true;
            }
        ));
        
        // 状态命令
        commandMap.put("status", new ConsoleCommand(
            "显示系统状态",
            "status - 显示系统当前状态",
            (args) -> {
                System.out.println("系统状态:");
                System.out.println("  WebSocket连接: " + (botClient.isOpen() ? "已连接" : "未连接"));
                
                // 显示更多状态信息
                Runtime runtime = Runtime.getRuntime();
                long totalMemory = runtime.totalMemory() / (1024 * 1024);
                long freeMemory = runtime.freeMemory() / (1024 * 1024);
                long usedMemory = totalMemory - freeMemory;
                
                System.out.println("  内存使用: " + usedMemory + "MB / " + totalMemory + "MB");
                System.out.println("  可用处理器: " + runtime.availableProcessors());
                System.out.println("  运行时间: " + (System.currentTimeMillis() - botClient.getStartupTime()) / 1000 + "秒");
                
                return true;
            }
        ));
        
        // 模型相关命令 
        commandMap.put("models", new ConsoleCommand(
            "列出所有可用的AI模型",
            "models",
            args -> {
                List<String> modelList = modelManager.listModels();
                if (modelList.isEmpty()) {
                    System.out.println("当前没有可用的模型");
                } else {
                    System.out.println("可用模型列表:");
                    for (String modelName : modelList) {
                        Map<String, String> details = modelManager.getModelDetails(modelName);
                        String status = details.getOrDefault("status", "未知");
                        String failureCount = details.getOrDefault("failure_count", "0");
                        
                        System.out.printf("- %s (类型: %s, 状态: %s, 失败计数: %s)%n",
                            modelName,
                            details.getOrDefault("type", "未知"),
                            status,
                            failureCount
                        );
                        
                        // 如果模型处于冷却状态，显示可用时间
                        if ("冷却中".equals(status)) {
                            System.out.printf("  将在 %s 后可用%n", 
                                details.getOrDefault("available_in", "未知时间"));
                        }
                    }
                }
                return true;
            }
        ));
        
        commandMap.put("model-reset", new ConsoleCommand(
            "重置指定模型的状态（解除冷却）",
            "model-reset <模型名称>",
            args -> {
                if (args.length < 1) {
                    System.out.println("请指定要重置的模型名称");
                    return false;
                }
                
                String modelName = args[0];
                boolean success = modelManager.resetModelStatus(modelName);
                
                if (success) {
                    System.out.printf("模型 %s 状态已重置，现在可以使用%n", modelName);
                } else {
                    System.out.printf("模型 %s 不存在或无法重置%n", modelName);
                }
                
                return true;
            }
        ));
        
        commandMap.put("model-refresh", new ConsoleCommand(
            "刷新所有模型（重新加载）",
            "model-refresh",
            args -> {
                System.out.println("正在刷新模型列表...");
                modelManager.refreshModels();
                System.out.println("模型列表已刷新");
                
                // 显示刷新后的模型列表
                List<String> modelList = modelManager.listModels();
                if (modelList.isEmpty()) {
                    System.out.println("刷新后没有可用的模型");
                } else {
                    System.out.println("刷新后的可用模型列表:");
                    for (String modelName : modelList) {
                        Map<String, String> details = modelManager.getModelDetails(modelName);
                        System.out.printf("- %s (类型: %s)%n",
                            modelName,
                            details.getOrDefault("type", "未知")
                        );
                    }
                }
                
                return true;
            }
        ));
        
        commandMap.put("model-test", new ConsoleCommand(
            "测试指定模型是否正常工作",
            "model-test <模型名称>",
            args -> {
                if (args.length < 1) {
                    System.out.println("请指定要测试的模型名称");
                    return false;
                }
                
                String modelName = args[0];
                
                if (!modelManager.hasModel(modelName)) {
                    System.out.printf("模型 %s 不存在%n", modelName);
                    return false;
                }
                
                System.out.printf("正在测试模型 %s ...%n", modelName);
                
                // 构建简单的测试会话
                List<Map<String, String>> conversation = new ArrayList<>();
                Map<String, String> message = new HashMap<>();
                message.put("role", "user");
                message.put("content", "你好，这是一个测试消息，请简短回复。");
                conversation.add(message);
                
                try {
                    long startTime = System.currentTimeMillis();
                    String systemPrompt = "你是一个测试助手，请用简短的一句话回复。";
                    
                    System.out.println("发送测试请求...");
                    String reply = modelManager.generateReply(modelName, systemPrompt, conversation, true);
                    long endTime = System.currentTimeMillis();
                    
                    System.out.println("测试结果:");
                    System.out.println("响应时间: " + (endTime - startTime) + "ms");
                    System.out.println("AI回复: " + reply);
                    System.out.println("测试完成，模型工作正常");
                } catch (Exception e) {
                    System.out.println("测试失败，错误: " + e.getMessage());
                    e.printStackTrace();
                    return false;
                }
                
                return true;
            }
        ));
    }
    
    /**
     * 启动命令处理线程
     */
    public void start() {
        logger.info("启动控制台命令处理线程");
        
        executor.submit(() -> {
            Scanner scanner = new Scanner(System.in);
            System.out.println("控制台命令模式已启动，输入'help'查看可用命令");
            
            while (running) {
                try {
                    System.out.print("> ");
                    String input = scanner.nextLine().trim();
                    
                    if (input.isEmpty()) {
                        continue;
                    }
                    
                    // 解析命令和参数
                    String[] parts = input.split("\\s+");
                    String cmd = parts[0].toLowerCase();
                    String[] args = Arrays.copyOfRange(parts, 1, parts.length);
                    
                    // 执行命令
                    executeCommand(cmd, args);
                } catch (Exception e) {
                    logger.error("处理控制台命令时出错", e);
                    System.out.println("命令执行出错: " + e.getMessage());
                }
            }
            
            scanner.close();
        });
    }
    
    /**
     * 执行命令
     * @param cmd 命令名
     * @param args 参数
     */
    private void executeCommand(String cmd, String[] args) {
        ConsoleCommand command = commandMap.get(cmd.toLowerCase());
        if (command != null) {
            try {
                if (!command.execute(args)) {
                    // 执行失败，显示帮助
                    showCommandHelp(cmd);
                }
            } catch (Exception e) {
                logger.error("执行命令时出错: " + cmd, e);
                System.out.println("执行命令时出错: " + e.getMessage());
            }
        } else {
            System.out.println("未知命令: " + cmd);
            showAllCommands();
        }
    }
    
    /**
     * 执行命令字符串
     * 公开此方法以便GUI控制台可以调用
     * @param commandString 完整命令字符串，包括命令名和参数
     */
    public void executeCommand(String commandString) {
        if (commandString == null || commandString.trim().isEmpty()) {
            return;
        }
        
        // 分割命令和参数
        String[] parts = commandString.trim().split("\\s+", 2);
        String cmd = parts[0];
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];
        
        executeCommand(cmd, args);
    }
    
    /**
     * 显示所有可用命令
     */
    private void showAllCommands() {
        System.out.println("可用命令:");
        for (Map.Entry<String, ConsoleCommand> entry : commandMap.entrySet()) {
            System.out.println("  " + entry.getKey() + " - " + entry.getValue().getDescription());
        }
        System.out.println("\n输入'help <命令名>'查看详细用法");
    }
    
    /**
     * 显示指定命令的帮助信息
     * @param cmd 命令名
     */
    private void showCommandHelp(String cmd) {
        ConsoleCommand command = commandMap.get(cmd);
        
        if (command == null) {
            System.out.println("未知命令: " + cmd);
            return;
        }
        
        System.out.println("命令: " + cmd);
        System.out.println("描述: " + command.getDescription());
        System.out.println("用法: " + command.getUsage());
    }
    
    /**
     * 优雅关闭命令处理线程
     */
    public void shutdown() {
        logger.info("关闭控制台命令处理线程");
        running = false;
        executor.shutdown();
    }
} 