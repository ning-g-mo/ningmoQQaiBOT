package cn.ningmo.gui;

import cn.ningmo.bot.OneBotClient;
import cn.ningmo.config.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;
import java.util.Vector;

/**
 * 群组管理面板
 */
public class GroupPanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(GroupPanel.class);
    
    private final DataManager dataManager;
    private final OneBotClient botClient;
    
    private JTable groupTable;
    private DefaultTableModel tableModel;
    private JTextField groupIdField;
    
    /**
     * 构造函数
     */
    public GroupPanel(DataManager dataManager, OneBotClient botClient) {
        this.dataManager = dataManager;
        this.botClient = botClient;
        
        initUI();
        updateData();
    }
    
    /**
     * 初始化UI
     */
    private void initUI() {
        setLayout(new BorderLayout());
        
        // 创建表格
        String[] columnNames = {"群号", "AI状态", "操作"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 2; // 只有操作列可编辑
            }
        };
        groupTable = new JTable(tableModel);
        
        // 设置表格渲染器
        groupTable.getColumnModel().getColumn(2).setCellRenderer(new ButtonRenderer());
        groupTable.getColumnModel().getColumn(2).setCellEditor(new ButtonEditor(new JCheckBox(), this));
        
        // 设置表格列宽
        groupTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        groupTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        groupTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        
        // 添加表格到滚动面板
        JScrollPane scrollPane = new JScrollPane(groupTable);
        add(scrollPane, BorderLayout.CENTER);
        
        // 创建操作面板
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        
        // 添加群号输入框
        controlPanel.add(new JLabel("群号:"));
        groupIdField = new JTextField(15);
        controlPanel.add(groupIdField);
        
        // 添加启用按钮
        JButton enableButton = new JButton("启用AI");
        enableButton.addActionListener(e -> {
            String groupId = groupIdField.getText().trim();
            if (groupId.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入群号", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            dataManager.setGroupAIEnabled(groupId, true);
            updateData();
            JOptionPane.showMessageDialog(this, "已启用群 " + groupId + " 的AI功能", "成功", JOptionPane.INFORMATION_MESSAGE);
        });
        controlPanel.add(enableButton);
        
        // 添加禁用按钮
        JButton disableButton = new JButton("禁用AI");
        disableButton.addActionListener(e -> {
            String groupId = groupIdField.getText().trim();
            if (groupId.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入群号", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            dataManager.setGroupAIEnabled(groupId, false);
            updateData();
            JOptionPane.showMessageDialog(this, "已禁用群 " + groupId + " 的AI功能", "成功", JOptionPane.INFORMATION_MESSAGE);
        });
        controlPanel.add(disableButton);
        
        // 添加查看按钮
        JButton viewButton = new JButton("查看状态");
        viewButton.addActionListener(e -> {
            String groupId = groupIdField.getText().trim();
            if (groupId.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入群号", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            boolean enabled = dataManager.isGroupAIEnabled(groupId);
            JOptionPane.showMessageDialog(
                this,
                "群 " + groupId + " 的AI状态: " + (enabled ? "已启用" : "已禁用"),
                "群状态",
                JOptionPane.INFORMATION_MESSAGE
            );
        });
        controlPanel.add(viewButton);
        
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
        
        // 获取所有群组数据
        Map<String, Object> groupData = dataManager.getDataMap("groups");
        
        // 添加到表格
        for (Map.Entry<String, Object> entry : groupData.entrySet()) {
            String groupId = entry.getKey();
            boolean enabled = dataManager.isGroupAIEnabled(groupId);
            
            Vector<Object> row = new Vector<>();
            row.add(groupId);
            row.add(enabled ? "已启用" : "已禁用");
            row.add("操作");
            
            tableModel.addRow(row);
        }
    }
    
    /**
     * 切换群组AI状态
     */
    public void toggleGroupAIStatus(int row) {
        String groupId = (String) tableModel.getValueAt(row, 0);
        boolean currentStatus = dataManager.isGroupAIEnabled(groupId);
        
        // 切换状态
        dataManager.setGroupAIEnabled(groupId, !currentStatus);
        
        // 更新表格
        updateData();
    }
    
    /**
     * 按钮渲染器
     */
    static class ButtonRenderer extends JButton implements javax.swing.table.TableCellRenderer {
        public ButtonRenderer() {
            setOpaque(true);
        }
        
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            boolean enabled = ((String) table.getValueAt(row, 1)).equals("已启用");
            setText(enabled ? "禁用" : "启用");
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
        private final GroupPanel panel;
        private JTable table;
        
        public ButtonEditor(JCheckBox checkBox, GroupPanel panel) {
            super(checkBox);
            this.panel = panel;
            button = new JButton();
            button.setOpaque(true);
            button.addActionListener(e -> fireEditingStopped());
        }
        
        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            boolean enabled = ((String) table.getValueAt(row, 1)).equals("已启用");
            label = enabled ? "禁用" : "启用";
            button.setText(label);
            isPushed = true;
            this.table = table;
            return button;
        }
        
        @Override
        public Object getCellEditorValue() {
            if (isPushed) {
                // 处理按钮点击事件
                panel.toggleGroupAIStatus(table.getSelectedRow());
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