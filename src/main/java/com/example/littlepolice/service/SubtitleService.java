package com.example.littlepolice.service;

import com.example.littlepolice.model.SubtitleEntry;
import com.example.littlepolice.util.TokenCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubtitleService {
    private static final int MAX_BATCH_SIZE = 4000; // 每批最大字符数
    private static final Pattern TIME_CODE_PATTERN = Pattern.compile("\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2},\\d{3}");

    private final TokenCalculator tokenCalculator;

    @Data
    @AllArgsConstructor
    public static class BatchCorrection {
        private String text;
        private List<Integer> entryIndices;
        private int tokenCount;  // 添加token计数字段
    }

    public List<SubtitleEntry> parseSrtContent(String content) {
        List<SubtitleEntry> entries = new ArrayList<>();
        String[] blocks = content.trim().split("\\n\\s*\\n");
        
        for (String block : blocks) {
            String[] lines = block.trim().split("\\n");
            if (lines.length >= 3) {
                try {
                    int index = Integer.parseInt(lines[0].trim());
                    String timeCode = lines[1].trim();
                    
                    // 验证时间码格式
                    if (!TIME_CODE_PATTERN.matcher(timeCode).matches()) {
                        log.warn("跳过无效时间码的字幕条目: {}", index);
                        continue;
                    }
                    
                    // 合并剩余行作为字幕文本
                    String text = String.join("\n", Arrays.copyOfRange(lines, 2, lines.length));
                    
                    // 创建字幕条目，并检查是否需要修正
                    SubtitleEntry entry = new SubtitleEntry();
                    entry.setIndex(index);
                    entry.setTimeCode(timeCode);
                    entry.setText(text);
                    entry.setNeedsCorrection(needsCorrection(text));
                    
                    entries.add(entry);
                } catch (NumberFormatException e) {
                    log.warn("跳过无效序号的字幕条目");
                }
            }
        }
        
        log.info("解析到 {} 条字幕", entries.size());
        return entries;
    }

    public List<BatchCorrection> extractTextForCorrection(List<SubtitleEntry> entries) {
        List<BatchCorrection> batches = new ArrayList<>();
        StringBuilder currentBatch = new StringBuilder();
        List<Integer> currentIndices = new ArrayList<>();
        int currentSize = 0;
        
        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry entry = entries.get(i);
            String text = entry.getText().trim();
            
            // 检查是否需要修正
            if (entry.isNeedsCorrection()) {
                // 如果当前批次加上新文本会超过最大大小，创建新批次
                if (currentSize + text.length() + 2 > MAX_BATCH_SIZE && currentSize > 0) {
                    String batchText = currentBatch.toString();
                    int tokenCount = tokenCalculator.calculateTokens(batchText);
                    batches.add(new BatchCorrection(batchText, 
                                                  new ArrayList<>(currentIndices),
                                                  tokenCount));
                    currentBatch = new StringBuilder();
                    currentIndices = new ArrayList<>();
                    currentSize = 0;
                }
                
                // 添加到当前批次
                if (currentSize > 0) {
                    currentBatch.append("\n---\n");
                    currentSize += 2;
                }
                currentBatch.append(text);
                currentIndices.add(i);
                currentSize += text.length();
            }
        }
        
        // 添加最后一个批次
        if (currentSize > 0) {
            String batchText = currentBatch.toString();
            int tokenCount = tokenCalculator.calculateTokens(batchText);
            batches.add(new BatchCorrection(batchText, currentIndices, tokenCount));
        }
        
        // 记录每个批次的token数量
        int totalTokens = batches.stream().mapToInt(BatchCorrection::getTokenCount).sum();
        log.info("将 {} 条需要修正的字幕分成 {} 批处理，共计 {} 个token", 
                batches.stream().mapToInt(b -> b.getEntryIndices().size()).sum(),
                batches.size(),
                totalTokens);
        
        // 详细记录每个批次的信息
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
} 