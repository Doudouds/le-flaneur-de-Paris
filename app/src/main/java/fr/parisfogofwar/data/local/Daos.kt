package fr.parisfogofwar.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MonumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(monuments: List<MonumentEntity>)

    @Query("SELECT COUNT(*) FROM monuments")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM monuments")
    fun observeCount(): Flow<Int>

    @Query("SELECT m.*, c.discoveredAtEpochMillis FROM monuments m INNER JOIN discovered_cells c ON c.gridX = m.gridX AND c.gridY = m.gridY ORDER BY c.discoveredAtEpochMillis, m.name COLLATE NOCASE")
    fun observeRevealed(): Flow<List<RevealedMonumentRow>>

    @Query("SELECT * FROM monuments")
    fun observeAll(): Flow<List<MonumentEntity>>

    @Query("SELECT * FROM monuments WHERE id = :monumentId LIMIT 1")
    suspend fun findById(monumentId: String): MonumentEntity?

    @Query("SELECT DISTINCT gridX, gridY FROM monuments")
    fun observeMonumentCells(): Flow<List<MonumentCellRow>>

    @Query("""
        UPDATE monuments SET
            imageUrl = :imageUrl,
            imagePageUrl = :imagePageUrl,
            imageAuthor = :imageAuthor,
            imageLicense = :imageLicense,
            imageLicenseUrl = :imageLicenseUrl,
            imageSource = :imageSource,
            imageLookupCompleted = 1
        WHERE id = :monumentId
    """)
    suspend fun updateImage(
        monumentId: String,
        imageUrl: String?,
        imagePageUrl: String?,
        imageAuthor: String?,
        imageLicense: String?,
        imageLicenseUrl: String?,
        imageSource: String?,
    )
}

@Dao
interface DiscoveredCellDao {
    @Query("SELECT * FROM discovered_cells ORDER BY discoveredAtEpochMillis")
    fun observeAll(): Flow<List<DiscoveredCellEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cell: DiscoveredCellEntity): Long

    @Query("DELETE FROM discovered_cells")
    suspend fun deleteAll()
}
