// Copyright Volker Mische
// SPDX-License-Identifier: Apache-2.0

package cx.vmx.sdcontacts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobInfo.TriggerContentUri
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Base64
import android.util.Base64.DEFAULT
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat.getUnusedAppRestrictionsStatus
import androidx.core.content.UnusedAppRestrictionsConstants.*
import androidx.documentfile.provider.DocumentFile
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

const val READ_CONTACTS_PERMISSION_CODE = 1

const val PREFERENCES_NAME = "default"
const val PREFERENCES_KEY_DIRECTORY = "directory"
const val PREFERENCES_KEY_HASH = "hash"

const val CHANNEL_ID = "sdcard_default_channel"

class MainActivity : AppCompatActivity() {
    // Handle the result of selecting a directory.
    private val selectDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) {
        // Make sure that the write permissions are still granted, even if the application is
        // closed or the phone is rebooted.
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        applicationContext.contentResolver.takePersistableUriPermission(it, takeFlags)

        setOutputDirectory(this, it)

        // Export the contacts right after the directory was selected.
        saveVcf(this)
        // Schedule a job, so that next time the contacts change, they are exported.
        scheduleJob(this)

        // Show additional information in the app, now that a directory was selected.
        findViewById<TextView>(R.id.main_text).visibility = View.VISIBLE
        findViewById<Button>(R.id.exit).visibility = View.VISIBLE
    }

    // The original documentation says, that `startActivityForResult()` must be
    // used. As it is deprecated, use `registerForActivityResult()` instead.
    private val unusedAppRestrictions = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The user was asked to disable the "auto-permission" removal feature. Here we check
        // again if the user actually disabled it. If not, the user will be prompted again.
        val future: ListenableFuture<Int> =
            getUnusedAppRestrictionsStatus(this)
        future.addListener(
            { onUnusedAppRestrictionsStatus(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The permission to access the contacts also needs to be granted at run time.
        // Request the contacts permission right at the start. That also simplifies the application
        // flow as we can be sure the permission was granted before a button is pressed.
        if (this.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            this.requestContactsPermission()
        }

        // Permissions can be reset and app might be hibernated if it isn't used for a long time.
        // Make sure the user disables those features.
        val future: ListenableFuture<Int> =
            getUnusedAppRestrictionsStatus(this)
        future.addListener(
            { onUnusedAppRestrictionsStatus(future.get()) },
            ContextCompat.getMainExecutor(this)
        )

        // Create the NotificationChannel
        val name = R.string.notification_channel_name.toString()
        val descriptionText = R.string.notification_channel_description.toString()
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(CHANNEL_ID, name, importance)
        channel.description = descriptionText
        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)


        setContentView(R.layout.activity_main)

        val directoryString = getOutputDirectory(this)
        // Hide details if the directory isn't set yet.
        if (directoryString == null) {
            hideMoreInfo()
        } else {
            // The directory might no longer be accessible, hence check that it is. If it is not,
            // reset the stored directory setting.
            val directory = Uri.parse(directoryString)
            val outputDir = DocumentFile.fromTreeUri(this, directory)
            if (outputDir != null && !outputDir.canWrite()) {
                hideMoreInfo()
                resetPreferences(this)
            }
            // A directory is already set, schedule a job, so that next time the contacts change, they
            // are exported. This case usually doesn't happen, but it's there in case the job isn't
            // scheduled anymore, so that it is enough to just re-open the app.
            else {
                scheduleJob(this)
            }
        }
    }

    // Hides additional information that is only shown if a working directory was selected.
    private fun hideMoreInfo() {
        findViewById<TextView>(R.id.main_text).visibility = View.GONE
        findViewById<Button>(R.id.exit).visibility = View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            READ_CONTACTS_PERMISSION_CODE -> {
                // Permission granted.
                if ((grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED)
                ) {
                    // Save the contacts if a directory was already selected. This case should only
                    // happen if the application once had access to the contacts, but that access
                    // was revoked and then granted again.
                    val directoryString = getOutputDirectory(this)
                    if (directoryString != null) {
                        saveVcf(this)
                    }
                }
                // Permission is not granted.
                else {
                    val dialog = AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_permissions_title))

                    // Permission is permanently blocked
                    if (this.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED &&
                        !this.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
                    ) {
                        dialog.setMessage(getString(R.string.dialog_permissions_blocked_permanent))
                            .setNegativeButton(getString(R.string.dialog_permissions_button_exit)) { _: DialogInterface, _: Int ->
                                finish()
                            }
                            .setPositiveButton(getString(R.string.dialog_permissions_button_settings)) { _: DialogInterface, _: Int ->
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null)
                                )
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                            }
                    }
                    // Permission was denied, but it wasn't permanently blocked, hence we ask to request it again
                    else {
                        dialog.setTitle(getString(R.string.dialog_permissions_title))
                            .setMessage(getString(R.string.dialog_permissions_blocked))
                            .setPositiveButton(getString(R.string.dialog_permissions_button_rerequest)) { _: DialogInterface, _: Int ->
                                this.requestContactsPermission()
                            }
                    }
                    dialog.create()
                        .show()

                }
            }
        }
    }

    private fun onUnusedAppRestrictionsStatus(appRestrictionsStatus: Int) {
        when (appRestrictionsStatus) {
            // Couldn't fetch status. Check logs for details.
            ERROR -> {}

            // Restrictions don't apply to your app on this device.
            FEATURE_NOT_AVAILABLE -> {}

            // The user has disabled restrictions for your app.
            DISABLED -> {}

            // If the app isn't started for a few month, it gets hibernated on systems >= 12
            // (API level >= 31). This will make the application silently stop working. In order to
            // prevent that, the user needs to disable this feature, before this app starts working.
            // Earlier versions < 12 (API level < 31) only removes the granted permissions. This
            // also makes is not desirable, as this app should do it's job in the background, with
            // the user needed to be bothered.
            // Hence make both cases an hard error with a slightly different text
            API_30_BACKPORT, API_30, API_31 -> {
                val dialog = AlertDialog.Builder(this)
                if (appRestrictionsStatus == API_31) {
                    dialog
                        .setTitle(getString(R.string.dialog_permissions_hibernation_title))
                        .setMessage(getString(R.string.dialog_permissions_hibernation_text))
                } else {
                    dialog
                        .setTitle(getString(R.string.dialog_permissions_unused_title))
                        .setMessage(getString(R.string.dialog_permissions_unused_text))
                }
                dialog
                    .setPositiveButton(getString(R.string.dialog_permissions_button_settings)) { _: DialogInterface, _: Int ->
                        val intent = IntentCompat.createManageUnusedAppRestrictionsIntent(
                            this,
                            this.packageName
                        )
                        this.unusedAppRestrictions.launch(intent)
                    }
                    .create()
                    .show()
            }
        }
    }

    private fun requestContactsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CONTACTS),
            READ_CONTACTS_PERMISSION_CODE
        )
    }

    // Select a directory where the contacts get exported into. Once selected, the contacts are
    // exported already once and a job is started to export the contacts whenever the contacts
    // change. Even after a reboot the contacts are exported, without starting the application
    // again.
    @Suppress("UNUSED_PARAMETER")
    fun selectDirectory(view: View) {
        this.selectDirectory.launch(null)
    }

    // Exit the application, it's called by clicking on the "Exit" button.
    @Suppress("UNUSED_PARAMETER")
    fun exitApp(view: View) {
        finishAndRemoveTask()
    }
}

