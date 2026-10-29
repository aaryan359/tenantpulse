package com.tenantpulse.server.api;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingChange;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON body of {@code POST /change-sets}. A change with no {@code value} removes that override.
 */
public record ChangeSetRequest(String author, String reason, String idempotencyKey, List<ChangeRequest> changes) {

    public record ChangeRequest(String key, ScopeRequest scope, String value) {
    }

    public record ScopeRequest(ScopeLevel level, String clientId, String officeId, String shiftId) {
    }

    /** Converts to the domain object; throws IllegalArgumentException for a malformed change. */
    public ChangeSet toChangeSet() {
        List<SettingChange> converted = new ArrayList<>();
        if (changes != null) {
            for (ChangeRequest change : changes) {
                if (change == null || change.key() == null || change.scope() == null || change.scope().level() == null) {
                    throw new IllegalArgumentException("each change needs a key and a scope with a level");
                }
                ScopeRequest s = change.scope();
                Scope scope = new Scope(s.level(), s.clientId(), s.officeId(), s.shiftId());
                converted.add(new SettingChange(change.key(), scope, change.value()));
            }
        }
        return new ChangeSet(author, reason, idempotencyKey, converted);
    }
}
