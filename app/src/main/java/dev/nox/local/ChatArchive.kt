package dev.nox.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Local chat storage. All calls are on the service main thread; each mutation is atomic.
 * The keyguard assistant deliberately has no reference to this store. */
internal class ChatArchive(context: Context) : SQLiteOpenHelper(context, "nox_chats.db", null, 2) {
    data class ThreadInfo(val id: String, val title: String, val updated: Long)
    companion object {
        const val NEW_TITLE = "Новый чат"
        const val PAGE = 120
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY, title TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, parent_chat_id TEXT, fork_message_id INTEGER)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE, is_user INTEGER NOT NULL, text TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'other')")
        db.execSQL("CREATE INDEX messages_chat ON messages(chat_id, id)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'other'")
        db.execSQL("ALTER TABLE chats ADD COLUMN parent_chat_id TEXT")
        db.execSQL("ALTER TABLE chats ADD COLUMN fork_message_id INTEGER")
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val value = block(db)
            db.setTransactionSuccessful()
            return value
        } finally { db.endTransaction() }
    }
    private fun active(db: SQLiteDatabase): String? =
        db.rawQuery("SELECT value FROM meta WHERE key='active'", null).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun select(db: SQLiteDatabase, id: String) {
        check(db.rawQuery("SELECT id FROM chats WHERE id=?", arrayOf(id)).use { it.moveToFirst() })
        db.insertWithOnConflict("meta", null, ContentValues().apply {
            put("key", "active"); put("value", id)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it >= 0) }
    }
    private fun create(db: SQLiteDatabase): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.insertOrThrow("chats", null, ContentValues().apply {
            put("id", id); put("title", NEW_TITLE); put("created", now); put("updated", now)
        })
        select(db, id)
        return id
    }
    private fun append(db: SQLiteDatabase, id: String, turn: NoxService.Turn): NoxService.Turn {
        val now = System.currentTimeMillis()
        val row = db.insertOrThrow("messages", null, ContentValues().apply {
            put("chat_id", id); put("is_user", if (turn.user) 1 else 0)
            put("text", turn.text); put("state", turn.state); put("created", now); put("kind", turn.kind)
        })
        db.update("chats", ContentValues().apply { put("updated", now) }, "id=?", arrayOf(id))
        if (turn.user) {
            val title = turn.text.replace(Regex("\\s+"), " ").trim().take(55).ifBlank { NEW_TITLE }
            db.update("chats", ContentValues().apply { put("title", title) },
                "id=? AND title=?", arrayOf(id, NEW_TITLE))
        }
        return turn.copy(id = row)
    }
    /** Legacy history is read only if the DB is empty. The old preference is removed by the
     * caller only after this transaction commits. A crash cannot duplicate migrated messages. */
    fun initialize(legacy: () -> List<NoxService.Turn>): String = transaction { db ->
        val existing = active(db)
        if (existing != null) {
            check(db.rawQuery("SELECT id FROM chats WHERE id=?", arrayOf(existing)).use { it.moveToFirst() })
            existing
        } else {
            check(db.rawQuery("SELECT COUNT(*) FROM chats", null).use { it.moveToFirst(); it.getInt(0) == 0 })
            val id = create(db)
            legacy().forEach { append(db, id, it) }
            id
        }
    }
    fun append(id: String, turn: NoxService.Turn): NoxService.Turn = transaction { db ->
        check(active(db) == id)
        append(db, id, turn)
    }
    fun messages(id: String, limit: Int): List<NoxService.Turn> =
        readableDatabase.rawQuery("SELECT id,is_user,text,state,kind FROM messages WHERE chat_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(id, limit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(NoxService.Turn(cursor.getInt(1) == 1, cursor.getString(2),
                    cursor.getLong(0), cursor.getString(3), cursor.getString(4)))
            }.asReversed()
        }
    fun count(id: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE chat_id=?", arrayOf(id)).use {
        it.moveToFirst(); it.getInt(0)
    }
    fun title(id: String): String = readableDatabase.rawQuery("SELECT title FROM chats WHERE id=?", arrayOf(id)).use {
        check(it.moveToFirst()); it.getString(0)
    }
    fun threads(): List<ThreadInfo> = readableDatabase.rawQuery("SELECT id,title,updated FROM chats ORDER BY updated DESC,created DESC", null).use {
        buildList { while (it.moveToNext()) add(ThreadInfo(it.getString(0), it.getString(1), it.getLong(2))) }
    }
    fun branch(source: String, selectedId: Long, replacement: String?): String? = transaction { db ->
        if (active(db) != source) return@transaction null
        fun read(where: String, args: Array<String>): BranchMessage? =
            db.rawQuery("SELECT id,is_user,text,state,kind FROM messages WHERE chat_id=? AND $where",
                arrayOf(source, *args)).use { c ->
                if (c.moveToFirst()) BranchMessage(c.getLong(0), c.getInt(1) == 1, c.getString(2),
                    c.getString(3), c.getString(4)) else null
            }
        val selected = read("id=?", arrayOf(selectedId.toString())) ?: return@transaction null
        val previous = if (!selected.user) read("id<? ORDER BY id DESC LIMIT 1", arrayOf(selectedId.toString())) else null
        val plan = BranchPolicy.plan(selected, previous, replacement) ?: return@transaction null
        val next = create(db)
        // A branch is an independent snapshot; deleting it cannot delete the original.
        db.execSQL("INSERT INTO messages(chat_id,is_user,text,state,created,kind) SELECT ?,is_user,text,state,created,kind FROM messages WHERE chat_id=? AND id<? ORDER BY id",
            arrayOf<Any>(next, source, plan.fromUser))
        append(db, next, NoxService.Turn(true, plan.text))
        db.update("chats", ContentValues().apply {
            put("parent_chat_id", source); put("fork_message_id", plan.fromUser)
            put("title", ("Ветка: " + plan.text.replace(Regex("\\s+"), " ")).take(60))
        }, "id=?", arrayOf(next))
        next
    }
    fun newChat(): String = transaction { db ->
        val current = checkNotNull(active(db))
        val empty = db.rawQuery("SELECT 1 FROM messages WHERE chat_id=? LIMIT 1", arrayOf(current)).use { !it.moveToFirst() }
        if (empty) current else create(db)
    }
    fun open(id: String): String = transaction { db -> select(db, id); id }
    fun clear(id: String) = transaction { db ->
        check(active(db) == id)
        db.delete("messages", "chat_id=?", arrayOf(id))
        db.update("chats", ContentValues().apply {
            put("title", NEW_TITLE); put("updated", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
    }
    fun delete(id: String): String = transaction { db ->
        check(active(db) == id)
        check(db.delete("chats", "id=?", arrayOf(id)) == 1)
        val next = db.rawQuery("SELECT id FROM chats ORDER BY updated DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (next == null) create(db) else { select(db, next); next }
    }
}