fun getOutputDirectory(context: Context): String? {
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    return preferences?.getString(PREFERENCES_KEY_DIRECTORY, null)
}

// Sets the output directory, if `null` is given, it will remove the directory setting.
fun setOutputDirectory(context: Context, uri: Uri?) {
    val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, AppCompatActivity.MODE_PRIVATE).edit()
    if (uri == null) {
        preferences.remove(PREFERENCES_KEY_DIRECTORY)
    } else {
        preferences.putString(PREFERENCES_KEY_DIRECTORY, uri.toString())
    }
    preferences.apply()
}

// Returns the hash of contacts of the last export (if there was any).
fun getContactsHash(context: Context): ByteArray? {
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val baseEncoded = preferences?.getString(PREFERENCES_KEY_HASH, null)
    return if (baseEncoded == null) {
        null
    } else {
        Base64.decode(baseEncoded, DEFAULT)
    }
}

// Sets the hash of the contacts of the last export, if `null` is given, it will remove the
// hash setting.
fun setContactsHash(context: Context, hash: ByteArray?) {
    val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, AppCompatActivity.MODE_PRIVATE).edit()
    if (hash == null) {
        preferences.remove(PREFERENCES_KEY_HASH)
    } else {
        // Hex encoding would be nicer, but keep it simple.
        val baseEncoded = Base64.encodeToString(hash, DEFAULT)
        preferences.putString(PREFERENCES_KEY_HASH, baseEncoded)
    }
    preferences.apply()
}

// Reset all preferences. This is in done when encountering failures.
fun resetPreferences(context: Context) {
    setOutputDirectory(context, null)
    setContactsHash(context, null)
}

