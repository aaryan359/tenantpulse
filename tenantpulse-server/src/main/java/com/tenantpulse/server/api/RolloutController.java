package com.tenantpulse.server.api;

import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.service.RolloutService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Staged rollout: send a change to a few offices first, promote it to the whole client later. */
@RestController
@RequestMapping("/v1/rollouts")
public class RolloutController {

    private final RolloutService rollouts;

    public RolloutController(RolloutService rollouts) {
        this.rollouts = rollouts;
    }

    public record StageRequest(String clientId, List<String> offices, List<RolloutService.SettingValue> changes,
                               String reason, String idempotencyKey) {
    }

    public record PromoteRequest(String reason, String idempotencyKey) {
    }

    /** {@code rolloutId} is the version of the stage; it is what promote refers to. */
    public record RolloutResponse(long rolloutId, long version, Instant publishedAt) {
    }

    @PostMapping
    public RolloutResponse stage(Caller caller, @RequestBody StageRequest request) {
        PublishedChangeSet stage = rollouts.stage(caller, request.clientId(), request.offices(), request.changes(),
                request.reason(), request.idempotencyKey());
        return new RolloutResponse(stage.version(), stage.version(), stage.publishedAt());
    }

    @PostMapping("/{rolloutId}/promote")
    public RolloutResponse promote(Caller caller, @PathVariable long rolloutId, @RequestBody PromoteRequest request) {
        PublishedChangeSet promotion = rollouts.promote(caller, rolloutId, request.reason(), request.idempotencyKey());
        return new RolloutResponse(rolloutId, promotion.version(), promotion.publishedAt());
    }
}
