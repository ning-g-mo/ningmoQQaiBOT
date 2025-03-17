package cn.ningmo.gui;

import cn.ningmo.config.FilterWordManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * 屏蔽词管理面板
 */
public class FilterWordPanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(FilterWordPanel.class);
    
    private final FilterWordManager filterWordManager;
    
    private JTable filterWordTable;
    private DefaultTableModel tableModel;
    private JTextField filterWordField;
    private JLabel statusLabel;
    
    /**
     * 构造函数
     */
    public FilterWordPanel(FilterWordManager filterWordManager) {
        this.filterWordManager = filterWordManager;
        
        initUI();
        updateData();
    }
    
    /**
     * 初始化UI
     */
    private void initUI() {
        setLayout(new BorderLayout());
        
        // 创建表格
        String[] columnNames = {"屏蔽词", "操作"};
        tableModel = new DefaultTableModel(columnNames, 0);
        filterWordTable = new JTable(tableModel);
        
        // 设置表格列宽
        filterWordTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        filterWordTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        
        // 设置操作列的渲染器和编辑器
        filterWordTable.getColumnModel().getColumn(1).setCellRenderer(new ButtonRenderer("删除"));
        filterWordTable.getColumnModel().getColumn(1).setCellEditor(new ButtonEditor(new JCheckBox(), "删除", this));
        
        // 添加表格到滚动面板
        JScrollPane scrollPane = new JScrollPane(filterWordTable);
        add(scrollPane, BorderLayout.CENTER);
        
        // 创建操作面板
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BorderLayout());
        
        // 创建状态面板
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel = new JLabel("屏蔽词功能状态: " + (filterWordManager.isFilterEnabled() ? "已启用" : "已禁用"));
        statusPanel.add(statusLabel);
        statusPanel.add(new JLabel("  |  屏蔽词回复: " + filterWordManager.getFilterReplyMessage()));
        
        // 添加状态面板
        controlPanel.add(statusPanel, BorderLayout.NORTH);
        
        // 创建操作按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        // 添加屏蔽词输入框
        buttonPanel.add(new JLabel("屏蔽词:"));
        filterWordField = new JTextField(20);
        buttonPanel.add(filterWordField);
        
        // 添加添加按钮
        JButton addButton = new JButton("添加屏蔽词");
        addButton.addActionListener(e -> {
            String filterWord = filterWordField.getText().trim();
            if (filterWord.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入屏蔽词", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (filterWordManager.addFilterWord(filterWord)) {
                updateData();
                JOptionPane.showMessageDialog(this, "已添加屏蔽词: " + filterWord, "成功", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "屏蔽词已存在: " + filterWord, "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        buttonPanel.add(addButton);
        
        // 添加删除按钮
        JButton removeButton = new JButton("删除屏蔽词");
        removeButton.addActionListener(e -> {
            String filterWord = filterWordField.getText().trim();
            if (filterWord.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入屏蔽词", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (filterWordManager.removeFilterWord(filterWord)) {
                updateData();
                JOptionPane.showMessageDialog(this, "已删除屏蔽词: " + filterWord, "成功", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "屏蔽词不存在: " + filterWord, "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        buttonPanel.add(removeButton);
        
        // 添加刷新按钮
        JButton refreshButton = new JButton("刷新");
        refreshButton.addActionListener(e -> updateData());
        buttonPanel.add(refreshButton);
        
        // 添加按钮面板
        controlPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        // 添加操作面板
        add(controlPanel, BorderLayout.SOUTH);
    }
    
    /**
     * 更新数据
     */
    public void updateData() {
        // 清空表格
        tableModel.setRowCount(0);
        
        // 获取所有屏蔽词
        List<String> filterWords = filterWordManager.getFilterWords();
        
        // 添加到表格
        for (String word : filterWords) {
            Object[] row = {word, "删除"};
            tableModel.addRow(row);
        }
        
        // 更新状态标签
        statusLabel.setText("屏蔽词功能状态: " + (filterWordManager.isFilterEnabled() ? "已启用" : "已禁用"));
    }
    
    /**
     * 删除屏蔽词
     */
    public void removeFilterWord(int row) {
        String word = (String) tableModel.getValueAt(row, 0);
        if (filterWordManager.removeFilterWord(word)) {
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
        private final FilterWordPanel panel;
        
        public ButtonEditor(JCheckBox checkBox, String label, FilterWordPanel panel) {
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
                panel.removeFilterWord(panel.filterWordTable.getSelectedRow());
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