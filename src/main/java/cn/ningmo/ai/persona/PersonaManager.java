package cn.ningmo.ai.persona;

import cn.ningmo.config.ConfigLoader;
import cn.ningmo.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PersonaManager {
    private static final Logger logger = LoggerFactory.getLogger(PersonaManager.class);
    
    private final ConfigLoader configLoader;
    private final Map<String, String> personas;
    private final String personaDir = "r"; // 人设文件目录
    
    public PersonaManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.personas = new HashMap<>();
        
        // 确保人设目录存在
        ensurePersonaDirectoryExists();
        
        // 初始化人设（仅从目录加载）
        initPersonas();
    }
    
    /**
     * 确保人设目录存在
     */
    private void ensurePersonaDirectoryExists() {
        Path dirPath = Paths.get(personaDir);
        if (!Files.exists(dirPath)) {
            try {
                Files.createDirectories(dirPath);
                logger.info("创建人设目录: {}", personaDir);
                
                // 创建默认人设文件
                createDefaultPersonaFiles();
            } catch (IOException e) {
                logger.error("创建人设目录失败: {}", personaDir, e);
            }
        }
    }
    
    /**
     * 创建默认的人设文件
     */
    private void createDefaultPersonaFiles() {
        try {
            // 检查默认人设文件是否存在，不存在才创建
            Path defaultPath = Paths.get(personaDir, "default.md");
            if (!Files.exists(defaultPath)) {
                Files.write(defaultPath, 
                        "你是一个有用的AI助手，请用中文回答问题。".getBytes(StandardCharsets.UTF_8));
                logger.info("已创建默认人设文件");
            }
            
            // 创建猫娘人设
            Path catgirlPath = Paths.get(personaDir, "猫娘.md");
            if (!Files.exists(catgirlPath)) {
                Files.write(catgirlPath, 
                        "你是一个可爱的猫娘，说话时请在句尾加上喵~，性格可爱，温顺，喜欢撒娇。".getBytes(StandardCharsets.UTF_8));
                logger.info("已创建猫娘人设文件");
            }
            
            // 创建专业顾问人设
            Path advisorPath = Paths.get(personaDir, "专业顾问.md");
            if (!Files.exists(advisorPath)) {
                Files.write(advisorPath, 
                        "你是一个专业的顾问，擅长分析问题并给出专业的建议。回答要全面、客观，语气要严谨、专业。".getBytes(StandardCharsets.UTF_8));
                logger.info("已创建专业顾问人设文件");
            }
        } catch (IOException e) {
            logger.error("创建默认人设文件失败", e);
        }
    }
    
    private void initPersonas() {
        // 从r目录加载人设文件
        loadPersonasFromFiles();
        
        // 确保至少有默认人设
        ensureDefaultPersona();
        
        logger.info("已加载{}个人设", personas.size());
    }
    
    /**
     * 确保默认人设存在
     */
    private void ensureDefaultPersona() {
        if (!personas.containsKey("default")) {
            // 如果文件中没有加载到默认人设，则添加一个内存中的默认人设
            personas.put("default", "你是一个有用的AI助手，请用中文回答问题。");
            logger.warn("未找到默认人设文件，使用内存中的默认人设");
            
            // 尝试创建默认人设文件
            try {
                Path defaultPath = Paths.get(personaDir, "default.md");
                Files.write(defaultPath, 
                        personas.get("default").getBytes(StandardCharsets.UTF_8));
                logger.info("已自动创建默认人设文件");
            } catch (IOException e) {
                logger.error("创建默认人设文件失败", e);
            }
        }
    }
    
    /**
     * 从r目录加载人设文件
     */
    private void loadPersonasFromFiles() {
        Path dirPath = Paths.get(personaDir);
        
        try (Stream<Path> pathStream = Files.list(dirPath)) {
            List<Path> mdFiles = pathStream
                    .filter(path -> path.toString().endsWith(".md"))
                    .collect(Collectors.toList());
            
            for (Path file : mdFiles) {
                String fileName = file.getFileName().toString();
                String personaName = fileName.substring(0, fileName.length() - 3); // 去掉.md后缀
                
                try {
                    String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    personas.put(personaName, content.trim());
                    logger.debug("从文件加载人设: {}", personaName);
                } catch (IOException e) {
                    logger.error("读取人设文件失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            logger.error("扫描人设目录失败", e);
        }
    }
    
    /**
     * 刷新人设列表（重新加载所有人设文件）
     */
    public void refreshPersonas() {
        personas.clear();
        initPersonas();
        logger.info("已刷新人设，当前共有{}个人设", personas.size());
    }
    
    /**
     * 获取所有可用人设的列表
     */
    public List<String> listPersonas() {
        return new ArrayList<>(personas.keySet());
    }
    
    /**
     * 检查人设是否存在
     */
    public boolean hasPersona(String personaName) {
        if (personas.containsKey(personaName)) {
            return true;
        }
        
        // 如果内存中没有，尝试从文件读取
        Path personaPath = Paths.get(personaDir, personaName + ".md");
        if (Files.exists(personaPath)) {
            try {
                String content = new String(Files.readAllBytes(personaPath), StandardCharsets.UTF_8);
                personas.put(personaName, content.trim());
                return true;
            } catch (IOException e) {
                logger.error("读取人设文件失败: {}", personaPath, e);
            }
        }
        
        return false;
    }
    
    /**
     * 获取人设提示词
     */
    public String getPersonaPrompt(String personaName) {
        String basePrompt;
        
        // 如果人设不存在于内存中，尝试从文件读取
        if (!personas.containsKey(personaName)) {
            Path personaPath = Paths.get(personaDir, personaName + ".md");
            if (Files.exists(personaPath)) {
                try {
                    String content = new String(Files.readAllBytes(personaPath), StandardCharsets.UTF_8);
                    personas.put(personaName, content.trim());
                    basePrompt = content.trim();
                } catch (IOException e) {
                    logger.error("读取人设文件失败: {}", personaPath, e);
                    logger.warn("人设{}不存在，使用默认人设", personaName);
                    basePrompt = personas.getOrDefault("default", "你是一个有用的AI助手，请用中文回答问题。");
                }
            } else {
                logger.warn("人设{}不存在，使用默认人设", personaName);
                basePrompt = personas.getOrDefault("default", "你是一个有用的AI助手，请用中文回答问题。");
            }
        } else {
            basePrompt = personas.get(personaName);
        }
        
        // 添加关于多段消息和艾特群友的附加说明
        String specialInstructions = 
            "\n\n特殊指令说明：\n" +
            "1. 如果你想发送多条消息，请在消息之间使用 \\n---\\n 作为分隔符。系统会将你的回复拆分成多条单独发送。\n" +
            "2. 如果你认为某个问题不需要回复，可以回复 [NO_RESPONSE] 表示不发送任何消息。\n" +
            "3. 如果你想艾特(提及)群里的某个成员，可以使用 [CQ:at,qq=成员QQ号] 格式。例如：[CQ:at,qq=123456789] 你好！\n" +
            "4. 当前对话的群成员列表可能会在系统指令的末尾提供，你可以从中选择需要艾特的成员。\n" +
            "5. 重要：请不要艾特机器人自己，这可能导致消息循环和系统问题。";
        
        return basePrompt + specialInstructions;
    }
    
    /**
     * 创建新人设
     * @param personaName 人设名称
     * @param content 人设内容
     * @return 是否成功创建
     */
    public boolean createPersona(String personaName, String content) {
        // 检查人设名称是否合法
        if (personaName.contains("/") || personaName.contains("\\")) {
            logger.error("人设名称含有非法字符: {}", personaName);
            return false;
        }
        
        Path personaPath = Paths.get(personaDir, personaName + ".md");
        try {
            Files.write(personaPath, content.getBytes(StandardCharsets.UTF_8));
            personas.put(personaName, content);
            logger.info("创建人设成功: {}", personaName);
            return true;
        } catch (IOException e) {
            logger.error("创建人设文件失败: {}", personaName, e);
            return false;
        }
    }
    
    /**
     * 删除人设
     * @param personaName 人设名称
     * @return 是否成功删除
     */
    public boolean deletePersona(String personaName) {
        // 不允许删除默认人设
        if ("default".equals(personaName)) {
            logger.error("不允许删除默认人设");
            return false;
        }
        
        Path personaPath = Paths.get(personaDir, personaName + ".md");
        try {
            boolean deleted = Files.deleteIfExists(personaPath);
            if (deleted) {
                personas.remove(personaName);
                logger.info("删除人设成功: {}", personaName);
                return true;
            } else {
                logger.warn("人设不存在，无法删除: {}", personaName);
                return false;
            }
        } catch (IOException e) {
            logger.error("删除人设文件失败: {}", personaName, e);
            return false;
        }
    }
} 