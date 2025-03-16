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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.ningmo.utils.CommonUtils;

public class MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(MessageHandler.class);
    
    private final OneBotClient botClient;
    private final ConfigLoader configLoader;
    private final DataManager dataManager;
    private final AIService aiService;
    private final ModelManager modelManager;
    private final PersonaManager personaManager;
    private final BlacklistManager blacklistManager;
    private final FilterWordManager filterWordManager;
    
    private final ExecutorService executor;
    
    public MessageHandler(OneBotClient botClient, ConfigLoader configLoader, DataManager dataManager, AIService aiService, BlacklistManager blacklistManager, FilterWordManager filterWordManager) {
        this.botClient = botClient;
        this.configLoader = configLoader;
        this.dataManager = dataManager;
        this.aiService = aiService;
        this.blacklistManager = blacklistManager;
        this.filterWordManager = filterWordManager;
        this.modelManager = new ModelManager(configLoader);
        this.personaManager = new PersonaManager(configLoader);
        this.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
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
     * 处理群消息并生成AI回复
     * @param groupId 群ID
     * @param userId 用户ID
     * @param content 消息内容
     * @param atSender 是否@发送者
     */
    private void processGroupAiReply(String groupId, String userId, String content, boolean atSender) {
        String cleanedContent = content.trim();
        if (cleanedContent.isEmpty()) {
            logger.debug("忽略空消息");
            return;
        }
        
        // 获取当前群成员信息
        boolean includeGroupMembers = configLoader.getConfig("ai.include_group_members", true);
        StringBuilder contextInfo = new StringBuilder();
        
        // 获取机器人自己的QQ号
        String botSelfId = configLoader.getConfigString("bot.self_id", "");
        
        // 添加群成员信息到上下文
        if (includeGroupMembers) {
            try {
                // 请求获取群成员列表（这会更新缓存）
                botClient.getGroupMemberList(groupId);
                
                // 获取缓存的群成员列表
                List<Map<String, String>> groupMembers = botClient.getGroupMembers(groupId);
                
                if (!groupMembers.isEmpty()) {
                    // 添加关于群成员的上下文信息
                    contextInfo.append("\n\n以下是当前群聊的活跃成员，你可以在回复中艾特他们：");
                    
                    // 添加提问者信息
                    contextInfo.append("\n- 提问者QQ: ").append(userId);
                    
                    // 添加其他群成员信息（限制数量，避免提示词过长）
                    int maxMembersToInclude = 10; // 最多包含10个成员
                    int count = 0;
                    
                    for (Map<String, String> member : groupMembers) {
                        if (count >= maxMembersToInclude) break;
                        
                        String memberId = member.get("user_id");
                        String memberName = member.get("card");
                        if (memberName == null || memberName.isEmpty()) {
                            memberName = member.get("nickname");
                        }
                        
                        // 跳过提问者自己（已经添加）和机器人自己
                        if (memberId.equals(userId) || memberId.equals(botSelfId)) continue;
                        
                        contextInfo.append("\n- ").append(memberName)
                                 .append(" (QQ: ").append(memberId).append(")");
                        count++;
                    }
                    
                    // 添加使用说明
                    contextInfo.append("\n\n你可以使用 [CQ:at,qq=成员QQ号] 格式来艾特群成员。例如：[CQ:at,qq=")
                             .append(userId).append("] 谢谢你的提问！");
                    
                    // 添加特别说明，不要艾特机器人自己
                    if (!botSelfId.isEmpty()) {
                        contextInfo.append("\n\n注意：请不要艾特机器人自己(QQ:")
                                 .append(botSelfId)
                                 .append(")，这可能导致消息循环。");
                    }
                }
            } catch (Exception e) {
                logger.error("获取群成员信息失败", e);
            }
        }
        
        try {
            executor.submit(() -> {
                try {
                    // 生成AI回复，添加群成员上下文
                    List<String> replyParts;
                    if (contextInfo.length() > 0) {
                        // 将群成员信息添加到用户消息中
                        String enhancedContent = cleanedContent + contextInfo.toString();
                        logger.debug("向AI提供了群成员信息，共{}字符", contextInfo.length());
                        replyParts = aiService.chatMultipart(userId, enhancedContent);
                    } else {
                        replyParts = aiService.chatMultipart(userId, cleanedContent);
                    }
                    
                    if (replyParts.isEmpty()) {
                        logger.info("AI选择不回复消息");
                        return;
                    }
                    
                    // 处理多段消息
                    int messageInterval = configLoader.getConfig("bot.messages.interval", 300);
                    boolean firstMessage = true;
                    
                    for (int i = 0; i < replyParts.size(); i++) {
                        String reply = replyParts.get(i);
                        
                        // 过滤掉回复中艾特机器人自己的部分
                        if (!botSelfId.isEmpty() && configLoader.getConfig("ai.filter_self_at", true)) {
                            String atBotPattern = "\\[CQ:at,qq=" + botSelfId + "(,[^\\]]*)?\\]";
                            reply = reply.replaceAll(atBotPattern, "");
                            
                            // 如果过滤后出现连续空格，将其合并
                            reply = reply.replaceAll("\\s+", " ").trim();
                        }
                        
                        // 如果过滤后消息为空，跳过这条消息
                        if (reply.isEmpty()) {
                            continue;
                        }
                        
                        // 如果配置了总是@发送者，且是第一条消息，且消息中没有已经@发送者，添加@
                        if (configLoader.getConfigBoolean("bot.always_at_sender") && 
                            firstMessage && 
                            atSender && 
                            !reply.contains("[CQ:at,qq=" + userId + "]")) {
                            reply = "[CQ:at,qq=" + userId + "] " + reply;
                        }
                        firstMessage = false;
                        
                        // 发送消息
                        botClient.sendGroupMessage(groupId, reply);
                        
                        // 如果还有更多消息，等待一下再发送
                        if (i < replyParts.size() - 1) {
                            try {
                                Thread.sleep(messageInterval);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                logger.error("消息发送间隔被中断", e);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("处理AI回复时出错", e);
                    botClient.sendGroupMessage(groupId, "处理回复时出错：" + e.getMessage());
                }
            });
        } catch (Exception e) {
            logger.error("提交AI处理任务失败", e);
            botClient.sendGroupMessage(groupId, "AI服务暂时不可用，请稍后再试");
        }
    }
    
    /**
     * 处理私聊消息并生成AI回复
     * @param userId 用户ID
     * @param content 消息内容
     */
    private void processPrivateAiReply(String userId, String content) {
        CompletableFuture.runAsync(() -> {
            try {
                // 获取多段回复
                List<String> replies = aiService.chatMultipart(userId, content);
                
                // 如果AI选择不回复
                if (replies.isEmpty()) {
                    logger.info("AI选择不回复私聊消息");
                    return;
                }
                
                // 获取消息间隔时间（毫秒）
                int messageInterval = configLoader.getConfig("bot.messages.interval", 300);
                
                // 发送所有消息
                for (int i = 0; i < replies.size(); i++) {
                    botClient.sendPrivateMessage(userId, replies.get(i));
                    
                    // 如果还有下一条消息，等待一段时间
                    if (i < replies.size() - 1) {
                        try {
                            Thread.sleep(messageInterval);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("处理私聊AI回复时出错", e);
                botClient.sendPrivateMessage(userId, "抱歉，处理回复时出现错误：" + e.getMessage());
            }
        }, executor);
    }
    
    private void handleGroupMessage(JSONObject message) {
        // 使用CommonUtils.safeGetString来安全获取group_id，避免类型转换异常
        String groupId = CommonUtils.safeGetString(message, "group_id");
        String userId = CommonUtils.safeGetString(message, "user_id");
        
        // 安全获取原始消息内容
        String content = CommonUtils.safeGetString(message, "raw_message");
        
        // 如果raw_message为空，尝试从message字段获取
        if (content.isEmpty() && message.has("message")) {
            content = CommonUtils.safeGetString(message, "message");
        }
        
        // 记录完整的原始消息，以便调试
        logger.debug("群 {} 用户 {} 发送原始消息: {}", groupId, userId, content);
        
        // 检查用户是否在黑名单中
        if (blacklistManager.isUserBlacklisted(userId)) {
            logger.info("用户 {} 在黑名单中，忽略其消息", userId);
            return;
        }
        
        // 检查是否为命令
        if (content.startsWith("/")) {
            handleGroupCommand(groupId, userId, content);
            return;
        }
        
        // 检查消息是否包含屏蔽词
        if (filterWordManager.containsFilterWord(content)) {
            logger.info("用户 {} 的消息包含屏蔽词，已拦截", userId);
            // 获取配置的拦截回复消息
            String replyMessage = filterWordManager.getFilterReplyMessage();
            // 是否艾特发送者，使用配置的设置
            boolean atSender = configLoader.getConfig("bot.always_at_sender", true);
            if (atSender) {
                replyMessage = "[CQ:at,qq=" + userId + "] " + replyMessage;
            }
            botClient.sendGroupMessage(groupId, replyMessage);
            return;
        }
        
        // 检查群是否启用AI
        if (!dataManager.isGroupAIEnabled(groupId)) {
            return;
        }
        
        // 获取管理员列表
        List<String> admins = getAdmins();
        logger.debug("机器人管理员列表: {}", admins);
        
        // 检查用户是否是管理员
        boolean isAdmin = isGroupAdmin(userId, groupId);
        if (isAdmin) {
            logger.debug("用户 {} 是群 {} 的管理员", userId, groupId);
        }
        
        // 获取消息的内容（移除CQ码）
        String messageText = content;
        
        // 检查是否被@
        boolean isAtBot = false;
        
        // 获取机器人QQ号
        String botQQ = configLoader.getConfigString("bot.self_id");
        
        // 检查是否包含@机器人的CQ码
        if (content.contains("[CQ:at,qq=" + botQQ + "]")) {
            isAtBot = true;
            // 移除@信息
            messageText = content.replace("[CQ:at,qq=" + botQQ + "]", "").trim();
        }
        
        // 获取机器人名字
        String botName = configLoader.getConfigString("bot.name", "柠枺");
        
        // 检查是否包含机器人名字
        boolean containsBotName = messageText.contains(botName);
        
        // 日志记录消息触发条件
        logger.debug("消息触发检测: isAtBot={}, containsBotName={}, isEnabled={}", isAtBot, containsBotName, dataManager.isGroupAIEnabled(groupId));
        
        // 如果群启用了AI，并且（被@了或消息包含机器人名字）
        if (dataManager.isGroupAIEnabled(groupId) && (isAtBot || containsBotName)) {
            // 如果消息包含机器人名字，移除名字
            if (containsBotName) {
                messageText = messageText.replace(botName, "").trim();
            }
            
            // 处理AI回复
            processGroupAiReply(groupId, userId, messageText, isAtBot);
        }
    }
    
    private void handlePrivateMessage(JSONObject message) {
        try {
            String userId = CommonUtils.safeGetString(message, "user_id");
            String rawMessage = message.optString("raw_message", "");
            
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
    
    private void handleGroupCommand(String groupId, String userId, String command) {
        try {
            // 检查是否是管理员
            boolean isAdmin = isGroupAdmin(userId, groupId);
            
            // 获取所有管理员
            List<String> admins = getAdmins();
            
            // 检查是否是全局管理员
            boolean isGlobalAdmin = admins.contains(userId);
            if (isGlobalAdmin) {
                logger.debug("用户 {} 是机器人全局管理员", userId);
            }
            
            // 启用AI命令
            if (command.matches("^/启用ai|/开启ai|/启用AI|/开启AI$")) {
                if (isAdmin) {
                    dataManager.setGroupAIEnabled(groupId, true);
                    botClient.sendGroupMessage(groupId, "AI已启用，可以开始聊天了");
                } else {
                    botClient.sendGroupMessage(groupId, "只有群管理员或机器人管理员才能启用AI");
                }
                return;
            }
            
            // 禁用AI命令
            if (command.matches("^/禁用ai|/关闭ai|/禁用AI|/关闭AI$")) {
                if (isAdmin) {
                    dataManager.setGroupAIEnabled(groupId, false);
                    botClient.sendGroupMessage(groupId, "AI已禁用");
                } else {
                    botClient.sendGroupMessage(groupId, "只有群管理员或机器人管理员才能禁用AI");
                }
                return;
            }
            
            // 拉黑用户命令 - 只有机器人全局管理员可以使用
            if (command.startsWith("/拉黑 ") || command.startsWith("/加入黑名单 ")) {
                if (isGlobalAdmin) {
                    // 从指令中提取目标用户ID
                    String targetId;
                    if (command.startsWith("/拉黑 ")) {
                        targetId = command.substring("/拉黑 ".length());
                    } else {
                        targetId = command.substring("/加入黑名单 ".length());
                    }
                    
                    // 日志输出原始指令内容
                    logger.debug("拉黑命令原始内容: {}", targetId);
                    
                    // 从艾特CQ码中提取用户ID
                    String extractedId = CommonUtils.extractAtUserIdFromCQCode(targetId);
                    if (extractedId != null) {
                        logger.info("成功从艾特中提取用户ID: {}", extractedId);
                        targetId = extractedId;
                    } else if (targetId.contains("CQ:at")) {
                        // 如果包含CQ:at但未能提取到ID，输出更详细的日志
                        logger.warn("包含艾特代码但未能提取ID: {}", targetId);
                    }
                    
                    handleBlacklistCommand(groupId, targetId.trim(), true);
                } else {
                    botClient.sendGroupMessage(groupId, "只有机器人管理员才能使用拉黑功能");
                }
                return;
            }
            
            // 解除拉黑命令 - 只有机器人全局管理员可以使用
            if (command.startsWith("/解除拉黑 ") || command.startsWith("/移出黑名单 ")) {
                if (isGlobalAdmin) {
                    // 从指令中提取目标用户ID
                    String targetId;
                    if (command.startsWith("/解除拉黑 ")) {
                        targetId = command.substring("/解除拉黑 ".length());
                    } else {
                        targetId = command.substring("/移出黑名单 ".length());
                    }
                    
                    // 日志输出原始指令内容
                    logger.debug("解除拉黑命令原始内容: {}", targetId);
                    
                    // 从艾特CQ码中提取用户ID
                    String extractedId = CommonUtils.extractAtUserIdFromCQCode(targetId);
                    if (extractedId != null) {
                        logger.info("成功从艾特中提取用户ID: {}", extractedId);
                        targetId = extractedId;
                    } else if (targetId.contains("CQ:at")) {
                        // 如果包含CQ:at但未能提取到ID，输出更详细的日志
                        logger.warn("包含艾特代码但未能提取ID: {}", targetId);
                    }
                    
                    handleBlacklistCommand(groupId, targetId.trim(), false);
                } else {
                    botClient.sendGroupMessage(groupId, "只有机器人管理员才能使用解除拉黑功能");
                }
                return;
            }
            
            // 查看黑名单命令 - 只有机器人全局管理员可以使用
            if (command.equals("/查看黑名单") || command.equals("/黑名单列表")) {
                if (isGlobalAdmin) {
                    List<String> blacklist = blacklistManager.getBlacklistedUsers();
                    if (blacklist.isEmpty()) {
                        botClient.sendGroupMessage(groupId, "当前黑名单为空");
                    } else {
                        botClient.sendGroupMessage(groupId, "当前黑名单：\n" + String.join("\n", blacklist));
                    }
                } else {
                    botClient.sendGroupMessage(groupId, "只有机器人管理员才能查看黑名单");
                }
                return;
            }
            
            // 添加屏蔽词命令 - 只有机器人全局管理员可以使用
            if (command.startsWith("/添加屏蔽词 ") || command.startsWith("/增加屏蔽词 ")) {
                if (isGlobalAdmin) {
                    // 从指令中提取屏蔽词
                    String word;
                    if (command.startsWith("/添加屏蔽词 ")) {
                        word = command.substring("/添加屏蔽词 ".length()).trim();
                    } else {
                        word = command.substring("/增加屏蔽词 ".length()).trim();
                    }
                    
                    if (word.isEmpty()) {
                        botClient.sendGroupMessage(groupId, "请提供要添加的屏蔽词");
                        return;
                    }
                    
                    // 添加屏蔽词
                    boolean success = filterWordManager.addFilterWord(word);
                    if (success) {
                        botClient.sendGroupMessage(groupId, "屏蔽词\"" + word + "\"已添加");
                    } else {
                        botClient.sendGroupMessage(groupId, "屏蔽词\"" + word + "\"已存在");
                    }
                } else {
                    botClient.sendGroupMessage(groupId, "只有机器人管理员才能管理屏蔽词");
                }
                return;
            }
            
            // 删除屏蔽词命令 - 只有机器人全局管理员可以使用
            if (command.startsWith("/删除屏蔽词 ") || command.startsWith("/移除屏蔽词 ")) {
                if (isGlobalAdmin) {
                    // 从指令中提取屏蔽词
                    String word;
                    if (command.startsWith("/删除屏蔽词 ")) {
                        word = command.substring("/删除屏蔽词 ".length()).trim();
                    } else {
                        word = command.substring("/移除屏蔽词 ".length()).trim();
                    }
                    
                    if (word.isEmpty()) {
                        botClient.sendGroupMessage(groupId, "请提供要删除的屏蔽词");
                        return;
                    }
                    
                    // 删除屏蔽词
                    boolean success = filterWordManager.removeFilterWord(word);
                    if (success) {
                        botClient.sendGroupMessage(groupId, "屏蔽词\"" + word + "\"已删除");
                    } else {
                        botClient.sendGroupMessage(groupId, "屏蔽词\"" + word + "\"不存在");
                    }
                } else {
                    botClient.sendGroupMessage(groupId, "只有机器人管理员才能管理屏蔽词");
                }
                return;
            }
            
            // 查看屏蔽词命令 - 只有机器人全局管理员可以使用
            if (command.equals("/查看屏蔽词") || command.equals("/屏蔽词列表")) {
                if (isGlobalAdmin) {
                    List<String> filterWords = filterWordManager.getFilterWords();
                    if (filterWords.isEmpty()) {
                        botClient.sendGroupMessage(groupId, "当前屏蔽词列表为空");
                    } else {
                        botClient.sendGroupMessage(groupId, "当前屏蔽词列表（共" + filterWords.size() + "个）：\n" + String.join("\n", filterWords));
                    }
                } else {
                    botClient.sendGroupMessage(groupId, "只有机器人管理员才能查看屏蔽词列表");
                }
                return;
            }
            
            // 处理人设相关命令
            if (command.startsWith("/新增人设 ") || command.startsWith("/添加人设 ")) {
                handleAddPersonaCommand(groupId, userId, command, true);
                return;
            } else if (command.startsWith("/删除人设 ")) {
                handleDeletePersonaCommand(groupId, userId, command, true);
                return;
            } else if (command.equals("/刷新人设")) {
                if (isGroupAdmin(userId, groupId)) {
                    personaManager.refreshPersonas();
                    botClient.sendGroupMessage(groupId, "人设列表已刷新！");
                } else {
                    botClient.sendGroupMessage(groupId, "只有管理员才能刷新人设列表。");
                }
                return;
            }
            
            // 处理帮助命令
            if (command.equals("/帮助") || command.equals("/help")) {
                StringBuilder helpMsg = new StringBuilder("柠枺AI机器人指令列表：\n");
                helpMsg.append("基础功能：\n");
                helpMsg.append("/切换模型 [模型名] - 切换AI模型\n");
                helpMsg.append("/查看模型 - 查看可用的AI模型\n");
                helpMsg.append("/切换人设 [人设名] - 切换AI人设\n");
                helpMsg.append("/查看人设 - 查看可用的人设\n");
                helpMsg.append("/清除对话 - 清除当前对话历史\n");
                helpMsg.append("/查看对话 - 查看当前对话历史\n");
                
                if (isAdmin) {
                    helpMsg.append("\n管理员功能：\n");
                    helpMsg.append("/启用ai - 启用AI功能\n");
                    helpMsg.append("/禁用ai - 禁用AI功能\n");
                    helpMsg.append("/新增人设 [人设名] [人设内容] - 创建新人设\n");
                    helpMsg.append("/删除人设 [人设名] - 删除指定人设\n");
                    helpMsg.append("/刷新人设 - 刷新人设列表\n");
                }
                
                if (isGlobalAdmin) {
                    helpMsg.append("\n全局管理员功能：\n");
                    helpMsg.append("/拉黑 [QQ号或@用户] - 将用户加入黑名单\n");
                    helpMsg.append("/解除拉黑 [QQ号或@用户] - 将用户从黑名单移除\n");
                    helpMsg.append("/查看黑名单 - 查看当前黑名单列表\n");
                    helpMsg.append("/添加屏蔽词 [词语] - 添加屏蔽词\n");
                    helpMsg.append("/删除屏蔽词 [词语] - 删除屏蔽词\n");
                    helpMsg.append("/查看屏蔽词 - 查看屏蔽词列表\n");
                }
                
                botClient.sendGroupMessage(groupId, helpMsg.toString());
                return;
            }
            
            // 未知命令
            logger.debug("未知命令: {}", command);
        } catch (Exception e) {
            logger.error("处理群命令时出错", e);
            botClient.sendGroupMessage(groupId, "处理命令时出错：" + e.getMessage());
        }
    }
    
    private void handlePrivateCommand(String userId, String command) {
        try {
            // 获取所有管理员
            List<String> admins = getAdmins();
            boolean isGlobalAdmin = admins.contains(userId);
            
            // 管理员专用命令
            if (isGlobalAdmin) {
                // 拉黑用户命令
                if (command.startsWith("/拉黑 ") || command.startsWith("/加入黑名单 ")) {
                    // 从指令中提取目标用户ID
                    String targetId;
                    if (command.startsWith("/拉黑 ")) {
                        targetId = command.substring("/拉黑 ".length());
                    } else {
                        targetId = command.substring("/加入黑名单 ".length());
                    }
                    
                    // 日志输出原始指令内容
                    logger.debug("私聊拉黑命令原始内容: {}", targetId);
                    
                    // 从艾特CQ码中提取用户ID
                    String extractedId = CommonUtils.extractAtUserIdFromCQCode(targetId);
                    if (extractedId != null) {
                        logger.info("私聊成功从艾特中提取用户ID: {}", extractedId);
                        targetId = extractedId;
                    } else if (targetId.contains("CQ:at")) {
                        // 如果包含CQ:at但未能提取到ID，输出更详细的日志
                        logger.warn("私聊包含艾特代码但未能提取ID: {}", targetId);
                    }
                    
                    handlePrivateBlacklistCommand(userId, targetId.trim(), true);
                    return;
                }
                
                // 解除拉黑命令
                if (command.startsWith("/解除拉黑 ") || command.startsWith("/移出黑名单 ")) {
                    // 从指令中提取目标用户ID
                    String targetId;
                    if (command.startsWith("/解除拉黑 ")) {
                        targetId = command.substring("/解除拉黑 ".length());
                    } else {
                        targetId = command.substring("/移出黑名单 ".length());
                    }
                    
                    // 日志输出原始指令内容
                    logger.debug("私聊解除拉黑命令原始内容: {}", targetId);
                    
                    // 从艾特CQ码中提取用户ID
                    String extractedId = CommonUtils.extractAtUserIdFromCQCode(targetId);
                    if (extractedId != null) {
                        logger.info("私聊成功从艾特中提取用户ID: {}", extractedId);
                        targetId = extractedId;
                    } else if (targetId.contains("CQ:at")) {
                        // 如果包含CQ:at但未能提取到ID，输出更详细的日志
                        logger.warn("私聊包含艾特代码但未能提取ID: {}", targetId);
                    }
                    
                    handlePrivateBlacklistCommand(userId, targetId.trim(), false);
                    return;
                }
                
                // 查看黑名单命令
                if (command.equals("/查看黑名单") || command.equals("/黑名单列表")) {
                    List<String> blacklist = blacklistManager.getBlacklistedUsers();
                    if (blacklist.isEmpty()) {
                        botClient.sendPrivateMessage(userId, "当前黑名单为空");
                    } else {
                        botClient.sendPrivateMessage(userId, "当前黑名单：\n" + String.join("\n", blacklist));
                    }
                    return;
                }

                // 添加屏蔽词命令
                if (command.startsWith("/添加屏蔽词 ") || command.startsWith("/增加屏蔽词 ")) {
                    // 提取屏蔽词
                    String word;
                    if (command.startsWith("/添加屏蔽词 ")) {
                        word = command.substring("/添加屏蔽词 ".length()).trim();
                    } else {
                        word = command.substring("/增加屏蔽词 ".length()).trim();
                    }
                    
                    if (word.isEmpty()) {
                        botClient.sendPrivateMessage(userId, "请提供要添加的屏蔽词");
                        return;
                    }
                    
                    // 添加屏蔽词
                    boolean success = filterWordManager.addFilterWord(word);
                    if (success) {
                        botClient.sendPrivateMessage(userId, "屏蔽词\"" + word + "\"已添加");
                    } else {
                        botClient.sendPrivateMessage(userId, "屏蔽词\"" + word + "\"已存在");
                    }
                    return;
                }
                
                // 删除屏蔽词命令
                if (command.startsWith("/删除屏蔽词 ") || command.startsWith("/移除屏蔽词 ")) {
                    // 提取屏蔽词
                    String word;
                    if (command.startsWith("/删除屏蔽词 ")) {
                        word = command.substring("/删除屏蔽词 ".length()).trim();
                    } else {
                        word = command.substring("/移除屏蔽词 ".length()).trim();
                    }
                    
                    if (word.isEmpty()) {
                        botClient.sendPrivateMessage(userId, "请提供要删除的屏蔽词");
                        return;
                    }
                    
                    // 删除屏蔽词
                    boolean success = filterWordManager.removeFilterWord(word);
                    if (success) {
                        botClient.sendPrivateMessage(userId, "屏蔽词\"" + word + "\"已删除");
                    } else {
                        botClient.sendPrivateMessage(userId, "屏蔽词\"" + word + "\"不存在");
                    }
                    return;
                }
                
                // 查看屏蔽词命令
                if (command.equals("/查看屏蔽词") || command.equals("/屏蔽词列表")) {
                    List<String> filterWords = filterWordManager.getFilterWords();
                    if (filterWords.isEmpty()) {
                        botClient.sendPrivateMessage(userId, "当前屏蔽词列表为空");
                    } else {
                        botClient.sendPrivateMessage(userId, "当前屏蔽词列表（共" + filterWords.size() + "个）：\n" + String.join("\n", filterWords));
                    }
                    return;
                }
            }
            
            // 去掉命令前后的空格
            command = command.trim();
            
            if (command.equals("/帮助") || command.equals("/help")) {
                StringBuilder sb = new StringBuilder("柠枺AI机器人命令列表：\n\n");
                sb.append("基本命令：\n");
                sb.append("/查看人设 - 查看可用人设列表\n");
                sb.append("/切换人设 [人设名] - 切换到指定人设\n");
                sb.append("/查看模型 - 查看可用模型列表\n");
                sb.append("/切换模型 [模型名] - 切换到指定模型\n");
                sb.append("/组合 [模型名] [人设名] - 同时设置模型和人设\n");
                sb.append("/清除对话 - 清除当前对话历史\n");
                sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                sb.append("/模型信息 [模型名] - 查看指定模型的详细信息\n");
                sb.append("/对话历史 - 显示当前对话的最近几条消息\n");
                
                // 管理员命令
                if (isGlobalAdmin) {
                    sb.append("\n管理员命令：\n");
                    sb.append("/启用ai - 启用群AI功能\n");
                    sb.append("/禁用ai - 禁用群AI功能\n");
                    sb.append("/模型详情 - 查看模型详细信息\n");
                    sb.append("\n人设管理命令：\n");
                    sb.append("/新增人设 [人设名] [人设内容] - 创建新的人设\n");
                    sb.append("/删除人设 [人设名] - 删除指定人设\n");
                    sb.append("/刷新人设 - 刷新人设列表（重新加载人设文件）\n");
                    sb.append("\n黑名单管理命令：\n");
                    sb.append("/拉黑 [QQ号或@用户] - 将用户加入黑名单\n");
                    sb.append("/解除拉黑 [QQ号或@用户] - 将用户从黑名单移除\n");
                    sb.append("/查看黑名单 - 查看当前黑名单列表\n");
                    sb.append("\n屏蔽词管理命令：\n");
                    sb.append("/添加屏蔽词 [词语] - 添加屏蔽词\n");
                    sb.append("/删除屏蔽词 [词语] - 删除屏蔽词\n");
                    sb.append("/查看屏蔽词 - 查看屏蔽词列表\n");
                }
                
                botClient.sendPrivateMessage(userId, sb.toString());
            } else if (command.startsWith("/切换人设 ")) {
                String persona = command.substring("/切换人设 ".length()).trim();
                handlePersonaSwitch(userId, persona, true, null);
            } else if (command.startsWith("/切换模型 ")) {
                String model = command.substring("/切换模型 ".length()).trim();
                if (modelManager.hasModel(model)) {
                    dataManager.setUserModel(userId, model);
                    botClient.sendPrivateMessage(userId, "模型已切换为：" + model);
                } else {
                    botClient.sendPrivateMessage(userId, "模型不存在，请使用 /查看模型 查看可用模型。");
                }
            } else {
                // 处理人设相关命令
                if (command.startsWith("/新增人设 ") || command.startsWith("/添加人设 ")) {
                    handleAddPersonaCommand("", userId, command, false);
                    return;
                } else if (command.startsWith("/删除人设 ")) {
                    handleDeletePersonaCommand("", userId, command, false);
                    return;
                } else if (command.equals("/刷新人设")) {
                    // 检查是否是管理员
                    if (isGroupAdmin(userId, "")) {
                        personaManager.refreshPersonas();
                        botClient.sendPrivateMessage(userId, "人设列表已刷新！");
                    } else {
                        botClient.sendPrivateMessage(userId, "只有管理员才能刷新人设列表。");
                    }
                    return;
                }
                
                if (command.equals("/帮助") || command.equals("/help")) {
                    StringBuilder sb = new StringBuilder("柠枺AI机器人命令列表：\n\n");
                    sb.append("基本命令：\n");
                    sb.append("/查看人设 - 查看可用人设列表\n");
                    sb.append("/切换人设 [人设名] - 切换到指定人设\n");
                    sb.append("/查看模型 - 查看可用模型列表\n");
                    sb.append("/切换模型 [模型名] - 切换到指定模型\n");
                    sb.append("/组合 [模型名] [人设名] - 同时设置模型和人设\n");
                    sb.append("/清除对话 - 清除当前对话历史\n");
                    sb.append("/ai状态 - 查询当前群AI功能是否启用\n");
                    sb.append("/模型信息 [模型名] - 查看指定模型的详细信息\n");
                    sb.append("/对话历史 - 显示当前对话的最近几条消息\n");
                    
                    // 管理员命令
                    if (isGlobalAdmin) {
                        sb.append("\n管理员命令：\n");
                        sb.append("/启用ai - 启用群AI功能\n");
                        sb.append("/禁用ai - 禁用群AI功能\n");
                        sb.append("/模型详情 - 查看模型详细信息\n");
                        sb.append("\n人设管理命令：\n");
                        sb.append("/新增人设 [人设名] [人设内容] - 创建新的人设\n");
                        sb.append("/删除人设 [人设名] - 删除指定人设\n");
                        sb.append("/刷新人设 - 刷新人设列表（重新加载人设文件）\n");
                        sb.append("\n黑名单管理命令：\n");
                        sb.append("/拉黑 [QQ号或@用户] - 将用户加入黑名单\n");
                        sb.append("/解除拉黑 [QQ号或@用户] - 将用户从黑名单移除\n");
                        sb.append("/查看黑名单 - 查看当前黑名单列表\n");
                        sb.append("\n屏蔽词管理命令：\n");
                        sb.append("/添加屏蔽词 [词语] - 添加屏蔽词\n");
                        sb.append("/删除屏蔽词 [词语] - 删除屏蔽词\n");
                        sb.append("/查看屏蔽词 - 查看屏蔽词列表\n");
                    }
                    
                    botClient.sendPrivateMessage(userId, sb.toString());
                } else {
                    botClient.sendPrivateMessage(userId, "未知命令。使用 /帮助 查看可用命令。");
                }
            }
        } catch (Exception e) {
            logger.error("处理私聊命令时出错", e);
            botClient.sendPrivateMessage(userId, "处理命令时出错：" + e.getMessage());
        }
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
     */
    private boolean isGroupAdmin(String userId, String groupId) {
        try {
            // 首先检查是否是机器人的全局管理员
            Object adminsObj = configLoader.getConfigMap("bot").getOrDefault("admins", new ArrayList<>());
            List<String> admins = new ArrayList<>();
            
            if (adminsObj instanceof List<?>) {
                for (Object item : (List<?>) adminsObj) {
                    if (item instanceof String) {
                        admins.add((String) item);
                    }
                }
            }
            
            // 打印管理员列表，帮助调试
            logger.debug("机器人管理员列表: {}", admins);
            
            // 如果是全局管理员，直接返回true
            if (admins.contains(userId)) {
                logger.debug("用户 {} 是机器人全局管理员", userId);
                return true;
            }
            
            // 这里可以添加群管理员检查
            // OneBot的API暂未实现，后续可以通过botClient.getGroupMemberInfo方法获取用户在群中的角色
            
            logger.debug("用户 {} 不是群 {} 的管理员", userId, groupId);
            return false;
        } catch (Exception e) {
            logger.error("检查管理员权限时发生错误", e);
            return false; // 出错时保守处理，当作非管理员
        }
    }
    
    /**
     * 处理添加人设命令
     */
    private void handleAddPersonaCommand(String groupId, String userId, String command, boolean isGroup) {
        // 只有管理员可以添加人设
        if (!isGroupAdmin(userId, groupId)) {
            String message = "只有管理员才能添加人设。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 解析命令
        String content;
        if (command.startsWith("/新增人设 ")) {
            content = command.substring("/新增人设 ".length());
        } else {
            content = command.substring("/添加人设 ".length());
        }
        
        // 分割人设名和内容
        int spaceIndex = content.indexOf(" ");
        if (spaceIndex <= 0) {
            String message = "命令格式错误，正确格式：/新增人设 人设名 人设内容";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        String personaName = content.substring(0, spaceIndex).trim();
        String personaContent = content.substring(spaceIndex + 1).trim();
        
        if (personaName.isEmpty() || personaContent.isEmpty()) {
            String message = "人设名和人设内容不能为空。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 创建人设
        boolean success = personaManager.createPersona(personaName, personaContent);
        String message;
        if (success) {
            message = "人设「" + personaName + "」创建成功！\n使用 /切换人设 " + personaName + " 来使用此人设。";
        } else {
            message = "人设创建失败，请检查人设名是否合法或联系管理员。";
        }
        
        if (isGroup) {
            botClient.sendGroupMessage(groupId, message);
        } else {
            botClient.sendPrivateMessage(userId, message);
        }
    }
    
    /**
     * 处理删除人设命令
     */
    private void handleDeletePersonaCommand(String groupId, String userId, String command, boolean isGroup) {
        // 只有管理员可以删除人设
        if (!isGroupAdmin(userId, groupId)) {
            String message = "只有管理员才能删除人设。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 解析命令
        String personaName = command.substring("/删除人设 ".length()).trim();
        
        if (personaName.isEmpty()) {
            String message = "命令格式错误，正确格式：/删除人设 人设名";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 不允许删除默认人设
        if ("default".equalsIgnoreCase(personaName)) {
            String message = "不允许删除默认人设。";
            if (isGroup) {
                botClient.sendGroupMessage(groupId, message);
            } else {
                botClient.sendPrivateMessage(userId, message);
            }
            return;
        }
        
        // 删除人设
        boolean success = personaManager.deletePersona(personaName);
        String message;
        if (success) {
            message = "人设「" + personaName + "」已删除。";
        } else {
            message = "删除人设失败，该人设可能不存在或无法删除。";
        }
        
        if (isGroup) {
            botClient.sendGroupMessage(groupId, message);
        } else {
            botClient.sendPrivateMessage(userId, message);
        }
    }
    
    /**
     * 处理拉黑和解除拉黑命令
     * @param groupId 群ID
     * @param targetUserId 目标用户ID
     * @param blacklist true=拉黑，false=解除拉黑
     */
    private void handleBlacklistCommand(String groupId, String targetUserId, boolean blacklist) {
        logger.info("处理黑名单命令，目标用户：{}，操作：{}", targetUserId, blacklist ? "拉黑" : "解除拉黑");
        
        // 如果目标用户ID为空或格式不正确，发送错误提示
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            botClient.sendGroupMessage(groupId, "请提供有效的QQ号或@要" + (blacklist ? "拉黑" : "解除拉黑") + "的用户");
            return;
        }
        
        // 尝试清理可能的额外字符
        targetUserId = targetUserId.trim();
        
        // 确保用户ID是有效的QQ号（纯数字）
        if (!targetUserId.matches("\\d+")) {
            botClient.sendGroupMessage(groupId, "无效的QQ号：" + targetUserId + "，请提供正确的QQ号或直接@用户");
            logger.warn("无效的用户ID格式: {}", targetUserId);
            return;
        }
        
        // 执行拉黑或解除拉黑操作
        boolean success;
        if (blacklist) {
            success = blacklistManager.addToBlacklist(targetUserId);
            if (success) {
                botClient.sendGroupMessage(groupId, "已将用户 " + targetUserId + " 加入黑名单");
                logger.info("已将用户 {} 加入黑名单", targetUserId);
            } else {
                botClient.sendGroupMessage(groupId, "用户 " + targetUserId + " 已在黑名单中");
                logger.info("用户 {} 已在黑名单中", targetUserId);
            }
        } else {
            success = blacklistManager.removeFromBlacklist(targetUserId);
            if (success) {
                botClient.sendGroupMessage(groupId, "已将用户 " + targetUserId + " 从黑名单中移除");
                logger.info("已将用户 {} 从黑名单中移除", targetUserId);
            } else {
                botClient.sendGroupMessage(groupId, "用户 " + targetUserId + " 不在黑名单中");
                logger.info("用户 {} 不在黑名单中", targetUserId);
            }
        }
    }
    
    /**
     * 处理私聊中的拉黑和解除拉黑命令
     * @param userId 管理员ID
     * @param targetUserId 目标用户ID
     * @param blacklist true=拉黑，false=解除拉黑
     */
    private void handlePrivateBlacklistCommand(String userId, String targetUserId, boolean blacklist) {
        try {
            // 验证QQ号是否合法
            if (!targetUserId.matches("\\d+")) {
                botClient.sendPrivateMessage(userId, "QQ号格式不正确，请输入数字QQ号");
                return;
            }
            
            boolean success;
            String message;
            
            if (blacklist) {
                success = blacklistManager.addToBlacklist(targetUserId);
                message = success ? 
                        "用户 " + targetUserId + " 已被加入黑名单，该用户无法使用AI服务" : 
                        "用户 " + targetUserId + " 已经在黑名单中";
            } else {
                success = blacklistManager.removeFromBlacklist(targetUserId);
                message = success ? 
                        "用户 " + targetUserId + " 已从黑名单中移除，可以正常使用AI服务" : 
                        "用户 " + targetUserId + " 不在黑名单中";
            }
            
            botClient.sendPrivateMessage(userId, message);
        } catch (Exception e) {
            logger.error("处理私聊黑名单命令时出错", e);
            botClient.sendPrivateMessage(userId, "处理命令时出错：" + e.getMessage());
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
}