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
    private static final String TOKENIZER_SCRIPT = PROJECT_PATH + "/deepseek_tokenizer.py";
    private static final String VENV_PYTHON = PROJECT_PATH + "/venv/bin/python3";
    
    @PostConstruct
    public void init() {
        // 验证tokenizer脚本是否存在
        File tokenizerScript = new File(TOKENIZER_SCRIPT);
        if (!tokenizerScript.exists()) {
            log.error("Tokenizer脚本不存在: {}", TOKENIZER_SCRIPT);
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

    // Token计算功能已禁用
    public int calculateTokens(String text) {
        return 0; // 直接返回0，不再计算token
    }
} 