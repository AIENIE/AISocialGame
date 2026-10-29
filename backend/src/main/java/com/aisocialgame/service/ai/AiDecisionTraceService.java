package com.aisocialgame.service.ai;

import com.aisocialgame.model.AiDecisionTrace;
import com.aisocialgame.repository.AiDecisionTraceRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class AiDecisionTraceService {
    private final AiDecisionTraceRepository traceRepository;

    public AiDecisionTraceService(AiDecisionTraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    @Transactional(readOnly = true)
    public Page<AiDecisionTrace> search(String roomId,
                                        String gameId,
                                        String personaId,
                                        String action,
                                        Boolean fallback,
                                        String qualityFlag,
                                        int page,
                                        int size) {
        int effectivePage = Math.max(0, page);
        int effectiveSize = Math.min(Math.max(size, 1), 100);
        return traceRepository.findAll(spec(roomId, gameId, personaId, action, fallback, qualityFlag),
                PageRequest.of(effectivePage, effectiveSize, Sort.by(Sort.Direction.DESC, "id")));
    }

    private Specification<AiDecisionTrace> spec(String roomId,
                                                String gameId,
                                                String personaId,
                                                String action,
                                                Boolean fallback,
                                                String qualityFlag) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(roomId)) {
                predicates.add(cb.equal(root.get("roomId"), roomId));
            }
            if (StringUtils.hasText(gameId)) {
                predicates.add(cb.equal(root.get("gameId"), gameId));
            }
            if (StringUtils.hasText(personaId)) {
                predicates.add(cb.equal(root.get("personaId"), personaId));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (fallback != null) {
                predicates.add(cb.equal(root.get("fallback"), fallback));
            }
            if (StringUtils.hasText(qualityFlag)) {
                predicates.add(cb.like(root.get("quality").as(String.class), "%" + qualityFlag + "%"));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

}
