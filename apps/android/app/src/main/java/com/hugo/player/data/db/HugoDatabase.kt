package com.hugo.player.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channels", indices = [Index("type"), Index("group")])
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val type: String,
    val playlistId: String
)

@Entity(tableName = "categories", indices = [Index("type")])
data class CategoryEntity(
    @PrimaryKey val name: String,
    val count: Int,
    val type: String,
    val playlistId: String
)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = :type ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun getByType(playlistId: String, type: String, limit: Int, offset: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = :type AND `group` = :group ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun getByTypeAndGroup(playlistId: String, type: String, group: String, limit: Int, offset: Int): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND type = :type")
    suspend fun countByType(playlistId: String, type: String): Int

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND type = :type ORDER BY count DESC")
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

@Database(entities = [ChannelEntity::class, CategoryEntity::class], version = 1, exportSchema = false)
abstract class HugoDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile private var INSTANCE: HugoDatabase? = null
        fun get(context: android.content.Context): HugoDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context, HugoDatabase::class.java, "hugo.db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
