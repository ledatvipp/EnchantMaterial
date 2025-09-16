package org.ledat.enchantMaterial.booster;

import java.util.Objects;

/**
 * Kết quả khi xử lý một yêu cầu booster.
 */
public class BoosterActivationResult {

    public enum Status {
        CREATED,
        EXTENDED,
        REPLACED,
        QUEUED
    }

    private final BoosterRequest request;
    private final boolean success;
    private final Status status;
    private final Booster booster;
    private final Booster previousBooster;
    private final BoosterFailureReason failureReason;
    private final long additionalDurationSeconds;
    private final String message;

    private BoosterActivationResult(Builder builder) {
        this.request = builder.request;
        this.success = builder.success;
        this.status = builder.status;
        this.booster = builder.booster;
        this.previousBooster = builder.previousBooster;
        this.failureReason = builder.failureReason;
        this.additionalDurationSeconds = builder.additionalDurationSeconds;
        this.message = builder.message;
    }

    public static BoosterActivationResult created(BoosterRequest request, Booster booster, String message) {
        return new Builder(request, true)
                .status(Status.CREATED)
                .booster(booster)
                .message(message)
                .build();
    }

    public static BoosterActivationResult extended(BoosterRequest request, Booster booster,
                                                   Booster previous, long additionalSeconds, String message) {
        return new Builder(request, true)
                .status(Status.EXTENDED)
                .booster(booster)
                .previousBooster(previous)
                .additionalDurationSeconds(additionalSeconds)
                .message(message)
                .build();
    }

    public static BoosterActivationResult replaced(BoosterRequest request, Booster booster,
                                                   Booster previous, String message) {
        return new Builder(request, true)
                .status(Status.REPLACED)
                .booster(booster)
                .previousBooster(previous)
                .message(message)
                .build();
    }

    public static BoosterActivationResult queued(BoosterRequest request, Booster booster, String message) {
        return new Builder(request, true)
                .status(Status.QUEUED)
                .booster(booster)
                .message(message)
                .build();
    }

    public static BoosterActivationResult failure(BoosterRequest request, BoosterFailureReason reason, String message) {
        return new Builder(request, false)
                .failureReason(reason)
                .message(message)
                .build();
    }

    public BoosterRequest getRequest() {
        return request;
    }

    public boolean isSuccess() {
        return success;
    }

    public Status getStatus() {
        return status;
    }

    public Booster getBooster() {
        return booster;
    }

    public Booster getPreviousBooster() {
        return previousBooster;
    }

    public BoosterFailureReason getFailureReason() {
        return failureReason;
    }

    public long getAdditionalDurationSeconds() {
        return additionalDurationSeconds;
    }

    public String getMessage() {
        return message;
    }

    public boolean replacedExisting() {
        return status == Status.REPLACED;
    }

    public boolean extendedExisting() {
        return status == Status.EXTENDED;
    }

    public boolean createdNew() {
        return status == Status.CREATED;
    }

    public boolean queuedBooster() {
        return status == Status.QUEUED;
    }

    public static class Builder {
        private final BoosterRequest request;
        private final boolean success;
        private Status status;
        private Booster booster;
        private Booster previousBooster;
        private BoosterFailureReason failureReason;
        private long additionalDurationSeconds;
        private String message;

        public Builder(BoosterRequest request, boolean success) {
            this.request = Objects.requireNonNull(request, "request");
            this.success = success;
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder booster(Booster booster) {
            this.booster = booster;
            return this;
        }

        public Builder previousBooster(Booster previousBooster) {
            this.previousBooster = previousBooster;
            return this;
        }

        public Builder failureReason(BoosterFailureReason failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        public Builder additionalDurationSeconds(long additionalDurationSeconds) {
            this.additionalDurationSeconds = additionalDurationSeconds;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public BoosterActivationResult build() {
            return new BoosterActivationResult(this);
        }
    }
}
