package com.aegis.api.controller;

import com.aegis.api.ApiKeyFilter;
import com.aegis.api.service.ProviderChatModel;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {
    private final ProviderChatModel model;
    public ProviderController(ProviderChatModel model) { this.model = model; }

    @GetMapping
    public Map<String, Object> status() { return model.status(); }

    @PostMapping("/test")
    public ResponseEntity<?> test(HttpServletRequest request) {
        Object roles = request.getAttribute(ApiKeyFilter.ATTR_ROLES);
        if (!(roles instanceof Set<?> set) || !set.contains(ApiKeyFilter.ROLE_SUPERVISOR))
            return ResponseEntity.status(403).body(Map.of("error", "Supervisor role required"));
        if (!model.configured()) return ResponseEntity.status(409).body(Map.of("error", "Configure provider key and model on the server first"));
        try {
            model.call(new Prompt("Reply with the single word OK. This is a connection test; no customer data is included."));
            return ResponseEntity.ok(Map.of("ok", true, "status", model.status()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "Provider test failed. Check server credentials, model access and connectivity."));
        }
    }
}
