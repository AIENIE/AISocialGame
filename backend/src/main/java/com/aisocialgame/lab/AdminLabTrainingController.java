package com.aisocialgame.lab;
import com.aisocialgame.web.CurrentAdmin;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/admin/lab/experiments/{id}/training-runs")
public class AdminLabTrainingController {
    private final LabTrainingService service;
    public AdminLabTrainingController(LabTrainingService service){this.service=service;}
    @PostMapping public Map<String,Object> create(@CurrentAdmin String admin,@PathVariable String id,@RequestBody LabTrainingService.Request request){return service.create(id,request);}
    @GetMapping("/{operationId}") public Map<String,Object> inspect(@CurrentAdmin String admin,@PathVariable String id,@PathVariable String operationId){return service.inspect(id,operationId);}
    @PostMapping("/{operationId}/control/{action}") public Map<String,Object> control(@CurrentAdmin String admin,@PathVariable String id,@PathVariable String operationId,@PathVariable String action){return service.control(id,operationId,action);}
    @GetMapping("/{operationId}/cases") public Map<String,Object> cases(@CurrentAdmin String admin,@PathVariable String id,@PathVariable String operationId){return service.material(id,operationId);}
}
