package fr.parisfogofwar.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "monuments",
    indices = [Index(value = ["gridX", "gridY"]), Index(value = ["name"])]
)
data class MonumentEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val category: String?,
    val description: String?,
    val imageUrl: String?,
    val merimeeReference: String?,
    val osmId: String?,
    val gridX: Long,
    val gridY: Long,
)

@Entity(tableName = "discovered_cells", primaryKeys = ["gridX", "gridY"])
data class DiscoveredCellEntity(
    val gridX: Long,
    val gridY: Long,
    val discoveredAtEpochMillis: Long,
)

data class MonumentCellRow(
    val gridX: Long,
    val gridY: Long,
)

data class RevealedMonumentRow(
    @Embedded val monument: MonumentEntity,
    val discoveredAtEpochMillis: Long,
)
