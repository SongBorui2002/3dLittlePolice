package com.example.littlepolice.model;

import lombok.Data;

@Data
public class ModelParameters {

    private String model;
    private int maxTokens;
    private double temperature;
    private double topP;
    private int topK;
    private double frequencyPenalty;

}
