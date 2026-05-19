package com.aisocialgame.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;

public class AiOcrRequest {
    @Size(max = 2048)
    @Pattern(regexp = "^$|^https?://.+", message = "imageUrl 必须是 http/https URL")
    private String imageUrl;

    @Size(max = 5_000_000)
    private String imageBase64;

    @Size(max = 2048)
    @Pattern(regexp = "^$|^https?://.+", message = "documentUrl 必须是 http/https URL")
    private String documentUrl;

    @Size(max = 128)
    private String model;

    @Size(max = 64)
    @Pattern(regexp = "^$|^[0-9,\\- ]+$", message = "pages 格式不合法")
    private String pages;

    @Pattern(regexp = "^$|^(TEXT|MARKDOWN|JSON)$", message = "outputType 不支持")
    private String outputType;

    @JsonIgnore
    @AssertTrue(message = "请提供 imageUrl、imageBase64 或 documentUrl")
    public boolean isSourceProvided() {
        return StringUtils.hasText(imageUrl) || StringUtils.hasText(imageBase64) || StringUtils.hasText(documentUrl);
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }

    public String getDocumentUrl() {
        return documentUrl;
    }

    public void setDocumentUrl(String documentUrl) {
        this.documentUrl = documentUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPages() {
        return pages;
    }

    public void setPages(String pages) {
        this.pages = pages;
    }

    public String getOutputType() {
        return outputType;
    }

    public void setOutputType(String outputType) {
        this.outputType = outputType;
    }
}
