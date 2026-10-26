package com.fx.recorder.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: SessionEntity)

    @Update
    suspend fun update(s: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE startedAt >= :sinceMs AND status != 'discarded'")
    fun observeSince(sinceMs: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE status = 'sealed' AND endedAt < :threshold ORDER BY endedAt ASC")
    suspend fun expiredSealed(threshold: Long): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE status = 'sealed' ORDER BY endedAt ASC")
    suspend fun sealedOldestFirst(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE status = 'recording'")
    suspend fun recordingSessions(): List<SessionEntity>

    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE sessions SET ackedAt = :ts WHERE id = :id AND status = 'sealed'")
    suspend fun markAcked(id: String, ts: Long)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(e: EventEntity)

    @Query("SELECT * FROM events ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE ts < :threshold")
    suspend fun purgeOlderThan(threshold: Long)
}

@Dao
interface TopicDao {
    @Insert
    suspend fun insert(t: TopicEntity)

    @Update
    suspend fun update(t: TopicEntity)

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun byId(id: String): TopicEntity?

    @Query("SELECT * FROM topics WHERE status = 'open' ORDER BY startMs DESC LIMIT 1")
    suspend fun openTopic(): TopicEntity?

    @Query("SELECT * FROM topics WHERE status = 'open'")
    suspend fun openTopics(): List<TopicEntity>

    @Query("SELECT * FROM topics ORDER BY startMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE status IN ('closed','low_value') ORDER BY startMs ASC")
    suspend fun closedOldestFirst(): List<TopicEntity>

    @Query("SELECT COUNT(*) FROM topics")
    suspend fun count(): Int
}

@Dao
interface MeetingDao {
    @Insert
    suspend fun insert(m: MeetingEntity)

    @Update
    suspend fun update(m: MeetingEntity)

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun byId(id: String): MeetingEntity?

    @Query("SELECT * FROM meetings WHERE status = 'open' ORDER BY startMs DESC LIMIT 1")
    suspend fun openMeeting(): MeetingEntity?

    @Query("SELECT * FROM meetings ORDER BY startMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE endMs IS NOT NULL ORDER BY startMs ASC")
    suspend fun closedOldestFirst(): List<MeetingEntity>
}
