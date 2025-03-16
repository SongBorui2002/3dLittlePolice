package com.example.littlepolice.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleEntry {
    private int index;           // 序列号
    private String timeCode;     // 时间信息
    private String text;         // 字幕文本
    private boolean needsCorrection;  // 是否需要修正（包含"的得地"）
    
    public String toSrtFormat() {
        return String.format("%d\n%s\n%s\n", index, timeCode, text);
    }
} 