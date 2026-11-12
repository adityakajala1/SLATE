package app.slate.data

import android.content.Context
import android.net.Uri
import androidx.room.*
import app.slate.core.DocFormat
import app.slate.core.io.DocIO
import app.slate.core.io.UriInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "recent")
data class RecentDoc(
    @PrimaryKey val uri: String,
    val name: String,
    val ext: String,
    val size: Long,
    val lastOpened: Long,
    /** Last page / slide / paragraph index. */
    val position: Int = 0,
    /** Total pages / slides when known (for "Page 85 of 200"). */
    val total: Int = 0,
    val thumbPath: String? = null,
) {
    val format: DocFormat? get() = DocFormat.fromName("x.$ext")
}

@Entity(tableName = "favorite")
data class Favorite(
    /** content:// URI or absolute file path. */
    @PrimaryKey val id: String,
    val name: String,
    val isDir: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
)

@Dao
interface RecentDao {
    @Query("SELECT * FROM recent ORDER BY lastOpened DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecentDoc>>

    @Query("SELECT * FROM recent WHERE uri = :uri")
    suspend fun get(uri: String): RecentDoc?

    @Upsert suspend fun upsert(doc: RecentDoc)

    @Query("UPDATE recent SET position = :pos, total = :total WHERE uri = :uri")
    suspend fun setPosition(uri: String, pos: Int, total: Int)

    @Query("UPDATE recent SET thumbPath = :path WHERE uri = :uri")
    suspend fun setThumb(uri: String, path: String?)

    @Query("UPDATE recent SET uri = :newUri, name = :name WHERE uri = :oldUri")
    suspend fun rename(oldUri: String, newUri: String, name: String)

    @Query("DELETE FROM recent WHERE uri = :uri") suspend fun delete(uri: String)
    @Query("DELETE FROM recent") suspend fun clear()
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite ORDER BY addedAt DESC") fun observe(): Flow<List<Favorite>>
    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE id = :id)") fun isFavorite(id: String): Flow<Boolean>
    @Upsert suspend fun upsert(f: Favorite)
    @Query("DELETE FROM favorite WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE favorite SET id = :newId, name = :name WHERE id = :oldId") suspend fun rename(oldId: String, newId: String, name: String)
}

@Database(entities = [RecentDoc::class, Favorite::class], version = 1, exportSchema = false)
abstract class SlateDb : RoomDatabase() {
    abstract fun recents(): RecentDao
    abstract fun favorites(): FavoriteDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, SlateDb::class.java, "slate.db")
            .fallbackToDestructiveMigration().build()
    }
}

/** Recent documents + "continue reading" positions. */
class RecentsRepository(private val context: Context, private val dao: RecentDao) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observe(limit: Int = 30) = dao.observe(limit)

    suspend fun get(uri: Uri) = dao.get(uri.toString())

    /** Record that [uri] was opened now. Safe to call from any thread. */
    fun touch(uri: Uri, info: UriInfo?) {
        io.launch {
            val i = info ?: runCatching { DocIO.info(context, uri) }.getOrNull() ?: return@launch
            val old = dao.get(uri.toString())
            val ext = DocFormat.detect(i.name, i.mime)?.ext ?: i.name.substringAfterLast('.', "")
            dao.upsert(
                RecentDoc(
                    uri = uri.toString(), name = i.name, ext = ext, size = i.size,
                    lastOpened = System.currentTimeMillis(),
                    position = old?.position ?: 0, total = old?.total ?: 0, thumbPath = old?.thumbPath,
                ),
            )
            if (old?.thumbPath == null) Thumbnails.generate(context, uri, DocFormat.detect(i.name, i.mime))?.let {
                dao.setThumb(uri.toString(), it.absolutePath)
            }
        }
    }

    fun setPosition(uri: Uri, position: Int, total: Int) {
        io.launch { dao.setPosition(uri.toString(), position, total) }
    }

    fun remove(uri: String) { io.launch { dao.delete(uri) } }
    fun clear() { io.launch { dao.clear() } }
    suspend fun rename(oldUri: String, newUri: String, name: String) = dao.rename(oldUri, newUri, name)
}

class FavoritesRepository(private val dao: FavoriteDao) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun observe() = dao.observe()
    fun isFavorite(id: String) = dao.isFavorite(id)
    fun toggle(id: String, name: String, isDir: Boolean, currently: Boolean) {
        io.launch { if (currently) dao.delete(id) else dao.upsert(Favorite(id, name, isDir)) }
    }
    fun remove(id: String) { io.launch { dao.delete(id) } }
    suspend fun rename(oldId: String, newId: String, name: String) = dao.rename(oldId, newId, name)
}
