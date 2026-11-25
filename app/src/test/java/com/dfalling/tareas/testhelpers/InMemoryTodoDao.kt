package com.dfalling.tareas.testhelpers

import com.dfalling.tareas.data.PendingOp
import com.dfalling.tareas.data.TodoDao
import com.dfalling.tareas.data.TodoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [TodoDao] for JVM tests: same query semantics the engine relies on
 * (pending() in push order), none of the SQL.
 */
class InMemoryTodoDao : TodoDao {
    val rows = LinkedHashMap<String, TodoEntity>()

    override fun observeAll(): Flow<List<TodoEntity>> = flowOf(rows.values.toList())

    override suspend fun getAll(): List<TodoEntity> = rows.values.toList()

    override suspend fun get(localId: String): TodoEntity? = rows[localId]

    override suspend fun pending(): List<TodoEntity> =
        rows.values
            .filter { it.pendingOp != PendingOp.NONE }
            .sortedWith(compareBy({ it.createdAtEpochMs }, { it.localId }))

    override suspend fun upsert(todo: TodoEntity) {
        rows[todo.localId] = todo
    }

    override suspend fun upsertAll(todos: List<TodoEntity>) {
        todos.forEach { rows[it.localId] = it }
    }

    override suspend fun delete(localId: String) {
        rows.remove(localId)
    }

    override suspend fun clear() {
        rows.clear()
    }
}
