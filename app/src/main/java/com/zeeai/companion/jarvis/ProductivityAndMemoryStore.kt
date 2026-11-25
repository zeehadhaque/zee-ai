package com.zeeai.companion.jarvis

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TodoPriority(val label: String) { LOW("LOW"), MEDIUM("MEDIUM"), HIGH("HIGH") }

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val priority: TodoPriority = TodoPriority.MEDIUM,
    val isDone: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class CalendarEventItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dateTimeMs: Long = System.currentTimeMillis() + 3600_000L,
    val location: String = "",
    val isReminder: Boolean = true,
    val isCompleted: Boolean = false
)

data class KnowledgeNoteItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class SessionTaskStatus { SUCCESS, FAILED, CANCELLED }

data class TaskSessionEntry(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val status: SessionTaskStatus,
    val startedAtMs: Long = System.currentTimeMillis(),
    val summary: String = "",
    val steps: List<String> = emptyList()
)

data class CodifiedMacroSkill(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val stepsJson: String,
    val stepCount: Int,
    val successCount: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis()
)

class ProductivityAndMemoryStore(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zee_productivity_v1", Context.MODE_PRIVATE)

    private val _todos = MutableStateFlow(loadTodos())
    val todos: StateFlow<List<TodoItem>> = _todos.asStateFlow()

    private val _calendar = MutableStateFlow(loadCalendar())
    val calendar: StateFlow<List<CalendarEventItem>> = _calendar.asStateFlow()

    private val _notes = MutableStateFlow(loadNotes())
    val notes: StateFlow<List<KnowledgeNoteItem>> = _notes.asStateFlow()

    private val _sessions = MutableStateFlow(loadSessions())
    val sessions: StateFlow<List<TaskSessionEntry>> = _sessions.asStateFlow()

    private val _macros = MutableStateFlow(loadMacros())
    val macros: StateFlow<List<CodifiedMacroSkill>> = _macros.asStateFlow()

    // --- Todos ---
    fun addTodo(title: String, priority: TodoPriority = TodoPriority.MEDIUM) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val next = listOf(TodoItem(title = clean, priority = priority)) + _todos.value
        _todos.value = next
        saveTodos(next)
    }

    fun toggleTodo(id: String) {
        val next = _todos.value.map { if (it.id == id) it.copy(isDone = !it.isDone) else it }
        _todos.value = next
        saveTodos(next)
    }

    fun deleteTodo(id: String) {
        val next = _todos.value.filterNot { it.id == id }
        _todos.value = next
        saveTodos(next)
    }

    // --- Calendar & Reminders ---
    fun addCalendarReminder(title: String, dateTimeMs: Long = System.currentTimeMillis() + 3600_000L, location: String = "") {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val next = listOf(CalendarEventItem(title = clean, dateTimeMs = dateTimeMs, location = location)) + _calendar.value
        _calendar.value = next
        saveCalendar(next)
    }

    fun deleteCalendarItem(id: String) {
        val next = _calendar.value.filterNot { it.id == id }
        _calendar.value = next
        saveCalendar(next)
    }

    fun openNativeCalendarInsert(title: String = "ZEE AI Reminder", beginMs: Long = System.currentTimeMillis() + 3600_000L) {
        runCatching {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // --- Notes & Knowledge Base ---
    fun addNote(title: String, content: String) {
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) return
        val cleanTitle = title.trim().ifEmpty { "Note" }
        val next = listOf(KnowledgeNoteItem(title = cleanTitle, content = cleanContent)) + _notes.value
        _notes.value = next
        saveNotes(next)
    }

    fun deleteNote(id: String) {
        val next = _notes.value.filterNot { it.id == id }
        _notes.value = next
        saveNotes(next)
    }

    // --- Sessions History ---
    fun recordSession(goal: String, status: SessionTaskStatus, summary: String, steps: List<String> = emptyList()) {
        val entry = TaskSessionEntry(goal = goal, status = status, summary = summary, steps = steps)
        val next = (listOf(entry) + _sessions.value).take(30)
        _sessions.value = next
        saveSessions(next)
    }

    fun deleteSession(id: String) {
        val next = _sessions.value.filterNot { it.id == id }
        _sessions.value = next
        saveSessions(next)
    }

    // --- Macros ---
    fun saveMacro(goal: String, stepsJson: String) {
        val count = runCatching { JSONArray(stepsJson).length() }.getOrDefault(0)
        val next = listOf(CodifiedMacroSkill(goal = goal.trim().ifEmpty { "Custom Skill" }, stepsJson = stepsJson, stepCount = count)) + _macros.value
        _macros.value = next
        saveMacros(next)
    }

    fun incrementMacroUsage(id: String) {
        val next = _macros.value.map { if (it.id == id) it.copy(successCount = it.successCount + 1) else it }
        _macros.value = next
        saveMacros(next)
    }

    fun deleteMacro(id: String) {
        val next = _macros.value.filterNot { it.id == id }
        _macros.value = next
        saveMacros(next)
    }

    // --- JSON Persistence ---
    private fun loadTodos(): List<TodoItem> = runCatching {
        val arr = JSONArray(prefs.getString("todos_json", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TodoItem(
                id = o.optString("id", UUID.randomUUID().toString()),
                title = o.optString("title"),
                priority = runCatching { TodoPriority.valueOf(o.optString("priority", "MEDIUM")) }.getOrDefault(TodoPriority.MEDIUM),
                isDone = o.optBoolean("isDone", false),
                createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis())
            )
        }
    }.getOrDefault(emptyList())

    private fun saveTodos(list: List<TodoItem>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("priority", t.priority.name)
                put("isDone", t.isDone); put("createdAtMs", t.createdAtMs)
            })
        }
        prefs.edit().putString("todos_json", arr.toString()).apply()
    }

    private fun loadCalendar(): List<CalendarEventItem> = runCatching {
        val arr = JSONArray(prefs.getString("calendar_json", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CalendarEventItem(
                id = o.optString("id"),
                title = o.optString("title"),
                dateTimeMs = o.optLong("dateTimeMs"),
                location = o.optString("location"),
                isReminder = o.optBoolean("isReminder", true),
                isCompleted = o.optBoolean("isCompleted", false)
            )
        }
    }.getOrDefault(emptyList())

    private fun saveCalendar(list: List<CalendarEventItem>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("title", c.title); put("dateTimeMs", c.dateTimeMs)
                put("location", c.location); put("isReminder", c.isReminder); put("isCompleted", c.isCompleted)
            })
        }
        prefs.edit().putString("calendar_json", arr.toString()).apply()
    }

    private fun loadNotes(): List<KnowledgeNoteItem> = runCatching {
        val arr = JSONArray(prefs.getString("notes_json", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KnowledgeNoteItem(
                id = o.optString("id"),
                title = o.optString("title"),
                content = o.optString("content"),
                createdAtMs = o.optLong("createdAtMs")
            )
        }
    }.getOrDefault(emptyList())

    private fun saveNotes(list: List<KnowledgeNoteItem>) {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id); put("title", n.title); put("content", n.content); put("createdAtMs", n.createdAtMs)
            })
        }
        prefs.edit().putString("notes_json", arr.toString()).apply()
    }

    private fun loadSessions(): List<TaskSessionEntry> = runCatching {
        val arr = JSONArray(prefs.getString("sessions_json", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val stepsArr = o.optJSONArray("steps") ?: JSONArray()
            val stepsList = (0 until stepsArr.length()).map { stepsArr.getString(it) }
            TaskSessionEntry(
                id = o.optString("id"),
                goal = o.optString("goal"),
                status = runCatching { SessionTaskStatus.valueOf(o.optString("status", "SUCCESS")) }.getOrDefault(SessionTaskStatus.SUCCESS),
                startedAtMs = o.optLong("startedAtMs"),
                summary = o.optString("summary"),
                steps = stepsList
            )
        }
    }.getOrDefault(emptyList())

    private fun saveSessions(list: List<TaskSessionEntry>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("goal", s.goal); put("status", s.status.name)
                put("startedAtMs", s.startedAtMs); put("summary", s.summary)
                put("steps", JSONArray(s.steps))
            })
        }
        prefs.edit().putString("sessions_json", arr.toString()).apply()
    }

    private fun loadMacros(): List<CodifiedMacroSkill> = runCatching {
        val arr = JSONArray(prefs.getString("macros_json", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CodifiedMacroSkill(
                id = o.optString("id"),
                goal = o.optString("goal"),
                stepsJson = o.optString("stepsJson"),
                stepCount = o.optInt("stepCount", 0),
                successCount = o.optInt("successCount", 1),
                createdAtMs = o.optLong("createdAtMs")
            )
        }
    }.getOrDefault(emptyList())

    private fun saveMacros(list: List<CodifiedMacroSkill>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("goal", m.goal); put("stepsJson", m.stepsJson)
                put("stepCount", m.stepCount); put("successCount", m.successCount); put("createdAtMs", m.createdAtMs)
            })
        }
        prefs.edit().putString("macros_json", arr.toString()).apply()
    }
}
