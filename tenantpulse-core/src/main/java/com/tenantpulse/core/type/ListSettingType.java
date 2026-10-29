package com.tenantpulse.core.type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A comma-separated list of distinct, non-empty strings, e.g. the cab types allowed.
 * An empty {@code allowedElements} set means any element is accepted.
 */
public final class ListSettingType extends SettingValueType<List<String>> {

    private final Set<String> allowedElements;
    private final int maxSize;

    @SuppressWarnings("unchecked")
    public ListSettingType(Set<String> allowedElements, int maxSize) {
        super((Class<List<String>>) (Class<?>) List.class);
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be at least 1");
        }
        this.allowedElements = Set.copyOf(allowedElements);
        this.maxSize = maxSize;
    }

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String format(List<String> value) {
        return String.join(",", value);
    }

    @Override
    protected List<String> doParse(String raw) {
        List<String> items = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            String item = part.trim();
            if (item.isEmpty()) {
                throw invalid("list must not contain empty elements");
            }
            items.add(item);
        }
        return List.copyOf(items);
    }

    @Override
    protected void check(List<String> value) {
        if (value.size() > maxSize) {
            throw invalid("list must have at most " + maxSize + " elements");
        }
        if (new HashSet<>(value).size() != value.size()) {
            throw invalid("list must not contain duplicates");
        }
        if (!allowedElements.isEmpty() && !allowedElements.containsAll(value)) {
            throw invalid("list elements must be among " + new TreeSet<>(allowedElements));
        }
    }
}
