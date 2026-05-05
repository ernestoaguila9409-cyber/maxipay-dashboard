package com.ernesto.myapplication

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * In-memory cache of the `Settings/courseFiring` document.
 * Provides [enabled], [courses] (ordered), and lookup helpers for the kitchen send path.
 */
object CourseFiringCache {

    private const val TAG = "CourseFiringCache"
    private const val COLLECTION = "Settings"
    private const val DOCUMENT = "courseFiring"

    data class CourseDefinition(
        val id: String,
        val name: String,
        val order: Int,
        val delayAfterReadySeconds: Int,
    )

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var courses: List<CourseDefinition> = emptyList()
        private set

    private var registration: ListenerRegistration? = null

    @Synchronized
    fun start(db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        if (registration != null) return
        registration = db.collection(COLLECTION).document(DOCUMENT)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "listener error", err)
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) {
                    enabled = false
                    courses = emptyList()
                    return@addSnapshotListener
                }
                enabled = snap.getBoolean("enabled") == true
                val rawCourses = snap.get("courses")
                courses = parseCourses(rawCourses)
            }
    }

    @Synchronized
    fun stop() {
        registration?.remove()
        registration = null
        enabled = false
        courses = emptyList()
    }

    fun firstCourseId(): String? = courses.firstOrNull()?.id

    fun nextCourseAfter(courseId: String): CourseDefinition? {
        val idx = courses.indexOfFirst { it.id == courseId }
        if (idx < 0 || idx >= courses.size - 1) return null
        return courses[idx + 1]
    }

    fun courseById(courseId: String): CourseDefinition? = courses.firstOrNull { it.id == courseId }

    private fun parseCourses(raw: Any?): List<CourseDefinition> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = (map["name"] as? String)?.trim().orEmpty().ifEmpty { id }
            val order = (map["order"] as? Number)?.toInt() ?: 0
            val delay = (map["delayAfterReadySeconds"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            CourseDefinition(id, name, order, delay)
        }.sortedBy { it.order }
    }
}
