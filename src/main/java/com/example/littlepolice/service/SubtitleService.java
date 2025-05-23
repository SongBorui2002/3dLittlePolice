package com.example.littlepolice.service;

import com.example.littlepolice.model.SubtitleEntry;
import com.example.littlepolice.util.TokenCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.regex.Matcher;

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

    //定义全局变量最大批次值
    private static final int MAX_BATCH_SIZE = 26;

    private static final Pattern TIME_CODE_PATTERN = Pattern.compile("\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2},\\d{3}");

    private final TokenCalculator tokenCalculator;
    private final SiliconFlowService siliconFlowService;


    private final ObjectMapper mapper = new ObjectMapper();

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

        //行分割
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

        // 如果字幕条数小于200，使用单批处理
        if (entries.size() < 200) {
            return createSingleBatch(entries);
        }

        // 字幕条数大于20，使用多批处理
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
        int entriesPerBatch = needsCorrectionEntries.size() / MAX_BATCH_SIZE;
        int remainingEntries = needsCorrectionEntries.size() % MAX_BATCH_SIZE;

        // 创建多个批次
        int startIndex = 0;
        for (int batchNum = 0; batchNum < MAX_BATCH_SIZE; batchNum++) {
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
            log.info("第 {}/{} 批: {} 条字幕",
                    i + 1,
                    batches.size(),
                    batch.getEntryIndices().size());
//                    batch.getTokenCount());
        }

        return batches;
    }


    //修改分隔符，解决字幕error无法识别问题
    public void updateCorrectedText(List<SubtitleEntry> entries, BatchCorrection batch, String correctedText) {
        try {
//            // 记录原始返回内容
//            log.info("API返回原始内容:\n{}", correctedText);

            // 规范化分隔符
            String normalizedText = correctedText.replaceAll("\n\\s*---\\s*\n", "\n---\n");

            String[] correctedTexts = normalizedText.split("\n---\n");

            // 记录分割结果
            Map<String, Object> splitInfo = new HashMap<>();
            splitInfo.put("expectedCount", batch.getEntryIndices().size());
            splitInfo.put("actualCount", correctedTexts.length);
            splitInfo.put("splitResults", Arrays.asList(correctedTexts));

//            log.info("文本分割结果: {}",
//                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(splitInfo));

            Thread.sleep(10); // 100毫秒的延迟，通常足够了
            if (correctedTexts.length != batch.getEntryIndices().size()) {
                log.error("文本数量不匹配: 期望 {} 条，实际 {} 条",
                        batch.getEntryIndices().size(),
                        correctedTexts.length);

                // 尝试使用不同的分隔符重新分割
                correctedTexts = correctedText.split("\\s*---\\s*");
                log.info("使用alternative分隔符重新分割后数量: {}", correctedTexts.length);

                if (correctedTexts.length != batch.getEntryIndices().size()) {
                    log.error("重新分割后仍然不匹配，将使用可用部分");
                    int validSize = Math.min(correctedTexts.length, batch.getEntryIndices().size());
                    for (int i = 0; i < validSize; i++) {
                        int entryIndex = batch.getEntryIndices().get(i);
                        String corrected = correctedTexts[i].trim();
                        entries.get(entryIndex).setText(corrected);
                        entries.get(entryIndex).setNeedsCorrection(false);
                    }
                    return;
                }
            }

            // 正常处理
            for (int i = 0; i < correctedTexts.length; i++) {
                int entryIndex = batch.getEntryIndices().get(i);
                String corrected = correctedTexts[i].trim();
                entries.get(entryIndex).setText(corrected);
                entries.get(entryIndex).setNeedsCorrection(false);
            }
        } catch (Exception e) {
            log.error("处理修正文本时发生错误", e);
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

//    public void processSubtitles(List<SubtitleEntry> entries) {
//        List<BatchCorrection> batches = extractTextForCorrection(entries);
//
//        if (batches.isEmpty()) {
//            log.info("没有需要修正的字幕");
//            return;
//        }
//
//        // 收集所有批次的文本
//        List<String> batchTexts = batches.stream()
//                .map(BatchCorrection::getText)
//                .collect(Collectors.toList());
//
//        // 并行处理所有批次
//        List<String> correctedTexts = siliconFlowService.correctTextsParallel(batchTexts);
//
//        // 更新字幕内容
//        for (int i = 0; i < batches.size(); i++) {
//            updateCorrectedText(entries, batches.get(i), correctedTexts.get(i));
//        }
//    }

    public List<String> validateAndFilterModifications(
            List<BatchCorrection> batches,
            List<String> correctedTexts
    ) {
        List<String> validatedTexts = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            BatchCorrection batch = batches.get(i);
            String originalText = batch.getText();
            String correctedText = correctedTexts.get(i);

            log.debug("处理批次 {}/{}, 字幕索引: {}",
                    i + 1, batches.size(), batch.getEntryIndices());

            // 在原文中找出"的得地"位置
            List<Integer> positions = findDeDeDePositions(originalText);

            // 应用验证后的修改
            String validatedText = applyDeDeDeModifications(
                    originalText,
                    correctedText,
                    positions
            );

            validatedTexts.add(validatedText);
        }

        return validatedTexts;
    }

    private List<Integer> findDeDeDePositions(String text) {
        List<Integer> positions = new ArrayList<>();
        char[] targetChars = {'的', '得', '地'};
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            for (char target : targetChars) {
                if (c == target) {
                    positions.add(i);
                    break;
                }
            }
        }
        return positions;
    }

    private String applyDeDeDeModifications(
            String original,
            String modified,
            List<Integer> positions
    ) {
        StringBuilder result = new StringBuilder(original);
        int modificationCount = 0;

        for (int pos : positions) {
            Character modifiedChar = findMatchingCharacter(modified, original, pos);
            if (modifiedChar != null) {
                result.setCharAt(pos, modifiedChar);
                modificationCount++;
                log.debug("位置 {} 处: '{}' -> '{}'",
                        pos, original.charAt(pos), modifiedChar);
            }
        }

        log.debug("应用了 {} 处修改", modificationCount);
        return result.toString();
    }

    private Character findMatchingCharacter(String modified, String original, int position) {
        try {
            // 1. 先尝试严格匹配
            Character result = strictMatch(modified, original, position);
            if (result != null) {
                return result;
            }

            // 2. 如果严格匹配失败，尝试宽松匹配
            return looseMatch(modified, original, position);
        } catch (Exception e) {
            log.debug("位置 {} 的匹配失败: {}", position, e.getMessage());
            return null;
        }
    }

    private Character strictMatch(
            String modified,
            String original,
            int position
    ) {
        try {
            int contextSize = 1;
            int start = Math.max(0, position - contextSize);
            int end = Math.min(original.length(), position + contextSize + 1);
            String context = original.substring(start, end);
            char originalChar = original.charAt(position);

            // 构建搜索模式，避免使用字符字面量
            StringBuilder patternBuilder = new StringBuilder();
            for (int i = 0; i < context.length(); i++) {
                if (i == (position - start)) {
                    patternBuilder.append("[的得地]");
                } else {
                    patternBuilder.append(Pattern.quote(String.valueOf(context.charAt(i))));
                }
            }

            Pattern p = Pattern.compile(patternBuilder.toString());
            Matcher m = p.matcher(modified);

            if (m.find()) {
                int matchPos = m.start() + (position - start);
                if (matchPos < modified.length()) {
                    char c = modified.charAt(matchPos);
                    if (c == '的' || c == '得' || c == '地') {
                        return c;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("位置 {} 的匹配失败: {}", position, e.getMessage());
        }
        return null;
    }

    private Character looseMatch(String modified, String original, int position) {
        try {
            // 1. 只匹配目标字符前后各一个字符
            int start = Math.max(0, position - 1);
            int end = Math.min(original.length(), position + 2);
            String context = original.substring(start, end);

            // 2. 构建宽松的正则表达式
            StringBuilder patternBuilder = new StringBuilder();
            for (int i = 0; i < context.length(); i++) {
                if (i == (position - start)) {
                    patternBuilder.append("[的得地]");
                } else {
                    patternBuilder.append("\\S");  // 任意非空白字符
                }
            }

            Pattern p = Pattern.compile(patternBuilder.toString());
            Matcher m = p.matcher(modified);

            if (m.find()) {
                int matchPos = m.start() + 1;  // 中间位置
                if (matchPos < modified.length()) {
                    char c = modified.charAt(matchPos);
                    if (c == '的' || c == '得' || c == '地') {
                        return c;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("宽松匹配失败: {}", e.getMessage());
        }
        return null;
    }
} 