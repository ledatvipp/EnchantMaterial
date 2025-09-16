package org.ledat.enchantMaterial;

import java.util.UUID;

public class PlayerData {
    private UUID uuid;
    private int level;
    private double points;

    // Constructor
    public PlayerData(UUID uuid, int level, double points) {
        this.uuid = uuid;
        this.level = level;
        this.points = points;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getLevel() {
        return level;
    }

    public double getPoints() {
        return points;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public void setPoints(double points) {
        this.points = points;
    }
}
