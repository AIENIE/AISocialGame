package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import fireflychat.ai.v1.ListCallerModelsRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class AiModelCatalogClient {
    private final AiGatewayServiceGrpc.AiGatewayServiceBlockingStub stub;
    public AiModelCatalogClient(AiGatewayServiceGrpc.AiGatewayServiceBlockingStub stub) { this.stub = stub; }

    public List<AiModelOptionDto> listCallerModels() {
        try {
            return stub.withDeadlineAfter(5, TimeUnit.SECONDS).listCallerModels(ListCallerModelsRequest.getDefaultInstance())
                    .getModelsList().stream().map(AiModelOptionDto::from).toList();
        } catch (StatusRuntimeException failure) {
            String message = failure.getStatus().getCode() == Status.Code.UNIMPLEMENTED
                    ? "当前 AI 服务尚不支持调用方模型目录，请先升级 AI 服务"
                    : "模型同步失败，请稍后重试";
            var error = new ApiException(HttpStatus.BAD_GATEWAY, message, "AI_MODEL_SYNC_FAILED", Map.of());
            error.initCause(failure);
            throw error;
        }
    }
}
