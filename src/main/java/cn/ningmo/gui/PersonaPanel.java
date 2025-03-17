package cn.ningmo.gui;

import cn.ningmo.ai.persona.PersonaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 人设管理面板
 */
public class PersonaPanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(PersonaPanel.class);
    
    private final PersonaManager personaManager;
    
    private JTable personaTable;
    private DefaultTableModel tableModel;
    private JTextField personaNameField;
    private JTextArea personaContentArea;
    
    /**
     * 构造函数
     */
    public PersonaPanel(PersonaManager personaManager) {
        this.personaManager = personaManager;
        
        initUI();
        updateData();
    }
    
    /**
     * 初始化UI
     */
    private void initUI() {
        setLayout(new BorderLayout());
        
        // 创建表格
        String[] columnNames = {"人设名称", "操作"};
        tableModel = new DefaultTableModel(columnNames, 0);
        personaTable = new JTable(tableModel);
        
        // 设置表格列宽
        personaTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        personaTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        
        // 设置操作列的渲染器和编辑器
        personaTable.getColumnModel().getColumn(1).setCellRenderer(new ButtonPanelRenderer());
        personaTable.getColumnModel().getColumn(1).setCellEditor(new ButtonPanelEditor(new JCheckBox(), this));
        
        // 添加表格到滚动面板
        JScrollPane tableScrollPane = new JScrollPane(personaTable);
        tableScrollPane.setPreferredSize(new Dimension(400, 300));
        
        // 创建人设内容编辑面板
        JPanel editPanel = new JPanel(new BorderLayout());
        editPanel.setBorder(BorderFactory.createTitledBorder("人设编辑"));
        
        // 添加人设名称输入框
        JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        namePanel.add(new JLabel("人设名称:"));
        personaNameField = new JTextField(20);
        namePanel.add(personaNameField);
        
        // 添加人设名称面板
        editPanel.add(namePanel, BorderLayout.NORTH);
        
        // 添加人设内容文本区域
        personaContentArea = new JTextArea();
        personaContentArea.setLineWrap(true);
        personaContentArea.setWrapStyleWord(true);
        JScrollPane contentScrollPane = new JScrollPane(personaContentArea);
        contentScrollPane.setBorder(BorderFactory.createTitledBorder("人设内容"));
        editPanel.add(contentScrollPane, BorderLayout.CENTER);
        
        // 添加操作按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        // 查看人设按钮
        JButton viewButton = new JButton("查看选中人设");
        viewButton.addActionListener(e -> {
            int selectedRow = personaTable.getSelectedRow();
            if (selectedRow == -1) {
                JOptionPane.showMessageDialog(this, "请选择一个人设", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            String personaName = (String) tableModel.getValueAt(selectedRow, 0);
            String content = personaManager.getPersonaPrompt(personaName);
            
            personaNameField.setText(personaName);
            personaContentArea.setText(content);
        });
        buttonPanel.add(viewButton);
        
        // 创建人设按钮
        JButton createButton = new JButton("创建人设");
        createButton.addActionListener(e -> {
            String personaName = personaNameField.getText().trim();
            String content = personaContentArea.getText().trim();
            
            if (personaName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入人设名称", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (content.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入人设内容", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (personaManager.createPersona(personaName, content)) {
                updateData();
                JOptionPane.showMessageDialog(this, "已创建人设: " + personaName, "成功", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "创建人设失败", "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttonPanel.add(createButton);
        
        // 更新人设按钮
        JButton updateButton = new JButton("更新人设");
        updateButton.addActionListener(e -> {
            String personaName = personaNameField.getText().trim();
            String content = personaContentArea.getText().trim();
            
            if (personaName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入人设名称", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (content.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入人设内容", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // 检查人设是否存在
            if (!personaManager.hasPersona(personaName)) {
                JOptionPane.showMessageDialog(this, "人设不存在: " + personaName, "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // 创建人设文件
            try {
                File file = new File("r/" + personaName + ".md");
                FileWriter writer = new FileWriter(file);
                writer.write(content);
                writer.close();
                
                // 刷新人设
                personaManager.refreshPersonas();
                updateData();
                
                JOptionPane.showMessageDialog(this, "已更新人设: " + personaName, "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                logger.error("更新人设失败", ex);
                JOptionPane.showMessageDialog(this, "更新人设失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttonPanel.add(updateButton);
        
        // 刷新按钮
        JButton refreshButton = new JButton("刷新人设列表");
        refreshButton.addActionListener(e -> {
            personaManager.refreshPersonas();
            updateData();
        });
        buttonPanel.add(refreshButton);
        
        // 添加按钮面板
        editPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        // 创建分割面板
        JSplitPane splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            tableScrollPane,
            editPanel
        );
        splitPane.setResizeWeight(0.3); // 设置分割比例
        
        // 添加分割面板
        add(splitPane, BorderLayout.CENTER);
    }
    
    /**
     * 更新数据
     */
    public void updateData() {
        // 清空表格
        tableModel.setRowCount(0);
        
        // 获取所有人设
        for (String personaName : personaManager.listPersonas()) {
            Object[] row = {personaName, null};
            tableModel.addRow(row);
        }
    }
    
    /**
     * 删除人设
     */
    public void deletePersona(int row) {
        String personaName = (String) tableModel.getValueAt(row, 0);
        
        // 确认删除
        int result = JOptionPane.showConfirmDialog(
            this,
            "确定要删除人设 " + personaName + " 吗？",
            "删除确认",
            JOptionPane.YES_NO_OPTION
        );
        
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        
        if (personaManager.deletePersona(personaName)) {
            updateData();
            JOptionPane.showMessageDialog(this, "已删除人设: " + personaName, "成功", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "删除人设失败", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 查看人设
     */
    public void viewPersona(int row) {
        String personaName = (String) tableModel.getValueAt(row, 0);
        String content = personaManager.getPersonaPrompt(personaName);
        
        personaNameField.setText(personaName);
        personaContentArea.setText(content);
    }
    
    /**
     * 按钮面板渲染器
     */
    static class ButtonPanelRenderer extends JPanel implements javax.swing.table.TableCellRenderer {
        private final JButton viewButton;
        private final JButton deleteButton;
        
        public ButtonPanelRenderer() {
            setLayout(new FlowLayout(FlowLayout.CENTER, 5, 0));
            
            viewButton = new JButton("查看");
            deleteButton = new JButton("删除");
            
            add(viewButton);
            add(deleteButton);
        }
        
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            return this;
        }
    }
    
    /**
     * 按钮面板编辑器
     */
    static class ButtonPanelEditor extends DefaultCellEditor {
        protected JPanel panel;
        protected JButton viewButton;
        protected JButton deleteButton;
        private String action = "";
        private final PersonaPanel personaPanel;
        
        public ButtonPanelEditor(JCheckBox checkBox, PersonaPanel personaPanel) {
            super(checkBox);
            this.personaPanel = personaPanel;
            
            panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
            
            viewButton = new JButton("查看");
            viewButton.addActionListener(e -> {
                action = "view";
                fireEditingStopped();
            });
            
            deleteButton = new JButton("删除");
            deleteButton.addActionListener(e -> {
                action = "delete";
                fireEditingStopped();
            });
            
            panel.add(viewButton);
            panel.add(deleteButton);
        }
        
        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            action = "";
            return panel;
        }
        
        @Override
        public Object getCellEditorValue() {
            if ("view".equals(action)) {
                personaPanel.viewPersona(personaPanel.personaTable.getSelectedRow());
            } else if ("delete".equals(action)) {
                personaPanel.deletePersona(personaPanel.personaTable.getSelectedRow());
            }
            action = "";
            return "";
        }
        
        @Override
        public boolean stopCellEditing() {
            return super.stopCellEditing();
        }
    }
} 