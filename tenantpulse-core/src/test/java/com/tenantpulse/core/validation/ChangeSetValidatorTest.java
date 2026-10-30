package com.tenantpulse.core.validation;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.type.NumberSettingType;
import com.tenantpulse.core.type.TimeWindowSettingType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSetValidatorTest {

    private final ChangeSetValidator validator = new ChangeSetValidator(List.of(
            SettingDefinition.of("booking.cutoff.minutes", new NumberSettingType(0, 1440), "60", ""),
            SettingDefinition.of("night.window", new TimeWindowSettingType(false), "09:00-17:00", "")));

    private static ChangeSet set(SettingChange... changes) {
        return new ChangeSet("ravi", "reason", null, List.of(changes));
    }

    @Test
    void acceptsAValidChangeSet() {
        assertTrue(validator.validate(set(
                SettingChange.set("booking.cutoff.minutes", Scope.office("acme", "blr"), "30"))).isEmpty());
    }

    @Test
    void rejectsNegativeCutoff() {
        List<ValidationFailure> failures = validator.validate(set(
                SettingChange.set("booking.cutoff.minutes", Scope.client("acme"), "-30")));
        assertEquals(1, failures.size());
        assertEquals("booking.cutoff.minutes", failures.get(0).key());
    }

    @Test
    void rejectsWindowThatEndsBeforeItStarts() {
        assertFalse(validator.validate(set(
                SettingChange.set("night.window", Scope.global(), "17:00-09:00"))).isEmpty());
    }

    @Test
    void reportsEveryProblemNotJustTheFirst() {
        List<ValidationFailure> failures = validator.validate(set(
                SettingChange.set("booking.cutoff.minutes", Scope.client("acme"), "-1"),
                SettingChange.set("night.window", Scope.global(), "17:00-09:00"),
                SettingChange.set("nope", Scope.global(), "1")));
        assertEquals(3, failures.size());
    }

    @Test
    void rejectsDuplicateKeyAndScopeInOneSet() {
        List<ValidationFailure> failures = validator.validate(set(
                SettingChange.set("booking.cutoff.minutes", Scope.client("acme"), "30"),
                SettingChange.set("booking.cutoff.minutes", Scope.client("acme"), "20")));
        assertEquals(1, failures.size());
    }

    @Test
    void sameKeyAtDifferentScopesIsFine() {
        assertTrue(validator.validate(set(
                SettingChange.set("booking.cutoff.minutes", Scope.client("acme"), "30"),
                SettingChange.set("booking.cutoff.minutes", Scope.office("acme", "blr"), "20"))).isEmpty());
    }

    @Test
    void requiresAuthorReasonAndAtLeastOneChange() {
        assertEquals(3, validator.validate(new ChangeSet(" ", null, null, List.of())).size());
    }

    @Test
    void removalNeedsNoValueButTheKeyMustExist() {
        assertTrue(validator.validate(set(
                SettingChange.remove("booking.cutoff.minutes", Scope.client("acme")))).isEmpty());
        assertFalse(validator.validate(set(SettingChange.remove("nope", Scope.client("acme")))).isEmpty());
    }

    @Test
    void rejectsOversizedChangeSetsAndTexts() {
        List<SettingChange> many = new ArrayList<>();
        for (int i = 0; i <= ChangeSetValidator.MAX_CHANGES; i++) {
            many.add(SettingChange.set("booking.cutoff.minutes", Scope.office("acme", "o" + i), "30"));
        }
        assertFalse(validator.validate(new ChangeSet("a", "r", null, many)).isEmpty());
        assertFalse(validator.validate(new ChangeSet("a", "r".repeat(ChangeSetValidator.MAX_REASON_LENGTH + 1), null,
                List.of(SettingChange.set("booking.cutoff.minutes", Scope.client("acme"), "30")))).isEmpty());
    }

    @Test
    void failureMessagesNeverContainTheSubmittedValue() {
        List<ValidationFailure> failures = validator.validate(set(
                SettingChange.set("booking.cutoff.minutes", Scope.client("acme"), "secret-value-12345")));
        assertFalse(failures.get(0).message().contains("secret-value-12345"));
    }
}
