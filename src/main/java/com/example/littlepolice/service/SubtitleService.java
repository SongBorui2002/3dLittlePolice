package com.example.littlepolice.service;

import com.example.littlepolice.model.SubtitleEntry;
import com.example.littlepolice.util.TokenCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubtitleService {
    @Value("${subtitle.batch.max-size:4000}")
    private int maxBatchSize; // 每批最大字符数，可通过配置文件调整
    
    @Value("${subtitle.batch.max-tokens:2000}")
    private int maxBatchTokens; // 每批最大token数，可通过配置文件调整
    
    private static final Pattern TIME_CODE_PATTERN = Pattern.compile("\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2},\\d{3}");

    private final TokenCalculator tokenCalculator;
    private final DeepSeekService deepSeekService;

    @Data
    @AllArgsConstructor
    public static class BatchCorrection {
        private String text;
        private List<Integer> entryIndices;
        private int tokenCount;  // 添加token计数字段
    }

    public List<SubtitleEntry> parseSrtContent(String content) {
        List<SubtitleEntry> entries = new ArrayList<>();

        // 兼容 Windows 和 macOS/Linux 换行符
        String[] blocks = content.trim().split("\\r?\\n\\s*\\r?\\n");

        for (String block : blocks) {
            String[] lines = block.trim().split("\\r?\\n");

            if (lines.length >= 3) {
                try {
                    // 去除 UTF-8 BOM（\uFEFF）并确保序号是数字
                    String indexStr = lines[0].trim().replace("\uFEFF", "");
                    if (!indexStr.matches("\\d+")) {
                        log.warn("跳过无效序号的字幕条目: {}", indexStr);
                        continue;
                    }

                    int index = Integer.parseInt(indexStr);
                    String timeCode = lines[1].trim();

                    // 验证时间码格式
                    if (!TIME_CODE_PATTERN.matcher(timeCode).matches()) {
                        log.warn("跳过无效时间码的字幕条目: {}", index);
                        continue;
                    }

                    // 组合字幕文本
                    String text = String.join("\n", Arrays.copyOfRange(lines, 2, lines.length));

                    // 创建字幕条目，并检查是否需要修正
                    SubtitleEntry entry = new SubtitleEntry();
                    entry.setIndex(index);
                    entry.setTimeCode(timeCode);
                    entry.setText(text);
                    entry.setNeedsCorrection(needsCorrection(text));

                    entries.add(entry);
                } catch (NumberFormatException e) {
                    log.warn("跳过无法解析的字幕条目: {}", lines[0], e);
                }
            } else {
                log.warn("跳过格式不完整的字幕条目: {}", block);
            }
        }

        log.info("解析到 {} 条字幕", entries.size());
        return entries;
    }


    public List<BatchCorrection> extractTextForCorrection(List<SubtitleEntry> entries) {
        List<BatchCorrection> batches = new ArrayList<>();
        
        // 如果字幕条数小于20，使用单批处理
        if (entries.size() < 20) {
            return createSingleBatch(entries);
        }
        
        // 字幕条数大于20，使用三批处理
        return createTripleBatches(entries);
    }
    
    private List<BatchCorrection> createSingleBatch(List<SubtitleEntry> entries) {
        List<BatchCorrection> batches = new ArrayList<>();
        StringBuilder currentBatch = new StringBuilder();
        List<Integer> currentIndices = new ArrayList<>();
        int currentTokens = 0;
        
        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry entry = entries.get(i);
            if (entry.isNeedsCorrection()) {
                String text = entry.getText().trim();
                int textTokens = tokenCalculator.calculateTokens(text);
                
                // 如果当前批次加上新文本会超过最大字符数或token数，创建新批次
                if ((currentBatch.length() + text.length() + 2 > maxBatchSize || 
                     currentTokens + textTokens > maxBatchTokens) && 
                    currentBatch.length() > 0) {
                    batches.add(new BatchCorrection(currentBatch.toString(), 
                                                  new ArrayList<>(currentIndices),
                                                  currentTokens));
                    currentBatch = new StringBuilder();
                    currentIndices = new ArrayList<>();
                    currentTokens = 0;
                }
                
                // 添加到当前批次
                if (currentBatch.length() > 0) {
                    currentBatch.append("\n---\n");
                }
                currentBatch.append(text);
                currentIndices.add(i);
                currentTokens += textTokens;
            }
        }
        
        // 添加最后一个批次
        if (currentBatch.length() > 0) {
            batches.add(new BatchCorrection(currentBatch.toString(), 
                                          currentIndices,
                                          currentTokens));
        }
        
        return batches;
    }
    
    private List<BatchCorrection> createTripleBatches(List<SubtitleEntry> entries) {
        List<BatchCorrection> batches = new ArrayList<>();
        List<SubtitleEntry> needsCorrectionEntries = new ArrayList<>();
        
        // 收集需要修正的字幕
        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry entry = entries.get(i);
            if (entry.isNeedsCorrection()) {
                needsCorrectionEntries.add(entry);
            }
        }
        
        // 计算每个批次应该包含的字幕数量
        int entriesPerBatch = needsCorrectionEntries.size() / 3;
        int remainingEntries = needsCorrectionEntries.size() % 3;
        
        // 创建三个批次
        int startIndex = 0;
        for (int batchNum = 0; batchNum < 3; batchNum++) {
            int batchSize = entriesPerBatch + (batchNum < remainingEntries ? 1 : 0);
            if (batchSize == 0) continue;
            
            StringBuilder batchText = new StringBuilder();
            List<Integer> batchIndices = new ArrayList<>();
            int batchTokens = 0;
            int processedEntries = 0;
            
            // 处理当前批次的字幕
            while (startIndex < entries.size() && processedEntries < batchSize) {
                SubtitleEntry entry = entries.get(startIndex);
                if (entry.isNeedsCorrection()) {
                    String text = entry.getText().trim();
                    int textTokens = tokenCalculator.calculateTokens(text);
                    
                    // 检查是否超过最大字符数或token数
                    if (batchText.length() + text.length() + 2 > maxBatchSize || 
                        batchTokens + textTokens > maxBatchTokens) {
                        // 如果当前批次已经有内容，保存当前批次并创建新批次
                        if (batchText.length() > 0) {
                            batches.add(new BatchCorrection(batchText.toString(), 
                                                          new ArrayList<>(batchIndices),
                                                          batchTokens));
                            batchText = new StringBuilder();
                            batchIndices = new ArrayList<>();
                            batchTokens = 0;
                        }
                    }
                    
                    // 添加到当前批次
                    if (batchText.length() > 0) {
                        batchText.append("\n---\n");
                    }
                    batchText.append(text);
                    batchIndices.add(startIndex);
                    batchTokens += textTokens;
                    processedEntries++;
                }
                startIndex++;
            }
            
            // 保存最后一个批次
            if (batchText.length() > 0) {
                batches.add(new BatchCorrection(batchText.toString(), 
                                              batchIndices,
                                              batchTokens));
            }
        }
        
        // 记录批处理信息
        log.info("将 {} 条需要修正的字幕分成 {} 批处理", 
                needsCorrectionEntries.size(),
                batches.size());
        
        for (int i = 0; i < batches.size(); i++) {
            BatchCorrection batch = batches.get(i);
            log.info("第 {}/{} 批: {} 条字幕, {} 个token", 
                    i + 1, 
                    batches.size(), 
                    batch.getEntryIndices().size(),
                    batch.getTokenCount());
        }
        
        return batches;
    }

    public void updateCorrectedText(List<SubtitleEntry> entries, BatchCorrection batch, String correctedText) {
        String[] correctedTexts = correctedText.split("\n---\n");
        
        if (correctedTexts.length != batch.getEntryIndices().size()) {
            log.error("修正后的文本数量 ({}) 与原文本数量 ({}) 不匹配",
                    correctedTexts.length, batch.getEntryIndices().size());
            return;
        }
        
        // 计算修正后文本的token数量
        int correctedTokens = tokenCalculator.calculateTokens(correctedText);
        log.info("修正后文本token数量: {}", correctedTokens);
        
        for (int i = 0; i < correctedTexts.length; i++) {
            int entryIndex = batch.getEntryIndices().get(i);
            String corrected = correctedTexts[i].trim();
            entries.get(entryIndex).setText(corrected);
            entries.get(entryIndex).setNeedsCorrection(false); // 标记为已修正
        }
    }

    public String generateSrtContent(List<SubtitleEntry> entries) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry entry = entries.get(i);
            content.append(entry.getIndex()).append("\n");
            content.append(entry.getTimeCode()).append("\n");
            content.append(entry.getText()).append("\n");
            if (i < entries.size() - 1) {
                content.append("\n");
            }
        }
        return content.toString();
    }

    private boolean needsCorrection(String text) {
        // 移除HTML标签后再检查
        String plainText = text.replaceAll("<[^>]+>", "");
        
        // 检查是否包含"的得地"
        if (plainText.contains("的") || plainText.contains("得") || plainText.contains("地")) {
            return true;
        }
        
        // 可以添加其他需要修正的模式
        return false;
    }

    public void processSubtitles(List<SubtitleEntry> entries) {
        List<BatchCorrection> batches = extractTextForCorrection(entries);
        
        if (batches.isEmpty()) {
            log.info("没有需要修正的字幕");
            return;
        }

        // 收集所有批次的文本
        List<String> batchTexts = batches.stream()
            .map(BatchCorrection::getText)
            .collect(Collectors.toList());

        // 并行处理所有批次
        List<String> correctedTexts = deepSeekService.correctTextsParallel(batchTexts);

        // 更新字幕内容
        for (int i = 0; i < batches.size(); i++) {
            updateCorrectedText(entries, batches.get(i), correctedTexts.get(i));
        }
    }
} 