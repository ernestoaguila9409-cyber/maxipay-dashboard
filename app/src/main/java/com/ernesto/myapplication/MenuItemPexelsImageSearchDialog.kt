package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException

data class PexelsImageHit(
    val id: Long,
    val previewUrl: String,
    val sourceUrl: String,
    val photographer: String,
)

private object MenuItemPexelsImageParse {
    fun searchResult(data: Any?): Pair<String, List<PexelsImageHit>> {
        val m = data as? Map<*, *> ?: return "" to emptyList()
        val q = (m["query"] as? String) ?: ""
        val list = m["images"] as? List<*> ?: return q to emptyList()
        val out = list.mapNotNull { row ->
            val r = row as? Map<*, *> ?: return@mapNotNull null
            val id = (r["id"] as? Number)?.toLong() ?: return@mapNotNull null
            val preview = (r["previewUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val source = (r["sourceUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val photo = (r["photographer"] as? String) ?: ""
            PexelsImageHit(id, preview, source, photo)
        }
        return q to out
    }

    fun commitResult(data: Any?): String? =
        (data as? Map<*, *>)?.get("imageUrl") as? String
}

/**
 * Pexels image picker backed by Cloud Functions [menuItemImageSearch] / [menuItemImageCommitPexels].
 * Saves a Firebase Storage URL (same pipeline as the web dashboard); caller persists it to Firestore.
 */
class MenuItemPexelsImageSearchDialog(
    private val activity: AppCompatActivity,
    private val itemId: String,
    private val itemName: String,
    private val onFirebaseUrlReady: (String) -> Unit,
) {
    /**
     * Must match [MENU_IMAGE_REGION] in `functions/menu-item-image.js`.
     * If this mismatches the deployed region, the client returns NOT_FOUND.
     */
    private val firebaseApp = FirebaseApp.getInstance()
    private val functions: FirebaseFunctions =
        FirebaseFunctions.getInstance(firebaseApp, "us-central1")
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(firebaseApp)

    private var alertDialog: AlertDialog? = null
    private var progress: ProgressBar? = null
    private var txtError: TextView? = null
    private var editQuery: TextInputEditText? = null
    private var recycler: RecyclerView? = null
    private var btnSearch: MaterialButton? = null
    private var adapter: HitsAdapter? = null
    private var isCommitting = false

    fun show() {
        val ctx = activity
        val inflater = LayoutInflater.from(ctx)
        val root = inflater.inflate(R.layout.dialog_pexels_image_search, null, false)
        progress = root.findViewById(R.id.progressPexels)
        txtError = root.findViewById(R.id.txtPexelsError)
        editQuery = root.findViewById(R.id.editPexelsQuery)
        btnSearch = root.findViewById(R.id.btnPexelsRunSearch)
        recycler = root.findViewById(R.id.recyclerPexels)

        adapter = HitsAdapter { hit -> commitSelection(hit) }
        recycler?.layoutManager = GridLayoutManager(ctx, 3)
        recycler?.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.item_detail_pexels_dialog_title))
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        alertDialog = dialog

        btnSearch?.setOnClickListener {
            val manual = editQuery?.text?.toString()?.trim().orEmpty()
            if (manual.isEmpty()) {
                performSearch(manualOnly = false, manualQuery = "")
            } else {
                performSearch(manualOnly = true, manualQuery = manual)
            }
        }

        dialog.setOnShowListener {
            root.post { performSearch(manualOnly = false, manualQuery = "") }
        }
        dialog.show()
    }

