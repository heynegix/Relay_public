package com.example.relay.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Public-only signed regional trust state. The document never contains a regional private key.
 * The enclosing Relay database is SQLCipher protected; integrity is additionally bound by
 * [directoryDigest] and re-verified against the bundled public root before every use.
 */
@Entity(
    tableName = "regional_shelter_directories",
    primaryKeys = ["regionId"],
    indices = [Index("generation")],
)
data class RegionalDirectoryEntity(
    val regionId: String,
    val generation: Long,
    val directoryDigest: String,
    val directoryJson: String,
    val acceptedAtEpochMillis: Long,
)

@Dao
interface RegionalDirectoryDao {
    @Query("SELECT * FROM regional_shelter_directories WHERE regionId = :regionId")
    fun find(regionId: String): RegionalDirectoryEntity?

    @Query("SELECT * FROM regional_shelter_directories ORDER BY regionId")
    fun all(): List<RegionalDirectoryEntity>

    /** Called only inside a Room transaction after signature and rollback checks succeed. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun replace(entity: RegionalDirectoryEntity): Long
}
