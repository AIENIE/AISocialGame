package com.aisocialgame.service;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.AiChatRequest;
import com.aisocialgame.dto.AiChatResponse;
import com.aisocialgame.dto.AiMessageRequest;
import com.aisocialgame.dto.AiModelView;
import com.aisocialgame.dto.PagedResponse;
import com.aisocialgame.dto.admin.AdminAiDecisionTraceView;
import com.aisocialgame.dto.admin.AdminAiPersonaMemoryView;
import com.aisocialgame.dto.admin.AdminDashboardSummaryResponse;
import com.aisocialgame.dto.admin.AdminIntegrationStatusResponse;
import com.aisocialgame.dto.admin.AdminLedgerPageResponse;
import com.aisocialgame.dto.admin.AdminMigrateAllBalanceResponse;
import com.aisocialgame.dto.admin.AdminUserView;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.BillingGrpcClient;
import com.aisocialgame.integration.grpc.client.UserGrpcClient;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.integration.grpc.dto.ExternalUserProfile;
import com.aisocialgame.model.credit.CreditRedeemCode;
import com.aisocialgame.repository.AiPersonaMemoryRepository;
import com.aisocialgame.repository.CommunityPostRepository;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.ai.AiDecisionTraceService;
import com.aisocialgame.service.ai.AiReflectionService;
import com.aisocialgame.service.safety.AiSafetyContext;
import com.aisocialgame.service.safety.AiSafetyService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminOpsService {
    private final UserGrpcClient userGrpcClient;
    private final BillingGrpcClient billingGrpcClient;
    private final BalanceService balanceService;
    private final ProjectCreditService projectCreditService;
    private final AiProxyService aiProxyService;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final CommunityPostRepository communityPostRepository;
    private final GameStateRepository gameStateRepository;
    private final AppProperties appProperties;
    private final AiDecisionTraceService aiDecisionTraceService;
    private final AiPersonaMemoryRepository aiPersonaMemoryRepository;
    private final AiReflectionService aiReflectionService;
    private final AiSafetyService aiSafetyService;

    public AdminOpsService(UserGrpcClient userGrpcClient,
                           BillingGrpcClient billingGrpcClient,
                           BalanceService balanceService,
                           ProjectCreditService projectCreditService,
                           AiProxyService aiProxyService,
                           UserRepository userRepository,
                           RoomRepository roomRepository,
	                           CommunityPostRepository communityPostRepository,
	                           GameStateRepository gameStateRepository,
	                           AppProperties appProperties,
	                           AiDecisionTraceService aiDecisionTraceService,
	                           AiPersonaMemoryRepository aiPersonaMemoryRepository,
	                           AiReflectionService aiReflectionService,
                               AiSafetyService aiSafetyService) {
        this.userGrpcClient = userGrpcClient;
        this.billingGrpcClient = billingGrpcClient;
        this.balanceService = balanceService;
        this.projectCreditService = projectCreditService;
        this.aiProxyService = aiProxyService;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.communityPostRepository = communityPostRepository;
        this.gameStateRepository = gameStateRepository;
        this.appProperties = appProperties;
        this.aiDecisionTraceService = aiDecisionTraceService;
        this.aiPersonaMemoryRepository = aiPersonaMemoryRepository;
        this.aiReflectionService = aiReflectionService;
        this.aiSafetyService = aiSafetyService;
    }

    public AdminDashboardSummaryResponse dashboardSummary() {
        long users = userRepository.count();
        long rooms = roomRepository.count();
        long posts = communityPostRepository.count();
        long gameStates = gameStateRepository.count();
        int modelCount;
        try {
            modelCount = aiProxyService.listModels().size();
        } catch (Exception e) {
            modelCount = 0;
        }
        var safety = aiSafetyService.summary();
        return new AdminDashboardSummaryResponse(
                users,
                rooms,
                posts,
                gameStates,
                modelCount,
                safety.openHighRiskEvents(),
                safety.blockedLast24h(),
                safety.costAnomaliesLast24h(),
                safety.activeControls()
        );
    }

    public AdminIntegrationStatusResponse integrationStatus() {
        long probeUserId = Math.max(1, appProperties.getAi().getSystemUserId());
        List<AdminIntegrationStatusResponse.ServiceStatus> statuses = List.of(
                probe("user-service", () -> userGrpcClient.getUserBasic(probeUserId)),
                probe("pay-service", () -> billingGrpcClient.getBalance(appProperties.getProjectKey(), probeUserId)),
                probe("ai-service", aiProxyService::listModels)
        );
        return new AdminIntegrationStatusResponse(statuses);
    }

    public AdminUserView getUser(long userId) {
        ExternalUserProfile profile = userGrpcClient.getUserBasic(userId);
        if (profile == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        var banStatus = userGrpcClient.getBanStatus(userId);
        BalanceSnapshot balance = balanceService.getUserBalance(userId);
        return new AdminUserView(profile, banStatus, balance);
    }

    public AdminUserView banUser(long userId, String reason, boolean permanent, Instant expiresAt) {
        userGrpcClient.banUser(userId, reason, permanent, expiresAt, 0);
        return getUser(userId);
    }

    public AdminUserView unbanUser(long userId, String reason) {
        userGrpcClient.unbanUser(userId, reason, 0);
        return getUser(userId);
    }

    public BalanceSnapshot getBalance(long userId) {
        return balanceService.getUserBalance(userId);
    }

    public AdminLedgerPageResponse getLedger(long userId, int page, int size) {
        return new AdminLedgerPageResponse(
                projectCreditService.listLedgerEntriesForAdmin(userId, page, size)
        );
    }

    public BalanceSnapshot adjustBalance(long userId,
                                         long deltaTemp,
                                         long deltaPermanent,
                                         String reason,
                                         String operator,
                                         String requestId) {
        return projectCreditService.adjustBalance(userId, deltaTemp, deltaPermanent, reason, operator, requestId);
    }

    public BalanceSnapshot reverseBalance(long userId,
                                          String originalRequestId,
                                          String reason,
                                          String operator) {
        return projectCreditService.reverseByRequestId(userId, originalRequestId, reason, operator);
    }

    public BalanceSnapshot migrateUserBalance(long userId, String operator) {
        BalanceSnapshot projectSnapshot = billingGrpcClient.getProjectBalance(appProperties.getProjectKey(), userId);
        long publicTokens;
        try {
            publicTokens = billingGrpcClient.getPublicPermanentTokens(userId);
        } catch (Exception ignored) {
            publicTokens = 0;
        }
        return projectCreditService.migrateFromPayServiceSnapshot(
                userId,
                projectSnapshot.projectTempTokens(),
                projectSnapshot.projectPermanentTokens(),
                publicTokens,
                operator
        );
    }

    public AdminMigrateAllBalanceResponse migrateAllUsersBalance(String operator, Integer batchSize) {
        int effectiveBatchSize = batchSize == null ? 100 : Math.min(Math.max(batchSize, 1), 500);
        long scanned = 0;
        long success = 0;
        long failed = 0;
        List<AdminMigrateAllBalanceResponse.FailureItem> failureItems = new ArrayList<>();

        int page = 0;
        while (true) {
            var pageData = userRepository.findByExternalUserIdGreaterThan(
                    0L,
                    PageRequest.of(page, effectiveBatchSize, Sort.by(Sort.Direction.ASC, "externalUserId"))
            );
            if (pageData.isEmpty()) {
                break;
            }
            for (var user : pageData.getContent()) {
                if (user.getExternalUserId() == null || user.getExternalUserId() <= 0) {
                    continue;
                }
                scanned++;
                try {
                    migrateUserBalance(user.getExternalUserId(), operator);
                    success++;
                } catch (Exception ex) {
                    failed++;
                    if (failureItems.size() < 100) {
                        failureItems.add(new AdminMigrateAllBalanceResponse.FailureItem(
                                user.getExternalUserId(),
                                ex.getMessage() == null ? "unknown_error" : ex.getMessage()
                        ));
                    }
                }
            }
            if (!pageData.hasNext()) {
                break;
            }
            page++;
        }

        return new AdminMigrateAllBalanceResponse(scanned, success, failed, effectiveBatchSize, failureItems);
    }

    public CreditRedeemCode createRedeemCode(String code,
                                             long tokens,
                                             String creditType,
                                             Integer maxRedemptions,
                                             Instant validFrom,
                                             Instant validUntil,
                                             Boolean active) {
        return projectCreditService.createRedeemCode(
                code,
                tokens,
                creditType,
                maxRedemptions,
                validFrom,
                validUntil,
                active
        );
    }

    public List<AiModelView> listModels() {
        return aiProxyService.listModels().stream().map(AiModelView::new).toList();
    }

    public AiChatResponse testChat(long userId, String sessionId, String model, List<AiMessageRequest> messages) {
        AiChatRequest request = new AiChatRequest();
        request.setModel(model);
        request.setMessages(messages);
        long effectiveUserId = userId > 0 ? userId : Math.max(1, appProperties.getAi().getSystemUserId());
        AiSafetyContext context = AiSafetyContext.source(AiSafetyService.SOURCE_ADMIN_AI_TEST)
                .user(String.valueOf(effectiveUserId), null)
                .metadata("adminTest", true);
        return new AiChatResponse(aiProxyService.chatByIdentity(request, effectiveUserId, sessionId, context));
    }

    public PagedResponse<AdminAiDecisionTraceView> decisionTraces(String roomId,
                                                                  String gameId,
                                                                  String personaId,
                                                                  String action,
                                                                  Boolean fallback,
                                                                  String qualityFlag,
                                                                  int page,
                                                                  int size) {
        var result = aiDecisionTraceService.search(roomId, gameId, personaId, action, fallback, qualityFlag, page, size);
        return new PagedResponse<>(
                result.getContent().stream().map(AdminAiDecisionTraceView::new).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    public List<AdminAiPersonaMemoryView> personaMemories(String personaId) {
        if (personaId != null && !personaId.isBlank()) {
            return aiPersonaMemoryRepository.findByPersonaIdOrderByUpdatedAtDesc(personaId).stream()
                    .map(AdminAiPersonaMemoryView::new)
                    .toList();
        }
        return aiPersonaMemoryRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(AdminAiPersonaMemoryView::new)
                .toList();
    }

    public void resetPersonaMemory(Long id) {
        aiReflectionService.resetMemory(id);
    }

    private AdminIntegrationStatusResponse.ServiceStatus probe(String service, Probe probe) {
        try {
            probe.execute();
            return new AdminIntegrationStatusResponse.ServiceStatus(service, true, "ok");
        } catch (ApiException ex) {
            boolean reachable = ex.getStatus() != HttpStatus.SERVICE_UNAVAILABLE && ex.getStatus() != HttpStatus.BAD_GATEWAY;
            return new AdminIntegrationStatusResponse.ServiceStatus(service, reachable, ex.getMessage());
        } catch (Exception ex) {
            return new AdminIntegrationStatusResponse.ServiceStatus(service, false, ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface Probe {
        Object execute();
    }
}
