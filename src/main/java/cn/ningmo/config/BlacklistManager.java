package cn.ningmo.config;

import cn.ningmo.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 黑名单管理器
 * 管理被禁止使用AI服务的用户列表
 */
public class BlacklistManager {
    private static final Logger logger = LoggerFactory.getLogger(BlacklistManager.class);
    private static final String DATA_DIR = "data";
    private static final String BLACKLIST_FILE = DATA_DIR + "/blacklist.yml";
    
    private final Set<String> blacklistedUsers;
    private final ConfigLoader configLoader;
    
    public BlacklistManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.blacklistedUsers = Collections.newSetFromMap(new ConcurrentHashMap<>());
        
        // 确保data目录存在
        CommonUtils.ensureDirectoryExists(DATA_DIR);
        
        // 加载黑名单数据
        loadBlacklist();
    }
    
    /**
     * 加载黑名单数据
     */
    @SuppressWarnings("unchecked")
    private void loadBlacklist() {
        File blacklistFile = new File(BLACKLIST_FILE);
        
        // 如果黑名单文件不存在，创建一个空文件
        if (!blacklistFile.exists()) {
            createEmptyBlacklistFile();
            return;
        }
        
        // 加载黑名单文件
        try (InputStream input = new FileInputStream(BLACKLIST_FILE)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(input);
            
            if (data == null) {
                logger.warn("黑名单文件为空，使用空黑名单");
                return;
            }
            
            if (data.containsKey("blacklist") && data.get("blacklist") instanceof List) {
                List<Object> blacklist = (List<Object>) data.get("blacklist");
                for (Object item : blacklist) {
                    blacklistedUsers.add(String.valueOf(item));
                }
                logger.info("已加载{}个黑名单用户", blacklistedUsers.size());
            }
        } catch (IOException e) {
            logger.error("加载黑名单文件失败", e);
        }
    }
    
    /**
     * 创建空的黑名单文件
     */
    private void createEmptyBlacklistFile() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("blacklist", new ArrayList<>());
            
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            try (Writer writer = new FileWriter(BLACKLIST_FILE)) {
                yaml.dump(data, writer);
            }
            logger.info("已创建空黑名单文件");
        } catch (IOException e) {
            logger.error("创建黑名单文件失败", e);
        }
    }
    
    /**
     * 保存黑名单数据
     */
    public void saveBlacklist() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("blacklist", new ArrayList<>(blacklistedUsers));
            
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            try (Writer writer = new FileWriter(BLACKLIST_FILE)) {
                yaml.dump(data, writer);
            }
            logger.info("黑名单保存成功，共{}个用户", blacklistedUsers.size());
        } catch (IOException e) {
            logger.error("保存黑名单失败", e);
        }
    }
    
    /**
     * 检查用户是否在黑名单中
     * @param userId 用户QQ号
     * @return 是否在黑名单中
     */
    public boolean isUserBlacklisted(String userId) {
        return blacklistedUsers.contains(userId);
    }
    
    /**
     * 将用户添加到黑名单
     * @param userId 用户QQ号
     * @return 是否成功添加（用户不在黑名单中时才返回true）
     */
    public boolean addToBlacklist(String userId) {
        if (blacklistedUsers.add(userId)) {
            logger.info("用户{}已添加到黑名单", userId);
            saveBlacklist();
            return true;
        }
        return false;
    }
    
    /**
     * 将用户从黑名单中移除
     * @param userId 用户QQ号
     * @return 是否成功移除（用户在黑名单中时才返回true）
     */
    public boolean removeFromBlacklist(String userId) {
        if (blacklistedUsers.remove(userId)) {
            logger.info("用户{}已从黑名单中移除", userId);
            saveBlacklist();
            return true;
        }
        return false;
    }
    
    /**
     * 获取所有黑名单用户
     * @return 黑名单用户列表
     */
    public List<String> getBlacklistedUsers() {
        return new ArrayList<>(blacklistedUsers);
    }
} 