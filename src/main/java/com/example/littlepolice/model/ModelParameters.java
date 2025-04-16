package com.example.littlepolice.model;

import lombok.Data;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Component
public class ModelParameters {

    @JsonProperty("siliconflowKey")
    private String siliconflowKey;
    private String model;
    private int maxTokens;
    private double temperature;
    private double topP;
    private int topK;
    private double frequencyPenalty;

}
