package com.tenantpulse.server.service;

import com.tenantpulse.core.model.ChangeKind;
import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.resolve.ResolvedSetting;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.server.repository.ChangeSetRepository;
import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.security.TenantAccessPolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Staged rollout in two steps, both ordinary change sets in the log.
 *
 * <p><b>Stage:</b> the new values are set as OFFICE-level overrides for a few chosen offices. Those offices see
 * the change at once; everyone else in the client keeps the old value. <b>Promote:</b> one atomic change set sets the
 * same values at CLIENT level for everyone and removes the office overrides it replaces. A rollout is identified by
 * the version of its stage.
 */
@Service
public class RolloutService {

    static final int MAX_OFFICES = 50;

    public record SettingValue(String key, String value) {
    }

    private final ChangeSetService changeSets;
    private final ChangeSetRepository repository;
    private final ConfigStateService state;
    private final TenantAccessPolicy policy;

    public RolloutService(ChangeSetService changeSets, ChangeSetRepository repository, ConfigStateService state,
                          TenantAccessPolicy policy) {
        this.changeSets = changeSets;
        this.repository = repository;
        this.state = state;
        this.policy = policy;
    }

    public PublishedChangeSet stage(Caller caller, String clientId, List<String> offices, List<SettingValue> values,
                                    String reason, String idempotencyKey) {
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidRequestException("clientId is required");
        }
        policy.requireCanChangeClient(caller, clientId);
        if (offices == null || offices.isEmpty() || offices.size() > MAX_OFFICES) {
            throw new InvalidRequestException("offices must list between 1 and " + MAX_OFFICES + " offices");
        }
        if (values == null || values.isEmpty()) {
            throw new InvalidRequestException("at least one setting value is required");
        }
        Set<String> distinctOffices = new LinkedHashSet<>(offices);
        List<SettingChange> changes = new ArrayList<>();
        try {
            for (String office : distinctOffices) {
                for (SettingValue value : values) {
                    if (value == null || value.key() == null || value.value() == null) {
                        throw new InvalidRequestException("each setting needs a key and a value");
                    }
                    changes.add(SettingChange.set(value.key(), Scope.office(clientId, office), value.value()));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        return changeSets.publishAuthorized(new ChangeSet(caller.subject(), reason, idempotencyKey,
                ChangeKind.ROLLOUT_STAGE, null, changes));
    }

    /** Applies a staged rollout to the whole client. Promoting the same rollout twice returns the first promotion. */
    public PublishedChangeSet promote(Caller caller, long stageVersion, String reason, String idempotencyKey) {
        PublishedChangeSet stage = repository.findByVersion(stageVersion)
                .orElseThrow(() -> new NotFoundException("no such rollout"));
        if (stage.changeSet().kind() != ChangeKind.ROLLOUT_STAGE) {
            throw new InvalidRequestException("that version is not a staged rollout");
        }
        String clientId = stage.changeSet().changes().get(0).scope().clientId();
        policy.requireCanChangeClient(caller, clientId);

        var existing = repository.findPromotionOf(stageVersion);
        if (existing.isPresent()) {
            return existing.get();
        }

        ConfigSnapshot current = state.snapshot();
        Map<String, String> stagedValues = new LinkedHashMap<>();
        List<SettingChange> changes = new ArrayList<>();
        for (SettingChange staged : stage.changeSet().changes()) {
            stagedValues.putIfAbsent(staged.key(), staged.rawValue());
        }
        stagedValues.forEach((key, value) -> changes.add(SettingChange.set(key, Scope.client(clientId), value)));

        // Remove an office override only if it still holds the staged value; a later edit to it is left alone.
        for (SettingChange staged : stage.changeSet().changes()) {
            SettingDefinition definition = current.definition(staged.key());
            ResolvedSetting now = current.explain(staged.key(),
                    ScopeContext.office(clientId, staged.scope().officeId()));
            if (now.level() == ScopeLevel.OFFICE
                    && Objects.equals(now.value(), definition.parse(staged.rawValue()))) {
                changes.add(SettingChange.remove(staged.key(), staged.scope()));
            }
        }
        String key = idempotencyKey != null ? idempotencyKey : "promote:" + stageVersion;
        return changeSets.publishAuthorized(new ChangeSet(caller.subject(), reason, key,
                ChangeKind.ROLLOUT_PROMOTE, stageVersion, changes));
    }
}
