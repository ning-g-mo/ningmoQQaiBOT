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

public class DataManager {
    private static final Logger logger = LoggerFactory.getLogger(DataManager.class);
    private static final String DATA_FILE = "data.yml";
    
    private Map<String, Object> data;
    
    // 群组数据: groupId -> 是否开启AI
    private Map<String, Boolean> groupAIEnabled;
    
    // 用户数据: userId -> 用户信息
    private Map<String, Map<String, Object>> userData;
    
    public DataManager() {
        this.data = new ConcurrentHashMap<>();
        this.groupAIEnabled = new ConcurrentHashMap<>();
        this.userData = new ConcurrentHashMap<>();
    }
    
    public void loadData() {
        Path dataPath = Paths.get(DATA_FILE);
        
        // 如果数据文件不存在，创建默认数据文件
        if (!Files.exists(dataPath)) {
            createDefaultData();
        }
        
        // 加载数据文件
        try (InputStream input = new FileInputStream(DATA_FILE)) {
            Yaml yaml = new Yaml();
            data = yaml.load(input);
            if (data == null) {
                data = new HashMap<>();
            }
            
            // 加载群组数据
            Map<String, Object> groups = getDataMap("groups");
            for (Map.Entry<String, Object> entry : groups.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> groupData = (Map<String, Object>) entry.getValue();
                    groupAIEnabled.put(entry.getKey(), (Boolean) groupData.getOrDefault("ai_enabled", true));
                }
            }
            
            // 加载用户数据
            Map<String, Object> users = getDataMap("users");
            for (Map.Entry<String, Object> entry : users.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userInfo = (Map<String, Object>) entry.getValue();
                    userData.put(entry.getKey(), userInfo);
                }
            }
            
            logger.info("数据文件加载成功");
        } catch (IOException e) {
            logger.error("数据文件加载失败", e);
            data = new HashMap<>();
            groupAIEnabled = new ConcurrentHashMap<>();
            userData = new ConcurrentHashMap<>();
        }
    }
    
    public void saveData() {
        // 更新数据到主数据结构
        Map<String, Object> groups = new HashMap<>();
        for (Map.Entry<String, Boolean> entry : groupAIEnabled.entrySet()) {
            Map<String, Object> groupData = new HashMap<>();
            groupData.put("ai_enabled", entry.getValue());
            groups.put(entry.getKey(), groupData);
        }
        data.put("groups", groups);
        
        data.put("users", userData);
        
        // 保存数据
        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            try (Writer writer = new FileWriter(DATA_FILE)) {
                yaml.dump(data, writer);
            }
            logger.info("数据保存成功");
        } catch (IOException e) {
            logger.error("数据保存失败", e);
        }
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
    
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataMap(String key) {
        Object value = data.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        Map<String, Object> emptyMap = new HashMap<>();
        data.put(key, emptyMap);
        return emptyMap;
    }
    
    // 群组相关方法
    public boolean isGroupAIEnabled(String groupId) {
        return groupAIEnabled.getOrDefault(groupId, true);
    }
    
    public void setGroupAIEnabled(String groupId, boolean enabled) {
        groupAIEnabled.put(groupId, enabled);
        saveData();
    }
    
    // 用户相关方法
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserData(String userId) {
        return userData.computeIfAbsent(userId, k -> {
            Map<String, Object> defaultUserData = new HashMap<>();
            defaultUserData.put("model", "gpt-3.5-turbo");
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
} 