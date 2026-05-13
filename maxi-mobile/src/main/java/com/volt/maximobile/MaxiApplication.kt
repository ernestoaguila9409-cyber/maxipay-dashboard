package com.volt.maximobile

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

class MaxiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setProjectId("restaurantapp-180da")
                    .setApplicationId("1:306440161745:android:4ca7b358dcfdc3a956f8f0")
                    .setApiKey("AIzaSyBxlB1t_LCgiXR3ybeszzJrEpOZeXjofAI")
                    .setStorageBucket("restaurantapp-180da.firebasestorage.app")
                    .setGcmSenderId("306440161745")
                    .build(),
            )
        }
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnFailureListener { e ->
                Log.w(TAG, "Anonymous auth failed (Firestore may still be blocked by rules): ${e.message}")
            }
    }

    companion object {
        private const val TAG = "MaxiApplication"
    }
}
