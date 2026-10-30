package com.tenantpulse.server.service;

import com.tenantpulse.core.model.ChangeKind;
import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.core.snapshot.SnapshotDiff;
import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.security.TenantAccessPolicy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Rolls configuration back to how it was at an earlier version. History is never rewritten: the result is a new,
 * higher version whose content restores the old state, so every client simply moves forward to it. The response
 * lists exactly which overrides change. An operator's rollback only touches their own tenant's overrides; an admin may
 * roll back everything or one named client.
 */
@Service
public class RollbackService {

    /** What a rollback changed (or would change). {@code newVersion} is null for a dry run or when nothing differs. */
    public record Result(long targetVersion, Long newVersion, List<SnapshotDiff.Entry> diff) {
    }

    private final ChangeSetService changeSets;
    private final ConfigStateService state;
    private final TenantAccessPolicy policy;

    public RollbackService(ChangeSetService changeSets, ConfigStateService state, TenantAccessPolicy policy) {
        this.changeSets = changeSets;
        this.state = state;
        this.policy = policy;
    }

    public Result rollback(Caller caller, long targetVersion, String clientId, String reason, boolean dryRun,
                           String idempotencyKey) {
        // An operator can only roll back their own tenant; an admin can name one client or leave it open for all.
        String scopedClient = caller.isAdmin() ? blankToNull(clientId) : caller.tenantId();
        policy.requireCanChangeClient(caller, scopedClient);

        ConfigSnapshot current = state.snapshot();
        if (targetVersion < 0 || targetVersion >= current.version()) {
            throw new InvalidRequestException("target version must be an earlier version than the current one");
        }
        List<SnapshotDiff.Entry> diff = SnapshotDiff.between(current, state.stateAt(targetVersion)).stream()
                .filter(entry -> scopedClient == null || scopedClient.equals(entry.scope().clientId()))
                .toList();
        if (diff.isEmpty() || dryRun) {
            return new Result(targetVersion, null, diff);
        }

        List<SettingChange> changes = SnapshotDiff.toChanges(diff);
        PublishedChangeSet published = changeSets.publishAuthorized(new ChangeSet(caller.subject(), reason,
                idempotencyKey, ChangeKind.ROLLBACK, targetVersion, changes));
        return new Result(targetVersion, published.version(), diff);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
