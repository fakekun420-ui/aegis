package com.aegis.hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class OpencodeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: OpencodeAccessibilityService? = null
        @Volatile var lastEventPkg: String = ""
        @Volatile var needsA11yRepair: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Self-heal (pending a11y persistence): the framework strips this service
        // from enabled_accessibility_services on force-stop/crash. Re-add ourselves
        // alongside whatever is already enabled (BAXA etc.).
        // NOTE: app uid cannot write Secure settings (WRITE_SECURE_SETTINGS is
        // signature|privileged) and su shells from the app ns (5508) can reach
        // NEITHER /system/bin/settings NOR /usr/bin/node (verified). So the write
        // is delegated: CompanionService exposes POST /a11y-repair on :8766, and the
        // HUB (running in ubuntu chroot ns with full tooling) performs the
        // `settings put secure` via its own root shell on next /status poll.
        // Here we only flag the need.
        try {
            val cur = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val me = "${packageName}/.OpencodeAccessibilityService"
            needsA11yRepair = !cur.split(":").contains(me)
        } catch (_: Exception) { needsA11yRepair = true }
    }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let { lastEventPkg = it.toString() }
    }
    override fun onInterrupt() {}

    // --- acciones expuestas al bridge HTTP + TTS ---
    fun clickByText(text: String, exact: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = if (exact) root.findAccessibilityNodeInfosByText(text)
                    else findByContains(root, text)
        val target = nodes.firstOrNull { it.isClickable || it.isCheckable } ?: nodes.firstOrNull()
        return target?.let { clickNode(it) } ?: false
    }

    fun clickById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = if (Build.VERSION.SDK_INT >= 18)
            root.findAccessibilityNodeInfosByViewId(viewId) else emptyList()
        return nodes.firstOrNull()?.let { clickNode(it) } ?: false
    }

    fun setTextById(viewId: String, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = if (Build.VERSION.SDK_INT >= 18) root.findAccessibilityNodeInfosByViewId(viewId) else emptyList()
        val n = nodes.firstOrNull() ?: return false
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun tapAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < 24) {
            // fallback a shell
            RootShell.tap(x, y); return true
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        var ok = false
        dispatchGesture(gesture, object : GestureResultCallback(){
            override fun onCompleted(g: GestureDescription?) { ok = true; super.onCompleted(g) }
        }, null)
        // no bloqueamos; asumimos enviado
        return true
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun dumpWindow(maxChars: Int = 12000): String {
        val root = rootInActiveWindow ?: return "<no window>"
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (sb.length > maxChars) return
            val r = Rect(); n.getBoundsInScreen(r)
            val id = if (Build.VERSION.SDK_INT >= 18) n.viewIdResourceName ?: "" else ""
            val txt = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val cls = n.className?.toString() ?: ""
            val clickable = if (n.isClickable) " clickable" else ""
            sb.append("  ".repeat(depth))
            sb.append("$cls id=$id text=\"$txt\" desc=\"$desc\" bounds=$r$clickable\n")
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it, depth+1); it.recycle() }
            }
        }
        walk(root, 0)
        return sb.toString().take(maxChars)
    }

    private fun findByContains(root: AccessibilityNodeInfo, q: String): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun dfs(n: AccessibilityNodeInfo) {
            val t = n.text?.toString() ?: ""
            val d = n.contentDescription?.toString() ?: ""
            if (t.contains(q, true) || d.contains(q, true)) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { dfs(it) }
        }
        dfs(root)
        return out
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var p: AccessibilityNodeInfo? = n.parent
        while (p != null) {
            if (p.isClickable) { val ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); return ok }
            val pp = p.parent; p.recycle(); p = pp
        }
        // fallback: tap en centro del bounds
        val r = Rect(); n.getBoundsInScreen(r)
        if (!r.isEmpty) return tapAt(r.centerX(), r.centerY())
        return false
    }
}
