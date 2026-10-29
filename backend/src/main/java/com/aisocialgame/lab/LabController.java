package com.aisocialgame.lab;

import com.aisocialgame.model.User;
import com.aisocialgame.web.CurrentUser;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/lab")
public class LabController {
    private final LabService service;
    public LabController(LabService service) { this.service=service; }
    @GetMapping("/experiments")
    public Map<String,Object> list(@CurrentUser User user,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(page,size);
    }
    @GetMapping("/experiments/{id}")
    public Map<String,Object> detail(@CurrentUser User user,@PathVariable String id) { return service.detail(id,user.getId(),false); }
    @GetMapping("/experiments/{id}/matches")
    public Map<String,Object> matches(@CurrentUser User user,@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.matchPage(id,page,size,false);
    }
    @PostMapping("/experiments/{id}/enrollment")
    public Map<String,Object> enroll(@CurrentUser User user,@PathVariable String id) { return service.enroll(id,user); }
    @PostMapping("/experiments/{id}/enrollment/confirm")
    public Map<String,Object> confirm(@CurrentUser User user,@PathVariable String id) { return service.enrollmentAction(id,user,true); }
    @DeleteMapping("/experiments/{id}/enrollment")
    public Map<String,Object> withdraw(@CurrentUser User user,@PathVariable String id) { return service.enrollmentAction(id,user,false); }
}
