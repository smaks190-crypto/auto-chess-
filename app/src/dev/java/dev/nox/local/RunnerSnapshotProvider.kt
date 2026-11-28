package dev.nox.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Rect
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Dev build only. No cross-app snapshots, no action execution, no clipboard reads. */
class RunnerSnapshotProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != 2000) throw SecurityException("ADB shell only")
        if (method != "snapshot" || arg != null || extras?.isEmpty == false)
            throw IllegalArgumentException("Only snapshot")
        val ctx = requireNotNull(context)
        val task = FutureTask { snapshot(ctx.packageName) }
        val handler = Handler(Looper.getMainLooper())
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else handler.post(task)
        val result = try { task.get(900, TimeUnit.MILLISECONDS) }
        catch (_: Exception) { task.cancel(false); JSONObject().put("ok", false).put("error", "snapshot unavailable") }
        return Bundle().apply { putString("json", result.toString()) }
    }
    private fun snapshot(pkg: String): JSONObject {
        val ctx = requireNotNull(context)
        if (!DeviceInteraction.unlocked(ctx) || !MainActivity.isVisible)
            return JSONObject().put("ok", false).put("error", "Own visible unlocked activity required")
        val nav = NoxNavigationService.current
            ?: return JSONObject().put("ok", false).put("error", "Accessibility not connected")
        val root = nav.rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "No root")
        if (root.packageName?.toString() != pkg)
            return JSONObject().put("ok", false).put("error", "Different package")
        val start = SystemClock.elapsedRealtime()
        val nodes = JSONArray()
        var complete = true
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || visited++ >= 240 || SystemClock.elapsedRealtime() - start > 450L) {
                complete = false; return
            }
            if (n.packageName?.toString() != pkg || n.isPassword) return
            val b = Rect()
            n.getBoundsInScreen(b)
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            if (t.length > 1000 || d.length > 1000) complete = false
            if (n.isVisibleToUser) nodes.put(JSONObject()
                .put("text", t.take(1000)).put("desc", d.take(1000))
                .put("id", n.viewIdResourceName.orEmpty())
                .put("package", pkg).put("clickable", n.isClickable)
                .put("enabled", n.isEnabled).put("editable", n.isEditable)
                .put("focused", n.isFocused).put("bounds", "[${b.left},${b.top}][${b.right},${b.bottom}]"))
            for (i in 0 until n.childCount) {
                if (!complete) break
                n.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        if (!DeviceInteraction.unlocked(ctx) || !MainActivity.isVisible ||
            nav.rootInActiveWindow?.let { it.packageName?.toString() == pkg && it.windowId == root.windowId } != true)
            return JSONObject().put("ok", false).put("error", "Window changed")
        return JSONObject().put("ok", complete).put("package", pkg).put("nodes", nodes)
            .put("ms", SystemClock.elapsedRealtime() - start)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
