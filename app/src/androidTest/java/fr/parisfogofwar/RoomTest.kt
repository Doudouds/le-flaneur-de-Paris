package fr.parisfogofwar

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.parisfogofwar.data.local.AppDatabase
import fr.parisfogofwar.data.local.DiscoveredCellEntity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTest {
    @Test fun insertingSameCellKeepsFirstDiscovery() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), AppDatabase::class.java).build()
        val dao = db.discoveredCellDao()
        assertEquals(1L, dao.insert(DiscoveredCellEntity(1, 2, 100)))
        assertEquals(-1L, dao.insert(DiscoveredCellEntity(1, 2, 200)))
        assertEquals(100L, dao.observeAllOnce())
        db.close()
    }

    private suspend fun fr.parisfogofwar.data.local.DiscoveredCellDao.observeAllOnce(): Long = observeAll().let { flow ->
        flow.first().single().discoveredAtEpochMillis
    }
}
