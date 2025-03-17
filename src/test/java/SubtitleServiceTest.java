import com.example.littlepolice.model.SubtitleEntry;
import com.example.littlepolice.service.SubtitleService;
import com.example.littlepolice.util.TokenCalculator;
import com.example.littlepolice.service.DeepSeekService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

public class SubtitleServiceTest {

    @Test
    void testInvalidSequenceNumber() throws IOException {
        // 从文件读取字幕内容
        String filePath = "/Users/sbr/Desktop/[zmk.pw][色戒(完整未删减版)].Lust.Caution.2007.BluRay.720p.x264.AC3-CMCT_processed.srt";
        String srtContent = new String(Files.readAllBytes(Paths.get(filePath)));

        // 创建 Logger 的 mock 对象
        Logger mockLog = Mockito.mock(Logger.class);
        TokenCalculator mockTokenCalculator = Mockito.mock(TokenCalculator.class);
        DeepSeekService mockDeepSeekService = Mockito.mock(DeepSeekService.class);
        SubtitleService subtitleService = new SubtitleService(mockTokenCalculator, mockDeepSeekService);

        // 调用解析方法
        List<SubtitleEntry> entries = subtitleService.parseSrtContent(srtContent);

        // 输出解析结果
        System.out.println("解析到的有效字幕条目数量: " + entries.size());
        for (SubtitleEntry entry : entries) {
            System.out.println("有效字幕: 序号 " + entry.getIndex() + " -> " + entry.getText());
        }

        // 验证日志中是否记录了无效序号的警告
        verify(mockLog, atLeastOnce()).warn(contains("跳过无效序号的字幕条目"));
    }
}