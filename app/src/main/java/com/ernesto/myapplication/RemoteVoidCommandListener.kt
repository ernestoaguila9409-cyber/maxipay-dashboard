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
 * Web dashboard queues `RemotePaymentCommands` docs; the POS executes card voids and
 * settled card refunds (SPIn `/Payment/Return`) here using local [TerminalPrefs] (same as
 * [TransactionActivity]).
 *
 * Claims are serialized on a single background thread so two tablets do not double-process the same command.
 */
object RemoteVoidCommandListener {

    private const val TAG = "RemoteVoidCmd"
    const val COLLECTION = "RemotePaymentCommands"
    private const val CMD_VOID = "voidTransaction"
    private const val CMD_REFUND = "refundTransaction"
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
                    val cmdType = doc.getString("type") ?: continue
                    if (cmdType != CMD_VOID && cmdType != CMD_REFUND) continue
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

    private fun refundAmountInCentsFromCmd(cmd: DocumentSnapshot): Long {
        val raw = cmd.get("amountInCents") ?: return 0L
        return when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Double -> raw.toLong()
            is Number -> raw.toLong()
            else -> cmd.getLong("amountInCents") ?: 0L
        }
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

        val cmdType = cmdDoc.getString("type")?.trim().orEmpty()
        val txId = cmdDoc.getString("transactionId")?.trim().orEmpty()
        if (txId.isEmpty()) {
            markFailed(cmdDoc, "Missing transactionId")
            return
        }

        val voidedBy = cmdDoc.getString("voidedByLabel")?.trim().orEmpty().ifBlank { "Web dashboard" }
        val refundedBy = cmdDoc.getString("refundedByLabel")?.trim().orEmpty().ifBlank { "Web dashboard" }

        serial.execute {
            val claimed = try {
                val fresh = Tasks.await(cmdDoc.reference.get())
                if (fresh.getString("status") != "pending") return@execute
                val t = fresh.getString("type") ?: return@execute
                if (t != cmdType) return@execute
                if (t != CMD_VOID && t != CMD_REFUND) return@execute
                Tasks.await(
                    cmdDoc.reference.update(
                        mapOf(
                            "status" to "processing",
                            "processingStartedAt" to FieldValue.serverTimestamp(),
                        ),
                    ),
                )
                Pair(t, fresh)
            } catch (ex: Exception) {
                Log.d(TAG, "claim skip or lost $path: ${ex.message}")
                return@execute
            }
            val freshType = claimed.first
            val freshDoc = claimed.second

            when (freshType) {
                CMD_VOID -> RemoteVoidExecutor.execute(app, txId, voidedBy) { ok, msg ->
                    finishCommand(cmdDoc, ok, msg)
                }
                CMD_REFUND -> {
                    val orderId = freshDoc.getString("orderId")?.trim().orEmpty()
                    val amountCents = refundAmountInCentsFromCmd(freshDoc)
                    val lineKey = freshDoc.getString("refundedLineKey")?.trim()?.takeIf { it.isNotEmpty() }
                    val itemName = freshDoc.getString("refundedItemName")?.trim()?.takeIf { it.isNotEmpty() }
                    RemoteRefundExecutor.execute(
                        app,
                        saleTxId = txId,
                        orderId = orderId,
                        amountInCentsRequested = amountCents,
                        refundedBy = refundedBy,
                        refundedLineKey = lineKey,
                        refundedItemName = itemName,
                    ) { ok, msg ->
                        finishCommand(cmdDoc, ok, msg)
                    }
                }
                else -> return@execute
            }
        }
    }

    private fun finishCommand(cmdDoc: DocumentSnapshot, ok: Boolean, msg: String) {
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
