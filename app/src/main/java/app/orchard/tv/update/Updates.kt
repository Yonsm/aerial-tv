package app.orchard.tv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import app.orchard.tv.BuildConfig
import app.orchard.tv.guard.GuardSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update installation support. Aerial does not check releases or restore cached release notices;
 * an APK is downloaded only when an existing caller explicitly starts [install].
 */
object Updates {
    private const val TAG = "AerialUpdates"
    const val RELEASES_URL = "https://github.com/Yonsm/aerial-tv/releases/latest"

    /**
     * A source that delivers less than [STALL_BYTES] in [STALL_MS] is given up for the next one
     * (issue #390). A slow but moving download (about 8 KB/s and up) still finishes.
     */
    private const val STALL_MS = 30_000L
    private const val STALL_BYTES = 256 * 1024L

    /** This build's version, e.g. "1.17" (the guard build's "-guard" left off). */
    val current: String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** Checks are disabled, so Control Center never exposes a release notice. */
    val available: String? = null

    /** Where an install started from Control Center has got to. */
    var install by mutableStateOf<Install>(Install.Idle); private set

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        // A download left from an install that has since finished or been given up.
        scope.launch(Dispatchers.IO) {
            apkFile(context).takeIf { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.delete()
        }
    }

    fun diagnostics(): String = if (started) "checks disabled" else "not initialized"

    // ---- Installing the new version from Control Center ----

    sealed interface Install {
        data object Idle : Install
        data class Downloading(val fraction: Float) : Install
        /** The system installer is open; it asks the owner and replaces Aerial, which restarts. */
        data object Opened : Install
        /** Aerial may not install apps yet; the TV's setting for it is open. */
        data object NeedsPermission : Install
        /** Android 6 firmware requires storage access before the APK can be saved. */
        data object NeedsStoragePermission : Install
        data class Failed(val reason: Failure) : Install
    }

    enum class Failure {
        /** GitHub did not give a complete APK. */
        Download,
        /** The TV lets no app hand an APK to its installer, or has no installer to hand it to. */
        Blocked,
        /** This copy is not the official package or not signed with the official key. */
        NotOfficial,
        /** The downloaded APK is older than this build. */
        NotNewer,
    }

    /** The official package. Copies renamed for one firmware (OPPO, see docs/devices) cannot update from the release. */
    private const val PACKAGE = "app.orchard.tv"

    /** Whether this copy can update itself from the release at all. */
    fun canInstall(context: Context): Boolean = context.packageName == PACKAGE

