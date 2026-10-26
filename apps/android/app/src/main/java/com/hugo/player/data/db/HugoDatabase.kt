package com.hugo.player.data.db

import androidx.room.*

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId", "type", "group"]),
        Index(value = ["playlistId", "type", "seriesName"]),
        // achar o gêmeo dublado/legendado ("Nome [L] (Ano)") rápido
        Index(value = ["playlistId", "type", "name"]),
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

data class GroupCount(val name: String, val count: Int)

/** Série legendada ("Nome [L] (Ano)") que tem a versão dublada na mesma playlist. */
@Entity(tableName = "series_twins", primaryKeys = ["playlistId", "legName"])
data class SeriesTwinEntity(val playlistId: String, val legName: String, val dubName: String)

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
           AND (:dedupe = 0 OR instr(name, ' [L]') = 0 OR NOT EXISTS (
               SELECT 1 FROM channels d WHERE d.playlistId = channels.playlistId AND d.type = channels.type
               AND d.name = replace(channels.name, ' [L]', '')
               AND (:group IS NULL OR d.`group` = :group)
               AND (:query IS NULL OR d.name LIKE '%' || :query || '%')))
           AND `group` NOT IN (:hidden)
           ORDER BY CASE WHEN :keepOrder THEN id END, name COLLATE NOCASE, id
           LIMIT :limit OFFSET :offset"""
    )
    suspend fun page(playlistId: String, type: String, group: String?, query: String?, keepOrder: Boolean, limit: Int, offset: Int, dedupe: Boolean, hidden: List<String>): List<ChannelEntity>

    @Query(
        """SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND type = :type
           AND (:group IS NULL OR `group` = :group)
           AND (:query IS NULL OR name LIKE '%' || :query || '%')
           AND (:dedupe = 0 OR instr(name, ' [L]') = 0 OR NOT EXISTS (
               SELECT 1 FROM channels d WHERE d.playlistId = channels.playlistId AND d.type = channels.type
               AND d.name = replace(channels.name, ' [L]', '')
               AND (:group IS NULL OR d.`group` = :group)
               AND (:query IS NULL OR d.name LIKE '%' || :query || '%')))
           AND `group` NOT IN (:hidden)"""
    )
    suspend fun countFiltered(playlistId: String, type: String, group: String?, query: String?, dedupe: Boolean, hidden: List<String>): Int

    /** Versões legendadas cujo nome exato está na lista (para juntar ao card dublado). */
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = :type AND name IN (:names)")
    suspend fun byNames(playlistId: String, type: String, names: List<String>): List<ChannelEntity>

    /** Contagem por categoria já sem o legendado que tem gêmeo dublado na mesma categoria. */
    @Query(
        """SELECT `group` AS name, COUNT(*) AS count FROM channels c WHERE c.playlistId = :playlistId AND c.type = :type
           AND (instr(c.name, ' [L]') = 0 OR NOT EXISTS (SELECT 1 FROM channels d WHERE d.playlistId = c.playlistId AND d.type = c.type
               AND d.name = replace(c.name, ' [L]', '') AND d.`group` = c.`group`))
           GROUP BY `group`"""
    )
    suspend fun mergedGroupCounts(playlistId: String, type: String): List<GroupCount>

    @Query(
        """SELECT COALESCE(seriesName, name) AS seriesName, MAX(logo) AS logo, COUNT(*) AS episodes, COUNT(DISTINCT season) AS seasons
           FROM channels WHERE playlistId = :playlistId AND type = 'series'
           AND (:group IS NULL OR `group` = :group)
           AND (:query IS NULL OR seriesName LIKE '%' || :query || '%')
           AND `group` NOT IN (:hidden)
           AND NOT EXISTS (SELECT 1 FROM series_twins t WHERE t.playlistId = :playlistId AND t.legName = channels.seriesName
               AND EXISTS (SELECT 1 FROM channels d WHERE d.playlistId = :playlistId AND d.type = 'series' AND d.seriesName = t.dubName
                   AND (:group IS NULL OR d.`group` = :group)
                   AND (:query IS NULL OR d.seriesName LIKE '%' || :query || '%')))
           GROUP BY seriesName ORDER BY seriesName COLLATE NOCASE LIMIT :limit OFFSET :offset"""
    )
    suspend fun seriesPage(playlistId: String, group: String?, query: String?, limit: Int, offset: Int, hidden: List<String>): List<SeriesSummary>

    @Query(
        """SELECT COUNT(DISTINCT COALESCE(seriesName, name)) FROM channels WHERE playlistId = :playlistId AND type = 'series'
           AND (:group IS NULL OR `group` = :group)
           AND (:query IS NULL OR seriesName LIKE '%' || :query || '%')
           AND `group` NOT IN (:hidden)
           AND NOT EXISTS (SELECT 1 FROM series_twins t WHERE t.playlistId = :playlistId AND t.legName = channels.seriesName
               AND EXISTS (SELECT 1 FROM channels d WHERE d.playlistId = :playlistId AND d.type = 'series' AND d.seriesName = t.dubName
                   AND (:group IS NULL OR d.`group` = :group)
                   AND (:query IS NULL OR d.seriesName LIKE '%' || :query || '%')))"""
    )
    suspend fun seriesCount(playlistId: String, group: String?, query: String?, hidden: List<String>): Int

    @Query("SELECT DISTINCT seriesName FROM channels WHERE playlistId = :playlistId AND type = 'series' AND seriesName IS NOT NULL")
    suspend fun seriesNames(playlistId: String): List<String>

    @Query("DELETE FROM series_twins WHERE playlistId = :playlistId")
    suspend fun deleteSeriesTwins(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesTwins(rows: List<SeriesTwinEntity>)

    @Query("SELECT * FROM series_twins WHERE playlistId = :playlistId AND (dubName = :name OR legName = :name) LIMIT 1")
    suspend fun seriesTwinOf(playlistId: String, name: String): SeriesTwinEntity?

    @Query("SELECT * FROM series_twins WHERE playlistId = :playlistId AND dubName IN (:names)")
    suspend fun seriesTwinsForDubs(playlistId: String, names: List<String>): List<SeriesTwinEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = 'series' AND seriesName = :seriesName ORDER BY season, episode, name")
    suspend fun episodes(playlistId: String, seriesName: String): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND type = :type")
    suspend fun countByType(playlistId: String, type: String): Int

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = 'live' AND url IN (:urls)")
    suspend fun liveByUrls(playlistId: String, urls: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND url IN (:urls)")
    suspend fun byUrls(playlistId: String, urls: List<String>): List<ChannelEntity>

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

@Database(entities = [ChannelEntity::class, CategoryEntity::class, SeriesTwinEntity::class], version = 5, exportSchema = false)
abstract class HugoDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    companion object {
        const val FILE_NAME = "hugo.db"
        @Volatile private var INSTANCE: HugoDatabase? = null
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN epgId TEXT")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_playlistId_type_name` ON `channels` (`playlistId`, `type`, `name`)")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `series_twins` (`playlistId` TEXT NOT NULL, `legName` TEXT NOT NULL, `dubName` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `legName`))")
            }
        }
        fun get(context: android.content.Context): HugoDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, HugoDatabase::class.java, FILE_NAME)
                    // 2 -> 3 só acrescenta a coluna do EPG: listas já baixadas continuam (sem reimportar)
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
