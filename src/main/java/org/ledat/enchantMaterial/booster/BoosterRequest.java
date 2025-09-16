package org.ledat.enchantMaterial.booster;

import java.util.Objects;

/**
 * Đại diện cho một yêu cầu kích hoạt hoặc thao tác booster.
 */
public class BoosterRequest {
    private final BoosterType type;
    private final double multiplier;
    private final long durationSeconds;
    private final BoosterStackingStrategy stackingStrategy;
    private final BoosterSource source;
    private final String note;
    private final boolean bypassLimit;
    private final boolean bypassValidation;
    private final boolean silent;
    private final boolean saveToStorage;
    private final Booster customBooster;

    private BoosterRequest(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type");
        this.multiplier = builder.multiplier;
        this.durationSeconds = builder.durationSeconds;
        this.stackingStrategy = builder.stackingStrategy;
        this.source = builder.source;
        this.note = builder.note;
        this.bypassLimit = builder.bypassLimit;
        this.bypassValidation = builder.bypassValidation;
        this.silent = builder.silent;
        this.saveToStorage = builder.saveToStorage;
        this.customBooster = builder.customBooster;

        if (this.customBooster == null) {
            if (this.multiplier <= 0) {
                throw new IllegalArgumentException("Multiplier phải lớn hơn 0");
            }
            if (this.durationSeconds <= 0) {
                throw new IllegalArgumentException("Duration phải lớn hơn 0");
            }
        }
    }

    public BoosterType getType() {
        return type;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public BoosterStackingStrategy getStackingStrategy() {
        return stackingStrategy;
    }

    public BoosterSource getSource() {
        return source;
    }

    public String getNote() {
        return note;
    }

    public boolean isBypassLimit() {
        return bypassLimit;
    }

    public boolean isBypassValidation() {
        return bypassValidation;
    }

    public boolean isSilent() {
        return silent;
    }

    public boolean isSaveToStorage() {
        return saveToStorage;
    }

    public boolean hasCustomBooster() {
        return customBooster != null;
    }

    public Booster getCustomBooster() {
        return customBooster;
    }

    public BoosterRequest withCustomBooster(Booster booster) {
        return toBuilder()
                .customBooster(booster)
                .multiplier(booster.getMultiplier())
                .durationSeconds(Math.max(1L, booster.getTotalDurationMillis() / 1000L))
                .build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder(BoosterType type) {
        return new Builder(type);
    }

    public static BoosterRequest fromExistingBooster(Booster booster, BoosterSource source) {
        return BoosterRequest.builder(booster.getType())
                .multiplier(booster.getMultiplier())
                .durationSeconds(Math.max(1L, booster.getTotalDurationMillis() / 1000L))
                .source(source)
                .customBooster(booster)
                .saveToStorage(false)
                .stackingStrategy(BoosterStackingStrategy.SMART)
                .build();
    }

    public static class Builder {
        private final BoosterType type;
        private double multiplier;
        private long durationSeconds;
        private BoosterStackingStrategy stackingStrategy = BoosterStackingStrategy.SMART;
        private BoosterSource source = BoosterSource.UNKNOWN;
        private String note;
        private boolean bypassLimit;
        private boolean bypassValidation;
        private boolean silent;
        private boolean saveToStorage = true;
        private Booster customBooster;

        private Builder(BoosterType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        private Builder(BoosterRequest request) {
            this.type = request.type;
            this.multiplier = request.multiplier;
            this.durationSeconds = request.durationSeconds;
            this.stackingStrategy = request.stackingStrategy;
            this.source = request.source;
            this.note = request.note;
            this.bypassLimit = request.bypassLimit;
            this.bypassValidation = request.bypassValidation;
            this.silent = request.silent;
            this.saveToStorage = request.saveToStorage;
            this.customBooster = request.customBooster;
        }

        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        public Builder durationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
            return this;
        }

        public Builder stackingStrategy(BoosterStackingStrategy stackingStrategy) {
            this.stackingStrategy = stackingStrategy;
            return this;
        }

        public Builder source(BoosterSource source) {
            this.source = source;
            return this;
        }

        public Builder note(String note) {
            this.note = note;
            return this;
        }

        public Builder bypassLimit(boolean bypassLimit) {
            this.bypassLimit = bypassLimit;
            return this;
        }

        public Builder bypassValidation(boolean bypassValidation) {
            this.bypassValidation = bypassValidation;
            return this;
        }

        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        public Builder saveToStorage(boolean saveToStorage) {
            this.saveToStorage = saveToStorage;
            return this;
        }

        public Builder customBooster(Booster customBooster) {
            this.customBooster = customBooster;
            return this;
        }

        public BoosterRequest build() {
            return new BoosterRequest(this);
        }
    }
}
