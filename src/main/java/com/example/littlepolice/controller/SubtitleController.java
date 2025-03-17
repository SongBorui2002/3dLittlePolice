package com.example.littlepolice.controller;

import com.example.littlepolice.model.SubtitleEntry;
import com.example.littlepolice.service.DeepSeekService;
import com.example.littlepolice.service.SubtitleService;
import com.example.littlepolice.service.SubtitleService.BatchCorrection;
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
import java.util.List;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class SubtitleController {
    private final SubtitleService subtitleService;
    private final DeepSeekService deepSeekService;

    @Value("${temp.file.path:${java.io.tmpdir}/subtitles}")
    private String tempFilePath;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/correct")
    public ResponseEntity<?> correctSubtitle(@RequestParam("file") MultipartFile file) {
        try {
            log.info("开始处理字幕文件: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());
            
            // 确保临时目录存在
            Path tempDir = Paths.get(tempFilePath);
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
                log.info("创建临时目录: {}", tempDir);
            }

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
            
            if (batches.isEmpty()) {
                log.info("没有需要修正的文本，返回原文件");
                return saveAndReturnFile(content, file.getOriginalFilename());
            }
            
            // 分批处理文本
            log.info("开始分批处理文本，共 {} 批", batches.size());
            
            // 收集所有批次的文本
            List<String> batchTexts = batches.stream()
                .map(BatchCorrection::getText)
                .collect(java.util.stream.Collectors.toList());
            
            // 并行处理所有批次
            try {
                List<String> correctedTexts = deepSeekService.correctTextsParallel(batchTexts);
                
                // 更新所有批次的修正文本
                for (int i = 0; i < batches.size(); i++) {
                    subtitleService.updateCorrectedText(entries, batches.get(i), correctedTexts.get(i));
                }
            } catch (Exception e) {
                log.error("并行处理批次时发生错误", e);
                throw new RuntimeException("并行处理失败: " + e.getMessage());
            }
            
            // 生成新的 SRT 内容
            log.info("生成新的 SRT 内容...");
            String newContent = subtitleService.generateSrtContent(entries);

            // 保存并返回文件
            log.info("保存并返回修正后的文件...");
            return saveAndReturnFile(newContent, file.getOriginalFilename());
            
        } catch (Exception e) {
            log.error("处理字幕文件时发生错误", e);
            return ResponseEntity.internalServerError()
                .body("处理字幕文件时发生错误: " + e.getMessage());
        }
    }
    
    private ResponseEntity<Resource> saveAndReturnFile(String content, String originalFilename) throws IOException {
        // 生成唯一的文件名
        String uniqueFilename = UUID.randomUUID().toString() + "_" + originalFilename;
        Path filePath = Paths.get(tempFilePath, uniqueFilename);
        
        log.info("保存临时文件: {}", filePath);
        
        // 使用UTF-8编码写入文件
        Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
        
        // 创建文件资源
        Resource resource;
        try {
            resource = new UrlResource(filePath.toUri());
            log.info("创建文件资源成功: {}", resource.getURI());
        } catch (MalformedURLException e) {
            log.error("无法创建文件资源", e);
            throw new IOException("无法创建文件资源", e);
        }
        
        // 设置响应头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        
        // 对文件名进行 URL 编码，并添加双引号
        String encodedFilename = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8.name())
            .replace("+", "%20");
        headers.add(HttpHeaders.CONTENT_DISPOSITION, 
            "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        
        log.info("设置响应头 Content-Disposition: {}", headers.getFirst(HttpHeaders.CONTENT_DISPOSITION));
        
        // 启动定时任务，在一定时间后删除临时文件（例如30分钟）
        new Thread(() -> {
            try {
                Thread.sleep(30 * 60 * 1000); // 30分钟
                if (Files.deleteIfExists(filePath)) {
                    log.info("成功删除临时文件: {}", filePath);
                }
            } catch (Exception e) {
                log.error("删除临时文件失败: {}", filePath, e);
            }
        }).start();
        
        return ResponseEntity.ok()
            .headers(headers)
            .body(resource);
    }
} 