package com.salamu.menu.site.api;

import java.time.LocalTime;
import java.util.Objects;

public record OpeningInterval(LocalTime opensAt, LocalTime closesAt) implements Comparable<OpeningInterval> {

    public OpeningInterval {
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("opensAt must be before closesAt");
        }
    }

    @Override
    public int compareTo(OpeningInterval other) {
        int byOpen = opensAt.compareTo(other.opensAt);
        return byOpen != 0 ? byOpen : closesAt.compareTo(other.closesAt);
    }
}
