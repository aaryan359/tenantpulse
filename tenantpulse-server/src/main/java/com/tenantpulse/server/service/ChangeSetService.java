package com.tenantpulse.server.service;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.validation.ChangeSetValidator;
import com.tenantpulse.core.validation.ValidationFailure;
import com.tenantpulse.server.repository.ChangeSetRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Publishes change sets: validate first, and only a valid one reaches the repository. */
@Service
public class ChangeSetService {

    private final ChangeSetRepository repository;
    private final ChangeSetValidator validator;

    public ChangeSetService(ChangeSetRepository repository, SettingCatalog catalog) {
        this.repository = repository;
        this.validator = new ChangeSetValidator(catalog.definitions());
    }

    public PublishedChangeSet publish(ChangeSet changeSet) {
        List<ValidationFailure> failures = validator.validate(changeSet);
        if (!failures.isEmpty()) {
            throw new ChangeSetRejectedException(failures);
        }
        return repository.publish(changeSet);
    }
}