    /**
     * Downloads this flavor's APK of the latest release and opens the system installer on it.
     * Not PackageInstaller sessions: TCL's verifier (docs/devices/tcl-mt5879-cn.md) passes only
     * installs that go through com.android.packageinstaller, as Dangbei's do, and an ACTION_VIEW on
     * the APK makes the installer itself the one installing. Android 13 and later, a session first
     * (installBySession). The APK must be this package, signed
     * with this copy's key and no older, or the installer would only fail with a less clear message.
     */
    fun install(context: Context) {
        if (install is Install.Downloading) return
        val app = context.applicationContext
        if (!canInstall(app)) { install = Install.Failed(Failure.NotOfficial); return }
        if (LegacyUpdateStorage.needsPermission(app)) {
            install = Install.NeedsStoragePermission
            LegacyUpdateStorage.requestPermission(context)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            // Xiaomi MiTV-MFTR0 has this page; TCL MT5879 does not (tools/install.sh grants it there).
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(settings) }.isSuccess) {
                install = Install.NeedsPermission
                return
            }
            // No page to allow it on (Xiaomi MiTV-MFTP0, Android 9, has only Xiaomi's own settings:
            // "this TV does not let apps install apps", issue #284). Firmware installers often let
            // it through or ask themselves, so the download goes ahead and the installer decides.
            Log.i(TAG, "not allowed to install and no page to allow it on; trying the installer anyway")
        }
        install = Install.Downloading(0f)
        scope.launch {
            val file = apkFile(app)
            val ready = withContext(Dispatchers.IO) {
                // A download kept from a moment ago, when the owner backed out of the installer.
                verify(app, file)?.takeIf { testApk == null && it.versionName?.substringBefore('-') == available }
                    ?: downloadFrom(app, file)
            }
            val failure = when {
                ready == null -> Failure.Download
                !sameSigner(app, ready) -> Failure.NotOfficial
                versionCode(ready) < BuildConfig.VERSION_CODE -> Failure.NotNewer
                else -> null
            }
            if (failure != null) {
                Log.w(TAG, "not installing: $failure")
                if (failure != Failure.Download) file.delete()
                install = Install.Failed(failure)
                return@launch
            }
            val opened = if (Build.VERSION.SDK_INT >= 33) installBySession(app, file) else openInstaller(context, app, file)
            install = if (opened) Install.Opened else Install.Failed(Failure.Blocked)
        }
    }

    /**
     * From Android 13 the installer's ACTION_VIEW records the APK as a local file (packageSource 3),
     * and Android then restricts the app's settings (ACCESS_RESTRICTED_SETTINGS deny; seen on the
     * Android 13 TV emulator). On TCL T966D5 (Android 14) Home Guard was off after every update made
     * this way, and stayed on after one pushed by 多屏互动 (issue #723). A session that says where
     * the APK came from is not restricted; the system still asks the owner to confirm. Where the
     * firmware turns the session down, the installer's ACTION_VIEW as before (UpdateResult).
     */
    @RequiresApi(33)
    private fun installBySession(app: Context, file: File): Boolean = runCatching {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("aerial.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = Intent(app, UpdateResult::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            GuardSetup.noteInstallerOpened(app)
            session.commit(PendingIntent.getBroadcast(app, id, result, flags).intentSender)
        }
        Log.i(TAG, "install session $id committed for ${file.length()} bytes")
        true
    }.getOrElse {
        Log.w(TAG, "no install session; opening the installer", it)
        openInstaller(app, app, file)
    }

    /** The session's answer (UpdateResult): the owner's confirmation, or the installer instead. */
    internal fun onSessionResult(context: Context, intent: Intent) {
        val app = context.applicationContext
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
                    // Some Android 13+ firmware installers have no CONFIRM_INSTALL page but still
                    // take the APK by ACTION_VIEW (issue #827, model not known yet).
                    Log.w(TAG, "could not ask to confirm the update; opening the installer", it)
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    if (id >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(id) }
                    val file = apkFile(app)
                    if (!file.isFile || !openInstaller(app, app, file)) install = Install.Failed(Failure.Blocked)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // this process is replaced
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "update not confirmed")
                install = Install.Idle
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install session failed ($status, $message); opening the installer")
                val file = apkFile(app)
                if (!file.isFile || !openInstaller(app, app, file)) install = Install.Failed(Failure.Blocked)
            }
        }
    }

    /** If the installer closed without replacing Aerial, reset its button. */
    fun onHomeResumed() {
        if (install == Install.Opened) install = Install.Idle
    }

    fun onHomePaused() = Unit

    /** Back to the button, when the update page is left. */
    fun resetInstall() {
        if (install !is Install.Downloading) install = Install.Idle
    }

    private fun apkFile(context: Context): File {
        // Android 5 and 6 installers read a file:// URI, so there the APK sits where they can read it.
        val dir = if (Build.VERSION.SDK_INT >= 24) context.cacheDir else context.externalCacheDir ?: context.cacheDir
        return File(File(dir, "updates"), "aerial.apk")
    }

    /**
     * For testing the whole update on a TV before a release (ConfigReceiver's update_test): the APK
     * pushed to Android/data/app.orchard.tv/files/update-test.apk stands in for the download.
     */
    @Volatile var testApk: File? = null

    /** Downloads from this fork's release. Returns the APK's info once it parses. */
    private suspend fun downloadFrom(context: Context, file: File): PackageInfo? {
        testApk?.takeIf { it.isFile }?.let { test ->
            file.parentFile?.mkdirs()
            test.copyTo(file, overwrite = true)
            if (Build.VERSION.SDK_INT < 24) file.setReadable(true, false)
            return verify(context, file)
        }
        val name = "aerial-${BuildConfig.FLAVOR}.apk"
        val sources = listOf(
            "$RELEASES_URL/download/$name",
        )
        for (url in sources) {
            val ok = runCatching { download(url, file) }
                .onFailure { Log.w(TAG, "could not download $url", it) }
                .isSuccess
            if (ok) verify(context, file)?.let { return it }
            file.delete()
        }
        return null
    }

    private suspend fun download(url: String, file: File) {
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create update directory: $directory" }
        check(directory.canWrite()) { "Cannot write update directory: $directory" }
        val part = File(directory, file.name + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        LegacyUpdateTls.configure(appContext, c)
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        // A throttled route can keep the TLS handshake up and deliver a byte now and then, so no
        // read() ever blocks long enough to time out and the progress bar sits at 0% for good
        // (issue #390, FFALCON/TCL on China Unicom). So the bytes are counted, not each read timed.
        var windowAt = SystemClock.elapsedRealtime()
        var windowFrom = 0L
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${c.responseCode}")
            val total = c.contentLength.toLong()
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - windowAt >= STALL_MS) {
                            if (done - windowFrom < STALL_BYTES) error("stalled: ${done - windowFrom} bytes in ${(now - windowAt) / 1000} s")
                            windowAt = now
                            windowFrom = done
                        }
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            withContext(Dispatchers.Main) { install = Install.Downloading(percent / 100f) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) error("got $done of $total bytes")
            if (!part.renameTo(file)) error("could not keep the download")
            // The installer on Android 5 and 6 opens the file itself.
            if (Build.VERSION.SDK_INT < 24) file.setReadable(true, false)
        } finally {
            c.disconnect()
            part.delete()
        }
    }

    /** The APK's package info when it parses as this package, else null. */
    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File): PackageInfo? {
        if (!file.isFile) return null
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = runCatching { context.packageManager.getPackageArchiveInfo(file.path, flags) }.getOrNull() ?: return null
        return info.takeIf { it.packageName == context.packageName }
    }

    /** Whether [apk] is signed like this copy. If the archive's signers cannot be read, the installer decides. */
    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, apk: PackageInfo): Boolean {
        val pm = context.packageManager
        fun signers(info: PackageInfo?): Set<String>? {
            val sigs = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.apkContentsSigners else info?.signatures
            return sigs?.takeIf { it.isNotEmpty() }?.map { it.toCharsString() }?.toSet()
        }
        val theirs = signers(apk) ?: return true
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val ours = signers(runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()) ?: return true
        return theirs == ours
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun openInstaller(context: Context, app: Context, file: File): Boolean {
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        else Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            GuardSetup.noteInstallerOpened(app)
            context.startActivity(intent)
            Log.i(TAG, "installer opened for ${file.length()} bytes")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no installer", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "installer refused", e)
            false
        }
    }
}
