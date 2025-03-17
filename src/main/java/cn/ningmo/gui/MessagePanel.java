package cn.ningmo.gui;

import cn.ningmo.bot.OneBotClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * 消息发送面板
 */
public class MessagePanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(MessagePanel.class);
    
    private final OneBotClient botClient;
    
    private JTextField targetField;
    private JTextArea messageArea;
    private JComboBox<String> messageTypeComboBox;
    
    /**
     * 构造函数
     */
    public MessagePanel(OneBotClient botClient) {
        this.botClient = botClient;
        
        initUI();
    }
    
    /**
     * 初始化UI
     */
    private void initUI() {
        setLayout(new BorderLayout());
        
        // 创建顶部面板
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        // 添加消息类型选择
        topPanel.add(new JLabel("消息类型:"));
        messageTypeComboBox = new JComboBox<>(new String[]{"群消息", "私聊消息"});
        topPanel.add(messageTypeComboBox);
        
        // 添加目标输入框
        topPanel.add(new JLabel("目标:"));
        targetField = new JTextField(15);
        topPanel.add(targetField);
        
        // 添加顶部面板
        add(topPanel, BorderLayout.NORTH);
        
        // 创建消息输入区域
        messageArea = new JTextArea();
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(messageArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("消息内容"));
        add(scrollPane, BorderLayout.CENTER);
        
        // 创建底部面板
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        // 添加清空按钮
        JButton clearButton = new JButton("清空");
        clearButton.addActionListener(e -> {
            messageArea.setText("");
        });
        bottomPanel.add(clearButton);
        
        // 添加发送按钮
        JButton sendButton = new JButton("发送");
        sendButton.addActionListener(e -> sendMessage());
        bottomPanel.add(sendButton);
        
        // 添加底部面板
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    /**
     * 发送消息
     */
    private void sendMessage() {
        String target = targetField.getText().trim();
        String message = messageArea.getText().trim();
        String messageType = (String) messageTypeComboBox.getSelectedItem();
        
        if (target.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入目标群号或QQ号", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入消息内容", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        try {
            if ("群消息".equals(messageType)) {
                botClient.sendGroupMessage(target, message);
                JOptionPane.showMessageDialog(this, "已向群 " + target + " 发送消息", "成功", JOptionPane.INFORMATION_MESSAGE);
            } else if ("私聊消息".equals(messageType)) {
                botClient.sendPrivateMessage(target, message);
                JOptionPane.showMessageDialog(this, "已向用户 " + target + " 发送私聊消息", "成功", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            logger.error("发送消息失败", e);
            JOptionPane.showMessageDialog(this, "发送消息失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
} 