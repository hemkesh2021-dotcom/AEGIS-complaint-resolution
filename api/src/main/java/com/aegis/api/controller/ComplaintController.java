package com.aegis.api.controller;

import com.aegis.api.dto.ComplaintRequest;
import com.aegis.api.dto.ComplaintResponse;
import com.aegis.api.service.PipelineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entry point — receives a complaint and returns the full pipeline result. */
@RestController
@RequestMapping("/api/complaints")
@CrossOrigin // dev convenience; lock down origins before production
public class ComplaintController {

    private final PipelineService pipeline;

    public ComplaintController(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping
    public ComplaintResponse handle(@Valid @RequestBody ComplaintRequest request) {
        return pipeline.run(request, "CONSOLE");
    }
}
