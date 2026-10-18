package fr.parisfogofwar.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MonumentEntity::class, DiscoveredCellEntity::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun monumentDao(): MonumentDao
    abstract fun discoveredCellDao(): DiscoveredCellDao
}
