package cn.ningmo.gui;

import cn.ningmo.config.BlacklistManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * 黑名单管理面板
 */
public class BlacklistPanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(BlacklistPanel.class);
    
    private final BlacklistManager blacklistManager;
    
    private JTable blacklistTable;
    private DefaultTableModel tableModel;
    private JTextField userIdField;
    
    /**
     * 构造函数
     */
    public BlacklistPanel(BlacklistManager blacklistManager) {
        this.blacklistManager = blacklistManager;
        
        initUI();
        updateData();
    }
    
    /**
     * 初始化UI
     */
    private void initUI() {
        setLayout(new BorderLayout());
        
        // 创建表格
        String[] columnNames = {"QQ号", "操作"};
        tableModel = new DefaultTableModel(columnNames, 0);
        blacklistTable = new JTable(tableModel);
        
        // 设置表格列宽
        blacklistTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        blacklistTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        
        // 设置操作列的渲染器和编辑器
        blacklistTable.getColumnModel().getColumn(1).setCellRenderer(new ButtonRenderer("移除"));
        blacklistTable.getColumnModel().getColumn(1).setCellEditor(new ButtonEditor(new JCheckBox(), "移除", this));
        
        // 添加表格到滚动面板
        JScrollPane scrollPane = new JScrollPane(blacklistTable);
        add(scrollPane, BorderLayout.CENTER);
        
        // 创建操作面板
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        
        // 添加用户ID输入框
        controlPanel.add(new JLabel("QQ号:"));
        userIdField = new JTextField(15);
        controlPanel.add(userIdField);
        
        // 添加添加按钮
        JButton addButton = new JButton("添加到黑名单");
        addButton.addActionListener(e -> {
            String userId = userIdField.getText().trim();
            if (userId.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入QQ号", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (blacklistManager.addToBlacklist(userId)) {
                updateData();
                JOptionPane.showMessageDialog(this, "已将用户 " + userId + " 添加到黑名单", "成功", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "用户 " + userId + " 已在黑名单中", "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        controlPanel.add(addButton);
        
        // 添加移除按钮
        JButton removeButton = new JButton("从黑名单移除");
        removeButton.addActionListener(e -> {
            String userId = userIdField.getText().trim();
            if (userId.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入QQ号", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (blacklistManager.removeFromBlacklist(userId)) {
                updateData();
                JOptionPane.showMessageDialog(this, "已将用户 " + userId + " 从黑名单中移除", "成功", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "用户 " + userId + " 不在黑名单中", "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        controlPanel.add(removeButton);
        
        // 添加刷新按钮
        JButton refreshButton = new JButton("刷新");
        refreshButton.addActionListener(e -> updateData());
        controlPanel.add(refreshButton);
        
        // 添加操作面板
        add(controlPanel, BorderLayout.SOUTH);
    }
    
    /**
     * 更新数据
     */
    public void updateData() {
        // 清空表格
        tableModel.setRowCount(0);
        
        // 获取所有黑名单用户
        List<String> blacklistedUsers = blacklistManager.getBlacklistedUsers();
        
        // 添加到表格
        for (String userId : blacklistedUsers) {
            Object[] row = {userId, "移除"};
            tableModel.addRow(row);
        }
    }
    
    /**
     * 从黑名单移除用户
     */
    public void removeFromBlacklist(int row) {
        String userId = (String) tableModel.getValueAt(row, 0);
        if (blacklistManager.removeFromBlacklist(userId)) {
            updateData();
        }
    }
    
    /**
     * 按钮渲染器
     */
    static class ButtonRenderer extends JButton implements javax.swing.table.TableCellRenderer {
        public ButtonRenderer(String label) {
            setText(label);
            setOpaque(true);
        }
        
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            return this;
        }
    }
    
    /**
     * 按钮编辑器
     */
    static class ButtonEditor extends DefaultCellEditor {
        protected JButton button;
        private String label;
        private boolean isPushed;
        private final BlacklistPanel panel;
        
        public ButtonEditor(JCheckBox checkBox, String label, BlacklistPanel panel) {
            super(checkBox);
            this.panel = panel;
            this.label = label;
            
            button = new JButton(label);
            button.setOpaque(true);
            button.addActionListener(e -> fireEditingStopped());
        }
        
        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            button.setText(label);
            isPushed = true;
            return button;
        }
        
        @Override
        public Object getCellEditorValue() {
            if (isPushed) {
                // 处理按钮点击事件
                // 添加有效性检查，确保选中的行有效
                int selectedRow = panel.blacklistTable.getSelectedRow();
                if (selectedRow >= 0 && selectedRow < panel.tableModel.getRowCount()) {
                    panel.removeFromBlacklist(selectedRow);
                }
            }
            isPushed = false;
            return label;
        }
        
        @Override
        public boolean stopCellEditing() {
            isPushed = false;
            return super.stopCellEditing();
        }
    }
} 