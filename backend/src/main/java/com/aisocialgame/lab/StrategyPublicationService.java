package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.config.AiModelConfigService;
import jakarta.validation.constraints.*;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

@Service
public class StrategyPublicationService {
    public record Request(@NotBlank String operationId,@NotBlank String action,String strategyVersionId,
                          @NotNull @Size(min=1,max=4) List<@NotBlank String> personaIds,@NotBlank String mode) {}
    private final StrategyPublicationRepository publications;
    private final AiPersonaMemoryRepository memories;
    private final PersonaRepository personas;
    private final LabStrategyService strategies;
    private final AiModelConfigService models;
    private final LabStrategyAssessmentService assessments;
    private final TransactionTemplate transaction;
    public StrategyPublicationService(StrategyPublicationRepository publications,AiPersonaMemoryRepository memories,
            PersonaRepository personas,LabStrategyService strategies,AiModelConfigService models,LabStrategyAssessmentService assessments,PlatformTransactionManager manager) {
        this.publications=publications;this.memories=memories;this.personas=personas;this.strategies=strategies;this.models=models;
        this.assessments=assessments;
        this.transaction=new TransactionTemplate(manager);
    }

    public Map<String,Object> apply(Request request,String operator) {
        var parameters=parameters(request);String fingerprint=LabStrategyService.hash(parameters);
        for(int attempt=0;;attempt++) {
            try {return transaction.execute(status->applyLocked(request.operationId(),parameters,fingerprint,operator));}
            catch(DataIntegrityViolationException | TransientDataAccessException conflict) {if(attempt>=3) throw conflict;}
        }
    }
    private Map<String,Object> applyLocked(String operationId,Map<String,Object> parameters,String fingerprint,String operator) {
        var existing=publications.findByOperationId(operationId).orElse(null);
        if(existing!=null) return sameOperation(existing,fingerprint);
        String action=text(parameters.get("action")),versionId=text(parameters.get("strategyVersionId"));
        if("PUBLISH".equals(action)) {
            var strategy=strategies.requireVersion(versionId);LabStrategyService.validateCompatibility(strategy.compatibility);
            require("werewolf".equals(strategy.gameId),"只能发布狼人杀共享策略");
            assessments.requirePublishable(strategy);
        }
        var targets=strings(parameters.get("personaIds"));var bindings=new ArrayList<AiPersonaMemory>();
        for(String personaId:targets) {
            var memory=memories.lockStrategyBinding(personaId,"werewolf").orElse(null);
            if(memory==null) memory=memories.saveAndFlush(new AiPersonaMemory(personaId,"werewolf","GENERAL_V2"));
            bindings.add(memory);
        }
        // A duplicate may have committed while this operation waited for the same persona locks.
        existing=publications.findByOperationId(operationId).orElse(null);
        if(existing!=null) return sameOperation(existing,fingerprint);
        var publication=new StrategyPublication();publication.operationId=operationId;publication.requestHash=fingerprint;
        publication.action=action;publication.strategyVersionId=versionId.isBlank()?null:versionId;publication.operator=operator;
        publication.parameters=LabStrategyService.copy(parameters);
        for(var binding:bindings) publication.previousBindings.put(binding.getPersonaId(),bindingView(binding));
        var receipt=new LinkedHashMap<String,Object>();receipt.put("id",publication.id);receipt.put("operationId",operationId);
        receipt.put("action",action);if(publication.strategyVersionId!=null) receipt.put("strategyVersionId",publication.strategyVersionId);
        receipt.put("personaIds",targets);receipt.put("mode","TRIAL");receipt.put("createdAt",publication.createdAt.toString());
        var next=new ArrayList<Map<String,Object>>();
        for(var binding:bindings) {binding.setStrategyPublicationId(publication.id);next.add(bindingView(binding,publication));}
        receipt.put("bindings",next);publication.result=LabStrategyService.copy(receipt);
        publications.saveAndFlush(publication);memories.saveAllAndFlush(bindings);return LabStrategyService.copy(publication.result);
    }
    private Map<String,Object> parameters(Request request) {
        try {require(UUID.fromString(request.operationId()).toString().equalsIgnoreCase(request.operationId()),"operationId 必须为 UUID");}
        catch(IllegalArgumentException failure) {throw new ApiException(HttpStatus.BAD_REQUEST,"operationId 必须为 UUID");}
        require(Set.of("PUBLISH","WITHDRAW").contains(request.action()) && "TRIAL".equals(request.mode()),"仅支持显式发布或撤回试用策略");
        require(request.personaIds()!=null && !request.personaIds().isEmpty() && request.personaIds().size()<=4
                && request.personaIds().stream().distinct().count()==request.personaIds().size(),"请选择不重复的人格");
        var targets=request.personaIds().stream().sorted().toList();
        for(String id:targets) require(personas.findById(id)!=null && Set.of("ai1","ai2","ai3","ai4").contains(id),"策略仅绑定明确选择的四个基础人格");
        require("PUBLISH".equals(request.action())?!text(request.strategyVersionId()).isBlank():text(request.strategyVersionId()).isBlank(),"发布须指定版本，撤回不指定版本");
        return Map.of("action",request.action(),"strategyVersionId",text(request.strategyVersionId()),"personaIds",targets,"mode","TRIAL","gameId","werewolf");
    }
    private Map<String,Object> sameOperation(StrategyPublication publication,String hash) {
        if(!publication.requestHash.equals(hash)) throw new ApiException(HttpStatus.CONFLICT,"operationId 已用于其他发布参数","STRATEGY_OPERATION_CONFLICT",Map.of());
        return LabStrategyService.copy(publication.result);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> inspect(String operationId) {
        return LabStrategyService.copy(publications.findByOperationId(operationId).orElseThrow(LabService::missing).result);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> bindings() {
        return personas.findAll().stream().map(persona->memories.findByPersonaIdAndGameIdAndRoleKey(persona.getId(),"werewolf","GENERAL_V2")
                .map(this::bindingView).orElseGet(()->Map.<String,Object>of("personaId",persona.getId(),"gameId","werewolf"))).toList();
    }
    private Map<String,Object> bindingView(AiPersonaMemory memory) {
        var publication=memory.getStrategyPublicationId()==null?null:publications.findById(memory.getStrategyPublicationId()).orElseThrow(LabService::missing);
        return bindingView(memory,publication);
    }
    private Map<String,Object> bindingView(AiPersonaMemory memory,StrategyPublication publication) {
        var view=new LinkedHashMap<String,Object>();view.put("personaId",memory.getPersonaId());view.put("gameId",memory.getGameId());
        if(publication!=null) {
            view.put("publicationId",publication.id);view.put("action",publication.action);view.put("mode",publication.mode);view.put("publishedAt",publication.createdAt.toString());
            if("PUBLISH".equals(publication.action)) view.put("strategyVersionId",publication.strategyVersionId);
        }
        return view;
    }

    /** Called once in the game-start transaction, after identities are assigned and before the first AI job. */
    public static Map<String,Object> publicTrialMetadata(GameState state) {
        if(!"werewolf".equals(state.getGameId()) || !map(state.getData().get("lab")).isEmpty()) return Map.of();
        var result=new LinkedHashMap<String,Object>();
        for(var entry:map(state.getData().get(LabStrategyService.STATE_SNAPSHOTS)).entrySet()) {
            var frozen=map(entry.getValue());
            if(!"PUBLISHED".equals(frozen.get("source")) || !Boolean.TRUE.equals(frozen.get("compatible"))) continue;
            var version=map(frozen.get("strategySnapshot"));
            result.put(entry.getKey(),Map.of("mode","TRIAL","strategyVersionId",text(version.get("id")),"sequenceNumber",number(version.get("sequenceNumber"),0)));
        }
        return result;
    }

    @Transactional(readOnly=true)
    public void freezeState(GameState state) {
        if(!"werewolf".equals(state.getGameId()) || !map(state.getData().get("lab")).isEmpty()
                || state.getData().containsKey(LabStrategyService.STATE_SNAPSHOTS)) return;
        var frozen=new LinkedHashMap<String,Object>();
        for(var player:state.getPlayers()) {
            if(!player.isAi() || player.getPersonaId()==null) continue;
            var entry=new LinkedHashMap<String,Object>();entry.put("source","NONE");
            var selection=models.resolve("werewolf",player.getPlayerId()).toMap();entry.put("modelSelection",selection);
            var memory=memories.findByPersonaIdAndGameIdAndRoleKey(player.getPersonaId(),"werewolf","GENERAL_V2").orElse(null);
            if(memory!=null) {
                var publication=memory.getStrategyPublicationId()==null?null:publications.findById(memory.getStrategyPublicationId()).orElseThrow(LabService::missing);
                if(publication!=null && "PUBLISH".equals(publication.action)) {
                    var snapshot=strategies.strategySnapshot(publication.strategyVersionId);entry.put("publicationId",publication.id);
                    entry.put("strategySnapshot",snapshot);entry.put("source","PUBLISHED");
                    entry.put("compatible",LabStrategyService.compatible(snapshot,map(state.getData().get("rules")),selection));
                } else if("APPROVED".equals(memory.getReviewStatus()) && !text(memory.getApprovedSummary()).isBlank()) {
                    entry.put("source","LEGACY");entry.put("approvedExperience",memory.getApprovedSummary());
                }
            }
            frozen.put(player.getPlayerId(),entry);
        }
        state.getData().put(LabStrategyService.STATE_SNAPSHOTS,frozen);
    }
}
