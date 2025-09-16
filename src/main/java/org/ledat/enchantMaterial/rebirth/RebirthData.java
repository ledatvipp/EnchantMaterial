package org.ledat.enchantMaterial.rebirth;

import java.util.UUID;

public class RebirthData {
    private final UUID uuid;
    private int rebirthLevel;
    private long lastRebirthTime;
    
    public RebirthData(UUID uuid, int rebirthLevel, long lastRebirthTime) {
        this.uuid = uuid;
        this.rebirthLevel = rebirthLevel;
        this.lastRebirthTime = lastRebirthTime;
    }
    
    // Getters and Setters
    public UUID getUuid() { return uuid; }
    public int getRebirthLevel() { return rebirthLevel; }
    public void setRebirthLevel(int rebirthLevel) { this.rebirthLevel = rebirthLevel; }
    public long getLastRebirthTime() { return lastRebirthTime; }
    public void setLastRebirthTime(long lastRebirthTime) { this.lastRebirthTime = lastRebirthTime; }
}