package com.tenantpulse.server.security;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.server.service.InvalidRequestException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Tenant isolation in one place. A non-admin caller is bound to the tenant in their token: they can read it and,
 * if they are an operator, change it. They can never read or change another tenant, and only an admin may touch
 * platform-wide (GLOBAL) settings. Every controller goes through here, so no endpoint can forget the check.
 */
@Component
public class TenantAccessPolicy {

    /**
     * The tenant a read applies to. A non-admin always gets their own tenant; naming a different one is refused.
     * An admin must name the tenant explicitly.
     */
    public String tenantToRead(Caller caller, String requestedTenant) {
        if (caller.isAdmin()) {
            if (requestedTenant == null || requestedTenant.isBlank()) {
                throw new InvalidRequestException("admin requests must name a tenant");
            }
            return requestedTenant;
        }
        if (requestedTenant != null && !requestedTenant.isBlank() && !requestedTenant.equals(caller.tenantId())) {
            throw new AccessDeniedException("not allowed to access another tenant");
        }
        return caller.tenantId();
    }

    /** Refuses a change set that touches anything the caller may not change. */
    public void requireCanPublish(Caller caller, ChangeSet changeSet) {
        requireWriter(caller);
        if (caller.isAdmin()) {
            return;
        }
        for (SettingChange change : changeSet.changes()) {
            Scope scope = change.scope();
            if (scope.level() == ScopeLevel.GLOBAL || !caller.tenantId().equals(scope.clientId())) {
                throw new AccessDeniedException("not allowed to change settings outside your own tenant");
            }
        }
    }

    /** Refuses acting on a client other than the caller's own (admins may act on any). */
    public void requireCanChangeClient(Caller caller, String clientId) {
        requireWriter(caller);
        if (!caller.isAdmin() && !caller.tenantId().equals(clientId)) {
            throw new AccessDeniedException("not allowed to change another tenant");
        }
    }

    private static void requireWriter(Caller caller) {
        if (!caller.role().canWrite()) {
            throw new AccessDeniedException("this role cannot change settings");
        }
    }
}
