package com.hugo.player.data.db

import androidx.room.*

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId", "type", "group"]),
        Index(value = ["playlistId", "type", "seriesName"]),
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val type: String,
    val playlistId: String,
    /** Só para séries: nome da série (sem SxxEyy), temporada e episódio. */
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Ao vivo: tvg-id / epg_channel_id (para casar com o EPG). */
    val epgId: String? = null,
)

/** Categoria por playlist + tipo (o mesmo nome pode existir em live e filmes). */
@Entity(tableName = "categories", primaryKeys = ["playlistId", "type", "name"], indices = [Index(value = ["playlistId", "type"])])
data class CategoryEntity(
    val name: String,
    val count: Int,
    val type: String,
    val playlistId: String,
    /** Ordem original da lista. */
    val position: Int = 0,
)

/** Nome + tvg-id dos canais ao vivo (filtro do EPG baixado no aparelho). */
data class LiveEpgKey(val name: String, val epgId: String?)

/** Série agrupada (resultado de GROUP BY seriesName). */
data class SeriesSummary(
    val seriesName: String,
    val logo: String?,
    val episodes: Int,
    val seasons: Int,
)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = :type ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun getByType(playlistId: String, type: String, limit: Int, offset: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = :type AND `group` = :group ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun getByTypeAndGroup(playlistId: String, type: String, group: String, limit: Int, offset: Int): List<ChannelEntity>

    /** Página de itens com categoria e busca opcionais. Ao vivo mantém a ordem da lista (id). */
    @Query(
        """SELECT * FROM channels WHERE playlistId = :playlistId AND type = :type
           AND (:group IS NULL OR `group` = :group)
           AND (:query IS NULL OR name LIKE '%' || :query || '%')
           ORDER BY CASE WHEN :keepOrder THEN id END, name COLLATE NOCASE, id
           LIMIT :limit OFFSET :offset"""
    )
    suspend fun page(playlistId: String, type: String, group: String?, query: String?, keepOrder: Boolean, limit: Int, offset: Int): List<ChannelEntity>

    @Query(
        """SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND type = :type
           AND (:group IS NULL OR `group` = :group)
           AND (:query IS NULL OR name LIKE '%' || :query || '%')"""
    )
    suspend fun countFiltered(playlistId: String, type: String, group: String?, query: String?): Int

    @Query(
        """SELECT COALESCE(seriesName, name) AS seriesName, MAX(logo) AS logo, COUNT(*) AS episodes, COUNT(DISTINCT season) AS seasons
           FROM channels WHERE playlistId = :playlistId AND type = 'series'
           AND (:group IS NULL OR `group` = :group)
           AND (:query IS NULL OR seriesName LIKE '%' || :query || '%')
           GROUP BY seriesName ORDER BY seriesName COLLATE NOCASE LIMIT :limit OFFSET :offset"""
    )
    suspend fun seriesPage(playlistId: String, group: String?, query: String?, limit: Int, offset: Int): List<SeriesSummary>

    @Query(
        """SELECT COUNT(DISTINCT COALESCE(seriesName, name)) FROM channels WHERE playlistId = :playlistId AND type = 'series'
           AND (:group IS NULL OR `group` = :group)
           AND (:query IS NULL OR seriesName LIKE '%' || :query || '%')"""
    )
    suspend fun seriesCount(playlistId: String, group: String?, query: String?): Int

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = 'series' AND seriesName = :seriesName ORDER BY season, episode, name")
    suspend fun episodes(playlistId: String, seriesName: String): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND type = :type")
    suspend fun countByType(playlistId: String, type: String): Int

    @Query("SELECT name, epgId FROM channels WHERE playlistId = :playlistId AND type = 'live'")
    suspend fun liveEpgKeys(playlistId: String): List<LiveEpgKey>

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND type = :type ORDER BY position, name")
    suspend fun getCategories(playlistId: String, type: String): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannels(playlistId: String)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId")
    suspend fun deleteCategories(playlistId: String)
}

@Database(entities = [ChannelEntity::class, CategoryEntity::class], version = 3, exportSchema = false)
abstract class HugoDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile private var INSTANCE: HugoDatabase? = null
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN epgId TEXT")
            }
        }
        fun get(context: android.content.Context): HugoDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, HugoDatabase::class.java, "hugo.db")
                    // 2 -> 3 só acrescenta a coluna do EPG: listas já baixadas continuam (sem reimportar)
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
