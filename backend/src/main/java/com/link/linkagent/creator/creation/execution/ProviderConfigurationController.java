package com.link.linkagent.creator.creation.execution;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/creation-providers")
public class ProviderConfigurationController {
    private final ProviderConfiguration service;
    public ProviderConfigurationController(ProviderConfiguration service){this.service=service;}
    @GetMapping public ResponseEntity<ProviderConfiguration.View> get(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());}
    @PostMapping public ResponseEntity<ProviderConfiguration.View> save(@RequestBody @Valid ProviderConfiguration.Save input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(input));}
}
