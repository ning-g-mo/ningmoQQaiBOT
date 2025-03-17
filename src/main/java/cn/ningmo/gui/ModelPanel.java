package cn.ningmo.gui;

import cn.ningmo.ai.model.ModelManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

/**
 * 模型管理面板
 */
public class ModelPanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(ModelPanel.class);
    
    private final ModelManager modelManager;
    
    private JTable modelTable;
    private DefaultTableModel tableModel;
    
    /**
     * 构造函数
     */
    public ModelPanel(ModelManager modelManager) {
        this.modelManager = modelManager;
        
        initUI();
        updateData();
    }
    
    /**
     * 初始化UI
     */
    private void initUI() {
        setLayout(new BorderLayout());
        
        // 创建表格
        String[] columnNames = {"模型名称", "类型", "描述"};
        tableModel = new DefaultTableModel(columnNames, 0);
        modelTable = new JTable(tableModel);
        
        // 设置表格列宽
        modelTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        modelTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        modelTable.getColumnModel().getColumn(2).setPreferredWidth(400);
        
        // 添加表格到滚动面板
        JScrollPane scrollPane = new JScrollPane(modelTable);
        add(scrollPane, BorderLayout.CENTER);
        
        // 创建操作面板
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        
        // 添加刷新按钮
        JButton refreshButton = new JButton("刷新");
        refreshButton.addActionListener(e -> updateData());
        controlPanel.add(refreshButton);
        
        // 添加查看详情按钮
        JButton viewButton = new JButton("查看详情");
        viewButton.addActionListener(e -> {
            int selectedRow = modelTable.getSelectedRow();
            if (selectedRow == -1) {
                JOptionPane.showMessageDialog(this, "请选择一个模型", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            String modelName = (String) tableModel.getValueAt(selectedRow, 0);
            Map<String, String> details = modelManager.getModelDetails(modelName);
            
            // 显示模型详情
            JOptionPane.showMessageDialog(
                this,
                "模型名称: " + modelName + "\n" +
                "类型: " + details.getOrDefault("type", "未知") + "\n" +
                "描述: " + details.getOrDefault("description", "无描述"),
                "模型详情",
                JOptionPane.INFORMATION_MESSAGE
            );
        });
        controlPanel.add(viewButton);
        
        // 添加提示标签
        JLabel tipLabel = new JLabel("提示: 模型配置在config.yml文件中设置，请重启应用以应用配置更改");
        controlPanel.add(tipLabel);
        
        // 添加操作面板
        add(controlPanel, BorderLayout.SOUTH);
    }
    
    /**
     * 更新数据
     */
    public void updateData() {
        // 清空表格
        tableModel.setRowCount(0);
        
        // 获取所有模型
        for (String modelName : modelManager.listModels()) {
            Map<String, String> details = modelManager.getModelDetails(modelName);
            
            Object[] row = {
                modelName,
                details.getOrDefault("type", "未知"),
                details.getOrDefault("description", "无描述")
            };
            
            tableModel.addRow(row);
        }
    }
} 