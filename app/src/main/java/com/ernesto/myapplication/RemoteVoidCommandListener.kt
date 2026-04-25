package com.ernesto.myapplication

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.Executors

/**
 * Web dashboard queues `RemotePaymentCommands` docs; the POS executes card voids here using
 * local [TerminalPrefs] (same as [TransactionActivity]).
 *
 * Claims are serialized on a single background thread so two tablets do not double-void the same command.
 */
object RemoteVoidCommandListener {

    private const val TAG = "RemoteVoidCmd"
    const val COLLECTION = "RemotePaymentCommands"
    private const val CMD_VOID = "voidTransaction"
    private const val MAX_COMMAND_AGE_MS = 600_000L
    private const val FUTURE_SKEW_MS = 120_000L

    private var registration: ListenerRegistration? = null
    private val serial = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RemoteVoidCmdSerial")
    }

    fun start(context: Context) {
        if (registration != null) return
        val app = context.applicationContext
        val db = FirebaseFirestore.getInstance()
        registration = db.collection(COLLECTION)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.w(TAG, "listener: ${e.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                for (change in snap.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED &&
                        change.type != DocumentChange.Type.MODIFIED
                    ) {
                        continue
                    }
                    val doc = change.document
                    if (doc.getString("type") != CMD_VOID) continue
                    if (doc.getString("status") != "pending") continue
                    handlePending(app, doc)
                }
            }
        Log.d(TAG, "Started $COLLECTION listener")
    }

    fun stop() {
        registration?.remove()
        registration = null
        Log.d(TAG, "Stopped listener")
    }

    private fun handlePending(app: Context, cmdDoc: DocumentSnapshot) {
        val path = cmdDoc.reference.path

        val requestedAt = cmdDoc.getTimestamp("requestedAt") ?: run {
            Log.d(TAG, "Skip missing requestedAt: $path")
            return
        }
        val ageMs = System.currentTimeMillis() - requestedAt.toDate().time
        if (ageMs > MAX_COMMAND_AGE_MS) {
            Log.d(TAG, "Skip stale ($ageMs ms): $path")
            cmdDoc.reference.update(
                mapOf(
                    "status" to "failed",
                    "errorMessage" to "Command expired before POS processed it",
                    "completedAt" to FieldValue.serverTimestamp(),
                ),
            )
            return
        }
        if (ageMs < -FUTURE_SKEW_MS) {
            Log.d(TAG, "Skip future-dated: $path")
            return
        }

        val txId = cmdDoc.getString("transactionId")?.trim().orEmpty()
        if (txId.isEmpty()) {
            markFailed(cmdDoc, "Missing transactionId")
            return
        }

        val voidedBy = cmdDoc.getString("voidedByLabel")?.trim().orEmpty().ifBlank { "Web dashboard" }

        serial.execute {
            try {
                val fresh = Tasks.await(cmdDoc.reference.get())
                if (fresh.getString("status") != "pending") return@execute
                if (fresh.getString("type") != CMD_VOID) return@execute
                Tasks.await(
                    cmdDoc.reference.update(
                        mapOf(
                            "status" to "processing",
                            "processingStartedAt" to FieldValue.serverTimestamp(),
                        ),
                    ),
                )
            } catch (ex: Exception) {
                Log.d(TAG, "claim skip or lost $path: ${ex.message}")
                return@execute
            }

            RemoteVoidExecutor.execute(app, txId, voidedBy) { ok, msg ->
                if (ok) {
                    cmdDoc.reference.update(
                        mapOf(
                            "status" to "completed",
                            "completedAt" to FieldValue.serverTimestamp(),
                            "resultMessage" to msg,
                        ),
                    ).addOnFailureListener { Log.e(TAG, "mark completed", it) }
                } else {
                    cmdDoc.reference.update(
                        mapOf(
                            "status" to "failed",
                            "errorMessage" to msg,
                            "completedAt" to FieldValue.serverTimestamp(),
                        ),
                    ).addOnFailureListener { Log.e(TAG, "mark failed", it) }
                }
            }
        }
    }

    private fun markFailed(cmdDoc: DocumentSnapshot, message: String) {
        cmdDoc.reference.update(
            mapOf(
                "status" to "failed",
                "errorMessage" to message,
                "completedAt" to FieldValue.serverTimestamp(),
            ),
        )
    }
}
