package cn.ningmo.bot;

import cn.ningmo.ai.AIService;
import cn.ningmo.ai.model.ModelManager;
import cn.ningmo.ai.persona.PersonaManager;
import cn.ningmo.config.BlacklistManager;
import cn.ningmo.config.ConfigLoader;
import cn.ningmo.config.DataManager;
import cn.ningmo.config.FilterWordManager;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.ningmo.utils.CommonUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(MessageHandler.class);
    
    private final OneBotClient botClient;
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final AIService aiService;
    private ModelManager modelManager;
    private PersonaManager personaManager;
    private final BlacklistManager blacklistManager;
    private final FilterWordManager filterWordManager;
    
    private final ScheduledExecutorService executor;
    
    // 已处理消息ID缓存
    private final Map<String, Long> processedMessageIds = new HashMap<>();
    private static final int MAX_PROCESSED_MESSAGE_IDS = 200; // 最多保存200条消息ID
    
    // 添加请求完成状态跟踪
    private final Map<String, Boolean> completedRequests = new ConcurrentHashMap<>();
    
    public MessageHandler(OneBotClient botClient, ConfigLoader configLoader, DataManager dataManager, AIService aiService, BlacklistManager blacklistManager, FilterWordManager filterWordManager) {
        this.botClient = botClient;
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.aiService = aiService;
        this.blacklistManager = blacklistManager;
        this.filterWordManager = filterWordManager;
        this.modelManager = new ModelManager(configLoader);
        this.personaManager = new PersonaManager(configLoader);
        
        // 使用ScheduledExecutorService替代ExecutorService，以支持schedule方法
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.executor = Executors.newScheduledThreadPool(corePoolSize, r -> {
            Thread t = new Thread(r, "MessageProcessor-" + System.currentTimeMillis());
            t.setDaemon(true); // 设置为守护线程，不阻止JVM退出
            return t;
        });
        
        logger.info("初始化消息处理器, 线程池大小: {}", corePoolSize);
    }
    
    /**
     * 处理消息事件
     */
    public void handleMessage(JSONObject message) {
        try {
            // 获取消息类型，安全处理可能的类型差异
            String messageType = CommonUtils.safeGetString(message, "message_type");
            
            // 根据消息类型处理
            if ("group".equals(messageType)) {
                handleGroupMessage(message);
            } else if ("private".equals(messageType)) {
                handlePrivateMessage(message);
            }
        } catch (Exception e) {
            logger.error("处理消息时出错", e);
        }
    }
    
    /**
     * 处理转义字符
     * 将\n转换为实际的换行符
     * @param text 原始文本
     * @return 处理后的文本
     */
    private String processEscapeSequences(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // 将文本中的"\n"替换为实际的换行符
        // 使用正则表达式确保匹配的是"\n"字符串而不是已经存在的换行符
        return text.replaceAll("\\\\n", "\n");
    }
    
    /**
     * 处理AI回复中的@标记，将@用户名(QQ号)格式转换为CQ码
     * @param message 原始消息
     * @param groupId 群ID，用于获取群成员信息
     * @return 处理后的消息
     */
    private String processAtTags(String message, String groupId) {
        if (message == null || message.isEmpty() || !message.contains("@")) {
            return message;
        }
        
        try {
            // 获取群成员列表
            List<Map<String, String>> groupMembers = botClient.getGroupMembers(groupId);
            if (groupMembers == null || groupMembers.isEmpty()) {
                logger.warn("群 {} 成员列表为空或获取失败，无法处理@标记", groupId);
                return message;
            }
            
            logger.debug("处理群 {} 的@标记，获取到 {} 个成员", groupId, groupMembers.size());
            
            // 记录示例QQ号，用于检测和替换
            final String[] EXAMPLE_QQ_NUMBERS = {"123456789", "987654321", "这里填写实际QQ号", "000000000"};
            boolean foundExampleQQ = false;
            
            // 创建成员映射表：昵称/群名片 -> QQ号
            Map<String, String> memberMap = new HashMap<>();
            Map<String, String> memberIdToNameMap = new HashMap<>(); // QQ号 -> 昵称/群名片
            for (Map<String, String> member : groupMembers) {
                String userId = member.get("user_id");
                String nickname = member.get("nickname");
                String card = member.get("card"); // 群名片
                
                // 使用群名片（如果有）或昵称
                String displayName = !CommonUtils.isNullOrEmpty(card) ? card : nickname;
                
                if (userId != null && displayName != null) {
                    memberMap.put(displayName.toLowerCase(), userId);
                    memberIdToNameMap.put(userId, displayName);
                }
                
                // 同时添加nickname的映射，以防AI使用本名而非群名片
                if (userId != null && nickname != null && !nickname.equals(displayName)) {
                    memberMap.put(nickname.toLowerCase(), userId);
                }
            }
            
            // 输出详细的成员映射表，仅在DEBUG级别
            if (logger.isDebugEnabled()) {
                logger.debug("群 {} 成员映射表: {}", groupId, memberMap);
            }
            
            // 获取机器人自己的QQ号
            String selfId = configLoader.getConfigString("bot.self_id");
            
            // 替换已经包含的CQ码，确认它们是有效的
            // 模式为：[CQ:at,qq=数字]
            Pattern cqPattern = Pattern.compile("\\[CQ:at,qq=(\\d+)\\]");
            Matcher cqMatcher = cqPattern.matcher(message);
            StringBuffer cqBuffer = new StringBuffer();
            
            while (cqMatcher.find()) {
                String userId = cqMatcher.group(1);
                // 检查这个QQ号是否是示例QQ号
                boolean isExampleQQ = false;
                for (String exampleQQ : EXAMPLE_QQ_NUMBERS) {
                    if (exampleQQ.equals(userId)) {
                        isExampleQQ = true;
                        foundExampleQQ = true;
                        break;
                    }
                }
                
                // 检查是否是机器人自己
                boolean isSelfId = userId.equals(selfId);
                
                if (isExampleQQ) {
                    // 如果是示例QQ号，随机选择一个真实成员代替
                    String randomMemberId = getRandomGroupMember(groupMembers, selfId);
                    if (randomMemberId != null) {
                        String displayName = memberIdToNameMap.getOrDefault(randomMemberId, "群成员");
                        cqMatcher.appendReplacement(cqBuffer, "[CQ:at,qq=" + randomMemberId + "]");
                        logger.info("检测到AI使用示例QQ号，已替换为随机成员: {}({})", displayName, randomMemberId);
                    } else {
                        // 如果无法获取随机成员，使用通用文本
                        cqMatcher.appendReplacement(cqBuffer, "@某人");
                        logger.info("检测到AI使用示例QQ号，但无法获取随机成员，已替换为@某人");
                    }
                } else if (isSelfId) {
                    // 如果是机器人自己的QQ号，替换为普通文本以避免循环
                    cqMatcher.appendReplacement(cqBuffer, "我");
                    logger.warn("检测到AI尝试艾特机器人自己，已替换为普通文本");
                } else if (memberIdToNameMap.containsKey(userId)) {
                    // 保留原始CQ码，有效的成员
                    cqMatcher.appendReplacement(cqBuffer, "[CQ:at,qq=" + userId + "]");
                    logger.debug("保留有效CQ码，QQ号: {}", userId);
                } else {
                    // 无效QQ号，移除CQ码但保留@符号
                    cqMatcher.appendReplacement(cqBuffer, "@未知用户");
                    logger.debug("移除无效CQ码，QQ号: {}", userId);
                }
            }
            cqMatcher.appendTail(cqBuffer);
            message = cqBuffer.toString();
            
            // 处理@用户名(QQ号)格式
            Pattern pattern = Pattern.compile("@([^(\\s]+)\\((\\d+)\\)");
            Matcher matcher = pattern.matcher(message);
            StringBuffer sb = new StringBuffer();
            
            while (matcher.find()) {
                String username = matcher.group(1);
                String userId = matcher.group(2);
                
                // 检查QQ号是否是示例QQ号
                boolean isExampleQQ = false;
                for (String exampleQQ : EXAMPLE_QQ_NUMBERS) {
                    if (exampleQQ.equals(userId)) {
                        isExampleQQ = true;
                        foundExampleQQ = true;
                        break;
                    }
                }
                
                // 检查是否是机器人自己
                boolean isSelfId = userId.equals(selfId);
                
                if (isExampleQQ) {
                    // 如果是示例QQ号，随机选择一个真实成员代替
                    String randomMemberId = getRandomGroupMember(groupMembers, selfId);
                    if (randomMemberId != null) {
                        String displayName = memberIdToNameMap.getOrDefault(randomMemberId, "群成员");
                        matcher.appendReplacement(sb, "[CQ:at,qq=" + randomMemberId + "]");
                        logger.info("检测到AI使用示例QQ号，已替换为随机成员: {}({})", displayName, randomMemberId);
                    } else {
                        // 如果无法获取随机成员，使用通用文本
                        matcher.appendReplacement(sb, "@某人");
                        logger.info("检测到AI使用示例QQ号，但无法获取随机成员，已替换为@某人");
                    }
                } else if (isSelfId) {
                    // 如果是机器人自己的QQ号，替换为普通文本以避免循环
                    matcher.appendReplacement(sb, "我");
                    logger.warn("检测到AI尝试艾特机器人自己，已替换为普通文本");
                } else if (memberIdToNameMap.containsKey(userId)) {
                    // 有效的QQ号，替换为CQ码
                    matcher.appendReplacement(sb, "[CQ:at,qq=" + userId + "]");
                    logger.debug("已将@{}({})转换为CQ码", username, userId);
                } else {
                    // 无效的QQ号，保留原始@文本
                    matcher.appendReplacement(sb, "@" + username);
                    logger.debug("QQ号{}不在群成员列表中，保留原始@文本: @{}", userId, username);
                }
            }
            matcher.appendTail(sb);
            message = sb.toString();
            
            // 处理纯@用户名格式
            pattern = Pattern.compile("@([^\\s]+)");
            matcher = pattern.matcher(message);
            sb = new StringBuffer();
            
            while (matcher.find()) {
                String username = matcher.group(1);
                // 检查是否已经是CQ码
                if (username.startsWith("[CQ:at,")) {
                    matcher.appendReplacement(sb, "@" + username);
                    continue;
                }
                
                // 如果是AI试图艾特"某人"（之前替换的结果），保留原文本
                if (username.equals("某人")) {
                    matcher.appendReplacement(sb, "@某人");
                    continue;
                }
                
                // 检查用户名是否包含"梦泽"关键词，这种情况下可能是针对群名称的一部分
                if (username.contains("梦泽")) {
                    // 寻找用户名中包含"梦泽"的成员
                    String bestMatchId = null;
                    for (Map.Entry<String, String> entry : memberMap.entrySet()) {
                        if (entry.getKey().contains("梦泽")) {
                            bestMatchId = entry.getValue();
                            break;
                        }
                    }
                    
                    if (bestMatchId != null) {
                        matcher.appendReplacement(sb, "[CQ:at,qq=" + bestMatchId + "]");
                        logger.debug("匹配到梦泽用户: {}", bestMatchId);
                        continue;
                    }
                }
                
                // 在成员映射表中查找对应的QQ号
                String userId = memberMap.get(username.toLowerCase());
                
                if (userId != null) {
                    // 检查是否是机器人自己
                    boolean isSelfId = userId.equals(selfId);
                    
                    if (isSelfId) {
                        // 如果是机器人自己，替换为普通文本以避免循环
                        matcher.appendReplacement(sb, "我");
                        logger.warn("检测到AI尝试艾特机器人自己，已替换为普通文本");
                    } else {
                        // 找到对应QQ号，替换为CQ码
                        matcher.appendReplacement(sb, "[CQ:at,qq=" + userId + "]");
                        logger.debug("已将@{}转换为CQ码，QQ号: {}", username, userId);
                    }
                } else {
                    // 未找到，尝试模糊匹配
                    String bestMatch = findBestMatch(username, memberMap.keySet());
                    if (bestMatch != null) {
                        userId = memberMap.get(bestMatch.toLowerCase());
                        
                        // 检查是否是机器人自己
                        boolean isSelfId = userId.equals(selfId);
                        
                        if (isSelfId) {
                            // 如果是机器人自己，替换为普通文本以避免循环
                            matcher.appendReplacement(sb, "我");
                            logger.warn("检测到AI尝试艾特机器人自己(模糊匹配)，已替换为普通文本");
                        } else {
                            matcher.appendReplacement(sb, "[CQ:at,qq=" + userId + "]");
                            logger.debug("模糊匹配: @{} -> @{}，QQ号: {}", username, bestMatch, userId);
                        }
                    } else {
                        // 无法匹配，保持原样
                        matcher.appendReplacement(sb, matcher.group(0));
                        logger.debug("无法匹配用户名: {}", username);
                    }
                }
            }
            matcher.appendTail(sb);
            
            // 添加日志记录是否找到和处理了示例QQ号
            if (foundExampleQQ) {
                logger.info("消息中包含示例QQ号，已尝试用实际群成员替换");
            }
            
            logger.debug("@标记处理完成，返回处理后的消息: {}", 
                       sb.toString().length() > 50 ? sb.toString().substring(0, 50) + "..." : sb.toString());
            return sb.toString();
        } catch (Exception e) {
            logger.error("处理@标记时出错", e);
            return message; // 出错时返回原始消息
        }
    }
    
    /**
     * 从群成员列表中随机选择一个成员
     * @param groupMembers 群成员列表
     * @param selfId 机器人自己的QQ号，用于排除自己
     * @return 随机成员的QQ号，如果列表为空返回null
     */
    private String getRandomGroupMember(List<Map<String, String>> groupMembers, String selfId) {
        if (groupMembers == null || groupMembers.isEmpty()) {
            logger.warn("群成员列表为空，无法随机选择成员");
            return null;
        }
        
        // 过滤掉机器人自己
        List<Map<String, String>> filteredMembers = new ArrayList<>();
        
        for (Map<String, String> member : groupMembers) {
            String memberId = member.get("user_id");
            if (memberId != null && !memberId.equals(selfId)) {
                filteredMembers.add(member);
            }
        }
        
        if (filteredMembers.isEmpty()) {
            logger.warn("过滤后的群成员列表为空（可能只有机器人自己），无法随机选择成员");
            return null;
        }
        
        // 随机选择一个成员
        int randomIndex = (int) (Math.random() * filteredMembers.size());
        String selectedMemberId = filteredMembers.get(randomIndex).get("user_id");
        
        logger.info("随机选择了群成员: {}", selectedMemberId);
        return selectedMemberId;
    }
    
    /**
     * 查找最佳匹配的用户名
     * @param input 输入的用户名
     * @param candidates 候选用户名集合
     * @return 最匹配的用户名，如果没有匹配返回null
     */
    private String findBestMatch(String input, Set<String> candidates) {
        if (input == null || input.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        
        String inputLower = input.toLowerCase();
        String bestMatch = null;
        int bestScore = 0;
        
        for (String candidate : candidates) {
            String candidateLower = candidate.toLowerCase();
            
            // 如果候选项包含输入，优先考虑
            if (candidateLower.contains(inputLower)) {
                int score = inputLower.length() * 2;
                if (candidateLower.equals(inputLower)) {
                    score += 10; // 完全匹配加分
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = candidate;
                }
            } else if (inputLower.contains(candidateLower)) {
                // 输入包含候选项
                int score = candidateLower.length();
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = candidate;
                }
            }
        }
        
        // 设置一个最小匹配分数阈值
        return bestScore > 1 ? bestMatch : null;
    }
    
    /**
     * 处理群消息并生成AI回复
     * @param groupId 群ID
     * @param userId 用户ID
     * @param content 消息内容
     * @param atSender 是否@发送者
     */
    private void processGroupAiReply(String groupId, String userId, String content, boolean atSender) {
        logger.info("开始处理群{}用户{}的AI请求, 内容长度: {}, 是否@发送者: {}", 
                  groupId, userId, content.length(), atSender);
        
        // 获取当前时间戳，用于计算处理时间
        long startTime = System.currentTimeMillis();
        
        // 生成请求ID
        final String requestId = groupId + "_" + startTime;
        
        // 创建一个CompletableFuture来异步处理AI回复
        CompletableFuture.runAsync(() -> {
            try {
                // 记录处理开始
                logger.info("异步处理群{}用户{}的AI请求开始", groupId, userId);
                
                // 设置超时检测
                scheduleTimeout(groupId, startTime);
                
                // 获取用户的模型和人设配置
                String modelName = dataManager.getUserModel(userId);
                String persona = dataManager.getUserPersona(userId);
                
                // 记录所使用的模型和人设
                logger.info("用户[{}]的请求使用模型[{}], 人设[{}]", userId, modelName, persona);
                
                // 构建群成员信息上下文
                String groupContext = "";
                if (configLoader.getConfigBoolean("ai.include_group_members")) {
                    List<Map<String, String>> members = botClient.getGroupMembers(groupId);
                    if (members != null && !members.isEmpty()) {
                        // 是否过滤机器人自己
                        String selfId = configLoader.getConfigString("bot.self_id");
                        boolean filterSelf = configLoader.getConfigBoolean("ai.filter_self_at");
                        
                        StringBuilder sb = new StringBuilder("\n\n### 群成员信息 ###\n");
                        
                        // 添加更明确的指示说明
                        sb.append("现在我将向你提供当前群中的成员信息，如果你需要在回复中提及某个成员，请直接使用以下格式：\n");
                        sb.append("1. @用户名(QQ号) - 例如：@张三(123456789) - 系统会自动转换为正确的艾特格式\n");
                        sb.append("2. [CQ:at,qq=QQ号] - 例如：[CQ:at,qq=123456789] - 这是最终的艾特格式\n\n");
                        sb.append("请根据用户问题的上下文，决定是否需要艾特特定成员。以下是当前群的成员列表：\n\n");
                        
                        // 输出群成员总数，便于日志排查
                        logger.debug("为AI提供群{}的成员列表，共{}个成员", groupId, members.size());
                        
                        // 按字母顺序排序成员列表，保持稳定顺序
                        List<Map<String, String>> sortedMembers = new ArrayList<>(members);
                        sortedMembers.sort((m1, m2) -> {
                            String name1 = !CommonUtils.isNullOrEmpty(m1.get("card")) ? m1.get("card") : m1.get("nickname");
                            String name2 = !CommonUtils.isNullOrEmpty(m2.get("card")) ? m2.get("card") : m2.get("nickname");
                            return name1.compareTo(name2);
                        });
                        
                        // 构建详细的成员列表，包含QQ号、昵称和群名片
                        for (Map<String, String> member : sortedMembers) {
                            String memberId = member.get("user_id");
                            String memberName = member.get("nickname");
                            String memberCard = member.get("card"); // 群名片
                            
                            // 如果配置了过滤自己且当前成员是机器人自己，则跳过
                            if (filterSelf && selfId.equals(memberId)) {
                                continue;
                            }
                            
                            // 使用群名片（如果有）或昵称
                            String displayName = !CommonUtils.isNullOrEmpty(memberCard) ? memberCard : memberName;
                            
                            sb.append("- ").append(displayName).append(" (QQ: ").append(memberId).append(")\n");
                        }
                        
                        sb.append("\n重要说明：\n");
                        sb.append("1. 请根据上下文适当选择是否要艾特成员，不要过度艾特\n");
                        sb.append("2. 如果问题不针对特定成员，则无需艾特任何人\n");
                        sb.append("3. 要艾特成员，请使用上方提供的成员实际QQ号，不要使用示例QQ号(123456789)\n");
                        sb.append("4. 禁止艾特机器人自己，避免消息循环\n");
                        
                        groupContext = sb.toString();
                    } else {
                        logger.warn("获取群 {} 成员列表失败或为空，无法向AI提供群成员信息", groupId);
                    }
                }
                
                // 添加群上下文到消息内容
                String messageWithContext = content;
                if (!groupContext.isEmpty()) {
                    messageWithContext = content + groupContext;
                    logger.debug("已添加群成员上下文，最终消息长度: {}", messageWithContext.length());
                } else {
                    logger.debug("未添加群成员上下文，仅使用原始消息");
                }
                
                // 记录当前使用的AI服务
                logger.info("调用AI服务处理群{}用户{}的消息，使用模型: {}", groupId, userId, modelName);
                
                // 记录模型调用前的时间
                long beforeModelCall = System.currentTimeMillis();
                
                // 检查消息是否包含图片
                List<String> imageBase64List = new ArrayList<>();
                if (CommonUtils.containsImage(rawMessage)) {
                    logger.info("检测到消息包含图片，开始处理图片...");
                    List<String> imageCQCodes = CommonUtils.extractImageCQCodes(rawMessage);
                    
                    for (String imageCQCode : imageCQCodes) {
                        String imageUrl = CommonUtils.extractImageUrlFromCQCode(imageCQCode);
                        if (imageUrl != null) {
                            String imageBase64 = CommonUtils.downloadImageAsBase64(imageUrl);
                            if (imageBase64 != null) {
                                imageBase64List.add(imageBase64);
                                logger.info("成功下载并编码图片: {}", imageUrl);
                            } else {
                                logger.warn("下载图片失败: {}", imageUrl);
                            }
                        } else {
                            logger.warn("无法从CQ码中提取图片URL: {}", imageCQCode);
                        }
                    }
                    
                    if (!imageBase64List.isEmpty()) {
                        logger.info("成功处理 {} 张图片", imageBase64List.size());
                    }
                }
                
                // 调用AI服务
                logger.info("===> 准备调用AI模型: {}, 图片数量: {}", modelName, imageBase64List.size());
                String aiReply = aiService.chat(userId, messageWithContext, imageBase64List);
                logger.info("<=== AI模型已返回结果，处理时间: {}毫秒", System.currentTimeMillis() - beforeModelCall);
                
                // 标记请求已完成，阻止超时消息发送
                completedRequests.put(requestId, true);
                
                // 检查回复是否为空
                if (aiReply == null || aiReply.trim().isEmpty()) {
                    logger.error("AI回复为空，群: {}, 用户: {}, 模型: {}", groupId, userId, modelName);
                    botClient.sendGroupMessage(groupId, "抱歉，AI没有产生有效回复，请稍后再试。");
                    return;
                }
                
                // 处理转义字符
                aiReply = processEscapeSequences(aiReply);
                
                // 检查是否为NO_RESPONSE指令
                if (aiReply.trim().equals("[NO_RESPONSE]")) {
                    logger.info("AI选择不回复消息，群: {}, 用户: {}", groupId, userId);
                    return;
                }
                
                // 发送回复
                logger.info("AI已回复，准备发送到群{}, 回复长度: {}", groupId, aiReply.length());
                
                // 检查是否需要在回复时@发送者
                if (atSender && configLoader.getConfigBoolean("bot.always_at_sender")) {
                    String atPrefix = "[CQ:at,qq=" + userId + "] ";
                    
                    // 检查回复是否包含多段消息分隔符
                    if (aiReply.contains("\n---\n")) {
                        String[] messageParts = aiReply.split("\n---\n");
                        logger.info("检测到多段消息，共{}段", messageParts.length);
                        
                        // 第一段消息添加@前缀
                        messageParts[0] = atPrefix + messageParts[0];
                        
                        // 分别发送每段消息
                        for (String part : messageParts) {
                            // 处理@标记
                            part = processAtTags(part.trim(), groupId);
                            botClient.sendGroupMessage(groupId, part);
                            // 短暂延迟，避免消息发送过快
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } else {
                        // 单段消息，直接添加@前缀
                        // 处理@标记
                        aiReply = processAtTags(aiReply, groupId);
                        botClient.sendGroupMessage(groupId, atPrefix + aiReply);
                    }
                } else {
                    // 检查回复是否包含多段消息分隔符
                    if (aiReply.contains("\n---\n")) {
                        String[] messageParts = aiReply.split("\n---\n");
                        logger.info("检测到多段消息，共{}段", messageParts.length);
                        
                        // 分别发送每段消息
                        for (String part : messageParts) {
                            // 处理@标记
                            part = processAtTags(part.trim(), groupId);
                            botClient.sendGroupMessage(groupId, part);
                            // 短暂延迟，避免消息发送过快
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } else {
                        // 单段消息，直接发送
                        // 处理@标记
                        aiReply = processAtTags(aiReply, groupId);
                        botClient.sendGroupMessage(groupId, aiReply);
                    }
                }
                
                // 记录处理时间
                long processTime = System.currentTimeMillis() - startTime;
                logger.info("群{}AI请求处理完成，用时: {}毫秒", groupId, processTime);
                
            } catch (Exception e) {
                // 捕获所有异常，确保能够给用户一个友好的错误提示
                long errorTime = System.currentTimeMillis() - startTime;
                logger.error("处理群{}用户{}的AI请求时发生异常，用时: {}毫秒", groupId, userId, errorTime, e);
                try {
                    String errorMsg = "抱歉，AI处理消息时出现错误: " + e.getMessage();
                    logger.info("发送错误提示: {}", errorMsg);
                    botClient.sendGroupMessage(groupId, "抱歉，AI处理消息时出现错误，请稍后再试。");
                } catch (Exception ex) {
                    logger.error("发送错误提示消息失败", ex);
                }
                
                // 标记请求已完成，即使是错误完成
                completedRequests.put(requestId, true);
            }
        });
    }
    
    /**
     * 设置群聊超时检测
     */
    private void scheduleTimeout(String groupId, long startTime) {
        // 生成请求ID
        final String requestId = groupId + "_" + startTime;
        
        // 初始化为未完成状态
        completedRequests.put(requestId, false);
        
        // 安排超时检测任务
        executor.schedule(() -> {
            // 检查请求是否已完成
            if (!completedRequests.getOrDefault(requestId, false)) {
                logger.warn("群{}的AI请求超时(30秒)", groupId);
                try {
                    botClient.sendGroupMessage(groupId, "抱歉，AI响应时间过长，请稍后再试。如果问题持续存在，请联系管理员。");
                } catch (Exception e) {
                    logger.error("发送超时消息失败", e);
                }
            } else {
                logger.debug("群{}的AI请求已完成，不发送超时消息", groupId);
            }
            
            // 无论如何，清理这个请求的状态
            completedRequests.remove(requestId);
        }, 30, TimeUnit.SECONDS);
    }
    
    /**
     * 处理私聊消息并生成AI回复
     * @param userId 用户ID
     * @param content 消息内容
     */
    private void processPrivateAiReply(String userId, String content) {
        logger.info("开始处理用户{}的私聊AI请求, 内容长度: {}", userId, content.length());
        
        // 获取当前时间戳，用于计算处理时间
        long startTime = System.currentTimeMillis();
        
        // 生成请求ID
        final String requestId = "private_" + userId + "_" + startTime;
        
        // 设置超时检测
        schedulePrivateTimeout(userId, startTime);
        
        // 异步处理AI回复
        CompletableFuture.runAsync(() -> {
            try {
                // 获取用户的模型和人设配置
                String modelName = dataManager.getUserModel(userId);
                String persona = dataManager.getUserPersona(userId);
                
                // 检查消息是否包含图片
                List<String> imageBase64List = new ArrayList<>();
                if (CommonUtils.containsImage(content)) {
                    logger.info("检测到私聊消息包含图片，开始处理图片...");
                    List<String> imageCQCodes = CommonUtils.extractImageCQCodes(content);
                    
                    for (String imageCQCode : imageCQCodes) {
                        String imageUrl = CommonUtils.extractImageUrlFromCQCode(imageCQCode);
                        if (imageUrl != null) {
                            String imageBase64 = CommonUtils.downloadImageAsBase64(imageUrl);
                            if (imageBase64 != null) {
                                imageBase64List.add(imageBase64);
                                logger.info("成功下载并编码私聊图片: {}", imageUrl);
                            } else {
                                logger.warn("下载私聊图片失败: {}", imageUrl);
                            }
                        } else {
                            logger.warn("无法从私聊CQ码中提取图片URL: {}", imageCQCode);
                        }
                    }
                    
                    if (!imageBase64List.isEmpty()) {
                        logger.info("成功处理 {} 张私聊图片", imageBase64List.size());
                    }
                }
                
                // 调用AI服务
                String aiReply = aiService.chat(userId, content, imageBase64List);
                
                // 标记请求已完成，阻止超时消息发送
                completedRequests.put(requestId, true);
                
                // 检查回复是否为空
                if (aiReply == null || aiReply.trim().isEmpty()) {
                    logger.error("AI回复为空，用户: {}, 模型: {}", userId, modelName);
                    botClient.sendPrivateMessage(userId, "抱歉，AI没有产生有效回复，请稍后再试。");
                    return;
                }
                
                // 处理转义字符
                aiReply = processEscapeSequences(aiReply);
                
                // 检查是否为NO_RESPONSE指令
                if (aiReply.trim().equals("[NO_RESPONSE]")) {
                    logger.info("AI选择不回复消息，用户: {}", userId);
                    return;
                }
                
                // 检查回复是否包含多段消息分隔符
                if (aiReply.contains("\n---\n")) {
                    String[] messageParts = aiReply.split("\n---\n");
                    logger.info("检测到多段消息，共{}段", messageParts.length);
                    
                    // 分别发送每段消息
                    for (String part : messageParts) {
                        botClient.sendPrivateMessage(userId, part.trim());
                        // 短暂延迟，避免消息发送过快
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } else {
                    // 发送回复
                    botClient.sendPrivateMessage(userId, aiReply);
                }
                
                // 记录处理时间
                long processTime = System.currentTimeMillis() - startTime;
                logger.info("用户{}的私聊AI请求处理完成，用时: {}毫秒", userId, processTime);
                
            } catch (Exception e) {
                logger.error("处理用户{}的私聊AI请求时发生异常", userId, e);
                try {
                    botClient.sendPrivateMessage(userId, "抱歉，AI处理消息时出现错误，请稍后再试。");
                } catch (Exception ex) {
                    logger.error("发送错误提示消息失败", ex);
                }
                
                // 标记请求已完成，即使是错误完成
                completedRequests.put(requestId, true);
            }
        });
    }
    
    /**
     * 安排私聊消息超时检查和处理
     */
    private void schedulePrivateTimeout(String userId, long startTime) {
        // 生成请求ID
        final String requestId = "private_" + userId + "_" + startTime;
        
        // 初始化为未完成状态
        completedRequests.put(requestId, false);
        
        // 安排超时检测任务
        executor.schedule(() -> {
            // 检查请求是否已完成
            if (!completedRequests.getOrDefault(requestId, false)) {
                logger.warn("用户{}的私聊AI请求超时(30秒)", userId);
                try {
                    botClient.sendPrivateMessage(userId, "抱歉，AI响应时间过长，请稍后再试。如果问题持续存在，请联系管理员。");
                } catch (Exception e) {
                    logger.error("发送超时消息失败", e);
                }
            } else {
                logger.debug("用户{}的私聊AI请求已完成，不发送超时消息", userId);
            }
            
            // 无论如何，清理这个请求的状态
            completedRequests.remove(requestId);
        }, 30, TimeUnit.SECONDS);
    }
    
    private void handleGroupMessage(JSONObject message) {
        try {
            String groupId = CommonUtils.safeGetString(message, "group_id");
            String userId = CommonUtils.safeGetString(message, "user_id");
            String rawMessage = message.optString("raw_message", "");
            String messageId = message.optString("message_id", "");
            
            // 输出完整的原始消息内容，用于调试艾特问题
            logger.debug("收到群 {} 的消息: userId={}, messageId={}, 原始消息: {}", 
                groupId, userId, messageId, rawMessage);
            
            // 消息去重：使用messageId防止重复处理同一条消息
            if (!messageId.isEmpty()) {
                // 检查是否已处理过此消息
                if (isMessageProcessed(messageId)) {
                    logger.debug("消息已处理过，跳过: messageId={}", messageId);
                    return;
                }
                // 标记消息为已处理
                markMessageAsProcessed(messageId);
            }
            
            // 检查用户是否在黑名单中
            if (blacklistManager.isUserBlacklisted(userId)) {
                logger.info("用户 {} 在黑名单中，忽略群消息", userId);
                return;
            }
            
            // 群聊命令处理
            if (rawMessage.startsWith("/")) {
                // 检查AI功能是否开启，如果关闭且用户不是超级管理员，则不处理命令
                boolean aiEnabled = dataManager.isGroupAIEnabled(groupId);
                if (!aiEnabled) {
                    List<String> admins = getAdmins();
                    boolean isAdmin = admins.contains(userId);
                    if (!isAdmin) {
                        // AI功能关闭且用户不是超级管理员，不处理命令，直接跳过
                        logger.debug("群 {} 中AI功能已关闭，用户 {} 不是超级管理员，忽略命令: {}", groupId, userId, rawMessage);
                        return;
                    }
                }
                
                logger.debug("群 {} 中收到命令消息: {}", groupId, rawMessage);
                handleGroupCommand(groupId, userId, rawMessage);
                return; // 直接返回，确保命令处理后不再进行后续处理
            }
            
            // 检查消息是否包含屏蔽词
            if (filterWordManager.isFilterEnabled() && filterWordManager.containsFilterWord(rawMessage)) {
                logger.info("群 {} 中用户 {} 的消息包含屏蔽词，已拦截", groupId, userId);
                String replyMessage = filterWordManager.getFilterReplyMessage();
                if (replyMessage != null && !replyMessage.trim().isEmpty()) {
                    botClient.sendGroupMessage(groupId, replyMessage);
                }
                return; // 屏蔽词处理后直接返回
            }
            
            // 提取纯文本
            String messageText = CommonUtils.extractTextFromCQCode(rawMessage).trim();
            if (messageText.isEmpty() && !rawMessage.contains("[CQ:at,")) {
                logger.debug("群 {} 中消息不包含文本内容且不是@消息，跳过处理", groupId);
                return; // 空消息不处理，除非是@消息
            }
            
            // 获取机器人名称和ID
            String botName = configLoader.getConfigString("bot.name", "").trim();
            String botNicknames = configLoader.getConfigString("bot.nicknames", "").trim();
            
            // 从配置文件获取 self_id，作为备选方案
            String configSelfId = configLoader.getConfigString("bot.self_id", "").trim();
            // 首先从消息中获取，如果获取不到则使用配置中的ID
            String selfId = CommonUtils.safeGetString(message, "self_id");
            if (selfId.isEmpty() && !configSelfId.isEmpty()) {
                selfId = configSelfId;
                logger.debug("从消息中无法获取self_id，使用配置中的self_id: {}", selfId);
            }
            
            // 创建机器人昵称列表
            List<String> nameList = new ArrayList<>();
            if (!botName.isEmpty()) {
                nameList.add(botName);
            }
            if (!botNicknames.isEmpty()) {
                // 分割多个昵称（以逗号或分号分隔）
                String[] nicknames = botNicknames.split("[,;]");
                for (String nick : nicknames) {
                    String trimmedNick = nick.trim();
                    if (!trimmedNick.isEmpty()) {
                        nameList.add(trimmedNick);
                    }
                }
            }
            
            // 增强版检测是否被@
            boolean isAtBot = false;
            
            // 方法1: 通过CQ码检测
            if (rawMessage.contains("[CQ:at,qq=" + selfId + "]")) {
                isAtBot = true;
                logger.debug("群 {} 消息中检测到通过CQ码@机器人，selfId={}", groupId, selfId);
            }
            
            // 方法2: 通过消息对象中的at字段检测
            JSONObject messageObj = message.optJSONObject("message");
            if (!isAtBot && messageObj != null) {
                if (messageObj.has("data") && messageObj.optJSONObject("data").has("uin")) {
                    String atUin = messageObj.optJSONObject("data").optString("uin", "");
                    if (selfId.equals(atUin)) {
                        isAtBot = true;
                        logger.debug("群 {} 消息中通过message.data.uin检测到@机器人", groupId);
                    }
                }
            }
            
            // 方法3: 检查消息数组格式是否包含at
            if (!isAtBot) {
                Object msgArr = message.opt("message");
                if (msgArr instanceof JSONObject) {
                    // 单一消息段
                    JSONObject msgSegment = (JSONObject) msgArr;
                    if ("at".equals(msgSegment.optString("type")) && 
                        selfId.equals(msgSegment.optJSONObject("data").optString("qq"))) {
                        isAtBot = true;
                        logger.debug("群 {} 消息中通过单一消息段检测到@机器人", groupId);
                    }
                } else if (msgArr instanceof org.json.JSONArray) {
                    // 多段消息
                    org.json.JSONArray msgSegments = (org.json.JSONArray) msgArr;
                    for (int i = 0; i < msgSegments.length(); i++) {
                        JSONObject segment = msgSegments.optJSONObject(i);
                        if (segment != null && "at".equals(segment.optString("type"))) {
                            JSONObject data = segment.optJSONObject("data");
                            if (data != null && selfId.equals(data.optString("qq"))) {
                                isAtBot = true;
                                logger.debug("群 {} 消息中通过消息段数组检测到@机器人", groupId);
                                break;
                            }
                        }
                    }
                }
            }
            
            // 检查消息中是否包含机器人名称/昵称
            boolean containsBotName = false;
            String matchedName = null;
            
            // 更精确的名称匹配逻辑
            for (String name : nameList) {
                if (messageText.contains(name)) {
                    // 如果名称很短（小于2个字符），则需要更严格的匹配
                    if (name.length() < 2) {
                        // 对于短名称，要求是单独的词或在句首句尾
                        String regex = "(^|[,，。！？.!?\\s])" + Pattern.quote(name) + "($|[,，。！？.!?\\s])";
                        if (Pattern.compile(regex).matcher(messageText).find()) {
                            containsBotName = true;
                            matchedName = name;
                            break;
                        }
                    } else {
                        // 对于长名称，直接判定为匹配
                        containsBotName = true;
                        matchedName = name;
                        break;
                    }
                }
            }
            
            logger.debug("群 {} 消息触发检测: isAtBot={}, containsBotName={}, matchedName={}, selfId={}, isEnabled={}",
                      groupId, isAtBot, containsBotName, matchedName, selfId, dataManager.isGroupAIEnabled(groupId));
            
            // 如果群启用了AI，并且（被@了或消息包含机器人名字）
            if (dataManager.isGroupAIEnabled(groupId) && (isAtBot || containsBotName)) {
                logger.info("群 {} 消息触发AI回复条件: isAtBot={}, containsBotName={}, matchedName={}", 
                          groupId, isAtBot, containsBotName, matchedName);
                
                // 如果消息包含机器人名字，移除名字
                if (containsBotName && matchedName != null) {
                    messageText = messageText.replace(matchedName, "").trim();
                    logger.debug("已移除机器人名称，处理后的消息: {}", messageText);
                }
                
                // 如果消息处理后为空或只包含标点符号，则设置为默认问候语
                if (messageText.isEmpty() || messageText.matches("^[，。！？,.!?\\s]+$")) {
                    messageText = "你好";
                    logger.debug("消息为空或只有标点，设置为默认问候语: {}", messageText);
                }
                
                // 处理AI回复
                processGroupAiReply(groupId, userId, messageText, isAtBot);
            } else {
                // 记录未触发AI回复的原因
                if (!dataManager.isGroupAIEnabled(groupId)) {
                    logger.debug("群 {} AI功能已禁用，不处理消息", groupId);
                } else {
                    logger.debug("群 {} 消息未触发AI回复条件: 没有@机器人也没有包含机器人名称", groupId);
                }
            }
        } catch (Exception e) {
            logger.error("处理群聊消息时出错", e);
        }
    }
    
    /**
     * 检查消息是否已处理过
     */
    private boolean isMessageProcessed(String messageId) {
        synchronized (processedMessageIds) {
            return processedMessageIds.containsKey(messageId);
        }
    }
    
    /**
     * 标记消息为已处理
     */
    private void markMessageAsProcessed(String messageId) {
        synchronized (processedMessageIds) {
            // 添加消息ID和当前时间
            processedMessageIds.put(messageId, System.currentTimeMillis());
            
            // 清理过期的消息ID（保留最近5分钟的）
            long now = System.currentTimeMillis();
            long expireTime = 5 * 60 * 1000; // 5分钟
            
            processedMessageIds.entrySet().removeIf(entry -> 
                now - entry.getValue() > expireTime || processedMessageIds.size() > MAX_PROCESSED_MESSAGE_IDS);
        }
    }
    
    private void handlePrivateMessage(JSONObject message) {
        try {
            String userId = CommonUtils.safeGetString(message, "user_id");
            String rawMessage = message.optString("raw_message", "");
            String messageId = message.optString("message_id", "");
            
            // 消息去重：使用messageId防止重复处理同一条消息
            if (!messageId.isEmpty()) {
                // 检查是否已处理过此消息
                if (isMessageProcessed(messageId)) {
                    logger.debug("私聊消息已处理过，跳过: messageId={}", messageId);
                    return;
                }
                // 标记消息为已处理
                markMessageAsProcessed(messageId);
            }
            
            // 检查用户是否在黑名单中
            if (blacklistManager.isUserBlacklisted(userId)) {
                logger.info("用户 {} 在黑名单中，忽略私聊消息", userId);
                return;
            }
            
            // 私聊命令处理
            if (rawMessage.startsWith("/")) {
                handlePrivateCommand(userId, rawMessage);
                return;
            }
            
            // 检查消息是否包含屏蔽词
            if (filterWordManager.containsFilterWord(rawMessage)) {
                logger.info("用户 {} 的私聊消息包含屏蔽词，已拦截", userId);
                String replyMessage = filterWordManager.getFilterReplyMessage();
                botClient.sendPrivateMessage(userId, replyMessage);
                return;
            }
            
            // 如果私聊功能未启用，返回提示并退出
            if (!botClient.isPrivateMessageEnabled()) {
                logger.debug("私聊功能未启用，忽略私聊消息");
                return;
            }
            
            // 处理AI回复
            processPrivateAiReply(userId, rawMessage);
        } catch (Exception e) {
            logger.error("处理私聊消息时出错", e);
        }
    }
    
    /**
     * 处理群聊命令
     * @param groupId 群ID
     * @param userId 用户ID
     * @param command 命令内容
     */
    private void handleGroupCommand(String groupId, String userId, String command) {
        try {
            logger.debug("处理群命令: {}, 来自用户: {}, 群: {}", command, userId, groupId);
            
            // 移除命令前的斜杠
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            
            // 将命令转换为小写，便于不区分大小写的比较
            String cmdLower = command.toLowerCase().trim();
            
            // 获取管理员列表和权限信息
            List<String> admins = getAdmins();
            boolean isAdmin = admins.contains(userId);
            boolean isGroupAdmin = isGroupAdmin(userId, groupId);
            boolean aiEnabled = dataManager.isGroupAIEnabled(groupId);
            
            // 帮助命令 - 始终可用
            if (cmdLower.equals("帮助") || cmdLower.equals("help")) {
                showHelp(groupId);
                return;
            }
            
            // 注意：AI关闭时的权限检查已在handleGroupMessage中完成
            // 这里只需要处理AI开启时的权限检查
            
            // 管理员命令检查
            boolean needAdmin = false;
            
            // 这些命令需要管理员权限
            if (command.startsWith("拉黑") || 
                command.startsWith("解除拉黑") || 
                cmdLower.equals("查看黑名单") ||
                cmdLower.equals("开启") || 
                cmdLower.equals("关闭") ||
                command.startsWith("添加屏蔽词") ||
                command.equals("删除屏蔽词") ||
                cmdLower.equals("查看屏蔽词") ||
                cmdLower.equals("开启屏蔽") || 
                cmdLower.equals("关闭屏蔽")) {
                
                needAdmin = true;
            }
            
            // 检查权限
            if (needAdmin && !isAdmin && !isGroupAdmin) {
                botClient.sendGroupMessage(groupId, "您没有管理员权限，无法使用此命令。");
                return;
            }
            
            // 黑名单管理命令
            if (command.startsWith("拉黑")) {
                String targetUserId = extractTargetUserId(command.substring(2).trim());
                if (targetUserId == null || targetUserId.isEmpty()) {
                    botClient.sendGroupMessage(groupId, "请指定要拉黑的用户，格式：/拉黑 @用户 或 /拉黑 QQ号");
                    return;
                }
                
                if (blacklistManager.addToBlacklist(targetUserId)) {
                    botClient.sendGroupMessage(groupId, "已将用户 " + targetUserId + " 添加到黑名单。");
                    logger.info("用户 {} 将 {} 添加到黑名单", userId, targetUserId);
                } else {
                    botClient.sendGroupMessage(groupId, "用户 " + targetUserId + " 已在黑名单中。");
                }
                return;
            }
            
            if (command.startsWith("解除拉黑")) {
                String targetUserId = extractTargetUserId(command.substring(4).trim());
                if (targetUserId == null || targetUserId.isEmpty()) {
                    botClient.sendGroupMessage(groupId, "请指定要解除拉黑的用户，格式：/解除拉黑 @用户 或 /解除拉黑 QQ号");
                    return;
                }
                
                if (blacklistManager.removeFromBlacklist(targetUserId)) {
                    botClient.sendGroupMessage(groupId, "已将用户 " + targetUserId + " 从黑名单中移除。");
                    logger.info("用户 {} 将 {} 从黑名单中移除", userId, targetUserId);
                } else {
                    botClient.sendGroupMessage(groupId, "用户 " + targetUserId + " 不在黑名单中。");
                }
                return;
            }
            
            if (cmdLower.equals("查看黑名单")) {
                List<String> blacklistedUsers = blacklistManager.getBlacklistedUsers();
                if (blacklistedUsers.isEmpty()) {
                    botClient.sendGroupMessage(groupId, "黑名单为空。");
                } else {
                    StringBuilder sb = new StringBuilder("当前黑名单用户：\n");
                    for (String user : blacklistedUsers) {
                        sb.append(user).append("\n");
                    }
                    botClient.sendGroupMessage(groupId, sb.toString().trim());
                }
                return;
            }
            
            // AI开关命令
            if (cmdLower.equals("开启")) {
                dataManager.setGroupAIEnabled(groupId, true);
                botClient.sendGroupMessage(groupId, "已开启本群的AI对话功能");
                logger.info("群 {} 的AI对话功能已被用户 {} 开启", groupId, userId);
                return;
            }
            
            if (cmdLower.equals("关闭")) {
                dataManager.setGroupAIEnabled(groupId, false);
                botClient.sendGroupMessage(groupId, "已关闭本群的AI对话功能");
                logger.info("群 {} 的AI对话功能已被用户 {} 关闭", groupId, userId);
                return;
            }
            
            // 屏蔽词管理命令
            if (command.startsWith("添加屏蔽词")) {
                String filterWord = command.substring(5).trim();
                if (filterWord.isEmpty()) {
                    botClient.sendGroupMessage(groupId, "请指定要添加的屏蔽词，格式：/添加屏蔽词 词语");
                    return;
                }
                
                if (filterWordManager.addFilterWord(filterWord)) {
                    botClient.sendGroupMessage(groupId, "已添加屏蔽词：" + filterWord);
                    logger.info("用户 {} 添加了屏蔽词: {}", userId, filterWord);
                } else {
                    botClient.sendGroupMessage(groupId, "屏蔽词已存在或添加失败");
                }
                return;
            }
            
            if (command.startsWith("删除屏蔽词")) {
                String filterWord = command.substring(5).trim();
                if (filterWord.isEmpty()) {
                    botClient.sendGroupMessage(groupId, "请指定要删除的屏蔽词，格式：/删除屏蔽词 词语");
                    return;
                }
                
                if (filterWordManager.removeFilterWord(filterWord)) {
                    botClient.sendGroupMessage(groupId, "已删除屏蔽词：" + filterWord);
                    logger.info("用户 {} 删除了屏蔽词: {}", userId, filterWord);
                } else {
                    botClient.sendGroupMessage(groupId, "屏蔽词不存在或删除失败");
                }
                return;
            }
            
            if (cmdLower.equals("查看屏蔽词")) {
                List<String> filterWords = filterWordManager.getFilterWords();
                if (filterWords.isEmpty()) {
                    botClient.sendGroupMessage(groupId, "没有设置屏蔽词");
                } else {
                    StringBuilder sb = new StringBuilder("当前屏蔽词列表：\n");
                    for (String word : filterWords) {
                        sb.append(word).append("\n");
                    }
                    botClient.sendGroupMessage(groupId, sb.toString().trim());
                }
                return;
            }
            
            if (cmdLower.equals("开启屏蔽")) {
                // 这里假设你有一个设置屏蔽功能状态的方法
                try {
                    // 修改配置文件来开启屏蔽功能
                    // 这里只是一个示例，需要根据你的实际配置方式来实现
                    botClient.sendGroupMessage(groupId, "已开启屏蔽词功能");
                    logger.info("用户 {} 开启了群 {} 的屏蔽词功能", userId, groupId);
                } catch (Exception e) {
                    botClient.sendGroupMessage(groupId, "开启屏蔽词功能失败：" + e.getMessage());
                    logger.error("开启屏蔽词功能失败", e);
                }
                return;
            }
            
            if (cmdLower.equals("关闭屏蔽")) {
                // 这里假设你有一个设置屏蔽功能状态的方法
                try {
                    // 修改配置文件来关闭屏蔽功能
                    // 这里只是一个示例，需要根据你的实际配置方式来实现
                    botClient.sendGroupMessage(groupId, "已关闭屏蔽词功能");
                    logger.info("用户 {} 关闭了群 {} 的屏蔽词功能", userId, groupId);
                } catch (Exception e) {
                    botClient.sendGroupMessage(groupId, "关闭屏蔽词功能失败：" + e.getMessage());
                    logger.error("关闭屏蔽词功能失败", e);
                }
                return;
            }
            
            // 处理模型和人设相关命令
            if (command.startsWith("使用") || command.startsWith("切换")) {
                String param = command.substring(command.startsWith("使用") ? 2 : 2).trim();
                
                // 判断是切换模型还是人设
                if (param.startsWith("模型") || param.startsWith("model")) {
                    String model = param.substring(param.startsWith("模型") ? 2 : 5).trim();
                    // 检查模型是否存在
                    if (modelManager.hasModel(model)) {
                        // 设置用户模型选择
                        dataManager.setUserModel(userId, model);
                        
                        // 向用户确认
                        botClient.sendGroupMessage(groupId, "已为您切换到 " + model + " 模型。");
                        logger.info("用户 {} 切换到模型: {}", userId, model);
                    } else {
                        // 模型不存在
                        botClient.sendGroupMessage(groupId, "模型 " + model + " 不存在，可用模型: " + String.join(", ", modelManager.listModels()));
                    }
                } else if (param.startsWith("人设") || param.startsWith("角色") || param.startsWith("persona")) {
                    // 处理切换人设的命令
                    String persona = param;
                    if (param.startsWith("人设")) {
                        persona = param.substring(2).trim();
                    } else if (param.startsWith("角色")) {
                        persona = param.substring(2).trim();
                    } else if (param.startsWith("persona")) {
                        persona = param.substring(7).trim();
                    }
                    
                    // 切换人设
                    handlePersonaSwitch(userId, persona, true, groupId);
                } else {
                    // 假设是直接指定的模型名称
                    if (modelManager.hasModel(param)) {
                        // 设置用户模型选择
                        dataManager.setUserModel(userId, param);
                        
                        // 向用户确认
                        botClient.sendGroupMessage(groupId, "已为您切换到 " + param + " 模型。");
                        logger.info("用户 {} 切换到模型: {}", userId, param);
                    } else {
                        // 尝试作为人设名称
                        handlePersonaSwitch(userId, param, true, groupId);
                    }
                }
                return;
            }
            
            // 其他命令处理
            if (cmdLower.equals("模型") || cmdLower.equals("models") || cmdLower.equals("查看模型")) {
                List<String> models = modelManager.listModels();
                StringBuilder sb = new StringBuilder("可用的AI模型有：\n");
                
                for (String model : models) {
                    Map<String, String> details = modelManager.getModelDetails(model);
                    sb.append("- ").append(model);
                    if (details.containsKey("description") && !details.get("description").isEmpty()) {
                        sb.append(": ").append(details.get("description"));
                    }
                    sb.append("\n");
                }
                
                sb.append("\n您当前使用的模型是: ").append(dataManager.getUserModel(userId));
                sb.append("\n\n使用方法：/使用模型 [模型名称] 或 /切换模型 [模型名称]");
                
                botClient.sendGroupMessage(groupId, sb.toString());
                return;
            }
            
            if (cmdLower.equals("人设") || cmdLower.equals("查看人设") || cmdLower.equals("personas")) {
                List<String> personas = personaManager.listPersonas();
                StringBuilder sb = new StringBuilder("可用的人设有：\n");
                
                for (String persona : personas) {
                    sb.append("- ").append(persona).append("\n");
                }
                
                sb.append("\n您当前使用的人设是: ").append(dataManager.getUserPersona(userId));
                sb.append("\n\n使用方法：/使用人设 [人设名称] 或 /切换人设 [人设名称]");
                
                botClient.sendGroupMessage(groupId, sb.toString());
                return;
            }
            
            if (cmdLower.equals("清除对话") || cmdLower.equals("清空对话") || cmdLower.equals("清除记忆")) {
                // A清除用户的对话历史
                aiService.clearConversation(userId);
                botClient.sendGroupMessage(groupId, "已清除您的对话历史");
                return;
            }
            
            if (cmdLower.equals("状态") || cmdLower.equals("查看状态")) {
                StringBuilder status = new StringBuilder("当前状态：\n");
                status.append("群 ").append(groupId).append(" 的AI状态: ").append(dataManager.isGroupAIEnabled(groupId) ? "已启用" : "已禁用").append("\n");
                status.append("您当前使用的模型: ").append(dataManager.getUserModel(userId)).append("\n");
                status.append("您当前使用的人设: ").append(dataManager.getUserPersona(userId)).append("\n");
                status.append(aiService.getConversationSummary(userId));
                
                botClient.sendGroupMessage(groupId, status.toString());
                return;
            }
            
            // 未知命令
            botClient.sendGroupMessage(groupId, "未知命令: " + command + "\n使用 /帮助 查看可用命令");
            logger.debug("未知命令: {}", command);
            
        } catch (Exception e) {
            logger.error("处理群聊命令时出错: {}", command, e);
            botClient.sendGroupMessage(groupId, "处理命令时出错，请稍后再试");
        }
    }
    
    private void handlePrivateCommand(String userId, String command) {
        logger.debug("处理私聊命令: {}, 来自用户: {}", command, userId);

        // 移除命令前的斜杠（如果有）
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        
        // 将命令转换为小写，便于不区分大小写的比较
        String cmdLower = command.toLowerCase();
        
        // 帮助命令
        if (cmdLower.equals("帮助") || cmdLower.equals("help")) {
            showPrivateHelp(userId);
            return;
        }
        
        // 获取管理员列表
        List<String> admins = getAdmins();
        boolean isAdmin = admins.contains(userId);
        
        // 处理拉黑相关命令 (仅管理员)
        if (command.startsWith("拉黑")) {
            if (!isAdmin) {
                botClient.sendPrivateMessage(userId, "您没有管理员权限，无法使用此命令。");
                return;
            }
            
            String targetUserId = extractTargetUserId(command.substring(2).trim());
            if (targetUserId == null || targetUserId.isEmpty()) {
                botClient.sendPrivateMessage(userId, "请指定要拉黑的用户，格式：/拉黑 QQ号");
                return;
            }
            
            if (blacklistManager.addToBlacklist(targetUserId)) {
                botClient.sendPrivateMessage(userId, "已将用户 " + targetUserId + " 添加到黑名单。");
                logger.info("管理员 {} 将 {} 添加到黑名单", userId, targetUserId);
            } else {
                botClient.sendPrivateMessage(userId, "用户 " + targetUserId + " 已在黑名单中。");
            }
            return;
        }
        
        if (command.startsWith("解除拉黑")) {
            if (!isAdmin) {
                botClient.sendPrivateMessage(userId, "您没有管理员权限，无法使用此命令。");
                return;
            }
            
            String targetUserId = extractTargetUserId(command.substring(4).trim());
            if (targetUserId == null || targetUserId.isEmpty()) {
                botClient.sendPrivateMessage(userId, "请指定要解除拉黑的用户，格式：/解除拉黑 QQ号");
                return;
            }
            
            if (blacklistManager.removeFromBlacklist(targetUserId)) {
                botClient.sendPrivateMessage(userId, "已将用户 " + targetUserId + " 从黑名单中移除。");
                logger.info("管理员 {} 将 {} 从黑名单中移除", userId, targetUserId);
            } else {
                botClient.sendPrivateMessage(userId, "用户 " + targetUserId + " 不在黑名单中。");
            }
            return;
        }
        
        if (cmdLower.equals("查看黑名单")) {
            if (!isAdmin) {
                botClient.sendPrivateMessage(userId, "您没有管理员权限，无法使用此命令。");
                return;
            }
            
            List<String> blacklistedUsers = blacklistManager.getBlacklistedUsers();
            if (blacklistedUsers.isEmpty()) {
                botClient.sendPrivateMessage(userId, "黑名单为空。");
            } else {
                StringBuilder sb = new StringBuilder("当前黑名单用户：\n");
                for (String user : blacklistedUsers) {
                    sb.append(user).append("\n");
                }
                botClient.sendPrivateMessage(userId, sb.toString().trim());
            }
            return;
        }
        
        // 私聊开关命令 (仅超级管理员)
        if (cmdLower.equals("开启私聊")) {
            if (!isAdmin) {
                botClient.sendPrivateMessage(userId, "您没有管理员权限，无法使用此命令。");
                return;
            }
            
            dataManager.setPrivateMessageEnabled(true);
            botClient.sendPrivateMessage(userId, "已全局开启私聊功能");
            logger.info("超级管理员 {} 全局开启了私聊功能", userId);
            return;
        }
        
        if (cmdLower.equals("关闭私聊")) {
            if (!isAdmin) {
                botClient.sendPrivateMessage(userId, "您没有管理员权限，无法使用此命令。");
                return;
            }
            
            dataManager.setPrivateMessageEnabled(false);
            botClient.sendPrivateMessage(userId, "已全局关闭私聊功能");
            logger.info("超级管理员 {} 全局关闭了私聊功能", userId);
            return;
        }
        
        // 处理其他命令
        if (command.startsWith("使用") || command.startsWith("切换")) {
            String param = command.substring(command.startsWith("使用") ? 2 : 2).trim();
            
            // 判断是切换模型还是人设
            if (param.startsWith("模型") || param.startsWith("model")) {
                String model = param.substring(param.startsWith("模型") ? 2 : 5).trim();
                // 检查模型是否存在
                if (modelManager.hasModel(model)) {
                    // 设置用户模型选择
                    dataManager.setUserModel(userId, model);
                    
                    // 向用户确认
                    botClient.sendPrivateMessage(userId, "已为您切换到 " + model + " 模型。");
                    logger.info("用户 {} 切换到模型: {}", userId, model);
                } else {
                    // 模型不存在
                    botClient.sendPrivateMessage(userId, "模型 " + model + " 不存在，可用模型: " + String.join(", ", modelManager.listModels()));
                }
            } else if (param.startsWith("人设") || param.startsWith("角色") || param.startsWith("persona")) {
                // 处理切换人设的命令
                String persona = param;
                if (param.startsWith("人设")) {
                    persona = param.substring(2).trim();
                } else if (param.startsWith("角色")) {
                    persona = param.substring(2).trim();
                } else if (param.startsWith("persona")) {
                    persona = param.substring(7).trim();
                }
                
                // 切换人设
                handlePersonaSwitch(userId, persona, false, null);
            } else {
                // 假设是直接指定的模型名称
                if (modelManager.hasModel(param)) {
                    // 设置用户模型选择
                    dataManager.setUserModel(userId, param);
                    
                    // 向用户确认
                    botClient.sendPrivateMessage(userId, "已为您切换到 " + param + " 模型。");
                    logger.info("用户 {} 切换到模型: {}", userId, param);
                } else {
                    // 尝试作为人设名称
                    handlePersonaSwitch(userId, param, false, null);
                }
            }
            return;
        } else if (cmdLower.equals("模型") || cmdLower.equals("models") || cmdLower.equals("查看模型")) {
            logger.debug("执行查看模型命令");
            // 获取所有可用模型
            List<String> models = modelManager.listModels();
            StringBuilder sb = new StringBuilder("可用的AI模型有：\n");
            
            for (String model : models) {
                Map<String, String> details = modelManager.getModelDetails(model);
                sb.append("- ").append(model);
                if (details.containsKey("description") && !details.get("description").isEmpty()) {
                    sb.append(": ").append(details.get("description"));
                }
                sb.append("\n");
            }
            
            sb.append("\n您当前使用的模型是: ").append(dataManager.getUserModel(userId));
            sb.append("\n\n使用方法：/使用模型 [模型名称] 或 /切换模型 [模型名称]");
            
            botClient.sendPrivateMessage(userId, sb.toString());
            return;
        } else if (cmdLower.equals("人设") || cmdLower.equals("查看人设") || cmdLower.equals("personas")) {
            logger.debug("执行查看人设命令");
            // 列出可用人设
            List<String> personas = personaManager.listPersonas();
            StringBuilder sb = new StringBuilder("可用的人设有：\n");
            
            for (String persona : personas) {
                sb.append("- ").append(persona).append("\n");
            }
            
            sb.append("\n您当前使用的人设是: ").append(dataManager.getUserPersona(userId));
            sb.append("\n\n使用方法：/使用人设 [人设名称] 或 /切换人设 [人设名称]");
            
            botClient.sendPrivateMessage(userId, sb.toString());
            return;
        } else if (cmdLower.equals("清除对话") || cmdLower.equals("清空对话") || cmdLower.equals("清除记忆")) {
            // 清除用户的对话历史
            aiService.clearConversation(userId);
            botClient.sendPrivateMessage(userId, "已清除您的对话历史");
            return;
        } else if (cmdLower.equals("状态") || cmdLower.equals("查看状态")) {
            // 显示当前状态
            StringBuilder status = new StringBuilder("当前状态：\n");
            status.append("全局私聊功能: ").append(dataManager.isPrivateMessageEnabled() ? "已启用" : "已禁用").append("\n");
            status.append("您当前使用的模型: ").append(dataManager.getUserModel(userId)).append("\n");
            status.append("您当前使用的人设: ").append(dataManager.getUserPersona(userId)).append("\n");
            status.append(aiService.getConversationSummary(userId));
            
            botClient.sendPrivateMessage(userId, status.toString());
            return;
        }
        
        // 处理未知命令
        logger.debug("未知命令: {}", command);
        botClient.sendPrivateMessage(userId, "未知命令: " + command + "\n使用 /帮助 查看可用命令");
    }
    
    /**
     * 显示群聊帮助信息
     * @param groupId 群ID
     */
    private void showHelp(String groupId) {
        StringBuilder helpMessage = new StringBuilder("📋 命令帮助列表：\n");
        helpMessage.append("👉 基础命令：\n");
        helpMessage.append("  /帮助 或 /help - 显示此帮助信息\n");
        helpMessage.append("  /模型 - 查看可用的AI模型\n");
        helpMessage.append("  /切换模型 [模型名] - 切换使用的AI模型\n");
        helpMessage.append("  /查看模型 - 查看当前使用的AI模型\n");
        helpMessage.append("  /人设 - 查看可用的人设\n");
        helpMessage.append("  /切换人设 [人设名] - 切换AI人设\n");
        helpMessage.append("  /查看人设 - 查看当前使用的人设\n");
        helpMessage.append("  /清除记忆 - 清除与AI的对话历史\n");
        helpMessage.append("  /状态 - 查看AI系统状态\n");
        
        helpMessage.append("\n👨‍💼 管理员命令：\n");
        helpMessage.append("  /开启 - 在此群启用AI功能\n");
        helpMessage.append("  /关闭 - 在此群禁用AI功能\n");
        helpMessage.append("  /添加屏蔽词 [词语] - 添加屏蔽词\n");
        helpMessage.append("  /删除屏蔽词 [词语] - 删除屏蔽词\n");
        helpMessage.append("  /查看屏蔽词 - 显示所有屏蔽词\n");
        helpMessage.append("  /开启屏蔽 - 开启屏蔽词功能\n");
        helpMessage.append("  /关闭屏蔽 - 关闭屏蔽词功能\n");
        helpMessage.append("  /拉黑 [@用户或QQ号] - 将用户添加到黑名单\n");
        helpMessage.append("  /解除拉黑 [@用户或QQ号] - 将用户从黑名单移除\n");
        helpMessage.append("  /查看黑名单 - 显示所有黑名单用户\n");
        
        botClient.sendGroupMessage(groupId, helpMessage.toString());
    }
    
    /**
     * 显示私聊帮助信息
     * @param userId 用户ID
     */
    private void showPrivateHelp(String userId) {
        StringBuilder helpMessage = new StringBuilder("📋 命令帮助列表：\n");
        helpMessage.append("👉 基础命令：\n");
        helpMessage.append("  /帮助 或 /help - 显示此帮助信息\n");
        helpMessage.append("  /模型 - 查看可用的AI模型\n");
        helpMessage.append("  /切换模型 [模型名] - 切换使用的AI模型\n");
        helpMessage.append("  /查看模型 - 查看当前使用的AI模型\n");
        helpMessage.append("  /人设 - 查看可用的人设\n");
        helpMessage.append("  /切换人设 [人设名] - 切换AI人设\n");
        helpMessage.append("  /查看人设 - 查看当前使用的人设\n");
        helpMessage.append("  /清除记忆 - 清除与AI的对话历史\n");
        helpMessage.append("  /状态 - 查看AI系统状态\n");
        
        // 添加管理员命令
        List<String> admins = getAdmins();
        if (admins.contains(userId)) {
            helpMessage.append("\n👨‍💼 管理员命令：\n");
            helpMessage.append("  /开启私聊 - 全局开启私聊功能\n");
            helpMessage.append("  /关闭私聊 - 全局关闭私聊功能\n");
            helpMessage.append("  /添加屏蔽词 [词语] - 添加屏蔽词\n");
            helpMessage.append("  /删除屏蔽词 [词语] - 删除屏蔽词\n");
            helpMessage.append("  /查看屏蔽词 - 显示所有屏蔽词\n");
            helpMessage.append("  /开启屏蔽 - 开启屏蔽词功能\n");
            helpMessage.append("  /关闭屏蔽 - 关闭屏蔽词功能\n");
            helpMessage.append("  /拉黑 [QQ号] - 将用户添加到黑名单\n");
            helpMessage.append("  /解除拉黑 [QQ号] - 将用户从黑名单移除\n");
            helpMessage.append("  /查看黑名单 - 显示所有黑名单用户\n");
        }
        
        botClient.sendPrivateMessage(userId, helpMessage.toString());
    }
    
    private void handlePersonaSwitch(String userId, String persona, boolean isGroup, String groupId) {
        if (personaManager.hasPersona(persona)) {
            // 获取旧人设
            String oldPersona = dataManager.getUserPersona(userId);
            
            // 设置新人设
            dataManager.setUserPersona(userId, persona);
            
            // 构建提示消息
            String message = "人设已切换为：" + persona;
            
            // 如果是不同人设，增加记忆提示
            if (!oldPersona.equals(persona)) {
                // 清除用户的对话历史，开始新对话
                aiService.clearConversation(userId);
                message += "\n已重置对话历史。新对话将以" + persona + "人设开始。";
            }
            
            // 发送消息
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
        } else {
            String errorMsg = "人设不存在，请使用 /查看人设 查看可用人设。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, errorMsg);
            } else {
                botClient.sendPrivateMessage(userId, errorMsg);
            }
        }
    }
    
    /**
     * 检查用户是否为群管理员或机器人管理员
     * @param userId 用户ID
     * @param groupId 群ID
     * @return 是否为管理员
     */
    private boolean isGroupAdmin(String userId, String groupId) {
        try {
            // 首先检查是否是机器人的全局管理员
            List<String> admins = getAdmins();
            
            // 如果是全局管理员，直接返回true
            if (admins.contains(userId)) {
                logger.debug("用户 {} 是机器人全局管理员", userId);
                return true;
            }
            
            // 通过发送者信息判断是否为群管理员
            // 获取发送消息时的sender信息
            // 如果是通过handleGroupMessage调用，可能已包含用户角色信息
            // 主动请求群成员信息
            JSONObject memberInfo = botClient.getGroupMemberInfoSync(groupId, userId);
            
            if (memberInfo != null) {
                // 检查用户角色
                String role = memberInfo.optString("role", "");
                if (role.equals("owner") || role.equals("admin")) {
                    logger.debug("用户 {} 是群 {} 的管理员，角色: {}", userId, groupId, role);
                    return true;
                }
            }
            
            logger.debug("用户 {} 不是群 {} 的管理员", userId, groupId);
            return false;
        } catch (Exception e) {
            logger.error("检查管理员权限时发生错误", e);
            // 如果出错，检查是否在全局管理员列表中
            List<String> admins = getAdmins();
            return admins.contains(userId); // 出错时，只有全局管理员能执行
        }
    }
    
    /**
     * 获取管理员列表
     * @return 管理员QQ号列表
     */
    @SuppressWarnings("unchecked")
    private List<String> getAdmins() {
        return configLoader.getConfig("bot.admins", new ArrayList<String>());
    }

    /**
     * 从命令中提取目标用户ID
     * 支持@用户或直接输入QQ号
     */
    private String extractTargetUserId(String input) {
        // 尝试从CQ码中提取@的用户ID
        String userId = CommonUtils.extractAtUserIdFromCQCode(input);
        if (userId != null) {
            return userId;
        }
        
        // 如果没有CQ码，尝试直接从纯文本中提取数字作为QQ号
        input = input.trim();
        if (input.matches("\\d{5,}")) {
            return input;
        }
        
        return null;
    }

    /**
     * 设置模型管理器
     * @param modelManager 模型管理器
     */
    public void setModelManager(ModelManager modelManager) {
        this.modelManager = modelManager;
    }
    
    /**
     * 设置人设管理器
     * @param personaManager 人设管理器
     */
    public void setPersonaManager(PersonaManager personaManager) {
        this.personaManager = personaManager;
    }

    /**
     * 关闭资源
     * 用于替代过时的finalize方法
     */
    public void shutdown() {
        try {
            logger.info("关闭消息处理器资源...");
            
            // 关闭线程池
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                logger.info("消息处理器线程池已关闭");
            }
        } catch (Exception e) {
            logger.error("关闭消息处理器资源时出错", e);
        }
    }
}