package com.tenantpulse.core.model;

/** Levels a setting can be defined at. Declaration order is specificity: later wins. */
public enum ScopeLevel {
    GLOBAL,
    CLIENT,
    OFFICE,
    SHIFT
}
