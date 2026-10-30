package com.tenantpulse.core.type;

import com.tenantpulse.core.exception.InvalidSettingValueException;

import java.util.Map;
import java.util.Objects;

/**
 * Base class for the kinds of value a setting can hold. Values travel as text and are parsed
 * once into a typed object, so readers never parse on the hot path.
 *
 * <p>Subclasses implement {@link #doParse} and, if they have range rules, {@link #check}.
 * Error messages state the rule and never echo the input.
 */
public abstract class SettingValueType<T> {

    private final Class<T> valueClass;

    protected SettingValueType(Class<T> valueClass) {
        this.valueClass = Objects.requireNonNull(valueClass, "valueClass");
    }

    public final Class<T> valueClass() {
        return valueClass;
    }

    /** Short name used in error messages, e.g. {@code duration}. */
    public abstract String name();

    /** Stable identifier used on the wire; {@link SettingTypes#create} turns it back into a type. */
    public abstract String kind();

    /** The rules of this type as text, enough for {@link SettingTypes#create} to rebuild it. */
    public abstract Map<String, String> params();

    /** Parses and validates; throws {@link InvalidSettingValueException} on any problem. */
    public final T parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidSettingValueException("value must not be blank");
        }
        T value;
        try {
            value = doParse(raw.trim());
        } catch (InvalidSettingValueException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidSettingValueException("not a valid " + name() + " value");
        }
        check(value);
        return value;
    }

    /** Canonical text form of a parsed value; {@code parse(format(v))} equals {@code v}. */
    public abstract String format(T value);

    protected abstract T doParse(String raw);

    /** Range and consistency rules on an already parsed value. */
    protected void check(T value) {
    }

    protected static InvalidSettingValueException invalid(String message) {
        return new InvalidSettingValueException(message);
    }
}
