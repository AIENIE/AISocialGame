package com.aisocialgame.dto;

import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;

public class AiModelView {
    private long id;
    private String displayName;
    private String provider;
    private double inputRate;
    private double outputRate;
    private String type;
    private boolean supportsImageInput;

    public AiModelView(AiModelOptionDto option) {
        this.id = option.id();
        this.displayName = option.displayName();
        this.provider = option.provider();
        this.inputRate = option.inputRate();
        this.outputRate = option.outputRate();
        this.type = option.type();
        this.supportsImageInput = option.supportsImageInput();
    }

    public long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getProvider() {
        return provider;
    }

    public double getInputRate() {
        return inputRate;
    }

    public double getOutputRate() {
        return outputRate;
    }

    public String getType() {
        return type;
    }

    public boolean isSupportsImageInput() {
        return supportsImageInput;
    }
}
