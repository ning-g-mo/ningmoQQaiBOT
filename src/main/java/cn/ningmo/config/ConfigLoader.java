package cn.ningmo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String CONFIG_FILE = "config.yml";
    
    private Map<String, Object> config;
    
    public ConfigLoader() {
        this.config = new HashMap<>();
    }
    
    public void loadConfig() {
        Path configPath = Paths.get(CONFIG_FILE);
        
        // 如果配置文件不存在，创建默认配置文件
        if (!Files.exists(configPath)) {
            createDefaultConfig();
        }
        
        // 加载配置文件
        try (InputStream input = new FileInputStream(CONFIG_FILE)) {
            Yaml yaml = new Yaml();
            config = yaml.load(input);
            logger.info("配置文件加载成功");
        } catch (IOException e) {
            logger.error("配置文件加载失败", e);
            System.exit(1);
        }
        
        // 初始化日志
        initLogging();
    }
    
    private void createDefaultConfig() {
        logger.info("创建默认配置文件");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE);
             OutputStream output = new FileOutputStream(CONFIG_FILE)) {
            if (input == null) {
                logger.error("无法加载默认配置文件");
                System.exit(1);
            }
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) > 0) {
                output.write(buffer, 0, length);
            }
            
            logger.info("默认配置文件创建成功");
        } catch (IOException e) {
            logger.error("创建默认配置文件失败", e);
            System.exit(1);
        }
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, T defaultValue) {
        String[] keys = key.split("\\.");
        Map<String, Object> current = config;
        
        for (int i = 0; i < keys.length - 1; i++) {
            Object value = current.get(keys[i]);
            if (!(value instanceof Map)) {
                return defaultValue;
            }
            current = (Map<String, Object>) value;
        }
        
        Object value = current.get(keys[keys.length - 1]);
        if (value == null) {
            return defaultValue;
        }
        
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }
    
    /**
     * 获取配置中的字符串值，如果不存在则返回空字符串
     */
    public String getConfigString(String key) {
        return getConfigString(key, "");
    }
    
    /**
     * 获取配置中的字符串值，如果不存在则返回默认值
     */
    public String getConfigString(String key, String defaultValue) {
        Object value = getConfig(key, null);
        return value != null ? value.toString() : defaultValue;
    }
    
    public boolean getConfigBoolean(String key) {
        return getConfig(key, false);
    }
    
    @SuppressWarnings("unchecked")
    public Map<String, Object> getConfigMap(String key) {
        return getConfig(key, new HashMap<>());
    }
    
    /**
     * 初始化日志级别
     */
    private void initLogging() {
        String configLogLevel = getConfigString("logging.level", "");
        if (!configLogLevel.isEmpty()) {
            // 将配置中的日志级别设置为系统属性，供logback.xml使用
            System.setProperty("log.level", configLogLevel.toUpperCase());
            System.setProperty("log.project.level", configLogLevel.toUpperCase());
            logger.info("已设置日志级别：{}", configLogLevel.toUpperCase());
        }
    }
} 