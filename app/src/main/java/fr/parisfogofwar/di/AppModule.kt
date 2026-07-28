package fr.parisfogofwar.di

import android.content.Context
import androidx.room.Room
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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "paris_fog_of_war.db").build()

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
}
