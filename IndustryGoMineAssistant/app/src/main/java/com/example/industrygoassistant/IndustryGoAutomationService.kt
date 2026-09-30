package com.example.industrygoassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors

class IndustryGoAutomationService : AccessibilityService() {
    companion object {
        private const val TARGET = "de.industrygo.app"
        @Volatile private var instance: IndustryGoAutomationService? = null

        fun requestStart(context: Context) {
            context.getSharedPreferences("config", Context.MODE_PRIVATE)
                .edit().putBoolean("pendingStart", true).apply()
            instance?.startAutomation()
        }

        fun requestStop(context: Context) {
            context.getSharedPreferences("config", Context.MODE_PRIVATE)
                .edit().putBoolean("pendingStart", false).apply()
            instance?.stopAutomation("Manuell gestoppt")
        }

        fun isConnected(): Boolean = instance != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var automationJob: Job? = null
    @Volatile private var running = false
    private val candidatePoints = ArrayDeque<android.graphics.PointF>()
    private var lastArea: LocalArea? = null
    private var successfulBuilds = 0
    private var skippedPoints = 0

    override fun onServiceConnected() {
        instance = this
        publishStatus("Bedienungshilfe verbunden")
        if (prefs().getBoolean("pendingStart", false)) startAutomation()
    }

    override fun onDestroy() {
        stopAutomation("Dienst beendet")
        instance = null
        scope.cancel()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = stopAutomation("Bedienungshilfe unterbrochen")

    private fun startAutomation() {
        if (running) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !ScreenCaptureService.isReady()) {
            publishStatus("Bildschirmfreigabe erforderlich – Mine Assistant erneut öffnen")
            return
        }
        running = true
        prefs().edit().putBoolean("pendingStart", true).apply()
        automationJob = scope.launch { automationLoop() }
        publishStatus("Automatik gestartet – Industry GO öffnen")
    }

    private fun stopAutomation(reason: String) {
        running = false
        automationJob?.cancel()
        automationJob = null
        candidatePoints.clear()
        prefs().edit().putBoolean("pendingStart", false).apply()
        publishStatus(reason)
    }

    private suspend fun automationLoop() {
        while (running) {
            if (rootInActiveWindow?.packageName?.toString() != TARGET) {
                publishStatus("Warte auf Industry GO …")
                delay(700)
                continue
            }

            val mapShot = screenshot()
            if (mapShot == null) {
                publishStatus("Screenshot nicht möglich – Berechtigung prüfen")
                delay(1200)
                continue
            }

            if (candidatePoints.isEmpty()) {
                val area = GreenAreaDetector.detect(mapShot)
                if (area == null) {
                    publishStatus("96-m-Bereich nicht sicher erkannt – Zoom/Karte prüfen")
                    delay(1600)
                    continue
                }
                lastArea = area
                val localDiameter = prefs().getFloat("localDiameterMeters", 96f)
                val mineDiameter = prefs().getFloat("targetMineDiameterMeters", 20f)
                val margin = prefs().getFloat("safetyMarginMeters", 0.75f)
                DenseCirclePlanner.plan(area, localDiameter, mineDiameter, margin)
                    .filterNot { ScreenSafety.looksOccupied(mapShot, it) }
                    .forEach { candidatePoints.addLast(it) }
                publishStatus("Raster erkannt: ${candidatePoints.size} freie Kandidaten")
                if (candidatePoints.isEmpty()) {
                    delay(1500)
                    continue
                }
            }

            val point = candidatePoints.removeFirst()
            if (!insideDetectedArea(point)) {
                skippedPoints++
                continue
            }

            publishStatus("Prüfe Rasterpunkt ${successfulBuilds + skippedPoints + 1}")
            tap(point.x, point.y)
            delay(450)

            // Sondieren
            if (!clickText("Sondieren", "Probe", "Survey")) {
                val shot = screenshot()
                val text = shot?.let { OcrAnalyzer.recognize(it) }
                val probe = OcrAnalyzer.findButtonCenter(text, "Sondieren", "Probe", "Survey")
                if (probe != null) tap(probe.first, probe.second)
                else tapNormalized("probeX", "probeY", 0.20f, 0.83f, shot)
            }
            delay(prefs().getLongCompat("probeWaitMs", 1150L))

            var shot = screenshot() ?: continue
            var text = OcrAnalyzer.recognize(shot)
            val best = OcrAnalyzer.chooseBest(text)
            if (best == null) {
                skippedPoints++
                publishStatus("Keine zulässige Qualität erkannt – Punkt übersprungen")
                closeTransientIfPossible(text)
                delay(350)
                continue
            }

            publishStatus("Beste Auswahl: ${best.resource.displayName} ${best.quality}%")

            // If Industry GO still shows "Keine Quelle", best is an alternative row.
            // Tap the row and explicitly verify that the game changed to the selected
            // resource and now shows "Frei". This prevents the loop from immediately
            // starting a new sondierung when the alternative tap was missed.
            if (OcrAnalyzer.containsNoSource(text)) {
                var selected = false
                for (attempt in 0 until 3) {
                    val target = OcrAnalyzer.alternativeTapPoint(best, shot.width)
                    tap(target.first, target.second)
                    delay(450L + attempt * 180L)
                    val updatedShot = screenshot() ?: continue
                    shot = updatedShot
                    text = OcrAnalyzer.recognize(shot)
                    if (OcrAnalyzer.selectionLooksApplied(text, best)) {
                        selected = true
                        break
                    }
                }
                if (!selected) {
                    skippedPoints++
                    publishStatus("Alternative ${best.resource.displayName} ${best.quality}% wurde nicht übernommen")
                    delay(450)
                    continue
                }
            }

            publishStatus("Auswahl aktiv – öffne Baudialog")

            // Click the large bottom Bauen button. Prefer the lowest OCR match because
            // the map can contain other labels with similar text.
            shot = screenshot() ?: continue
            text = OcrAnalyzer.recognize(shot)
            val mainBuild = OcrAnalyzer.findLowestButtonCenter(text, "Bauen", "Build", "Errichten")
            if (mainBuild != null) {
                tap(mainBuild.first, mainBuild.second)
            } else if (!clickLowestText("Bauen", "Build", "Errichten")) {
                tapNormalized("buildX", "buildY", 0.59f, 0.83f, shot)
            }

            // Industry GO briefly shows "Bau wird vorbereitet..." before the dialog.
            // Poll instead of relying on one fixed delay, which is unreliable on
            // mobile data or slower phones.
            var verification: BuildVerification? = null
            for (attempt in 0 until 8) {
                delay(350)
                val dialogShot = screenshot() ?: continue
                shot = dialogShot
                text = OcrAnalyzer.recognize(shot)
                val v = OcrAnalyzer.verifyBuildDialog(text, best)
                if (v.safeToBuild) {
                    verification = v
                    break
                }
            }

            val verified = verification
            if (verified == null || !verified.safeToBuild) {
                skippedPoints++
                val reason = OcrAnalyzer.verifyBuildDialog(text, best).reason
                publishStatus("Baudialog nicht freigegeben: $reason")
                clickText("Abbrechen", "Cancel")
                delay(350)
                continue
            }

            val actualDiameter = verified.mineDiameterMeters?.toFloat() ?: prefs().getFloat("targetMineDiameterMeters", 20f)
            if (!insideDetectedAreaForDiameter(point, actualDiameter)) {
                skippedPoints++
                publishStatus("Nicht gebaut: ${actualDiameter.toInt()}-m-Mine würde 96-m-Bereich überschreiten")
                clickText("Abbrechen", "Cancel")
                candidatePoints.clear()
                continue
            }
            // Remember 20/26/32 m for the next adaptive re-plan.
            prefs().edit().putFloat("targetMineDiameterMeters", actualDiameter).apply()

            val dryRun = prefs().getBoolean("dryRun", true)
            if (dryRun) {
                publishStatus("TEST: ${best.resource.displayName} ${best.quality}% – ${verified.mineDiameterMeters ?: "?"} m, ohne Nexus")
                clickText("Abbrechen", "Cancel")
                delay(350)
                continue
            }

            // The dialog contains both the heading BAUEN and the final button.
            // Accessibility click prefers an actual clickable node; OCR fallback
            // chooses the lowest matching 'Bauen' text on the dialog.
            // v0.4: use the lowest OCR occurrence first. On Industry GO the dialog
            // heading and the confirmation button both say "Bauen"; using the first
            // OCR match can therefore hit the heading and do nothing.
            val finalBuild = OcrAnalyzer.findLowestButtonCenter(text, "Bauen", "Build")
            if (finalBuild != null) {
                tap(finalBuild.first, finalBuild.second)
            } else if (!clickLowestText("Bauen", "Build")) {
                publishStatus("Finalen Bauen-Button nicht sicher gefunden")
                clickText("Abbrechen", "Cancel")
                continue
            }
            delay(prefs().getLongCompat("buildWaitMs", 1450L))

            shot = screenshot() ?: continue
            text = OcrAnalyzer.recognize(shot)
            // One retry is useful on slower phones / mobile data: if the dialog is
            // still visible, tap the same confirmed button once more.
            if (!OcrAnalyzer.isBuildSuccess(text) && OcrAnalyzer.verifyBuildDialog(text, best).safeToBuild) {
                OcrAnalyzer.findLowestButtonCenter(text, "Bauen", "Build")?.let { tap(it.first, it.second) }
                delay(1100L)
                shot = screenshot() ?: continue
                text = OcrAnalyzer.recognize(shot)
            }
            if (OcrAnalyzer.isBuildSuccess(text)) {
                successfulBuilds++
                publishStatus("Mine #$successfulBuilds gebaut: ${best.resource.displayName} ${best.quality}%")
                // Re-scan the map after every successful build so the next plan
                // uses the newly occupied area instead of stale candidate points.
                candidatePoints.clear()
                delay(700)
            } else {
                skippedPoints++
                publishStatus("Keine Erfolgsmeldung erkannt – Raster wird neu geprüft")
                candidatePoints.clear()
                delay(700)
            }
        }
    }

    private fun insideDetectedArea(p: android.graphics.PointF): Boolean =
        insideDetectedAreaForDiameter(p, prefs().getFloat("targetMineDiameterMeters", 20f))

    private fun insideDetectedAreaForDiameter(p: android.graphics.PointF, mineDiameter: Float): Boolean {
        val area = lastArea ?: return false
        val localDiameter = prefs().getFloat("localDiameterMeters", 96f)
        val pxPerMeter = area.radiusPx * 2f / localDiameter
        val margin = prefs().getFloat("safetyMarginMeters", 0.75f)
        val allowed = area.radiusPx - ((mineDiameter / 2f) + margin / 2f) * pxPerMeter
        val dx = p.x - area.center.x
        val dy = p.y - area.center.y
        return dx * dx + dy * dy <= allowed * allowed
    }

    private fun clickText(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val match = nodes.firstOrNull { n ->
            val value = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ")
            labels.any { value.contains(it, ignoreCase = true) }
        } ?: return false
        return clickNodeOrParent(match)
    }

    private fun clickLowestText(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val matches = nodes.mapNotNull { n ->
            val value = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ")
            if (labels.any { value.equals(it, ignoreCase = true) || value.contains(it, ignoreCase = true) }) {
                val r = android.graphics.Rect(); n.getBoundsInScreen(r); n to r
            } else null
        }
        val best = matches.maxByOrNull { it.second.centerY() }?.first ?: return false
        return clickNodeOrParent(best)
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectNodes(it, out) }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        repeat(5) {
            if (n?.isClickable == true && n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            n = n?.parent
        }
        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            tap(rect.exactCenterX(), rect.exactCenterY())
            return true
        }
        return false
    }

