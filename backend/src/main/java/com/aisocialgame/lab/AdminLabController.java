package com.aisocialgame.lab;

import com.aisocialgame.web.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/lab")
public class AdminLabController {
    private final LabService service;
    private final LabCoachService coach;
    public AdminLabController(LabService service,LabCoachService coach) { this.service=service; this.coach=coach; }
    @GetMapping("/agents") public List<LabAgent> agents(@CurrentAdmin String operator) { return service.agents(); }
    @PostMapping("/agents") public LabAgent createAgent(@CurrentAdmin String operator,@Valid @RequestBody LabRequests.Agent request) { return service.saveAgent(null,request); }
    @PutMapping("/agents/{id}") public LabAgent saveAgent(@CurrentAdmin String operator,@PathVariable String id,@Valid @RequestBody LabRequests.Agent request) { return service.saveAgent(id,request); }
    @GetMapping("/experiments") public Map<String,Object> list(@CurrentAdmin String operator,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.list(page,size); }
    @PostMapping("/experiments") public Map<String,Object> create(@CurrentAdmin String operator,@Valid @RequestBody LabRequests.Experiment request) { return service.create(request,operator); }
    @GetMapping("/experiments/{id}") public Map<String,Object> detail(@CurrentAdmin String operator,@PathVariable String id) { return service.detail(id,null,true); }
    @GetMapping("/experiments/{id}/matches") public Map<String,Object> matches(@CurrentAdmin String operator,@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.matchPage(id,page,size,true); }
    @GetMapping("/experiments/{id}/experiences") public Map<String,Object> experiences(@CurrentAdmin String operator,@PathVariable String id,@RequestParam(defaultValue="0") int page) { return service.experiencePage(id,page); }
    @PostMapping("/experiments/{id}/control/{action}") public Map<String,Object> control(@CurrentAdmin String operator,@PathVariable String id,@PathVariable String action) { return service.control(id,action); }
    @PostMapping("/experiments/{id}/rollback/{experienceId}") public void rollback(@CurrentAdmin String operator,@PathVariable String id,@PathVariable String experienceId) { service.rollback(id,experienceId); }
    @PostMapping("/experiments/{id}/retry-review") public void retry(@CurrentAdmin String operator,@PathVariable String id) { coach.retry(id); }
    @PostMapping("/experiments/{id}/evaluations") public Map<String,Object> evaluate(@CurrentAdmin String operator,@PathVariable String id,@Valid @RequestBody LabRequests.Evaluation request) { return service.evaluate(id,request); }
}
