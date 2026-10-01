package com.hearth.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.hearth.core.HearthJson
import com.hearth.core.SyncRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/** Local mirror of the family's sync records; `dirty` = edited here and not yet acknowledged by the hub. */
@Entity(tableName = "records", primaryKeys = ["entity", "id"])
data class RecordEntity(
    val entity: String,
    val id: String,
    val hlc: String,
    val deleted: Boolean,
    val author: String,
    val payload: String,
    val dirty: Boolean,
)

fun RecordEntity.toRecord(): SyncRecord =
    SyncRecord(entity, id, hlc, deleted, author, HearthJson.parseToJsonElement(payload))

fun SyncRecord.toEntity(dirty: Boolean): RecordEntity =
    RecordEntity(entity, id, hlc, deleted, author, HearthJson.encodeToString(JsonElement.serializer(), payload), dirty)

@Dao
interface RecordDao {
    @Query("SELECT * FROM records")
    fun observeAll(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records")
    fun all(): List<RecordEntity>

    @Query("SELECT * FROM records WHERE entity = :entity AND id = :id")
    fun get(entity: String, id: String): RecordEntity?

    @Query("SELECT * FROM records WHERE dirty = 1")
    fun dirty(): List<RecordEntity>

    @Upsert
    fun upsert(record: RecordEntity)

    @Query("UPDATE records SET dirty = 0 WHERE entity = :entity AND id = :id AND hlc = :hlc")
    fun markClean(entity: String, id: String, hlc: String)

    @Query("DELETE FROM records")
    fun clear()
}

@Database(entities = [RecordEntity::class], version = 1, exportSchema = false)
abstract class HearthDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
}
