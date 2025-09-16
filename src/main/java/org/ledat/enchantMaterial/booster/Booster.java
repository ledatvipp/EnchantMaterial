package org.ledat.enchantMaterial.booster;

import java.util.Objects;

public class Booster {
    private final BoosterType type;
    private final double multiplier;
    private final long endTime;
    private final long startTime;
    
    /**
     * Constructor với validation
     */
    public Booster(BoosterType type, double multiplier, long durationSeconds) {
        if (type == null) {
            throw new IllegalArgumentException("BoosterType không được null");
        }
        if (multiplier <= 0) {
            throw new IllegalArgumentException("Multiplier phải > 0, nhận: " + multiplier);
        }
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("Duration phải > 0, nhận: " + durationSeconds);
        }
        
        this.type = type;
        this.multiplier = multiplier;
        this.startTime = System.currentTimeMillis();
        this.endTime = this.startTime + (durationSeconds * 1000L);
    }
    
    /**
     * Constructor để restore từ database
     */
    public Booster(BoosterType type, double multiplier, long startTime, long endTime) {
        if (type == null) {
            throw new IllegalArgumentException("BoosterType không được null");
        }
        if (multiplier <= 0) {
            throw new IllegalArgumentException("Multiplier phải > 0");
        }
        if (endTime <= startTime) {
            throw new IllegalArgumentException("EndTime phải > StartTime");
        }
        
        this.type = type;
        this.multiplier = multiplier;
        this.startTime = startTime;
        this.endTime = endTime;
    }
    
    public BoosterType getType() {
        return type;
    }
    
    public double getMultiplier() {
        return multiplier;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public long getEndTime() {
        return endTime;
    }
    
    public long getRemainingMillis() {
        return Math.max(0, endTime - System.currentTimeMillis());
    }
    
    public boolean isExpired() {
        return System.currentTimeMillis() >= endTime;
    }
    
    public long getTimeLeftSeconds() {
        return getRemainingMillis() / 1000L;
    }
    
    public double getProgressPercentage() {
        long totalDuration = endTime - startTime;
        long elapsed = System.currentTimeMillis() - startTime;
        return Math.min(100.0, Math.max(0.0, (double) elapsed / totalDuration * 100));
    }
    
    /**
     * Format thời gian còn lại với nhiều định dạng
     */
    public String formatTimeLeft() {
        return formatTimeLeft(TimeFormat.FULL);
    }
    
    public String formatTimeLeft(TimeFormat format) {
        long seconds = getTimeLeftSeconds();
        
        if (seconds <= 0) {
            return "00:00:00";
        }
        
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        switch (format) {
            case COMPACT:
                if (hours > 0) {
                    return String.format("%dh %dm", hours, minutes);
                } else if (minutes > 0) {
                    return String.format("%dm %ds", minutes, secs);
                } else {
                    return String.format("%ds", secs);
                }
            case SHORT:
                if (hours > 0) {
                    return String.format("%d:%02d:%02d", hours, minutes, secs);
                } else {
                    return String.format("%02d:%02d", minutes, secs);
                }
            case FULL:
            default:
                return String.format("%02d:%02d:%02d", hours, minutes, secs);
        }
    }
    
    /**
     * Kiểm tra xem booster có sắp hết hạn không
     */
    public boolean isExpiringSoon(long thresholdSeconds) {
        return getTimeLeftSeconds() <= thresholdSeconds;
    }
    
    /**
     * Tạo description cho booster
     */
    public String getDescription() {
        return String.format("%s Booster x%.1f (%s)", 
                type.getDisplayName(), 
                multiplier, 
                formatTimeLeft(TimeFormat.COMPACT));
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        Booster booster = (Booster) obj;
        return Double.compare(booster.multiplier, multiplier) == 0 &&
               endTime == booster.endTime &&
               startTime == booster.startTime &&
               type == booster.type;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(type, multiplier, startTime, endTime);
    }
    
    @Override
    public String toString() {
        return String.format("Booster{type=%s, multiplier=%.1f, remaining=%s, expired=%s}",
                type, multiplier, formatTimeLeft(TimeFormat.COMPACT), isExpired());
    }
    
    public enum TimeFormat {
        FULL,    // 01:23:45
        SHORT,   // 1:23:45 hoặc 23:45
        COMPACT  // 1h 23m hoặc 23m 45s hoặc 45s
    }
}
