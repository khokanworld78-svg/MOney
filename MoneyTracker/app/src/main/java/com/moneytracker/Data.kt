package com.moneytracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity data class Account(@PrimaryKey val name: String, val openingPaise: Long = 0L)

@Entity data class Txn(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String,            // yyyy-MM-dd
    val time: String,            // HH:mm
    val description: String,
    val amountPaise: Long,
    val account: String,
    val category: String = "",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long, val status: String, val message: String
)

@Dao interface MoneyDao {
    @Query("SELECT * FROM Account") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM Account") suspend fun accountsNow(): List<Account>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addAccount(a: Account)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAccount(a: Account)
    @Query("SELECT * FROM Txn ORDER BY date DESC, time DESC") fun txns(): Flow<List<Txn>>
    @Query("SELECT * FROM Txn ORDER BY date, time") suspend fun txnsNow(): List<Txn>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(t: Txn)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(t: List<Txn>)
    @Insert suspend fun log(l: SyncLog): Long
    @Query("UPDATE SyncLog SET status=:s, message=:m WHERE id=:id") suspend fun finish(id: Long, s: String, m: String)
    @Query("SELECT * FROM SyncLog ORDER BY id DESC LIMIT 30") fun logs(): Flow<List<SyncLog>>
    @Query("SELECT * FROM SyncLog ORDER BY id DESC LIMIT 1") suspend fun lastLog(): SyncLog?
    @Query("UPDATE SyncLog SET status='INTERRUPTED' WHERE status='STARTED'") suspend fun markInterrupted()
}

@Database(entities = [Account::class, Txn::class, SyncLog::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): MoneyDao
    companion object {
        @Volatile private var i: Db? = null
        fun get(c: Context): Db = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "money.db").build().also { i = it }
        }
    }
}
