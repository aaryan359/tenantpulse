package com.tenantpulse.server.api;

import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.service.RollbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** One call to restore an earlier version, returning the diff of what changes. Use {@code dryRun} to preview it. */
@RestController
@RequestMapping("/v1/rollbacks")
public class RollbackController {

    private final RollbackService rollbacks;

    public RollbackController(RollbackService rollbacks) {
        this.rollbacks = rollbacks;
    }

    public record RollbackRequest(long targetVersion, String reason, String clientId, boolean dryRun,
                                  String idempotencyKey) {
    }

    public record DiffView(String key, String scope, String before, String after) {
    }

    public record RollbackResponse(long targetVersion, Long newVersion, boolean dryRun, List<DiffView> diff) {
    }

    @PostMapping
    public RollbackResponse rollback(Caller caller, @RequestBody RollbackRequest request) {
        RollbackService.Result result = rollbacks.rollback(caller, request.targetVersion(), request.clientId(),
                request.reason(), request.dryRun(), request.idempotencyKey());
        List<DiffView> diff = result.diff().stream()
                .map(entry -> new DiffView(entry.key(), entry.scope().key(), entry.before(), entry.after())).toList();
        return new RollbackResponse(result.targetVersion(), result.newVersion(), request.dryRun(), diff);
    }
}
