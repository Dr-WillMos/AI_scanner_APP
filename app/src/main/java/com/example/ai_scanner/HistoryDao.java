package com.example.ai_scanner;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(HistoryEntity entity);

    @Query("SELECT * FROM history ORDER BY id DESC LIMIT :limit OFFSET :offset")
    List<HistoryEntity> getPagedHistory(int limit, int offset);

    @Query("SELECT COUNT(*) FROM history")
    int getCount();

    @Query("SELECT MAX(id) FROM history")
    Long getLatestId();

    @Query("DELETE FROM history")
    void deleteAll();

    @Query("SELECT * FROM history WHERE upload_status = :status ORDER BY id DESC")
    List<HistoryEntity> getByUploadStatus(String status);

    @Query("UPDATE history SET upload_status = :status, error_message = :errorMessage, "
         + "retry_count = :retryCount, risk_level = COALESCE(:riskLevel, risk_level), "
         + "score = CASE WHEN :score IS NOT NULL THEN :score ELSE score END, "
         + "reason = COALESCE(:reason, reason), source = COALESCE(:source, source), "
         + "transcription = COALESCE(:transcription, transcription) "
         + "WHERE id = :id")
    void updateUploadResult(long id, String status, String errorMessage, int retryCount,
                            String riskLevel, Double score, String reason,
                            String source, String transcription);

    @Query("SELECT * FROM history WHERE upload_status = 'FAILED' OR upload_status = 'UPLOADING' ORDER BY id ASC")
    List<HistoryEntity> getPendingOrFailed();
}