    private suspend fun closeTransientIfPossible(text: com.google.mlkit.vision.text.Text?) {
        OcrAnalyzer.findButtonCenter(text, "Abbrechen", "Cancel", "Schließen", "Close")?.let { tap(it.first, it.second) }
    }

    private fun tapNormalized(xKey: String, yKey: String, dx: Float, dy: Float, bitmap: Bitmap?) {
        if (bitmap == null) return
        val x = prefs().getFloat(xKey, dx).coerceIn(0f, 1f) * bitmap.width
        val y = prefs().getFloat(yKey, dy).coerceIn(0f, 1f) * bitmap.height
        tap(x, y)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 75))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val bitmap = ScreenCaptureService.latestBitmap()
            if (cont.isActive) cont.resume(bitmap) {}
            return@suspendCancellableCoroutine
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (cont.isActive) cont.resume(copy) {}
                }
                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        )
    }

    private fun prefs() = getSharedPreferences("config", MODE_PRIVATE)

    private fun publishStatus(message: String) {
        Log.i("IGOA", message)
        prefs().edit()
            .putString("status", message)
            .putInt("successfulBuilds", successfulBuilds)
            .putInt("skippedPoints", skippedPoints)
            .apply()
        sendBroadcast(android.content.Intent("com.example.industrygoassistant.STATUS").setPackage(packageName))
    }

    private fun android.content.SharedPreferences.getLongCompat(key: String, defaultValue: Long): Long {
        return try { getLong(key, defaultValue) } catch (_: ClassCastException) { defaultValue }
    }
}
