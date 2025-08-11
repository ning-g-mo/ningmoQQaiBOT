package cn.ningmo.utils;

import cn.ningmo.config.ConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/**
 * 图片处理器测试类
 */
public class ImageProcessorTest {
    
    private ImageProcessor imageProcessor;
    private ConfigLoader configLoader;
    
    @BeforeEach
    void setUp() {
        configLoader = new ConfigLoader();
        configLoader.loadConfig();
        imageProcessor = new ImageProcessor(configLoader);
    }
    
    @Test
    void testExtractImageCQCodes() {
        String message = "这是一张图片[CQ:image,file=test.jpg]和文字";
        List<String> codes = CommonUtils.extractImageCQCodes(message);
        assertEquals(1, codes.size());
        assertEquals("[CQ:image,file=test.jpg]", codes.get(0));
    }
    
    @Test
    void testExtractImageUrlFromCQCode() {
        String cqCode = "[CQ:image,file=test.jpg,url=http://example.com/test.jpg]";
        String url = CommonUtils.extractImageUrlFromCQCode(cqCode);
        assertEquals("http://example.com/test.jpg", url);
    }
    
    @Test
    void testContainsImage() {
        String messageWithImage = "这是包含图片的消息[CQ:image,file=test.jpg]";
        String messageWithoutImage = "这是不包含图片的消息";
        
        assertTrue(CommonUtils.containsImage(messageWithImage));
        assertFalse(CommonUtils.containsImage(messageWithoutImage));
    }
    
    @Test
    void testProcessImagesWithNoImage() {
        String message = "这是纯文本消息";
        ImageProcessor.ImageProcessResult result = imageProcessor.processImages(message);
        
        assertFalse(result.hasImages());
        assertFalse(result.hasError());
        assertEquals(0, result.getSuccessCount());
    }
    
    @Test
    void testProcessImagesWithInvalidCQCode() {
        String message = "这是包含无效CQ码的消息[CQ:image]";
        ImageProcessor.ImageProcessResult result = imageProcessor.processImages(message);
        
        assertFalse(result.hasImages());
        assertTrue(result.hasError());
        assertEquals("无法提取图片CQ码", result.getErrorMessage());
    }
    
    @Test
    void testGetImageMimeType() {
        assertEquals("image/jpeg", CommonUtils.getImageMimeType("http://example.com/test.jpg"));
        assertEquals("image/png", CommonUtils.getImageMimeType("http://example.com/test.png"));
        assertEquals("image/gif", CommonUtils.getImageMimeType("http://example.com/test.gif"));
        assertEquals("image/webp", CommonUtils.getImageMimeType("http://example.com/test.webp"));
        assertEquals("image/jpeg", CommonUtils.getImageMimeType("http://example.com/test")); // 默认
    }
}
