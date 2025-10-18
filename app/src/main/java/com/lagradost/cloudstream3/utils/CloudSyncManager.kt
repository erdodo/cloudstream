package com.lagradost.cloudstream3.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStore.mapper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * Cloud synchronization manager for automatic backup and restore
 */
object CloudSyncManager {
    private const val TAG = "CloudSyncManager"
    private const val USERNAME = "erdodo"
    private const val LOGIN_URL = "https://n8n.erdoganyesil.org/webhook/cloudstream-login?user_name=$USERNAME"
    private const val BACKUP_URL = "https://n8n.erdoganyesil.org/webhook/cloudstream-backup"

    private var backupJob: Job? = null
    private var isInitialized = false

    data class LoginResponse(
        @JsonProperty("row_number") val rowNumber: Int,
        @JsonProperty("id") val id: String,
        @JsonProperty("username") val username: String,
        @JsonProperty("password") val password: String,
        @JsonProperty("backup") val backup: String
    )

    /**
     * Initialize cloud sync - should be called when app starts
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true

        Log.d(TAG, "Initializing cloud sync...")

        // Login and restore backup on app start
        ioSafe {
            try {
                loginAndRestore(context)
                // Start periodic backup after 1 minute delay
                startPeriodicBackup(context)
            } catch (e: Exception) {
                logError(e)
                Log.e(TAG, "Failed to initialize cloud sync", e)
            }
        }
    }

    /**
     * Login and restore backup from cloud
     */
    private suspend fun loginAndRestore(context: Context) {
        try {
            Log.d(TAG, "Attempting login and restore...")

            val response = app.get(LOGIN_URL)
            if (!response.isSuccessful) {
                Log.e(TAG, "Login failed with status: ${response.code}")
                return
            }

            val loginData = mapper.readValue<LoginResponse>(response.text)
            Log.d(TAG, "Login successful for user: ${loginData.username}")

            // Check if there's backup data to restore
            if (loginData.backup.isNotEmpty() && loginData.backup != "{}") {
                Log.d(TAG, "Found backup data, restoring...")

                // Parse the backup JSON string
                val backupFile = mapper.readValue<BackupUtils.BackupFile>(loginData.backup)

                // Restore the backup
                BackupUtils.restore(
                    context = context,
                    backupFile = backupFile,
                    restoreSettings = true,
                    restoreDataStore = true
                )

                // Show toast on main thread
                main {
                    showToast(context.getString(R.string.backup_restored_from_cloud), Toast.LENGTH_LONG)
                }

                Log.d(TAG, "Backup restored successfully")
            } else {
                Log.d(TAG, "No backup data found")
            }

        } catch (e: Exception) {
            logError(e)
            Log.e(TAG, "Error during login and restore", e)
        }
    }

    /**
     * Start periodic backup every minute with initial 1 minute delay
     */
    private fun startPeriodicBackup(context: Context) {
        Log.d(TAG, "Starting periodic backup with 1 minute initial delay...")

        backupJob?.cancel()
        backupJob = ioSafe {
            // Wait 1 minute before starting backup cycle
            delay(60_000) // Initial 1 minute delay
            Log.d(TAG, "Initial delay completed, starting backup cycle...")
            
            while (true) {
                try {
                    performBackup(context)
                } catch (e: Exception) {
                    logError(e)
                    Log.e(TAG, "Error during periodic backup", e)
                }
                delay(60_000) // Wait 1 minute between backups
            }
        }
    }

    /**
     * Perform backup to cloud
     */
    private suspend fun performBackup(context: Context) {
        try {
            Log.d(TAG, "Starting backup...")

            // Get current backup data
            val backupFile = BackupUtils.getBackupData(context)
            if (backupFile == null) {
                Log.e(TAG, "Failed to get backup data")
                return
            }

            val backupJson = mapper.writeValueAsString(backupFile)

            // Encode the backup JSON for URL
            val encodedBackup = URLEncoder.encode(backupJson, "UTF-8")

            // Send backup to cloud
            val backupUrl = "$BACKUP_URL?user_name=$USERNAME&backup=$encodedBackup"
            val response = app.get(backupUrl)

            if (response.isSuccessful) {
                Log.d(TAG, "Backup successful")
            } else {
                Log.e(TAG, "Backup failed with status: ${response.code}")
            }

        } catch (e: Exception) {
            logError(e)
            Log.e(TAG, "Error during backup", e)
        }
    }

    /**
     * Stop cloud sync
     */
    fun stop() {
        Log.d(TAG, "Stopping cloud sync...")
        backupJob?.cancel()
        backupJob = null
        isInitialized = false
    }

    /**
     * Resume sync - only restore from cloud, don't restart backup cycle
     * This should be called when app comes back from background
     */
    fun onResume(context: Context) {
        if (!isInitialized) return
        
        Log.d(TAG, "App resumed, syncing from cloud...")
        ioSafe {
            try {
                loginAndRestore(context)
            } catch (e: Exception) {
                logError(e)
                Log.e(TAG, "Failed to sync on resume", e)
            }
        }
    }

    /**
     * Force manual backup
     */
    fun forceBackup(context: Context) {
        ioSafe {
            performBackup(context)
        }
    }
}