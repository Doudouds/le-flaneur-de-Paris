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
    @Test fun catalogCacheSkipsUnchangedImportAndPreservesProgressOnRefresh() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val preferences = context.getSharedPreferences("catalog_import", android.content.Context.MODE_PRIVATE)
        val previousFingerprint = preferences.getString("fingerprint", null)
        try {
            val repository = fr.parisfogofwar.data.repository.RoomMonumentRepository(
                context, db.monumentDao(), fr.parisfogofwar.domain.model.GameArea(),
            )
            // An empty database must be imported even if preferences survived a restore.
            preferences.edit().putString("fingerprint", "1:" + BuildConfig.CATALOG_FINGERPRINT).commit()
            repository.ensureCatalogImported()
            val original = db.monumentDao().observeAll().first().first()
            val modified = original.copy(description = "Cache test", imageUrl = "cached-image", imageLicense = "CC0")
            db.monumentDao().insertAll(listOf(modified))
            db.discoveredCellDao().insert(DiscoveredCellEntity(original.gridX, original.gridY, 123L))

            // Use another repository instance to simulate a new process opening the database.
            val reopened = fr.parisfogofwar.data.repository.RoomMonumentRepository(
                context, db.monumentDao(), fr.parisfogofwar.domain.model.GameArea(),
            )
            reopened.ensureCatalogImported()
            assertEquals("Cache test", db.monumentDao().findById(original.id)?.description)

            preferences.edit().putString("fingerprint", "old-catalog").commit()
            reopened.ensureCatalogImported()
            val refreshed = db.monumentDao().findById(original.id)!!
            assertEquals(original.description, refreshed.description)
            assertEquals("cached-image", refreshed.imageUrl)
            assertEquals("CC0", refreshed.imageLicense)
            assertEquals(123L, db.discoveredCellDao().observeAllOnce())
        } finally {
            preferences.edit().putString("fingerprint", previousFingerprint).commit()
            db.close()
        }
    }

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
