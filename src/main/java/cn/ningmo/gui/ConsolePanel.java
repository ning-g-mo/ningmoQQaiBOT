package cn.ningmo.gui;

import cn.ningmo.console.ConsoleCommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.PrintStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制台面板
 * 用于在GUI界面中执行命令行命令
 */
public class ConsolePanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(ConsolePanel.class);
    
    private final ConsoleCommandManager commandManager;
    
    private JTextArea consoleOutput;
    private JTextField commandInput;
    private JButton executeButton;
    private PrintStream originalOut;
    private ConsoleOutputStream consoleOutputStream;
    
    // 命令历史
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;
    
    /**
     * 构造函数
     */
    public ConsolePanel(ConsoleCommandManager commandManager) {
        this.commandManager = commandManager;
        
        initUI();
        redirectSystemOut();
    }
    
    /**
     * 初始化UI
     */
    private void initUI() {
        setLayout(new BorderLayout());
        
        // 创建控制台输出区域
        consoleOutput = new JTextArea();
        consoleOutput.setEditable(false);
        consoleOutput.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        consoleOutput.setBackground(new Color(40, 44, 52));
        consoleOutput.setForeground(new Color(171, 178, 191));
        consoleOutput.setCaretColor(Color.WHITE);
        
        // 自动滚动到底部
        DefaultCaret caret = (DefaultCaret) consoleOutput.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        
        // 添加滚动条
        JScrollPane scrollPane = new JScrollPane(consoleOutput);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(scrollPane, BorderLayout.CENTER);
        
        // 创建输入区域
        JPanel inputPanel = new JPanel(new BorderLayout());
        
        commandInput = new JTextField();
        commandInput.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        commandInput.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 64, 72)),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        
        // 添加键盘监听器，处理上下键浏览命令历史
        commandInput.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    showPreviousCommand();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    showNextCommand();
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    executeCommand();
                }
            }
        });
        
        executeButton = new JButton("执行");
        executeButton.addActionListener(this::onExecuteClick);
        
        inputPanel.add(new JLabel(" > "), BorderLayout.WEST);
        inputPanel.add(commandInput, BorderLayout.CENTER);
        inputPanel.add(executeButton, BorderLayout.EAST);
        
        add(inputPanel, BorderLayout.SOUTH);
        
        // 设置初始欢迎信息
        appendToConsole("柠枺AI控制台 - 输入 help 获取命令列表\n");
    }
    
    /**
     * 重定向System.out到控制台
     */
    private void redirectSystemOut() {
        originalOut = System.out;
        consoleOutputStream = new ConsoleOutputStream();
        System.setOut(new PrintStream(consoleOutputStream, true));
    }
    
    /**
     * 恢复原始System.out
     */
    public void restoreSystemOut() {
        if (originalOut != null) {
            System.setOut(originalOut);
        }
    }
    
    /**
     * 显示上一条命令
     */
    private void showPreviousCommand() {
        if (commandHistory.isEmpty()) return;
        
        if (historyIndex < commandHistory.size() - 1) {
            historyIndex++;
            commandInput.setText(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            commandInput.selectAll();
        }
    }
    
    /**
     * 显示下一条命令
     */
    private void showNextCommand() {
        if (commandHistory.isEmpty()) return;
        
        if (historyIndex > 0) {
            historyIndex--;
            commandInput.setText(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            commandInput.selectAll();
        } else if (historyIndex == 0) {
            historyIndex = -1;
            commandInput.setText("");
        }
    }
    
    /**
     * 添加文本到控制台
     */
    public void appendToConsole(String text) {
        SwingUtilities.invokeLater(() -> {
            consoleOutput.append(text);
            // 限制文本区域的大小
            limitConsoleText();
        });
    }
    
    /**
     * 限制控制台文本的行数
     */
    private void limitConsoleText() {
        String text = consoleOutput.getText();
        String[] lines = text.split("\n");
        
        if (lines.length > 1000) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 1000; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            consoleOutput.setText(sb.toString());
        }
    }
    
    /**
     * 执行命令按钮点击事件
     */
    private void onExecuteClick(ActionEvent e) {
        executeCommand();
    }
    
    /**
     * 执行输入的命令
     */
    private void executeCommand() {
        String command = commandInput.getText().trim();
        
        if (command.isEmpty()) {
            return;
        }
        
        // 添加命令到历史记录
        commandHistory.add(command);
        historyIndex = -1;
        
        // 在控制台显示输入的命令
        appendToConsole("> " + command + "\n");
        
        // 清空输入框
        commandInput.setText("");
        
        // 执行命令
        try {
            // 调用命令管理器执行命令
            Thread.ofVirtual().name("console-command-" + command).start(() -> {
                commandManager.executeCommand(command);
            });
        } catch (Exception ex) {
            logger.error("执行命令失败", ex);
            appendToConsole("执行命令失败: " + ex.getMessage() + "\n");
        }
    }
    
    /**
     * 用于捕获System.out输出的OutputStream
     */
    private class ConsoleOutputStream extends OutputStream {
        private final StringBuilder buffer = new StringBuilder();
        
        @Override
        public void write(int b) {
            char c = (char) b;
            
            if (c == '\n') {
                String line = buffer.toString();
                appendToConsole(line + "\n");
                buffer.setLength(0);
            } else {
                buffer.append(c);
            }
            
            // 同时输出到原始流
            if (originalOut != null) {
                originalOut.write(b);
            }
        }
    }
    
    /**
     * 更新数据
     * 这个方法是为了与其他面板保持一致的接口
     * 在控制台面板中不需要特别的更新操作
     */
    public void updateData() {
        // 控制台不需要定期更新数据
    }
} 