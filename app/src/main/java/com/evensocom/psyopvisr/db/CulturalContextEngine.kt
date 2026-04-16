package com.evensocom.psyopvisr.db

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "cultural_context")
data class CulturalContext(
    @PrimaryKey val label: String,
    val contextText: String,
    val alertLevel: Int // 0: Info, 1: Caution, 2: Critical
)

@Dao
interface CulturalContextDao {
    @Query("SELECT * FROM cultural_context WHERE label = :label LIMIT 1")
    suspend fun getContextFor(label: String): CulturalContext?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContext(context: CulturalContext)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(contexts: List<CulturalContext>)
}

@Database(entities = [CulturalContext::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun culturalContextDao(): CulturalContextDao
}

/**
 * Pre-seeded tactical labels mapped to cultural context strings and alert levels.
 *
 * Alert levels:
 *   0 = Info     — no immediate threat indicator
 *   1 = Caution  — elevated awareness required
 *   2 = Critical — immediate threat
 */
private val TACTICAL_SEED_DATA = listOf(
    CulturalContext("person",     "Civilian presence detected",         0),
    CulturalContext("car",        "Vehicle — possible mobile asset",    0),
    CulturalContext("truck",      "Large vehicle — potential transport", 1),
    CulturalContext("bus",        "Mass transport vehicle",              0),
    CulturalContext("motorcycle", "Two-wheel vehicle — fast mover",     1),
    CulturalContext("backpack",   "Unattended bag — IED risk",          1),
    CulturalContext("suitcase",   "Unattended luggage — IED risk",      1),
    CulturalContext("weapon",     "Weapon detected — CRITICAL THREAT",  2),
    CulturalContext("knife",      "Edged weapon — close-range threat",  2),
    CulturalContext("scissors",   "Edged implement detected",            1),
    CulturalContext("cell phone", "Comms device — possible trigger",    1),
    CulturalContext("laptop",     "Electronic device present",           0),
    CulturalContext("bottle",     "Possible IED vessel",                 1),
    CulturalContext("dog",        "Animal — possible guard animal",      0),
    CulturalContext("bicycle",    "Non-motorised vehicle",               0),
    CulturalContext("umbrella",   "Object obscuring hands",              1),
    CulturalContext("handbag",    "Carry item — verify contents",        0),
    CulturalContext("tie",        "Formal dress — possible official",    0),
    CulturalContext("hat",        "Head covering — partial ID obscured", 0),
)

class CulturalContextEngine(context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "tactical-context-db"
    )
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                super.onCreate(db)
                // Seed is written via the DAO in a coroutine after the DB is first created.
                // We schedule it on IO so it never blocks the calling thread.
            }
        })
        .fallbackToDestructiveMigration()
        .build()

    private val dao = db.culturalContextDao()

    /**
     * Must be called once after the database is first opened (e.g. from the service onCreate)
     * to populate the seed data. Subsequent calls are no-ops due to IGNORE conflict strategy.
     */
    suspend fun seedIfNeeded() = withContext(Dispatchers.IO) {
        dao.insertAll(TACTICAL_SEED_DATA)
    }

    /**
     * Returns the cultural context string for the given detection label, or null if unknown.
     * Always safe to call from a coroutine — internally dispatches to IO.
     */
    @WorkerThread
    suspend fun getContextFor(label: String): String? = withContext(Dispatchers.IO) {
        dao.getContextFor(label.lowercase().trim())?.contextText
    }

    /**
     * Returns the full [CulturalContext] entry (text + alertLevel) for the given label,
     * or null if the label is not in the database.
     */
    @WorkerThread
    suspend fun getContextFullEntry(label: String): CulturalContext? = withContext(Dispatchers.IO) {
        dao.getContextFor(label.lowercase().trim())
    }
}
