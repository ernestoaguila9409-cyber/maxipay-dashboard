package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.ernesto.myapplication.payments.PaymentTerminalConfig
import com.ernesto.myapplication.payments.PaymentTerminalRepository
import com.ernesto.myapplication.payments.SpinApiUrls
import com.ernesto.myapplication.payments.SpinCallTracker
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TerminalListActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TerminalHealth"
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L
        /** Min interval between Firestore lastSeen writes while terminal stays ONLINE (avoids spam). */
        private const val LAST_SEEN_PERSIST_INTERVAL_MS = 45_000L

        private val OFFLINE_MESSAGES = listOf(
            "route not found",
            "not connected",
            "timed out",
            "timeout",
            "offline",
            "unreachable",
            "unable to connect",
            "no response",
            "not responding",
            "connection refused",
            "connection failed",
            "powered off"
        )
    }

    private val db = FirebaseFirestore.getInstance()
    private val terminals = mutableListOf<Terminal>()
    private var terminalsListener: ListenerRegistration? = null
    private lateinit var adapter: TerminalAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var healthCheckRunnable: Runnable? = null
    private val lastSeenFirestoreWrittenAt = mutableMapOf<String, Long>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Payment Terminals"

        recycler = findViewById(R.id.recyclerTerminals)
        txtEmpty = findViewById(R.id.txtEmptyTerminals)

        adapter = TerminalAdapter(terminals) { terminal ->
            val intent = Intent(this, PaymentTerminalActivity::class.java)
            intent.putExtra("TERMINAL_ID", terminal.id)
            startActivity(intent)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnAddTerminal).setOnClickListener {
            startActivity(Intent(this, PaymentTerminalActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        attachPaymentTerminalsListener()
    }

    override fun onStop() {
        terminalsListener?.remove()
        terminalsListener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        startHealthCheck()
    }

    override fun onPause() {
        super.onPause()
        stopHealthCheck()
    }

    // ── Health-check scheduling ────────────────────────────────────

    private fun startHealthCheck() {
        stopHealthCheck()
        healthCheckRunnable = object : Runnable {
            override fun run() {
                runHealthCheck()
                mainHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(healthCheckRunnable!!, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun stopHealthCheck() {
        healthCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        healthCheckRunnable = null
    }

    // ── SPIn API health check ──────────────────────────────────────

    private fun runHealthCheck() {
        val toCheck = terminals.filter {
            it.tpn.isNotBlank() && it.registerId.isNotBlank() && it.authKey.isNotBlank()
        }
        if (toCheck.isEmpty()) return

        for (terminal in toCheck) {
            checkTerminalViaSpIn(terminal)
        }
    }

    private fun checkTerminalViaSpIn(terminal: Terminal) {
        val cfg = terminal.paymentConfig ?: return
        val healthCheckRef = "hc-${terminal.tpn}-${System.currentTimeMillis()}"
        val json = JSONObject().apply {
            put("PaymentType", "Credit")
            put("ReferenceId", healthCheckRef)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", false)
            put("Tpn", terminal.tpn)
            put("RegisterId", terminal.registerId)
            put("Authkey", terminal.authKey)
            put("SPInProxyTimeout", 8)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(SpinApiUrls.statusUrlForConfig(cfg))
            .post(body)
            .build()

        Log.d(TAG, "Health-check request for ${terminal.name}")

        SpinCallTracker.beginCall()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                SpinCallTracker.endCall()
                Log.w(TAG, "Health-check network error for ${terminal.name}: ${e.message}")
                applyStatus(terminal, isOnline = false)
            }

            override fun onResponse(call: Call, response: Response) {
                SpinCallTracker.endCall()
                val responseText = response.body?.string() ?: ""
                Log.d(TAG, "Health-check response [${response.code}] for ${terminal.name}: $responseText")

                val connected = parseConnectionStatus(response.code, responseText)
                applyStatus(terminal, isOnline = connected)
            }
        })
    }

    private fun parseConnectionStatus(httpCode: Int, responseText: String): Boolean {
        return try {
            val jsonObj = JSONObject(responseText)
            val gr = jsonObj.optJSONObject("GeneralResponse")

            val resultCode = gr?.optString("ResultCode", "") ?: ""
            val resultMessage = gr?.optString("ResultMessage", "") ?: ""
            val message = gr?.optString("Message", "") ?: ""
            val detailedMessage = gr?.optString("DetailedMessage", "") ?: ""

            Log.d(TAG, "Status parsed: http=$httpCode code=$resultCode " +
                    "msg=\"$resultMessage\" message=\"$message\" detail=\"$detailedMessage\"")

            if (resultCode == "0") return true

            val combined = "$resultMessage $message $detailedMessage".lowercase()
            val isOffline = OFFLINE_MESSAGES.any { combined.contains(it) }

            if (isOffline) {
                Log.d(TAG, "Terminal offline — matched in: $combined")
            }

            !isOffline
        } catch (e: Exception) {
            Log.e(TAG, "Parse error for response: $responseText", e)
            false
        }
    }

    private fun applyStatus(terminal: Terminal, isOnline: Boolean) {
        val wasOnline = terminal.status.equals("ONLINE", ignoreCase = true)
        val now = System.currentTimeMillis()

        if (isOnline) {
            terminal.status = "ONLINE"
            terminal.lastSeen = now
            // Always refresh Firestore lastSeen while online (throttled), so "Last seen" is not
            // stuck at the last OFFLINE→ONLINE transition from days ago.
            persistOnlineLastSeenToFirestore(terminal.id, now, force = !wasOnline)
        } else {
            terminal.status = "OFFLINE"
            if (wasOnline) persistStatusToFirestore(terminal.id, "OFFLINE", terminal.lastSeen)
        }

        if (isOnline != wasOnline) {
            mainHandler.post { adapter.notifyDataSetChanged() }
        }
    }

    private fun persistOnlineLastSeenToFirestore(terminalId: String, nowMs: Long, force: Boolean) {
        val lastWrite = lastSeenFirestoreWrittenAt[terminalId] ?: 0L
        if (!force && nowMs - lastWrite < LAST_SEEN_PERSIST_INTERVAL_MS) return
        persistStatusToFirestore(terminalId, "ONLINE", nowMs)
        lastSeenFirestoreWrittenAt[terminalId] = nowMs
    }

    // ── Firestore persistence ──────────────────────────────────────

    private fun persistStatusToFirestore(terminalId: String, status: String, lastSeenMs: Long?) {
        val data = hashMapOf<String, Any>("posConnectionStatus" to status)
        if (lastSeenMs != null) {
            data["posLastSeen"] = Timestamp(
                lastSeenMs / 1000,
                ((lastSeenMs % 1000) * 1_000_000).toInt()
            )
        }
        data["updatedAt"] = FieldValue.serverTimestamp()
        db.collection("payment_terminals").document(terminalId).update(data)
            .addOnFailureListener { Log.w(TAG, "Firestore status update failed: ${it.message}") }
    }

    // ── Load terminals (real-time, same collection as maxipay dashboard) ──

    private fun attachPaymentTerminalsListener() {
        terminalsListener?.remove()
        terminalsListener = db.collection("payment_terminals")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "payment_terminals listener: ${err.message}")
                    runOnUiThread {
                        Toast.makeText(
                            this@TerminalListActivity,
                            "Could not load terminals: ${err.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                terminals.clear()
                for (doc in snap.documents) {
                    val cfg = PaymentTerminalRepository.parsePaymentTerminalDocument(doc) ?: continue
                    val tpn = cfg.credential(PaymentTerminalConfig.Companion.ConfigKey.TPN)
                    val reg = cfg.credential(PaymentTerminalConfig.Companion.ConfigKey.REGISTER_ID)
                    val auth = cfg.credential(PaymentTerminalConfig.Companion.ConfigKey.AUTH_KEY)
                    val ip = cfg.credential("ipAddress")
                    val st = doc.getString("posConnectionStatus")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("status")?.takeIf { it.isNotBlank() }
                        ?: "OFFLINE"
                    val lastMs = readDisplayLastSeenMs(doc)
                    terminals.add(
                        Terminal(
                            id = doc.id,
                            name = cfg.name,
                            tpn = tpn,
                            ipAddress = ip,
                            registerId = reg,
                            authKey = auth,
                            status = st,
                            lastSeen = lastMs,
                            paymentConfig = cfg,
                        )
                    )
                }
                terminals.sortBy { it.name.trim().lowercase() }
                adapter.notifyDataSetChanged()
                if (terminals.isEmpty()) {
                    recycler.visibility = View.GONE
                    txtEmpty.visibility = View.VISIBLE
                } else {
                    recycler.visibility = View.VISIBLE
                    txtEmpty.visibility = View.GONE
                }
                runHealthCheck()
            }
    }

    /** Same fields the web dashboard uses for reachability. */
    private fun readDisplayLastSeenMs(doc: DocumentSnapshot): Long? {
        doc.getTimestamp("posLastSeen")?.let { return it.toDate().time }
        return readLastSeenMillis(doc)
    }

    /**
     * Normalize Firestore lastSeen to epoch millis (UTC). Handles Timestamp, legacy Date,
     * and numeric seconds vs milliseconds from dashboard/API.
     */
    private fun readLastSeenMillis(doc: DocumentSnapshot): Long? {
        doc.getTimestamp("lastSeen")?.let { return it.toDate().time }
        @Suppress("DEPRECATION")
        doc.getDate("lastSeen")?.let { return it.time }
        return when (val raw = doc.get("lastSeen")) {
            is Timestamp -> raw.toDate().time
            is Number -> normalizeLastSeenNumber(raw.toLong())
            else -> null
        }
    }

    /**
     * Values in ~1e9..1e10 are almost certainly Unix **seconds**; larger values are **ms**.
     */
    private fun normalizeLastSeenNumber(n: Long): Long {
        return if (n in 1_000_000_000L until 10_000_000_000L) n * 1000L else n
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
