package com.tenantpulse.core.validation;

import com.tenantpulse.core.exception.InvalidSettingValueException;
import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.model.SettingDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a change set before it is published. Returns every problem found, not just the first;
 * an empty list means the change set is safe to publish.
 */
public final class ChangeSetValidator {

    public static final int MAX_CHANGES = 200;
    public static final int MAX_AUTHOR_LENGTH = 200;
    public static final int MAX_REASON_LENGTH = 500;
    public static final int MAX_KEY_LENGTH = 200;

    private final Map<String, SettingDefinition> definitions = new HashMap<>();

    public ChangeSetValidator(Collection<SettingDefinition> definitions) {
        for (SettingDefinition definition : definitions) {
            this.definitions.put(definition.key(), definition);
        }
    }

    public List<ValidationFailure> validate(ChangeSet changeSet) {
        List<ValidationFailure> failures = new ArrayList<>();

        if (isBlank(changeSet.author())) {
            failures.add(new ValidationFailure(null, null, "author is required"));
        } else if (changeSet.author().length() > MAX_AUTHOR_LENGTH) {
            failures.add(new ValidationFailure(null, null, "author is too long"));
        }
        if (isBlank(changeSet.reason())) {
            failures.add(new ValidationFailure(null, null, "reason is required"));
        } else if (changeSet.reason().length() > MAX_REASON_LENGTH) {
            failures.add(new ValidationFailure(null, null, "reason is too long"));
        }
        if (changeSet.idempotencyKey() != null && changeSet.idempotencyKey().length() > MAX_KEY_LENGTH) {
            failures.add(new ValidationFailure(null, null, "idempotency key is too long"));
        }
        if (changeSet.changes().isEmpty()) {
            failures.add(new ValidationFailure(null, null, "change set has no changes"));
        } else if (changeSet.changes().size() > MAX_CHANGES) {
            failures.add(new ValidationFailure(null, null, "change set has more than " + MAX_CHANGES + " changes"));
            return failures;
        }

        Set<String> seen = new HashSet<>();
        for (SettingChange change : changeSet.changes()) {
            String scope = change.scope().key();
            if (!seen.add(change.key() + "@" + scope)) {
                failures.add(new ValidationFailure(change.key(), scope, "same setting and scope changed twice"));
                continue;
            }
            SettingDefinition definition = definitions.get(change.key());
            if (definition == null) {
                failures.add(new ValidationFailure(change.key(), scope, "unknown setting"));
                continue;
            }
            if (!change.isRemoval()) {
                try {
                    definition.parse(change.rawValue());
                } catch (InvalidSettingValueException e) {
                    failures.add(new ValidationFailure(change.key(), scope, e.getMessage()));
                }
            }
        }
        return failures;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
