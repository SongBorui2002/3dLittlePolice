package com.example.littlepolice.controller;

import com.example.littlepolice.model.SubtitleEntry;
import com.example.littlepolice.service.SiliconFlowService;
import com.example.littlepolice.service.SubtitleService;
import com.example.littlepolice.service.SubtitleService.BatchCorrection;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class SubtitleController {
    private final SubtitleService subtitleService;
    private final SiliconFlowService siliconflowService;

    // 用于返回原始和修正后内容的响应类
    @Data
    public static class SubtitleResponse {
        @JsonProperty("original")
        private String originalContent;
        @JsonProperty("corrected")
        private String correctedContent;

        public SubtitleResponse setOriginalContent(String content) {
            this.originalContent = content;
            return this;
        }

        public SubtitleResponse setCorrectedContent(String content) {
            this.correctedContent = content;
            return this;
        }

    }


    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/correct")
    public ResponseEntity<?> correctSubtitle(@RequestParam("file") MultipartFile file) {
        long startTime = System.currentTimeMillis();
        try {
            log.info("开始处理字幕文件: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());


            // 读取文件内容（使用UTF-8编码）
            log.info("读取文件内容...");
            String content = IOUtils.toString(file.getInputStream(), StandardCharsets.UTF_8);
            log.debug("文件内容前100个字符: {}", content.substring(0, Math.min(content.length(), 100)));

            // 解析字幕文件
            log.info("解析字幕文件...");
            List<SubtitleEntry> entries = subtitleService.parseSrtContent(content);
            log.info("解析到 {} 条字幕", entries.size());

            // 提取需要修正的文本
            log.info("提取需要修正的文本...");
            List<BatchCorrection> batches = subtitleService.extractTextForCorrection(entries);

            // 收集需要修正的字幕序号
            List<Integer> modifiedIndices = entries.stream()
                    .filter(SubtitleEntry::isNeedsCorrection)
                    .map(SubtitleEntry::getIndex)
                    .collect(Collectors.toList());

            if (batches.isEmpty()) {
                log.info("没有需要修正的文本，返回原文件");
                return ResponseEntity.ok(new SubtitleResponse()
                        .setOriginalContent(content)
                        .setCorrectedContent(content));

            }

            // 分批处理文本
            log.info("开始分批处理文本，共 {} 批", batches.size());

            // 收集所有批次的文本
            List<String> batchTexts = batches.stream()
                    .map(BatchCorrection::getText)
                    .collect(java.util.stream.Collectors.toList());

            // 并行处理所有批次
            try {
                //api响应开始
                long apiStartTime = System.currentTimeMillis();
                List<String> correctedTexts = siliconflowService.correctTextsParallel(batchTexts);
//                log.info("API请求完成，耗时: {}ms", System.currentTimeMillis() - apiStartTime);

                // 在这里添加验证步骤，直接使用已有的batches信息
                List<String> validatedTexts = subtitleService.validateAndFilterModifications(
                        batches,         // 已经包含了需要修改的字幕和索引信息
                        correctedTexts   // API返回的修改后文本
                );

                // 更新所有批次的修正文本
                long updateTime = System.currentTimeMillis();
                for (int i = 0; i < batches.size(); i++) {
                    subtitleService.updateCorrectedText(entries, batches.get(i), validatedTexts.get(i));
                }
//                log.info("更新字幕文本完成，耗时: {}ms", System.currentTimeMillis() - updateTime);

                // 生成新内容
                long generateTime = System.currentTimeMillis();
                String newContent = subtitleService.generateSrtContent(entries);
//                log.info("内容生成完成，耗时: {}ms", System.currentTimeMillis() - generateTime);

                // 构建响应
                long responseTime = System.currentTimeMillis();
                SubtitleResponse response = new SubtitleResponse()
                        .setOriginalContent(content)
                        .setCorrectedContent(newContent);

                long endTime = System.currentTimeMillis();
//                log.info("响应构建完成，耗时: {}ms", endTime - responseTime);
                log.info("总处理耗时: {}ms", endTime - startTime);

                return ResponseEntity.ok(response);
            } catch (Exception e) {
                log.error("并行处理批次时发生错误", e);
                throw new RuntimeException("并行处理失败: " + e.getMessage());
            }

//            // 生成新的 SRT 内容
//            log.info("生成新的 SRT 内容...");
//            String newContent = subtitleService.generateSrtContent(entries);
//
//            // 保存并返回文件
//            log.info("保存并返回修正后的文件...");
////            return saveAndReturnFile(newContent, file.getOriginalFilename());
//            SubtitleResponse response = new SubtitleResponse()
//                    .setOriginalContent(content)
//                    .setCorrectedContent(newContent);
//
//            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("处理字幕文件时发生错误", e);
            return ResponseEntity.internalServerError()
                    .body("处理字幕文件时发生错误: " + e.getMessage());
        }
    }

} 