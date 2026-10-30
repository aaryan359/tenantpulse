package com.tenantpulse.core.model;

/** Why a change set exists, kept for the audit trail and to find the pieces of a rollout. */
public enum ChangeKind {
    /** A normal edit by an operator. */
    PUBLISH,
    /** First stage of a rollout: overrides for a few chosen offices. */
    ROLLOUT_STAGE,
    /** Second stage: the staged value applied to the whole client. */
    ROLLOUT_PROMOTE,
    /** Restores the state of an earlier version, as a new forward version. */
    ROLLBACK
}
