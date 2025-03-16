package com.example.littlepolice.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class TokenCalculator {
    private static final String PROJECT_PATH = "/Users/sbr/Desktop/3dLitterPolice";
    private static final String TOKENIZER_PATH = "/Users/sbr/Downloads/deepseek_v3_tokenizer";
    private static final String VENV_PYTHON = PROJECT_PATH + "/venv/bin/python3";
    
    @PostConstruct
    public void init() {
        // 验证tokenizer文件夹是否存在
        File tokenizerDir = new File(TOKENIZER_PATH);
        if (!tokenizerDir.exists() || !tokenizerDir.isDirectory()) {
            log.error("Tokenizer目录不存在: {}", TOKENIZER_PATH);
            return;
        }
        
        // 验证虚拟环境是否存在
        File venvPython = new File(VENV_PYTHON);
        if (!venvPython.exists()) {
            log.error("Python虚拟环境不存在: {}", VENV_PYTHON);
            return;
        }
        
        log.info("Tokenizer初始化成功");
    }

    public int calculateTokens(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }

        try {
            // 准备命令
            ProcessBuilder pb = new ProcessBuilder(
                VENV_PYTHON,
                TOKENIZER_PATH + "/count_tokens.py", 
                text);
            pb.redirectErrorStream(true);

            // 执行命令
            Process process = pb.start();
            
            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // 等待进程完成
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.error("Token计算超时");
                return -1;
            }

            // 解析输出获取token数量
            String result = output.toString().trim();
            try {
                return Integer.parseInt(result);
            } catch (NumberFormatException e) {
                log.error("无法解析token计算结果: {}", result);
                return -1;
            }

        } catch (Exception e) {
            log.error("计算token时发生错误: {}", e.getMessage());
            return -1;
        }
    }
} 