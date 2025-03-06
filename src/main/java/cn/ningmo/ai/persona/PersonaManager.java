package cn.ningmo.ai.persona;

import cn.ningmo.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PersonaManager {
    private static final Logger logger = LoggerFactory.getLogger(PersonaManager.class);
    
    private final ConfigLoader configLoader;
    private final Map<String, String> personas;
    
    public PersonaManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.personas = new HashMap<>();
        initPersonas();
    }
    
    private void initPersonas() {
        Map<String, Object> personaConfigs = configLoader.getConfigMap("ai.personas");
        
        // 如果配置为空，添加默认人设
        if (personaConfigs.isEmpty()) {
            personas.put("default", "你是一个有用的AI助手，请用中文回答问题。");
            personas.put("猫娘", "你是一个可爱的猫娘，说话时请在句尾加上”喵~”，性格可爱，温顺，喜欢撒娇。");
            personas.put("专业顾问", "你是一个专业的顾问，擅长分析问题并给出专业的建议。回答要全面、客观，语气要严谨、专业。");
            return;
        }
        
        // 加载配置的所有人设
        for (Map.Entry<String, Object> entry : personaConfigs.entrySet()) {
            String personaName = entry.getKey();
            if (entry.getValue() instanceof String) {
                String prompt = (String) entry.getValue();
                personas.put(personaName, prompt);
            }
        }
        
        logger.info("已加载{}个人设", personas.size());
    }
    
    public List<String> listPersonas() {
        return new ArrayList<>(personas.keySet());
    }
    
    public boolean hasPersona(String personaName) {
        return personas.containsKey(personaName);
    }
    
    public String getPersonaPrompt(String personaName) {
        if (!personas.containsKey(personaName)) {
            logger.warn("人设{}不存在，使用默认人设", personaName);
            return personas.getOrDefault("default", "你是一个有用的AI助手，请用中文回答问题。");
        }
        
        return personas.get(personaName);
    }
} 