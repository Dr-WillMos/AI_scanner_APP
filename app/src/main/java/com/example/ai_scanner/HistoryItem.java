package com.example.ai_scanner;

public class HistoryItem {
    public final long id;
    public final String deviceId;
    public final String authorId;
    public final String riskLevel;
    public final double score;
    public final String createdAt;

    public HistoryItem(long id, String deviceId, String authorId, String riskLevel,
                       double score, String createdAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.authorId = authorId;
        this.riskLevel = riskLevel;
        this.score = score;
        this.createdAt = createdAt;
    }
}
