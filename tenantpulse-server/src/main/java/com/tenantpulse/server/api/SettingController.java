package com.tenantpulse.server.api;

import com.tenantpulse.core.resolve.ResolvedSetting;
import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.security.TenantAccessPolicy;
import com.tenantpulse.server.service.ConfigReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Human-friendly reads: what value applies to an office or shift, and which level supplied it. */
@RestController
@RequestMapping("/v1/config")
public class SettingController {

    private final ConfigReadService reads;
    private final TenantAccessPolicy policy;

    public SettingController(ConfigReadService reads, TenantAccessPolicy policy) {
        this.reads = reads;
        this.policy = policy;
    }

    /** Every setting as it resolves for the given office and shift. */
    @GetMapping
    public List<ResolvedSettingView> all(Caller caller, @RequestParam(required = false) String tenant,
                                         @RequestParam(required = false) String office,
                                         @RequestParam(required = false) String shift) {
        String tenantId = policy.tenantToRead(caller, tenant);
        return reads.resolveAll(tenantId, office, shift).stream().map(this::view).toList();
    }

    /** One setting, with the level that supplied its value. */
    @GetMapping("/{key}")
    public ResolvedSettingView explain(Caller caller, @PathVariable String key,
                                       @RequestParam(required = false) String tenant,
                                       @RequestParam(required = false) String office,
                                       @RequestParam(required = false) String shift) {
        String tenantId = policy.tenantToRead(caller, tenant);
        return view(reads.explain(tenantId, key, office, shift));
    }

    private ResolvedSettingView view(ResolvedSetting resolved) {
        return ResolvedSettingView.of(resolved, reads.format(resolved.key(), resolved.value()));
    }
}
