package cn.ningmo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;

public class DataManager {
    private static final Logger logger = LoggerFactory.getLogger(DataManager.class);
    private static final String DATA_FILE = "data.yml";
    
    private Map<String, Object> data;
    
    // 群组数据: groupId -> 是否开启AI
    private Map<String, Boolean> groupAIEnabled;
    
    // 用户数据: userId -> 用户信息
    private Map<String, Map<String, Object>> userData;
    
    // 全局私聊功能开关
    private Boolean privateMessageEnabled;
    
    private final ConfigLoader configLoader;
    
    public DataManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.data = new ConcurrentHashMap<>();
        this.groupAIEnabled = new ConcurrentHashMap<>();
        this.userData = new ConcurrentHashMap<>();
        this.privateMessageEnabled = null; // 初始化为null，表示使用配置文件中的默认值
    }
    
    @SuppressWarnings("unchecked")
    public void loadData() {
        File dataFile = new File(DATA_FILE);
        
        // 如果数据文件不存在，创建默认数据文件
        if (!dataFile.exists()) {
            createDefaultData();
        }
        
        // 初始化maps，防止NPE
        this.data = new ConcurrentHashMap<>();
        this.groupAIEnabled = new ConcurrentHashMap<>();
        this.userData = new ConcurrentHashMap<>();
        
        // 加载数据文件
        try (InputStream input = new FileInputStream(DATA_FILE)) {
            Yaml yaml = new Yaml();
            Map<String, Object> loadedData = yaml.load(input);
            
            if (loadedData == null) {
                logger.warn("数据文件为空，使用空数据");
                data = new HashMap<>();
                data.put("groups", new HashMap<>());
                data.put("users", new HashMap<>());
            } else {
                data = loadedData;
            }
            
            // 确保基本数据结构存在
            if (!data.containsKey("groups")) {
                data.put("groups", new HashMap<>());
            }
            
            if (!data.containsKey("users")) {
                data.put("users", new HashMap<>());
            }
            
            // 加载用户数据
            Map<String, Object> usersData = getDataMap("users");
            for (Map.Entry<String, Object> entry : usersData.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    userData.put(entry.getKey(), (Map<String, Object>) entry.getValue());
                }
            }
            
            // 加载群组数据
            Map<String, Object> groups = getDataMap("groups");
            for (Map.Entry<String, Object> entry : groups.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> groupData = (Map<String, Object>) entry.getValue();
                    groupAIEnabled.put(entry.getKey(), (Boolean) groupData.getOrDefault("ai_enabled", false));
                }
            }
            
            // 加载私聊功能设置
            Object privateMessageEnabledObj = data.get("private_message_enabled");
            if (privateMessageEnabledObj instanceof Boolean) {
                this.privateMessageEnabled = (Boolean) privateMessageEnabledObj;
                logger.info("从数据文件加载私聊功能状态: {}", this.privateMessageEnabled ? "启用" : "禁用");
            }
            
            logger.info("数据文件加载成功");
        } catch (IOException e) {
            logger.error("数据文件加载失败", e);
            // 初始化空数据
            data = new HashMap<>();
            data.put("groups", new HashMap<>());
            data.put("users", new HashMap<>());
        }
    }
    
    /**
     * 保存数据
     */
    public synchronized void saveData() {
        // 使用临时文件，避免保存失败导致数据丢失
        String tempFileName = DATA_FILE + ".temp";
        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            
            // 先写入临时文件
            try (Writer writer = new FileWriter(tempFileName)) {
                yaml.dump(data, writer);
            }
            
            // 成功写入临时文件后，替换原文件
            File tempFile = new File(tempFileName);
            File dataFile = new File(DATA_FILE);
            if (dataFile.exists() && !dataFile.delete()) {
                logger.warn("无法删除原数据文件，将尝试直接覆盖");
            }
            
            if (tempFile.renameTo(dataFile)) {
                logger.info("数据保存成功");
            } else {
                // 如果重命名失败，尝试复制内容
                try (FileReader reader = new FileReader(tempFileName);
                     FileWriter writer = new FileWriter(DATA_FILE)) {
                    char[] buffer = new char[1024];
                    int length;
                    while ((length = reader.read(buffer)) > 0) {
                        writer.write(buffer, 0, length);
                    }
                    logger.info("数据通过复制方式保存成功");
                }
            }
        } catch (IOException e) {
            logger.error("保存数据失败", e);
        } finally {
            // 清理临时文件
            try {
                File tempFile = new File(tempFileName);
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            } catch (Exception e) {
                logger.warn("清理临时文件失败", e);
            }
        }
    }
    
    /**
     * 获取数据中的Map
     * @param key 数据键
     * @return 数据Map，如果不存在则返回空Map
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getDataMap(String key) {
        if (data.containsKey(key)) {
            Object value = data.get(key);
            if (value instanceof Map) {
                try {
                    return (Map<String, Object>) value;
                } catch (ClassCastException e) {
                    logger.error("数据类型转换失败: {}", key, e);
                }
            } else {
                logger.warn("数据类型不是Map: {}, 实际类型: {}", key, value != null ? value.getClass().getName() : "null");
            }
        }
        // 如果不存在或类型错误，创建新的Map
        Map<String, Object> newMap = new HashMap<>();
        data.put(key, newMap);
        return newMap;
    }
    
    private void createDefaultData() {
        logger.info("创建默认数据文件");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(DATA_FILE);
             OutputStream output = new FileOutputStream(DATA_FILE)) {
            if (input != null) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = input.read(buffer)) > 0) {
                    output.write(buffer, 0, length);
                }
            } else {
                // 创建空数据文件
                Map<String, Object> defaultData = new HashMap<>();
                defaultData.put("groups", new HashMap<>());
                defaultData.put("users", new HashMap<>());
                
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                options.setPrettyFlow(true);
                
                Yaml yaml = new Yaml(options);
                yaml.dump(defaultData, new OutputStreamWriter(output));
            }
            
            logger.info("默认数据文件创建成功");
        } catch (IOException e) {
            logger.error("创建默认数据文件失败", e);
        }
    }
    
    // 群组相关方法
    public boolean isGroupAIEnabled(String groupId) {
        Map<String, Object> group = getGroupData(groupId);
        Object value = group.get("ai_enabled");
        
        // 明确处理可能的类型问题
        if (value instanceof Boolean) {
            boolean enabled = (Boolean) value;
            logger.debug("群 {} 的AI状态: {}", groupId, enabled ? "启用" : "禁用");
            return enabled;
        }
        
        // 默认禁用AI
        logger.debug("群 {} 的AI状态未设置，默认: 禁用", groupId);
        return false;
    }
    
    public void setGroupAIEnabled(String groupId, boolean enabled) {
        logger.info("设置群 {} 的AI状态为: {}", groupId, enabled ? "启用" : "禁用");
        Map<String, Object> group = getGroupData(groupId);
        group.put("ai_enabled", enabled);
        
        // 更新内存中的映射
        groupAIEnabled.put(groupId, enabled);
        
        // 立即保存数据，确保设置生效
        saveData();
        
        logger.info("成功更新群 {} 的AI状态为: {}", groupId, enabled ? "启用" : "禁用");
    }
    
    /**
     * 获取群组数据，如果不存在则创建默认数据
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getGroupData(String groupId) {
        Map<String, Object> groups = getDataMap("groups");
        
        if (!groups.containsKey(groupId)) {
            logger.info("为群 {} 创建默认数据", groupId);
            Map<String, Object> defaultGroupData = new HashMap<>();
            defaultGroupData.put("ai_enabled", false); // 默认禁用AI
            groups.put(groupId, defaultGroupData);
            // 延迟保存，避免频繁IO操作
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    saveData();
                }
            }, 5000);
        }
        
        Object groupData = groups.get(groupId);
        if (groupData instanceof Map) {
            try {
                return (Map<String, Object>) groupData;
            } catch (ClassCastException e) {
                logger.error("群组数据类型转换失败: {}", groupId, e);
            }
        }
        
        // 如果数据类型不正确，创建新的Map
        Map<String, Object> newGroupData = new HashMap<>();
        newGroupData.put("ai_enabled", false);
        groups.put(groupId, newGroupData);
        return newGroupData;
    }
    
    // 用户相关方法
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserData(String userId) {
        return userData.computeIfAbsent(userId, k -> {
            Map<String, Object> defaultUserData = new HashMap<>();
            // 从配置文件获取默认模型，而不是硬编码
            String defaultModel = configLoader.getConfigString("ai.default_model", "gpt-3.5-turbo");
            defaultUserData.put("model", defaultModel);
            defaultUserData.put("persona", "default");
            defaultUserData.put("conversation", new HashMap<>());
            return defaultUserData;
        });
    }
    
    public void setUserModel(String userId, String model) {
        Map<String, Object> user = getUserData(userId);
        user.put("model", model);
        saveData();
    }
    
    public void setUserPersona(String userId, String persona) {
        Map<String, Object> user = getUserData(userId);
        user.put("persona", persona);
        saveData();
    }
    
    public String getUserModel(String userId) {
        Map<String, Object> user = getUserData(userId);
        return (String) user.getOrDefault("model", "gpt-3.5-turbo");
    }
    
    public String getUserPersona(String userId) {
        Map<String, Object> user = getUserData(userId);
        return (String) user.getOrDefault("persona", "default");
    }
    
    // 私聊功能管理方法
    public boolean isPrivateMessageEnabled() {
        // 如果动态设置过，使用动态设置的值
        if (privateMessageEnabled != null) {
            return privateMessageEnabled;
        }
        // 否则使用配置文件中的默认值
        return configLoader.getConfigBoolean("bot.enable_private_message", false);
    }
    
    public void setPrivateMessageEnabled(boolean enabled) {
        logger.info("设置全局私聊功能状态为: {}", enabled ? "启用" : "禁用");
        this.privateMessageEnabled = enabled;
        
        // 将设置保存到数据文件中
        data.put("private_message_enabled", enabled);
        saveData();
        
        logger.info("成功更新全局私聊功能状态为: {}", enabled ? "启用" : "禁用");
    }
} 