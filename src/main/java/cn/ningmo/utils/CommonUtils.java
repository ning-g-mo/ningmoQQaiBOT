package cn.ningmo.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommonUtils {
    private static final Logger logger = LoggerFactory.getLogger(CommonUtils.class);
    private static final Random random = new Random();
    
    /**
     * 确保目录存在，如果不存在则创建
     */
    public static void ensureDirectoryExists(String directoryPath) {
        Path path = Paths.get(directoryPath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
                logger.info("已创建目录: {}", directoryPath);
            } catch (IOException e) {
                logger.error("创建目录失败: {}", directoryPath, e);
            }
        }
    }
    
    /**
     * 获取当前时间格式化字符串
     */
    public static String getCurrentTimeString(String format) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(format);
        return dateFormat.format(new Date());
    }
    
    /**
     * 从CQ码中提取消息内容
     */
    public static String extractTextFromCQCode(String message) {
        // 移除所有CQ码
        return message.replaceAll("\\[CQ:[^\\]]+\\]", "").trim();
    }
    
    /**
     * 从CQ码中提取@的用户ID
     */
    public static String extractAtUserIdFromCQCode(String message) {
        Pattern pattern = Pattern.compile("\\[CQ:at,qq=(\\d+)\\]");
        Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 生成指定范围内的随机整数
     */
    public static int randomInt(int min, int max) {
        return random.nextInt(max - min + 1) + min;
    }
    
    /**
     * 截断文本到指定长度
     */
    public static String truncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
    
    /**
     * 判断字符串是否为空或null
     */
    public static boolean isNullOrEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }
} 