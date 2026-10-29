package com.tenantpulse.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a read is happening: which client, office and shift the caller is serving.
 * Office and shift are optional; a null clientId means "platform defaults only".
 */
public record ScopeContext(String clientId, String officeId, String shiftId) {

    public ScopeContext {
        if (shiftId != null && officeId == null) {
            throw new IllegalArgumentException("shiftId requires officeId");
        }
        if (officeId != null && clientId == null) {
            throw new IllegalArgumentException("officeId requires clientId");
        }
    }

    public static ScopeContext global() {
        return new ScopeContext(null, null, null);
    }

    public static ScopeContext client(String clientId) {
        return new ScopeContext(clientId, null, null);
    }

    public static ScopeContext office(String clientId, String officeId) {
        return new ScopeContext(clientId, officeId, null);
    }

    public static ScopeContext shift(String clientId, String officeId, String shiftId) {
        return new ScopeContext(clientId, officeId, shiftId);
    }

    /** Scopes that apply to this context, most specific first, ending with GLOBAL. */
    public List<Scope> chain() {
        List<Scope> chain = new ArrayList<>(4);
        if (shiftId != null) {
            chain.add(Scope.shift(clientId, officeId, shiftId));
        }
        if (officeId != null) {
            chain.add(Scope.office(clientId, officeId));
        }
        if (clientId != null) {
            chain.add(Scope.client(clientId));
        }
        chain.add(Scope.global());
        return chain;
    }
}
