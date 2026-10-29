package app.orchard.tv.apps

import android.content.Context
import app.orchard.tv.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import org.json.JSONObject

/**
 * Names typed for apps on the phone page, by package name, kept in the settings prefs as JSON.
 * Home shows them in place of the app's own label (HomeViewModel.arrange); the tile art never
 * carries an app's name, so nothing is drawn again. Inputs are named in SourceLooks, folders in
 * LayoutStore.
 */
object AppNames {
    private const val PREFS = "settings"
    private const val KEY = "app_names"
    const val MAX_NAME = 24

    var all by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private lateinit var app: Context
    private lateinit var builtIns: Map<String, String>

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val json = runCatching { JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "{}") }
            .getOrDefault(JSONObject())
        builtIns = if (ConfigurationCompat.getLocales(app.resources.configuration)[0]?.language == "zh") {
            defaults.mapValues { app.getString(it.value) }
        } else emptyMap()
        all = builtIns + json.keys().asSequence().associateWith { json.optString(it) }.filterValues { it.isNotBlank() }
    }

    private val defaults = mapOf(
        "com.gitvdemo.video" to R.string.app_name_iqiyi,
        "com.cibn.tv" to R.string.app_name_youku,
        "com.xiaodianshi.tv.yst" to R.string.app_name_bilibili_tv,
        "blbl.cat3399" to R.string.app_name_blbl,
        "com.google.android.youtube.tv" to R.string.app_name_youtube,
        "org.smarttube.stable" to R.string.app_name_smarttube,
        "com.fongmi.android.tv" to R.string.app_name_fongmi,
    )

    fun of(key: String): String? = all[key]

    /** A blank name restores the built-in name, or the app's own. Call on the main thread: Home watches [all]. */
    fun rename(key: String, name: String?) {
        val clean = name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
        val nameToShow = clean ?: builtIns[key]
        all = if (nameToShow == null) all - key else all + (key to nameToShow)
        val json = runCatching { JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        if (clean == null) json.remove(key) else json.put(key, clean)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, json.toString()) }
    }
}
