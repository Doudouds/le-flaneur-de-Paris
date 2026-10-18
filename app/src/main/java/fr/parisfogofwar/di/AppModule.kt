package fr.parisfogofwar.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.parisfogofwar.data.local.AppDatabase
import fr.parisfogofwar.data.repository.RoomExplorationRepository
import fr.parisfogofwar.data.repository.RoomMonumentRepository
import fr.parisfogofwar.domain.model.GameArea
import fr.parisfogofwar.domain.repository.ExplorationRepository
import fr.parisfogofwar.domain.repository.MonumentRepository
import fr.parisfogofwar.domain.usecase.*
import fr.parisfogofwar.location.FusedLocationRepository
import fr.parisfogofwar.map.MapLibreOfflineMapRepository
import fr.parisfogofwar.map.OfflineMapRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    private val gridMigration100mTo200m = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE discovered_cells_v2 (
                    gridX INTEGER NOT NULL,
                    gridY INTEGER NOT NULL,
                    discoveredAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(gridX, gridY)
                )
            """.trimIndent())
            database.execSQL("""
                INSERT INTO discovered_cells_v2(gridX, gridY, discoveredAtEpochMillis)
                SELECT gridX / 2, gridY / 2, MIN(discoveredAtEpochMillis)
                FROM discovered_cells
                GROUP BY gridX / 2, gridY / 2
            """.trimIndent())
            database.execSQL("DROP TABLE discovered_cells")
            database.execSQL("ALTER TABLE discovered_cells_v2 RENAME TO discovered_cells")
            database.execSQL("UPDATE monuments SET gridX = gridX / 2, gridY = gridY / 2")
        }
    }

    private val imageMetadataMigration = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE monuments ADD COLUMN imagePageUrl TEXT")
            database.execSQL("ALTER TABLE monuments ADD COLUMN imageAuthor TEXT")
            database.execSQL("ALTER TABLE monuments ADD COLUMN imageLicense TEXT")
            database.execSQL("ALTER TABLE monuments ADD COLUMN imageLicenseUrl TEXT")
            database.execSQL("ALTER TABLE monuments ADD COLUMN imageSource TEXT")
            database.execSQL("ALTER TABLE monuments ADD COLUMN imageLookupCompleted INTEGER NOT NULL DEFAULT 0")
            // Older catalogues carried OSM/Google Photos URLs without reusable
            // license metadata. Do not show those links after the migration.
            database.execSQL("UPDATE monuments SET imageUrl = NULL")
        }
    }

    private val wikipediaUrlMigration = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE monuments ADD COLUMN wikipediaUrl TEXT")
        }
    }

    private val popHistoricalDescriptionMigration = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE monuments ADD COLUMN popHistoricalDescription TEXT")
        }
    }

    private val interestRatingMigration = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE monuments ADD COLUMN interestScore INTEGER")
            database.execSQL("ALTER TABLE monuments ADD COLUMN interestPercentile INTEGER")
            database.execSQL("ALTER TABLE monuments ADD COLUMN stars INTEGER")
        }
    }

    private val descriptionSourceMigration = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE monuments ADD COLUMN descriptionSource TEXT")
        }
    }

    private val historicalMetadataMigration = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE monuments ADD COLUMN historicalDates TEXT")
            database.execSQL("ALTER TABLE monuments ADD COLUMN historicalPeriod TEXT")
            database.execSQL("ALTER TABLE monuments ADD COLUMN protectionDates TEXT")
        }
    }

    private val imageVerificationMigration = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Images resolved by the previous matcher were not checked against
            // the monument name or location. Re-resolve every cached image so
            // the stricter matcher can remove stale or misassigned media.
            database.execSQL("""
                UPDATE monuments SET
                    imageUrl = NULL,
                    imagePageUrl = NULL,
                    imageAuthor = NULL,
                    imageLicense = NULL,
                    imageLicenseUrl = NULL,
                    imageSource = NULL,
                    imageLookupCompleted = 0
            """.trimIndent())
        }
    }

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "paris_fog_of_war.db")
            .addMigrations(gridMigration100mTo200m)
            .addMigrations(imageMetadataMigration)
            .addMigrations(wikipediaUrlMigration)
            .addMigrations(popHistoricalDescriptionMigration)
            .addMigrations(interestRatingMigration)
            .addMigrations(descriptionSourceMigration)
            .addMigrations(historicalMetadataMigration)
            .addMigrations(imageVerificationMigration)
            .build()

    @Provides fun provideMonumentDao(database: AppDatabase) = database.monumentDao()
    @Provides fun provideDiscoveredCellDao(database: AppDatabase) = database.discoveredCellDao()

    @Provides @Singleton
    fun provideGameArea() = GameArea()

    @Provides @Singleton
    fun provideMonumentRepository(@ApplicationContext context: Context, dao: fr.parisfogofwar.data.local.MonumentDao, area: GameArea): MonumentRepository =
        RoomMonumentRepository(context, dao, area)

    @Provides @Singleton
    fun provideExplorationRepository(dao: fr.parisfogofwar.data.local.DiscoveredCellDao): ExplorationRepository =
        RoomExplorationRepository(dao)

    @Provides fun provideObserveDiscoveredCells(repo: ExplorationRepository) = ObserveDiscoveredCellsUseCase(repo)
    @Provides fun provideDiscoverCurrentCell(repo: ExplorationRepository, area: GameArea) = DiscoverCurrentCellUseCase(repo, area)
    @Provides fun provideObserveRevealedMonuments(repo: MonumentRepository) = ObserveRevealedMonumentsUseCase(repo)
    @Provides fun provideObserveStatistics(area: GameArea, er: ExplorationRepository, mr: MonumentRepository) = ObserveExplorationStatisticsUseCase(area, er, mr)
    @Provides fun provideResetProgress(repo: ExplorationRepository) = ResetProgressUseCase(repo)

    @Provides @Singleton
    fun provideLocationRepository(repository: FusedLocationRepository): fr.parisfogofwar.domain.repository.LocationRepository = repository

    @Provides @Singleton
    fun provideOfflineMapRepository(repository: MapLibreOfflineMapRepository): OfflineMapRepository = repository
}
