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
import java.util.regex.Pattern;

/**
 * 屏蔽词管理器
 * 管理消息中的屏蔽词，拦截包含屏蔽词的消息
 */
public class FilterWordManager {
    private static final Logger logger = LoggerFactory.getLogger(FilterWordManager.class);
    private static final String DATA_DIR = "data";
    private static final String FILTER_WORDS_FILE = DATA_DIR + "/filter_words.yml";
    
    private final Set<String> filterWords;
    private final ConfigLoader configLoader;
    private final List<Pattern> filterPatterns;
    
    public FilterWordManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.filterWords = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.filterPatterns = new ArrayList<>();
        
        // 确保data目录存在
        CommonUtils.ensureDirectoryExists(DATA_DIR);
        
        // 加载屏蔽词数据
        loadFilterWords();
    }
    
    /**
     * 加载屏蔽词数据
     */
    @SuppressWarnings("unchecked")
    private void loadFilterWords() {
        File filterWordsFile = new File(FILTER_WORDS_FILE);
        
        // 如果屏蔽词文件不存在，创建一个空文件
        if (!filterWordsFile.exists()) {
            createEmptyFilterWordsFile();
            return;
        }
        
        // 加载屏蔽词文件
        try (InputStream input = new FileInputStream(FILTER_WORDS_FILE)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(input);
            
            if (data == null) {
                logger.warn("屏蔽词文件为空，使用空屏蔽词列表");
                return;
            }
            
            if (data.containsKey("filter_words") && data.get("filter_words") instanceof List) {
                List<Object> filterWordsList = (List<Object>) data.get("filter_words");
                for (Object item : filterWordsList) {
                    String word = String.valueOf(item);
                    filterWords.add(word);
                    try {
                        // 尝试将屏蔽词编译为正则表达式模式
                        filterPatterns.add(Pattern.compile(word, Pattern.CASE_INSENSITIVE));
                    } catch (Exception e) {
                        // 如果无法作为正则表达式编译，则作为普通字符串处理
                        filterPatterns.add(Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE));
                    }
                }
                logger.info("已加载{}个屏蔽词", filterWords.size());
            }
        } catch (IOException e) {
            logger.error("加载屏蔽词文件失败", e);
        }
    }
    
    /**
     * 创建空的屏蔽词文件
     */
    private void createEmptyFilterWordsFile() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("filter_words", new ArrayList<>());
            
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            try (Writer writer = new FileWriter(FILTER_WORDS_FILE)) {
                yaml.dump(data, writer);
            }
            logger.info("已创建空屏蔽词文件");
        } catch (IOException e) {
            logger.error("创建屏蔽词文件失败", e);
        }
    }
    
    /**
     * 保存屏蔽词数据
     */
    public void saveFilterWords() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("filter_words", new ArrayList<>(filterWords));
            
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            
            Yaml yaml = new Yaml(options);
            try (Writer writer = new FileWriter(FILTER_WORDS_FILE)) {
                yaml.dump(data, writer);
            }
            logger.info("屏蔽词列表保存成功，共{}个词语", filterWords.size());
        } catch (IOException e) {
            logger.error("保存屏蔽词列表失败", e);
        }
    }
    
    /**
     * 检查消息是否包含屏蔽词
     * @param message 待检查的消息
     * @return 是否包含屏蔽词
     */
    public boolean containsFilterWord(String message) {
        // 如果屏蔽词功能未启用，直接返回false
        if (!isFilterEnabled()) {
            return false;
        }
        
        // 如果消息为空，直接返回false
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        
        // 使用正则表达式模式检查
        for (Pattern pattern : filterPatterns) {
            if (pattern.matcher(message).find()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 添加屏蔽词
     * @param word 词语
     * @return 是否成功添加（词语不在屏蔽词列表中时才返回true）
     */
    public boolean addFilterWord(String word) {
        if (filterWords.add(word)) {
            try {
                // 尝试将屏蔽词编译为正则表达式模式
                filterPatterns.add(Pattern.compile(word, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                // 如果无法作为正则表达式编译，则作为普通字符串处理
                filterPatterns.add(Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE));
            }
            logger.info("已添加屏蔽词: {}", word);
            saveFilterWords();
            return true;
        }
        return false;
    }
    
    /**
     * 移除屏蔽词
     * @param word 词语
     * @return 是否成功移除（词语在屏蔽词列表中时才返回true）
     */
    public boolean removeFilterWord(String word) {
        if (filterWords.remove(word)) {
            // 重新构建正则表达式模式列表
            rebuildPatterns();
            logger.info("已移除屏蔽词: {}", word);
            saveFilterWords();
            return true;
        }
        return false;
    }
    
    /**
     * 重新构建正则表达式模式列表
     */
    private void rebuildPatterns() {
        filterPatterns.clear();
        for (String word : filterWords) {
            try {
                // 尝试将屏蔽词编译为正则表达式模式
                filterPatterns.add(Pattern.compile(word, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                // 如果无法作为正则表达式编译，则作为普通字符串处理
                filterPatterns.add(Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE));
            }
        }
    }
    
    /**
     * 获取所有屏蔽词
     * @return 屏蔽词列表
     */
    public List<String> getFilterWords() {
        return new ArrayList<>(filterWords);
    }
    
    /**
     * 检查屏蔽词功能是否启用
     * @return 是否启用
     */
    public boolean isFilterEnabled() {
        return configLoader.getConfig("filter.enabled", true);
    }
    
    /**
     * 获取触发屏蔽词时的回复消息
     * @return 回复消息
     */
    public String getFilterReplyMessage() {
        return configLoader.getConfigString("filter.reply_message", "您的消息包含屏蔽词，已被拦截");
    }
} 