package app.orchard.tv.apps

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Home's order, folders and hidden apps as the user arranges them (HANDOFF: "自动化测试"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LayoutStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    // Through android.graphics: Compose's ImageBitmap(w, h) passes a color space Robolectric's Bitmap lacks.
    private val art = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        .let { TileArt(it, null, Rect.Zero, Color.Black, it) }

    private fun app(pkg: String, label: String = pkg.substringAfterLast('.'), removable: Boolean = true) =
        AppEntry(pkg, label, ComponentName(pkg, "$pkg.Main"), leanback = true, removable = removable, art = art)

    private val a = app("test.a")
    private val b = app("test.b")
    private val c = app("test.c")
    private val d = app("test.d")
    private val installed = listOf(a, b, c, d)

    private fun folderTile(f: Folder, apps: List<AppEntry>) = app(f.key, f.name)

    /** The keys of Home's tiles, a folder's as its key. */
    private fun LayoutStore.home(all: List<AppEntry> = installed) = arrange(all, ::folderTile).home.map { it.key }

    /** A store whose first arrangement is [installed] in label order (a, b, c, d). */
    private fun store() = LayoutStore(context).also { it.home() }

    @Test fun moveForwardTakesTheTargetsSlot() {
        val s = store()
        s.move("test.a", "test.c")
        assertEquals(listOf("test.b", "test.c", "test.a", "test.d"), s.home())
    }

    @Test fun moveBackTakesTheTargetsSlot() {
        val s = store()
        s.move("test.d", "test.b")
        assertEquals(listOf("test.a", "test.d", "test.b", "test.c"), s.home())
    }

    @Test fun moveOverAHiddenAppLandsOnTheVisibleSlot() {
        val s = store()
        s.hide("test.b")
        s.move("test.a", "test.c")
        assertEquals(listOf("test.c", "test.a", "test.d"), s.home())
        s.move("test.a", "test.c")
        assertEquals(listOf("test.a", "test.c", "test.d"), s.home())
    }

    @Test fun moveOntoItselfOrAnUnknownAppChangesNothing() {
        val s = store()
        s.move("test.b", "test.b")
        s.move("test.b", "test.missing")
        s.move("test.missing", "test.b")
        assertEquals(listOf("test.a", "test.b", "test.c", "test.d"), s.home())
    }

    @Test fun theOrderOutlivesTheStore() {
        store().move("test.d", "test.a")
        assertEquals(listOf("test.d", "test.a", "test.b", "test.c"), LayoutStore(context).home())
    }

    @Test fun newAppsJoinAtTheFrontAndMissingOnesKeepTheirPlace() {
        val s = store()
        s.move("test.d", "test.a")
        // b is updating: gone for now, back in its place after.
        assertEquals(listOf("test.d", "test.a", "test.c"), s.home(listOf(a, c, d)))
        val e = app("test.e")
        assertEquals(listOf("test.e", "test.d", "test.a", "test.b", "test.c"), s.home(installed + e))
    }

    @Test fun aNewFolderTakesTheAppsPlace() {
        val s = store()
        val id = s.putInFolder("test.b", null, "Kids")
        val folder = FOLDER_KEY_PREFIX + id
        assertEquals(listOf("test.a", folder, "test.c", "test.d"), s.home())
        s.putInFolder("test.d", id, "")
        assertEquals(listOf("test.a", folder, "test.c"), s.home())
        assertEquals(listOf("test.b", "test.d"), s.folderHolding("test.d")?.apps)
    }

    @Test fun takenOutOfAFolderTheAppSitsJustAfterIt() {
        val s = store()
        val id = s.putInFolder("test.b", null, "Kids")
        s.putInFolder("test.c", id, "")
        s.takeOutOfFolder("test.b")
        assertEquals(listOf("test.a", FOLDER_KEY_PREFIX + id, "test.b", "test.d"), s.home())
        assertNull(s.folderHolding("test.b"))
    }

    @Test fun aFolderWhoseLastAppLeavesGoesAway() {
        val s = store()
        val id = s.putInFolder("test.b", null, "Kids")
        s.takeOutOfFolder("test.b")
        assertEquals(listOf("test.a", "test.b", "test.c", "test.d"), s.home())
        assertEquals(emptyList<Folder>(), s.folders())
        // The id is gone too: a later folder does not find the old one.
        assertEquals(id, s.putInFolder("test.c", null, "Again"))
    }

    @Test fun ungroupPutsTheFoldersAppsInItsPlace() {
        val s = store()
        val id = s.putInFolder("test.c", null, "Kids")
        s.putInFolder("test.a", id, "")
        assertEquals(listOf("test.b", FOLDER_KEY_PREFIX + id, "test.d"), s.home())
        s.ungroup(id)
        assertEquals(listOf("test.b", "test.c", "test.a", "test.d"), s.home())
    }

    @Test fun aFolderOfHiddenAppsShowsNoTileButKeepsThem() {
        val s = store()
        val id = s.putInFolder("test.b", null, "Kids")
        s.hide("test.b")
        assertEquals(listOf("test.a", "test.c", "test.d"), s.home())
        s.unhide("test.b")
        assertEquals(listOf("test.a", FOLDER_KEY_PREFIX + id, "test.c", "test.d"), s.home())
    }

    @Test fun moveInFolderReordersOnlyThatFolder() {
        val s = store()
        val id = s.putInFolder("test.a", null, "Kids")
        s.putInFolder("test.b", id, "")
        s.putInFolder("test.c", id, "")
        s.moveInFolder("test.a", "test.c")
        assertEquals(listOf("test.b", "test.c", "test.a"), s.folderHolding("test.a")?.apps)
        s.moveInFolder("test.a", "test.d") // d is on Home, not in the folder
        assertEquals(listOf("test.b", "test.c", "test.a"), s.folderHolding("test.a")?.apps)
    }

    @Test fun firstRunPutsStreamingFirstAndSettingsLast() {
        val youtube = app("com.google.android.youtube.tv", "YouTube")
        val settings = app("com.android.tv.settings", "Settings")
        val other = app("test.other", "阿里")
        val home = LayoutStore(context).home(listOf(settings, other, youtube))
        assertEquals(listOf(youtube.key, other.key, settings.key), home)
    }

    @Test fun firstRunUsesLocalizedLabelFallback() {
        val alpha = app("test.alpha", "alpha")
        val bravo = app("test.bravo", "Bravo")
        val zulu = app("test.zulu", "Zulu")

        assertEquals(
            listOf(alpha.key, bravo.key, zulu.key),
            LayoutStore(context).home(listOf(zulu, bravo, alpha)),
        )
    }

    @Test fun systemAppsUseTheSameLocalizedFallback() {
        val zulu = app("test.system.zulu", "Zulu", removable = false)
        val alpha = app("test.system.alpha", "Alpha", removable = false)

        assertEquals(listOf(alpha.key, zulu.key), LayoutStore(context).home(listOf(zulu, alpha)))
    }

    @Test fun firstRunHidesAospSettingsBesideXiaomis() {
        val aosp = app("com.android.tv.settings", "Settings")
        val xiaomi = app("com.xiaomi.mitv.settings", "设置")
        val arrangement = LayoutStore(context).arrange(listOf(aosp, xiaomi), ::folderTile)
        assertEquals(listOf(xiaomi.key), arrangement.home.map { it.key })
        assertEquals(listOf(aosp.key), arrangement.hidden.map { it.key })
    }
}
