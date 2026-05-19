package com.aisocialgame.service.credit;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.integration.grpc.dto.LedgerEntrySnapshot;
import com.aisocialgame.integration.grpc.dto.PagedLedgerSnapshot;
import com.aisocialgame.integration.grpc.dto.PagedResult;
import com.aisocialgame.integration.grpc.dto.UsageRecordSnapshot;
import com.aisocialgame.model.credit.CreditAccount;
import com.aisocialgame.model.credit.CreditLedgerEntry;
import com.aisocialgame.repository.credit.CreditLedgerEntryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class CreditLedgerService {
    private final CreditLedgerEntryRepository creditLedgerEntryRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public CreditLedgerService(CreditLedgerEntryRepository creditLedgerEntryRepository,
                               AppProperties appProperties,
                               ObjectMapper objectMapper) {
        this.creditLedgerEntryRepository = creditLedgerEntryRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public PagedResult<UsageRecordSnapshot> listUsageRecords(long userId, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        var pageData = creditLedgerEntryRepository.findByUserIdAndProjectKeyAndTypeInOrderByIdDesc(
                userId,
                appProperties.getProjectKey(),
                List.of("CONSUME"),
                PageRequest.of(normalizedPage - 1, normalizedSize)
        );
        List<UsageRecordSnapshot> records = pageData.getContent().stream()
                .map(entry -> {
                    Map<String, String> metadata = deserializeMetadata(entry.getMetadataJson());
                    long promptTokens = parseLong(metadata.get("promptTokens"));
                    long completionTokens = parseLong(metadata.get("completionTokens"));
                    long billedTokens = parseLong(metadata.getOrDefault("billedTokens", String.valueOf(Math.abs(entry.getTokenDeltaTemp() + entry.getTokenDeltaPermanent()))));
                    return new UsageRecordSnapshot(
                            entry.getId(),
                            entry.getRequestId(),
                            entry.getProjectKey(),
                            metadata.getOrDefault("modelKey", ""),
                            promptTokens,
                            completionTokens,
                            billedTokens,
                            entry.getCreatedAt()
                    );
                })
                .toList();
        return new PagedResult<>(normalizedPage, normalizedSize, pageData.getTotalElements(), records);
    }

    public PagedLedgerSnapshot listLedgerEntriesForAdmin(long userId, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        var pageData = creditLedgerEntryRepository.findByUserIdAndProjectKeyOrderByIdDesc(
                userId,
                appProperties.getProjectKey(),
                PageRequest.of(normalizedPage - 1, normalizedSize)
        );
        List<LedgerEntrySnapshot> entries = pageData.getContent().stream()
                .map(entry -> new LedgerEntrySnapshot(
                        entry.getId(),
                        entry.getRequestId(),
                        entry.getProjectKey(),
                        entry.getType(),
                        entry.getTokenDeltaTemp(),
                        entry.getTokenDeltaPermanent(),
                        entry.getTokenDeltaPublic(),
                        entry.getBalanceTemp(),
                        entry.getBalancePermanent(),
                        entry.getBalancePublic(),
                        entry.getSource(),
                        entry.getCreatedAt(),
                        deserializeMetadata(entry.getMetadataJson())
                ))
                .toList();
        return new PagedLedgerSnapshot(normalizedPage, normalizedSize, pageData.getTotalElements(), entries);
    }

    public void insertLedgerEntry(String requestId,
                                  long userId,
                                  String type,
                                  long deltaTemp,
                                  long deltaPermanent,
                                  long deltaPublic,
                                  long publicBalance,
                                  String source,
                                  Map<String, String> metadata,
                                  Long relatedEntryId,
                                  CreditAccount account) {
        if (creditLedgerEntryRepository.findByRequestId(requestId).isPresent()) {
            return;
        }
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setRequestId(requestId);
        entry.setUserId(userId);
        entry.setProjectKey(appProperties.getProjectKey());
        entry.setType(type);
        entry.setTokenDeltaTemp(deltaTemp);
        entry.setTokenDeltaPermanent(deltaPermanent);
        entry.setTokenDeltaPublic(deltaPublic);
        entry.setBalanceTemp(account.getTempBalance());
        entry.setBalancePermanent(account.getPermanentBalance());
        entry.setBalancePublic(publicBalance);
        entry.setSource(source);
        entry.setMetadataJson(serializeMetadata(metadata));
        entry.setRelatedEntryId(relatedEntryId);
        creditLedgerEntryRepository.save(entry);
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, String> deserializeMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
