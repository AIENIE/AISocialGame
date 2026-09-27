package com.aisocialgame.service.safety;

import com.aisocialgame.model.AiSafetyEvent;
import com.aisocialgame.repository.AiSafetyEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SafetyAuditWriter {
    private final AiSafetyEventRepository events;
    public SafetyAuditWriter(AiSafetyEventRepository events) { this.events = events; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public AiSafetyEvent save(AiSafetyEvent event) { return events.saveAndFlush(event); }
}
