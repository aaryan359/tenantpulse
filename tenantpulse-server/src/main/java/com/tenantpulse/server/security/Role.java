package com.tenantpulse.server.security;

/**
 * What a caller may do.
 * <ul>
 *   <li>{@code SERVICE}: a backend service reading its own tenant's configuration (the embedded client library).</li>
 *   <li>{@code EMPLOYEE}: a person who may look at their own tenant's configuration, read-only.</li>
 *   <li>{@code OPERATOR}: may change, stage, promote and roll back settings of their own tenant only.</li>
 *   <li>{@code ADMIN}: platform staff; may act on any tenant and on platform-wide (GLOBAL) settings.</li>
 * </ul>
 */
public enum Role {
    SERVICE,
    EMPLOYEE,
    OPERATOR,
    ADMIN;

    public boolean canWrite() {
        return this == OPERATOR || this == ADMIN;
    }
}
