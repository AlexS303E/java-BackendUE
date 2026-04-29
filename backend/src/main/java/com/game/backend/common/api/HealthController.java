package com.game.backend.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    public ProblemDetail health() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.OK);
        detail.setTitle("Backend For UE is running");
        return detail;
    }
}