fun showNotificationSuccess(context: Context) {
    val builder = NotificationCompat.Builder(context, context.getString(R.string.app_name))
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notification_title))
        .setContentText(context.getString(R.string.notification_text))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setChannelId(CHANNEL_ID)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
    NotificationManagerCompat.from(context).notify(1, builder.build())
}

// Show a notification if there is something wrong with the permission to the contacts/
fun permissionError(context: Context) {
    showNotificationError(
        context,
        R.string.notification_error_permission_title,
        R.string.notification_error_permission_text
    )
}

// Show a notification if there is something wrong with the directory we store the exports in.
fun directoryError(context: Context) {
    // If something is wrong with the directory, reset its value, so that it can be set again.
    resetPreferences(context)
    showNotificationError(
        context,
        R.string.notification_error_directory_title,
        R.string.notification_error_directory_text
    )
}

// Show a notification if there is something wrong with the directory we store the exports in.
fun showNotificationError(context: Context, titleResource: Int, textResource: Int) {
    val intent = Intent(context, MainActivity::class.java).apply {
        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE
    )
    val builder = NotificationCompat.Builder(context, context.getString(R.string.app_name))
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(titleResource))
        .setContentText(context.getString(textResource))
        .setColorized(true)
        .setColor(0xffff0000.toInt())
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setChannelId(CHANNEL_ID)
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
    NotificationManagerCompat.from(context).notify(1, builder.build())
}

// Code is based on
// https://stackoverflow.com/questions/8147563/export-the-contacts-as-vcf-file/49361046#49361046
fun saveVcf(context: Context) {
    // In case the permission to the directory was revoked, send an error via notification
    val directoryString = getOutputDirectory(context)
    if (directoryString == null) {
        directoryError(context)
        return
    }

    // In case the permission to the contacts was revoked, send an error via notification
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        permissionError(context)
        return
    }

    val phones = context.contentResolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
        ),
        null,
        null,
        // Sort by lookup key, so that we can filter out duplicates easily.
        ContactsContract.Contacts.LOOKUP_KEY,
    )

    if (phones != null) {
        // Temporarily store all contacts in memory, so that they can be hashed in order to find
        // out whether they changed compared to the previous export or not.
        val contacts = ByteArrayOutputStream()

        // Store the previous lookup key, so that we can skip duplicated entries.
        var prevLookupKey: String? = null
        while (phones.moveToNext()) {
            val lookupKey =
                phones.getString(phones.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))
            if (lookupKey != prevLookupKey) {
                val uri =
                    Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)

                val fd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                val inputStream = fd?.createInputStream()
                inputStream?.copyTo(contacts)
            }
            prevLookupKey = lookupKey
        }
        phones.close()

        val contactsHash = MessageDigest.getInstance("SHA-256").digest(contacts.toByteArray())
        val prevContactsHash = getContactsHash(context)
        // Only do the actual export if the contacts changed since the last export.
        if (!contactsHash.contentEquals(prevContactsHash)) {
            val time = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            val timestamp = time.format(formatter)

            val directory = Uri.parse(directoryString)
            val outputDir = DocumentFile.fromTreeUri(context, directory)
            val outputStream: OutputStream
            // If there is anything wrong with accessing/writing files, display an error
            try {
                val outputFile = outputDir!!.createFile("text/vCard", "contacts_$timestamp.vcf")!!
                outputStream = context.contentResolver.openOutputStream(outputFile.uri)!!
            } catch (exception: Exception) {
                directoryError(context)
                return
            }

            contacts.writeTo(outputStream)
            setContactsHash(context, contactsHash)
            showNotificationSuccess(context)
        }
    }
}

fun scheduleJob(context: Context) {
    // There will always only be a single job at a time, hence we can hard-code the job ID.
    val jobId = 1

    val component = ComponentName(context, ExportContactsJob::class.java)
    val jobInfo = JobInfo.Builder(jobId, component)
        .addTriggerContentUri(
            TriggerContentUri(
                ContactsContract.Contacts.CONTENT_VCARD_URI,
                0
            )
        )
        .build()
    val jobScheduler = context.getSystemService(JobScheduler::class.java)
    jobScheduler.schedule(jobInfo)
}

class StartAppOnBoot : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (Intent.ACTION_BOOT_COMPLETED == intent!!.action) {
            scheduleJob(context!!)
        }
    }
}

class ExportContactsJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        // Export the contacts.
        saveVcf(appContext)
        // Export was done, reschedule this job for the next time contacts are changed.
        scheduleJob(appContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}