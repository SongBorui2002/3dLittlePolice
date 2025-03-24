package com.example.littlepolice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SiliconFlowRequest {
    private String model;
    private List<Message> messages;
    private boolean stream;
    @JsonProperty("max_tokens")
    private int maxTokens;
    private double temperature;
    @JsonProperty("top_p")
    private double topP;
    @JsonProperty("top_k")
    private int topK;
    @JsonProperty("frequency_penalty")
    private double frequencyPenalty;
    private int n;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }
}
