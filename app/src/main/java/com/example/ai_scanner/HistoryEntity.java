package com.example.ai_scanner;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "history")
public class HistoryEntity {

    @PrimaryKey
    public long id;

    @ColumnInfo(name = "device_id")
    public String deviceId;

    @ColumnInfo(name = "author_id")
    public String authorId;

    @ColumnInfo(name = "risk_level")
    public String riskLevel;

    public double score;

    @ColumnInfo(name = "created_at")
    public String createdAt;

    public String reason;

    public String source;

    public String transcription;

    @ColumnInfo(name = "request_id")
    public String requestId;

    @ColumnInfo(name = "upload_status")
    public String uploadStatus;

    @ColumnInfo(name = "error_message")
    public String errorMessage;

    @ColumnInfo(name = "retry_count")
    public int retryCount;

    @ColumnInfo(name = "video_uri")
    public String videoUri;

    public HistoryEntity() {
    }

    @Ignore
    public HistoryEntity(long id, String deviceId, String authorId, String riskLevel,
                         double score, String createdAt, String reason, String source,
                         String transcription) {
        this.id = id;
        this.deviceId = deviceId;
        this.authorId = authorId;
        this.riskLevel = riskLevel;
        this.score = score;
        this.createdAt = createdAt;
        this.reason = reason;
        this.source = source;
        this.transcription = transcription;
    }

    public HistoryItem toHistoryItem() {
        return new HistoryItem(id, deviceId, authorId, riskLevel, score, createdAt);
    }
}
