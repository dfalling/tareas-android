package com.dfalling.tareas.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    /** Live feed for the UI; consumers re-group and re-sort via [Tabs]. */
    @Query("SELECT * FROM todos ORDER BY createdAtEpochMs, localId")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos")
    suspend fun getAll(): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE localId = :localId")
    suspend fun get(localId: String): TodoEntity?

    /** The push queue, in stable sync order (PLAN.md §5). */
    @Query("SELECT * FROM todos WHERE pendingOp != 'NONE' ORDER BY createdAtEpochMs, localId")
    suspend fun pending(): List<TodoEntity>

    @Upsert
    suspend fun upsert(todo: TodoEntity)

    @Upsert
    suspend fun upsertAll(todos: List<TodoEntity>)

    @Query("DELETE FROM todos WHERE localId = :localId")
    suspend fun delete(localId: String)

    @Query("DELETE FROM todos")
    suspend fun clear()
}
