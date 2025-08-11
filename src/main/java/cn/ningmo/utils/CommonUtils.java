package cn.ningmo.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;

import org.json.JSONObject;

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
        // 首先使用正则表达式匹配标准格式 [CQ:at,qq=数字]
        Pattern standardPattern = Pattern.compile("\\[CQ:at,qq=(\\d+)\\]");
        Matcher standardMatcher = standardPattern.matcher(message);
        if (standardMatcher.find()) {
            return standardMatcher.group(1);
        }
        
        // 尝试匹配更宽松的格式 (如果CQ码可能有其他参数)
        Pattern loosePattern = Pattern.compile("CQ:at,qq=(\\d+)");
        Matcher looseMatcher = loosePattern.matcher(message);
        if (looseMatcher.find()) {
            return looseMatcher.group(1);
        }
        
        // 尝试在消息中直接查找纯数字 (作为最后的备选方案)
        Pattern numberPattern = Pattern.compile("^\\s*(\\d{5,})\\s*$");
        Matcher numberMatcher = numberPattern.matcher(message);
        if (numberMatcher.find()) {
            return numberMatcher.group(1);
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

    /**
     * 从JSONObject中安全获取字符串，适用于各种数据类型
     */
    public static String safeGetString(JSONObject json, String key) {
        if (!json.has(key)) return "";
        
        Object obj = json.get(key);
        return obj == null ? "" : String.valueOf(obj);
    }

    /**
     * 从JSONObject中安全获取长整型
     */
    public static long safeGetLong(JSONObject json, String key, long defaultValue) {
        if (!json.has(key)) return defaultValue;
        
        try {
            Object obj = json.get(key);
            if (obj instanceof Number) {
                return ((Number) obj).longValue();
            } else if (obj instanceof String) {
                return Long.parseLong((String) obj);
            }
        } catch (Exception ignored) {
        }
        
        return defaultValue;
    }

    /**
     * 从JSONObject中安全获取整型
     */
    public static int safeGetInt(JSONObject json, String key, int defaultValue) {
        if (!json.has(key)) return defaultValue;
        
        try {
            Object obj = json.get(key);
            if (obj instanceof Number) {
                return ((Number) obj).intValue();
            } else if (obj instanceof String) {
                return Integer.parseInt((String) obj);
            }
        } catch (Exception ignored) {
        }
        
        return defaultValue;
    }

    /**
     * 检查字符串是否为空
     */
    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    /**
     * 从消息中提取图片CQ码
     * @param message 原始消息
     * @return 图片CQ码列表
     */
    public static List<String> extractImageCQCodes(String message) {
        List<String> imageCodes = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[CQ:image[^\\]]*\\]");
        Matcher matcher = pattern.matcher(message);
        
        while (matcher.find()) {
            imageCodes.add(matcher.group());
        }
        
        return imageCodes;
    }
    
    /**
     * 从图片CQ码中提取图片URL
     * @param imageCQCode 图片CQ码
     * @return 图片URL，如果提取失败返回null
     */
    public static String extractImageUrlFromCQCode(String imageCQCode) {
        // 匹配 file= 参数
        Pattern filePattern = Pattern.compile("file=([^,\\]]+)");
        Matcher fileMatcher = filePattern.matcher(imageCQCode);
        if (fileMatcher.find()) {
            return fileMatcher.group(1);
        }
        
        // 匹配 url= 参数
        Pattern urlPattern = Pattern.compile("url=([^,\\]]+)");
        Matcher urlMatcher = urlPattern.matcher(imageCQCode);
        if (urlMatcher.find()) {
            return urlMatcher.group(1);
        }
        
        return null;
    }
    
    /**
     * 下载图片并转换为base64编码
     * @param imageUrl 图片URL
     * @return base64编码的图片数据，如果失败返回null
     */
    public static String downloadImageAsBase64(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            
            // 设置连接超时和读取超时
            java.net.URLConnection connection = url.openConnection();
            connection.setConnectTimeout(10000); // 10秒连接超时
            connection.setReadTimeout(30000);    // 30秒读取超时
            
            try (InputStream inputStream = connection.getInputStream()) {
                byte[] imageBytes = inputStream.readAllBytes();
                
                // 检查图片大小，避免过大的图片
                if (imageBytes.length > 10 * 1024 * 1024) { // 10MB限制
                    logger.warn("图片过大，跳过处理: {} ({} bytes)", imageUrl, imageBytes.length);
                    return null;
                }
                
                // 检查图片格式
                String contentType = connection.getContentType();
                if (contentType != null && !contentType.startsWith("image/")) {
                    logger.warn("URL不是图片格式: {} (Content-Type: {})", imageUrl, contentType);
                    return null;
                }
                
                return Base64.getEncoder().encodeToString(imageBytes);
            }
        } catch (Exception e) {
            logger.error("下载图片失败: {}", imageUrl, e);
            return null;
        }
    }
    
    /**
     * 检测消息是否包含图片
     * @param message 消息内容
     * @return 是否包含图片
     */
    public static boolean containsImage(String message) {
        return message.contains("[CQ:image");
    }
    
    /**
     * 获取图片的MIME类型
     * @param imageUrl 图片URL
     * @return MIME类型，如果无法确定返回image/jpeg
     */
    public static String getImageMimeType(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            java.net.URLConnection connection = url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            
            String contentType = connection.getContentType();
            if (contentType != null && contentType.startsWith("image/")) {
                return contentType;
            }
        } catch (Exception e) {
            logger.debug("无法获取图片MIME类型: {}", imageUrl, e);
        }
        
        // 根据文件扩展名推断
        if (imageUrl.toLowerCase().endsWith(".png")) {
            return "image/png";
        } else if (imageUrl.toLowerCase().endsWith(".gif")) {
            return "image/gif";
        } else if (imageUrl.toLowerCase().endsWith(".webp")) {
            return "image/webp";
        }
        
        // 默认返回jpeg
        return "image/jpeg";
    }
} 