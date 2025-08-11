package cn.ningmo.utils;

import cn.ningmo.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片处理工具类
 * 用于处理QQ机器人消息中的图片CQ码
 */
public class ImageProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessor.class);
    
    private final ConfigLoader configLoader;
    private final boolean imageEnabled;
    private final int maxSizeMB;
    private final int timeoutSeconds;
    private final List<String> supportedFormats;
    
    public ImageProcessor(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.imageEnabled = configLoader.getConfigBoolean("ai.image.enabled", true);
        this.maxSizeMB = configLoader.getConfigInt("ai.image.max_size_mb", 10);
        this.timeoutSeconds = configLoader.getConfigInt("ai.image.timeout_seconds", 30);
        this.supportedFormats = configLoader.getConfigList("ai.image.supported_formats", 
            List.of("image/jpeg", "image/png", "image/gif", "image/webp"));
        
        logger.info("图片处理器初始化完成: enabled={}, maxSize={}MB, timeout={}s", 
                   imageEnabled, maxSizeMB, timeoutSeconds);
    }
    
    /**
     * 处理消息中的图片
     * @param message 原始消息
     * @return 处理结果
     */
    public ImageProcessResult processImages(String message) {
        if (!imageEnabled) {
            logger.debug("图片处理功能已禁用");
            return new ImageProcessResult(new ArrayList<>(), 0, "图片处理功能已禁用");
        }
        
        if (!CommonUtils.containsImage(message)) {
            return new ImageProcessResult(new ArrayList<>(), 0, null);
        }
        
        logger.info("开始处理消息中的图片...");
        List<String> imageBase64List = new ArrayList<>();
        List<String> imageCQCodes = CommonUtils.extractImageCQCodes(message);
        
        if (imageCQCodes.isEmpty()) {
            logger.warn("消息包含图片标记但无法提取CQ码: {}", message);
            return new ImageProcessResult(new ArrayList<>(), 0, "无法提取图片CQ码");
        }
        
        logger.info("提取到 {} 个图片CQ码", imageCQCodes.size());
        int successCount = 0;
        int invalidCQCount = 0;
        
        for (String imageCQCode : imageCQCodes) {
            String imageUrl = CommonUtils.extractImageUrlFromCQCode(imageCQCode);
            if (imageUrl == null) {
                logger.warn("无法从CQ码中提取图片URL: {}", imageCQCode);
                invalidCQCount++;
                continue;
            }
            
            logger.debug("开始下载图片: {}", imageUrl);
            String imageBase64 = downloadAndEncodeImage(imageUrl);
            if (imageBase64 != null) {
                imageBase64List.add(imageBase64);
                successCount++;
                logger.info("成功处理图片: {} (大小: {} bytes)", imageUrl, imageBase64.length());
            } else {
                logger.warn("图片处理失败: {}", imageUrl);
            }
        }
        
        String errorMessage = null;
        if (invalidCQCount == imageCQCodes.size()) {
            // 所有CQ码都无法提取URL
            errorMessage = "无法提取图片CQ码";
        } else if (successCount == 0 && !imageCQCodes.isEmpty()) {
            // 可以提取URL但所有图片处理失败
            errorMessage = "所有图片处理失败";
        }
        
        logger.info("图片处理完成: 成功 {}/{}, 准备发送给AI模型", successCount, imageCQCodes.size());
        return new ImageProcessResult(imageBase64List, successCount, errorMessage);
    }
    
    /**
     * 下载并编码图片
     * @param imageUrl 图片URL
     * @return base64编码的图片数据，如果失败返回null
     */
    private String downloadAndEncodeImage(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(timeoutSeconds * 1000);
            connection.setReadTimeout(timeoutSeconds * 1000);
            
            // 检查内容类型
            String contentType = connection.getContentType();
            if (contentType != null && !isSupportedFormat(contentType)) {
                // 特殊处理QQ图片URL，它们可能返回JSON而不是直接的图片
                if (imageUrl.contains("multimedia.nt.qq.com.cn") && contentType.contains("application/json")) {
                    logger.info("检测到QQ图片URL，尝试从JSON响应中提取图片数据: {}", imageUrl);
                    // 这里可以添加从JSON响应中提取图片URL的逻辑
                    // 暂时跳过，因为需要解析JSON响应
                    logger.warn("QQ图片URL需要特殊处理，暂时跳过: {}", imageUrl);
                    return null;
                } else {
                    logger.warn("不支持的图片格式: {} (Content-Type: {})", imageUrl, contentType);
                    return null;
                }
            }
            
            try (InputStream inputStream = connection.getInputStream()) {
                byte[] imageBytes = inputStream.readAllBytes();
                
                // 检查图片大小
                if (imageBytes.length > maxSizeMB * 1024 * 1024) {
                    logger.warn("图片过大，跳过处理: {} ({} bytes, 限制: {}MB)", 
                              imageUrl, imageBytes.length, maxSizeMB);
                    return null;
                }
                
                return java.util.Base64.getEncoder().encodeToString(imageBytes);
            }
        } catch (IOException e) {
            logger.error("下载图片失败: {}", imageUrl, e);
            return null;
        } catch (Exception e) {
            logger.error("处理图片时发生未知错误: {}", imageUrl, e);
            return null;
        }
    }
    
    /**
     * 检查是否为支持的图片格式
     * @param contentType 内容类型
     * @return 是否支持
     */
    private boolean isSupportedFormat(String contentType) {
        if (contentType == null) {
            return false;
        }
        
        String lowerContentType = contentType.toLowerCase();
        for (String format : supportedFormats) {
            if (lowerContentType.startsWith(format.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 图片处理结果
     */
    public static class ImageProcessResult {
        private final List<String> imageBase64List;
        private final int successCount;
        private final String errorMessage;
        
        public ImageProcessResult(List<String> imageBase64List, int successCount, String errorMessage) {
            this.imageBase64List = imageBase64List;
            this.successCount = successCount;
            this.errorMessage = errorMessage;
        }
        
        public List<String> getImageBase64List() {
            return imageBase64List;
        }
        
        public int getSuccessCount() {
            return successCount;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public boolean hasImages() {
            return !imageBase64List.isEmpty();
        }
        
        public boolean hasError() {
            return errorMessage != null;
        }
    }
}
