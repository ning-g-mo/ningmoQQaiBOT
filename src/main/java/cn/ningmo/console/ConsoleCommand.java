package cn.ningmo.console;

import java.util.function.Function;

/**
 * 控制台命令
 * 用于定义命令的基本属性和执行逻辑
 */
public class ConsoleCommand {
    private final String description;
    private final String usage;
    private final Function<String[], Boolean> executor;
    
    /**
     * 构造函数
     * @param description 命令描述
     * @param usage 命令用法
     * @param executor 命令执行器，参数为命令参数，返回值表示是否执行成功
     */
    public ConsoleCommand(String description, String usage, Function<String[], Boolean> executor) {
        this.description = description;
        this.usage = usage;
        this.executor = executor;
    }
    
    /**
     * 获取命令描述
     * @return 命令描述
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * 获取命令用法
     * @return 命令用法
     */
    public String getUsage() {
        return usage;
    }
    
    /**
     * 执行命令
     * @param args 命令参数
     * @return 是否执行成功
     */
    public boolean execute(String[] args) {
        return executor.apply(args);
    }
} 