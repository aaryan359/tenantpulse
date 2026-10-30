package com.tenantpulse.server.service;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.validation.ChangeSetValidator;
import com.tenantpulse.core.validation.ValidationFailure;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import com.tenantpulse.server.repository.ChangeSetRepository;
import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.security.TenantAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Publishes change sets. The order matters: check the caller may change these settings, validate every value,
 * store the change set (this is the commit), and only then update the in-memory state and tell clients. Nothing
 * is announced before it is safely stored.
 */
@Service
public class ChangeSetService {

    private static final Logger log = LoggerFactory.getLogger(ChangeSetService.class);

    private final ChangeSetRepository repository;
    private final ChangeSetValidator validator;
    private final TenantAccessPolicy policy;
    private final ConfigStateService state;
    private final ChangePropagator propagator;
    private final TenantPulseMetrics metrics;

    public ChangeSetService(ChangeSetRepository repository, SettingCatalog catalog, TenantAccessPolicy policy,
                            ConfigStateService state, ChangePropagator propagator, TenantPulseMetrics metrics) {
        this.repository = repository;
        this.validator = new ChangeSetValidator(catalog.definitions());
        this.policy = policy;
        this.state = state;
        this.propagator = propagator;
        this.metrics = metrics;
    }

    /** Publishes on behalf of {@code caller}, who must be allowed to change every scope in the change set. */
    public PublishedChangeSet publish(Caller caller, ChangeSet changeSet) {
        policy.requireCanPublish(caller, changeSet);
        return publishAuthorized(changeSet);
    }

    /** Publishes a change set whose caller has already been authorised by the calling service. */
    PublishedChangeSet publishAuthorized(ChangeSet changeSet) {
        List<ValidationFailure> failures = validator.validate(changeSet);
        if (!failures.isEmpty()) {
            metrics.publishRejected("validation");
            throw new ChangeSetRejectedException(failures);
        }
        long knownBefore = state.snapshot().version();
        PublishedChangeSet published = repository.publish(changeSet);
        boolean duplicate = published.version() <= knownBefore;
        metrics.published(changeSet.kind().name(), duplicate);
        log.info("change set published version={} kind={} changes={} duplicate={}", published.version(),
                changeSet.kind(), changeSet.changes().size(), duplicate);
        propagator.refreshAndNotify();
        return published;
    }
}