    /**
     * Cloud Functions `onCall` requires a valid Firebase ID token. We always refresh the token
     * before invoking so the Functions client attaches a non-expired Authorization header.
     */
    private fun withFreshIdToken(onReady: () -> Unit, onAuthError: (String) -> Unit) {
        fun refreshAndRun(user: FirebaseUser) {
            user.getIdToken(true)
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        onAuthError(task.exception?.message ?: activity.getString(R.string.item_detail_pexels_auth_failed))
                        return@addOnCompleteListener
                    }
                    onReady()
                }
        }

        val existing = auth.currentUser
        if (existing != null) {
            refreshAndRun(existing)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    refreshAndRun(user)
                } else {
                    onAuthError(activity.getString(R.string.item_detail_pexels_auth_failed))
                }
            }
            .addOnFailureListener { e ->
                onAuthError(e.message ?: activity.getString(R.string.item_detail_pexels_auth_failed))
            }
    }

    private fun performSearch(manualOnly: Boolean, manualQuery: String) {
        progress?.visibility = View.VISIBLE
        txtError?.visibility = View.GONE
        btnSearch?.isEnabled = false

        withFreshIdToken(
            onReady = {
                val data = hashMapOf<String, Any>()
                if (manualOnly && manualQuery.isNotEmpty()) {
                    data["query"] = manualQuery
                } else {
                    data["itemName"] = itemName
                }

                functions.getHttpsCallable("menuItemImageSearch")
                    .call(data)
                    .addOnSuccessListener { result ->
                        progress?.visibility = View.GONE
                        btnSearch?.isEnabled = true
                        val (usedQuery, hits) = MenuItemPexelsImageParse.searchResult(result.data)
                        editQuery?.setText(usedQuery)
                        adapter?.submit(hits)
                        if (hits.isEmpty()) {
                            txtError?.text = activity.getString(R.string.item_detail_pexels_no_results)
                            txtError?.visibility = View.VISIBLE
                        }
                    }
                    .addOnFailureListener { e ->
                        progress?.visibility = View.GONE
                        btnSearch?.isEnabled = true
                        txtError?.text = functionsErrorMessage(e)
                        txtError?.visibility = View.VISIBLE
                        adapter?.submit(emptyList())
                    }
            },
            onAuthError = { msg ->
                progress?.visibility = View.GONE
                btnSearch?.isEnabled = true
                txtError?.text = msg
                txtError?.visibility = View.VISIBLE
                adapter?.submit(emptyList())
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun commitSelection(hit: PexelsImageHit) {
        if (isCommitting) return
        isCommitting = true
        progress?.visibility = View.VISIBLE
        txtError?.visibility = View.GONE

        withFreshIdToken(
            onReady = {
                val data = hashMapOf(
                    "itemId" to itemId,
                    "sourceUrl" to hit.sourceUrl,
                )
                functions.getHttpsCallable("menuItemImageCommitPexels")
                    .call(data)
                    .addOnSuccessListener { result ->
                        isCommitting = false
                        progress?.visibility = View.GONE
                        val url = MenuItemPexelsImageParse.commitResult(result.data)?.trim()?.takeIf { it.isNotEmpty() }
                        if (url == null) {
                            Toast.makeText(activity, R.string.item_detail_pexels_failed, Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }
                        alertDialog?.dismiss()
                        alertDialog = null
                        onFirebaseUrlReady(url)
                    }
                    .addOnFailureListener { e ->
                        isCommitting = false
                        progress?.visibility = View.GONE
                        txtError?.text = functionsErrorMessage(e)
                        txtError?.visibility = View.VISIBLE
                    }
            },
            onAuthError = { msg ->
                isCommitting = false
                progress?.visibility = View.GONE
                txtError?.text = msg
                txtError?.visibility = View.VISIBLE
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun functionsErrorMessage(e: Exception): String {
        if (e is FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.NOT_FOUND) {
                return activity.getString(R.string.item_detail_pexels_error_not_found)
            }
            if (e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                return activity.getString(R.string.item_detail_pexels_error_unauthenticated)
            }
            val detail = e.message?.takeIf { it.isNotBlank() }
            if (detail != null) return detail
        }
        return e.message ?: activity.getString(R.string.item_detail_pexels_failed)
    }
}

private class HitsAdapter(
    private val onPick: (PexelsImageHit) -> Unit,
) : RecyclerView.Adapter<HitsAdapter.VH>() {
    private var items: List<PexelsImageHit> = emptyList()

    fun submit(new: List<PexelsImageHit>) {
        items = new
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pexels_pick_cell, parent, false)
        return VH(v, onPick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(
        itemView: View,
        private val onPick: (PexelsImageHit) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val img = itemView.findViewById<android.widget.ImageView>(R.id.imgPexelsThumb)
        private val txtPhoto = itemView.findViewById<TextView>(R.id.txtPexelsPhotographer)

        fun bind(hit: PexelsImageHit) {
            img.load(hit.previewUrl) { crossfade(true) }
            if (hit.photographer.isNotBlank()) {
                txtPhoto.visibility = View.VISIBLE
                txtPhoto.text = hit.photographer
            } else {
                txtPhoto.visibility = View.GONE
            }
            itemView.setOnClickListener { onPick(hit) }
        }
    }
}
