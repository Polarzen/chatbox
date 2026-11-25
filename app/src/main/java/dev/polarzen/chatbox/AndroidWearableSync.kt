package dev.polarzen.chatbox

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

private const val HISTORY_MS = 30L * 86_400_000L
private val metricNames = setOf("steps", "sleep", "exercise", "heart_rate")
private fun JSONArray.strings() = (0 until length()).map { getString(it) }.toSet()
private fun JSONObject.metrics() = getJSONArray("metrics").strings()

internal fun wearableIndexSurvives(metric: String, destination: JSONObject, policies: List<JSONObject>): Boolean =
    policies.isNotEmpty() && (policies + destination).all { policy ->
        metric in policy.metrics() && policy.optString("source") == destination.optString("source") &&
            policy.optString("profile") == destination.optString("profile")
    }

/** The transport turns every failure before a successful address proof into DeviceAddressFailure.
 * Only an authenticated destination's 401 is a terminal acknowledgement of revoked access. */
internal suspend fun verifiedWearableRevocation(send: suspend () -> Unit): Boolean = try {
    send()
    true
} catch (e: CancellationException) { throw e }
catch (e: SharedHttpFailure) { e.status == 401 }
catch (_: Exception) { false }

private fun WearableSettings.wire(zone: String = ZoneId.systemDefault().id) =
    JSONObject().put("profile", profile).put("sid", sid).put("source", source)
        .put("metrics", JSONArray(metrics.sorted())).put("timezone", zone)
        .put("morningTime", morningTime).put("waitMinutes", windowMinutes)
        .put("workoutEnabled", exerciseEvents).put("morningEnabled", morningTask)
        .put("notifyWorkout", notifyWorkout && exerciseEvents).put("notifyMorning", notifyMorning && morningTask)

private fun JSONObject.settings() = WearableSettings(metrics(), getString("profile"), getString("sid"),
    getBoolean("workoutEnabled"), getBoolean("morningEnabled"), getBoolean("notifyWorkout"),
    getBoolean("notifyMorning"), getString("morningTime"),
    getInt("waitMinutes"), getString("source"))

internal class AndroidWearableSync private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("wearable-sharing", Context.MODE_PRIVATE)
    private val cipher = AndroidSharedCipher("chatbox.wearable.outbox", "wearable-v1".toByteArray())
    private val health = WearableHealthSource(context)
    private val api = SharedDeviceTransport(false)
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = Mutex()
    private val changes = Any()
    private val ui = MutableStateFlow(snapshot())
    val state: StateFlow<WearableSyncState> = ui
    @Volatile private var visible = false
    private var foregroundJob: Job? = null
    private var watchingNetwork = false
    private val online = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { kick() }
    }

    private fun json(key: String): JSONObject? = prefs.getString(key, null)?.let(::JSONObject)
    private fun protected(value: JSONObject) = Base64.encodeToString(cipher.encrypt(value.toString(), true), Base64.NO_WRAP)
    private fun decrypted(key: String): JSONObject? = prefs.getString(key, null)?.let {
        JSONObject(cipher.decrypt(Base64.decode(it, Base64.NO_WRAP)))
    }
    private fun actualBinding(): DeviceBinding? = AndroidDeviceStore(context).use { store ->
        store.grant()?.takeIf { it.second }?.first
    }
    private fun savedBinding(): DeviceBinding? = decrypted("peer")?.let {
        DeviceBinding(it.getString("address"), it.getString("instance"), it.getString("device"), it.getString("token"), false)
    }
    private fun DeviceBinding.same(other: DeviceBinding?) = instance == other?.instance && device == other.device
    private fun DeviceBinding.protect() = protected(JSONObject().put("address", address).put("instance", instance)
        .put("device", device).put("token", token))

    private fun snapshot(notice: String = ""): WearableSyncState {
        val settings = json("desired")?.settings() ?: WearableSettings()
        val statuses = json("status") ?: JSONObject()
        val values = statuses.keys().asSequence().filter { it in settings.metrics }.associateWith { statuses.getString(it) }
        val pendingRevoke = prefs.contains("revokes")
        val message = notice.ifEmpty { if (pendingRevoke) "原配对设备的健康撤销正在等待送达。" else "" }
        return WearableSyncState(settings, prefs.getLong("lastSync", 0L), values,
            values.filterValues { it == "ok" }.mapValues { setOf(settings.source) }, message,
            prefs.getBoolean("dirty", false) || prefs.contains("clear") || prefs.contains("barrier") ||
                pendingRevoke && !prefs.contains("peer"))
    }

    @Synchronized fun foreground(active: Boolean) {
        if (visible == active) return
        visible = active
        updateNetworkWatcher()
        if (active) kick() else foregroundJob?.cancel()
    }

    @Synchronized private fun updateNetworkWatcher() {
        val required = visible && hasWork()
        if (required == watchingNetwork) return
        if (required) network.registerDefaultNetworkCallback(online) else network.unregisterNetworkCallback(online)
        watchingNetwork = required
    }

    @Synchronized private fun kick() {
        if (!visible || !hasWork() || foregroundJob?.isActive == true) return
        foregroundJob = scope.launch { runSync(background = false) }
    }

    private fun schedule() {
        val work = hasWork()
        WearableJobs.schedule(context, periodic = work, retry = work)
        updateNetworkWatcher()
        kick()
    }

    /** Settings edits are committed locally before the scheduler or transport can observe them. */
    suspend fun save(settings: WearableSettings) = withContext(Dispatchers.IO) {
        require(settings.metrics.isNotEmpty() && metricNames.containsAll(settings.metrics) &&
            settings.source in WearableHealthSource.miFitnessPackages && settings.profile.isNotBlank() &&
            (settings.sid.isNotBlank() || !settings.notifyWorkout && !settings.notifyMorning))
        val binding = checkNotNull(actualBinding()) { "请先配对电脑。" }
        val permissions = health.grantedPermissions()
        synchronized(changes) {
            val old = savedBinding()
            if (old != null && !binding.same(old)) retirePeer(old)
            trimDistance(settings, permissions)
            val edit = prefs.edit().putString("peer", binding.protect())
                .putBoolean("accessible", true)
                .putBoolean("distance", WearableHealthSource.distancePermission in permissions)
                .putString("desired", settings.wire().toString())
            configure(edit, effective(settings, permissions))
            check(edit.commit())
            ui.value = snapshot()
        }
        schedule()
    }

    private fun effective(settings: WearableSettings, permissions: Set<String>): JSONObject {
        val metrics = settings.metrics.filterTo(mutableSetOf()) { WearableHealthSource.permissionFor(it) in permissions }
        if ("exercise" !in metrics) metrics.remove("heart_rate")
        return settings.copy(metrics = metrics, exerciseEvents = settings.exerciseEvents && "exercise" in metrics,
            morningTask = settings.morningTask && "sleep" in metrics).wire()
    }

    private fun trimDistance(settings: WearableSettings, permissions: Set<String>) {
        if (!prefs.getBoolean("distance", false) || WearableHealthSource.distancePermission in permissions ||
            "exercise" !in json("policy")?.metrics().orEmpty()) return
        // Distance is an optional field of an exercise. Fence and purge the old exercise
        // scope even when background reads cannot yet rebuild those rows without distance.
        val edit = prefs.edit().putBoolean("distance", false)
        configure(edit, effective(settings.copy(metrics = settings.metrics - "exercise"), permissions), force = true)
        check(edit.commit())
    }

    private fun configure(edit: SharedPreferences.Editor, body: JSONObject, force: Boolean = false) {
        val previous = json("policy")
        if (!force && previous?.let { JSONObject(it.toString()).apply { remove("revision") }.toString() } == body.toString()) return
        var revision = prefs.getLong("revision", 0L)
        // If an undelivered restriction would be widened again, deliver the common
        // narrower policy first. A stale grant can never precede a pending revocation.
        val previousBarrier = json("barrier")
        val scopes = listOfNotNull(previous, previousBarrier).toMutableList()
        val pending = previousBarrier ?: previous?.takeIf { prefs.getBoolean("dirty", false) }
        if (pending != null) {
            val old = pending.settings()
            val next = body.settings()
            val sameScope = old.profile == next.profile && old.source == next.source
            val common = if (sameScope) old.metrics intersect next.metrics else emptySet()
            val lower = next.copy(metrics = common,
                notifyWorkout = next.notifyWorkout && old.notifyWorkout && old.sid == next.sid,
                notifyMorning = next.notifyMorning && old.notifyMorning && old.sid == next.sid,
                exerciseEvents = next.exerciseEvents && old.exerciseEvents,
                morningTask = next.morningTask && old.morningTask)
            if (lower != next) {
                val barrier = lower.wire().put("revision", ++revision)
                edit.putString("barrier", barrier.toString())
                scopes.add(barrier)
            } else edit.remove("barrier")
        }
        val interrupted = if (body.metrics().isEmpty()) null else decrypted("batch")
        for (metric in metricNames) {
            if (!wearableIndexSurvives(metric, body, scopes)) {
                edit.remove("index-$metric").remove("ready-$metric")
            } else if (interrupted?.getString("metric") == metric) {
                // A previous upload might have reached the service before this policy edit.
                // Reconcile its IDs at strictly newer versions after the consent fence.
                val index = json("index-$metric") ?: JSONObject()
                val stamps = interrupted.getJSONObject("stamps")
                for (id in stamps.keys()) {
                    val stamp = stamps.getJSONObject(id)
                    stamp.put("hash", "unconfirmed").put("start", stamp.optLong("start", stamp.getLong("end")))
                    index.put(id, stamp)
                }
                edit.putString("index-$metric", index.toString())
            }
        }
        edit.putLong("revision", ++revision).putString("policy", body.put("revision", revision).toString())
            .putBoolean("dirty", true).remove("batch").putLong("cursor", 0L)
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        synchronized(changes) { stopLocked() }
        schedule()
    }

    private fun stopLocked() {
        if (!prefs.contains("peer")) return
        val edit = prefs.edit().remove("desired").remove("status").remove("lastSync").remove("clear")
        configure(edit, WearableSettings().wire())
        check(edit.commit())
        ui.value = snapshot()
    }

    private fun hasWork() = prefs.contains("peer") || prefs.contains("revokes")

    private fun cancelIfIdle() = synchronized(changes) {
        if (!hasWork()) {
            WearableJobs.schedule(context, periodic = false, retry = false)
            updateNetworkWatcher()
        }
    }

    private suspend fun request(binding: DeviceBinding, action: String, body: JSONObject): JSONObject = try {
        api.wearable(binding, action, body)
    } catch (e: SharedHttpFailure) {
        if (e.status == 401) {
            synchronized(changes) {
                if (binding.same(savedBinding())) {
                    val revokes = prefs.getString("revokes", null)
                    val edit = prefs.edit().clear().putLong("revision", prefs.getLong("revision", 0L))
                    if (revokes != null) edit.putString("revokes", revokes)
                    check(edit.commit())
                    ui.value = snapshot("电脑已撤销此手机的健康共享权限。")
                }
            }
            cancelIfIdle()
        }
        throw e
    }

    // A local disconnect removes the chat credential. Retain its encrypted copy only for
    // this exact empty-policy request; a replacement pairing must obtain its own consent.
    private fun retirePeer(old: DeviceBinding) {
        val revision = prefs.getLong("revision", 0L) + 1L
        val queue = decrypted("revokes") ?: JSONObject()
        queue.put(old.device, JSONObject().put("peer", prefs.getString("peer", null))
            .put("body", WearableSettings().wire().put("revision", revision)))
        check(prefs.edit().clear().putLong("revision", revision).putString("revokes", protected(queue)).commit())
        ui.value = snapshot()
    }

    private suspend fun sendRevocations(): Boolean {
        val queue = synchronized(changes) { decrypted("revokes") } ?: return true
        var done = true
        for (id in queue.keys()) {
            val item = queue.getJSONObject(id)
            val peer = JSONObject(cipher.decrypt(Base64.decode(item.getString("peer"), Base64.NO_WRAP)))
            val binding = DeviceBinding(peer.getString("address"), peer.getString("instance"), id, peer.getString("token"), false)
            val body = item.getJSONObject("body")
            val confirmed = verifiedWearableRevocation {
                check(api.wearable(binding, "config", body).getLong("revision") == body.getLong("revision"))
            }
            if (!confirmed) { done = false; continue }
            synchronized(changes) {
                val pending = decrypted("revokes") ?: return@synchronized
                pending.remove(id)
                val edit = prefs.edit()
                if (pending.length() == 0) edit.remove("revokes") else edit.putString("revokes", protected(pending))
                check(edit.commit())
                ui.value = snapshot()
            }
        }
        return done
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(changes) {
            val policy = json("policy") ?: return@synchronized
            val edit = prefs.edit().putBoolean("clear", true)
                .putLong("since", System.currentTimeMillis()).remove("status").remove("lastSync")
            configure(edit, JSONObject(policy.toString()).apply { remove("revision") }, force = true)
            for (metric in metricNames) edit.remove("index-$metric").remove("ready-$metric")
            check(edit.commit())
            ui.value = snapshot()
        }
        schedule()
    }

    /** Explicit user action only. A timeout leaves rotation uncertain and is never retried here. */
    suspend fun issueCredential(): String = withContext(Dispatchers.IO) {
        runner.withLock {
            val binding = checkNotNull(actualBinding())
            check(binding.same(savedBinding()))
            prepare(binding)
            controls(binding)
            val policy = synchronized(changes) {
                check(binding.same(savedBinding()))
                checkNotNull(json("policy")).also { checkCurrent(it.getLong("revision")) }
            }
            check(policy.metrics().isNotEmpty())
            request(binding, "credential", JSONObject().put("revision", policy.getLong("revision")))
                .getString("token").also {
                    check(it.isNotBlank())
                    synchronized(changes) { checkCurrent(policy.getLong("revision")) }
                }
        }
    }

    private suspend fun prepare(current: DeviceBinding?) {
        synchronized(changes) {
            val peer = savedBinding() ?: return
            if (!peer.same(current)) { retirePeer(peer); return }
            if (!prefs.contains("desired")) return
        }
        val permissions = try { if (health.available) health.grantedPermissions() else null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        synchronized(changes) {
            val peer = savedBinding() ?: return
            if (!peer.same(current)) throw Changed()
            val settings = json("desired")?.settings() ?: return
            if (permissions != null) trimDistance(settings, permissions)
            val accessible = permissions != null
            val edit = prefs.edit().putBoolean("accessible", accessible)
            if (permissions != null) edit.putBoolean("distance", WearableHealthSource.distancePermission in permissions)
            val verified = permissions ?: json("policy")?.metrics().orEmpty().map {
                WearableHealthSource.permissionFor(it)
            }.toSet()
            configure(edit, effective(settings, verified), force = accessible != prefs.getBoolean("accessible", false))
            val status = json("status") ?: JSONObject()
            for (metric in settings.metrics) {
                if (permissions == null || WearableHealthSource.permissionFor(metric) !in permissions) {
                    status.put(metric, if (accessible) "no_permission" else "source_unavailable")
                }
            }
            edit.putString("status", status.toString())
            if (peer.address != current?.address && current != null) edit.putString("peer", current.protect())
            check(edit.commit())
            ui.value = snapshot()
        }
    }

    private suspend fun controls(binding: DeviceBinding) {
        for (key in listOf("barrier", "policy")) {
            val body = synchronized(changes) {
                if (!binding.same(savedBinding())) throw Changed()
                if (key == "policy" && prefs.contains("barrier")) throw Changed()
                if (key == "policy" && !prefs.getBoolean("dirty", false)) null else json(key)
            } ?: continue
            val rev = body.getLong("revision")
            val response = request(binding, "config", body)
            check(response.getLong("revision") == rev && response.getLong("cursor") == 0L)
            synchronized(changes) {
                if (json(key)?.getLong("revision") != rev) throw Changed()
                val edit = prefs.edit()
                if (key == "barrier") edit.remove(key) else edit.putBoolean("dirty", false)
                if (key == "policy" && !prefs.contains("desired") && body.metrics().isEmpty()) {
                    // Keep only the monotonic counter for a future grant on this device.
                    edit.remove("peer").remove("policy").remove("since")
                }
                check(edit.commit())
                ui.value = snapshot()
            }
        }
        if (prefs.contains("clear")) {
            val before = synchronized(changes) {
                if (!binding.same(savedBinding()) || prefs.getBoolean("dirty", false)) throw Changed()
                checkNotNull(json("policy"))
            }
            val rev = before.getLong("revision")
            check(request(binding, "clear", JSONObject().put("revision", rev)).getLong("revision") == rev)
            synchronized(changes) {
                if (prefs.getLong("revision", 0L) != rev) throw Changed()
                val edit = prefs.edit().remove("clear")
                configure(edit, before.apply { remove("revision") }, force = true)
                check(edit.commit())
            }
            controls(binding)
        }
    }

    private class Changed : Exception()

    suspend fun runSync(background: Boolean): Boolean = withContext(Dispatchers.IO) {
        runner.withLock {
            try {
                if (!hasWork()) return@withLock true
                val current = actualBinding()
                // Stop/clear are independent of the health provider or background permission.
                if (prefs.contains("peer")) prepare(current)
                val revoked = sendRevocations()
                val binding = savedBinding()
                if (binding == null) {
                    cancelIfIdle()
                    return@withLock revoked
                }
                controls(binding)
                val policy = json("policy")
                if (policy != null && policy.metrics().isNotEmpty()) {
                    check(binding.same(current))
                    if (prefs.getBoolean("accessible", false) && background && !visible && (!health.backgroundAvailable() ||
                        WearableHealthSource.backgroundPermission !in health.grantedPermissions())) {
                        synchronized(changes) { ui.value = snapshot("系统尚未允许后台读取，打开 Chatbox 后将继续同步。") }
                    } else syncRecords(binding, policy)
                }
                cancelIfIdle()
                revoked
            } catch (e: CancellationException) { throw e }
            catch (_: Changed) { false }
            catch (e: Exception) {
                if (e is SharedHttpFailure && e.status == 401 && !hasWork()) return@withLock true
                val message = if (e is SharedHttpFailure) "共享服务返回 HTTP ${e.status}，请检查授权和服务版本。"
                    else "手环数据同步尚未完成，将在网络或系统条件允许时重试。"
                synchronized(changes) { ui.value = snapshot(message) }
                false
            }
        }
    }

    private fun checkCurrent(revision: Long) {
        if (prefs.getLong("revision", 0L) != revision || prefs.getBoolean("dirty", false) ||
            prefs.contains("barrier") || prefs.contains("clear")) throw Changed()
    }

    private suspend fun syncRecords(binding: DeviceBinding, policy: JSONObject) {
        val revision = policy.getLong("revision")
        val source = policy.getString("source")
        val end = System.currentTimeMillis()
        val start = maxOf(end - HISTORY_MS, prefs.getLong("since", 0L))
        if (end <= start) return
        decrypted("batch")?.let { deliver(binding, it) }
        suspend fun read(metric: String, workouts: List<JSONObject> = emptyList()): List<JSONObject>? {
            synchronized(changes) { checkCurrent(revision) }
            if (!prefs.getBoolean("accessible", false)) return null
            if (!visible && (!health.backgroundAvailable() ||
                WearableHealthSource.backgroundPermission !in health.grantedPermissions())) throw Changed()
            return try {
                health.readMetric(metric, source, start, end, workouts).filter { it.getLong("endMs") <= end }
            } catch (e: CancellationException) { throw e }
            catch (e: SecurityException) {
                prepare(actualBinding())
                synchronized(changes) { checkCurrent(revision) }
                null
            } catch (_: Exception) { null }
        }
        val metrics = policy.metrics()
        val workouts = if ("exercise" in metrics) read("exercise") else null
        // Publish workout-scoped samples first so a newly enqueued exercise can query them.
        for (metric in listOf("steps", "sleep", "heart_rate", "exercise").filter { it in metrics }) {
            val index = json("index-$metric") ?: JSONObject()
            val rows = when (metric) {
                "exercise" -> workouts
                "heart_rate" -> workouts?.let { read(metric, it) }
                else -> read(metric)
            }
            val status = when {
                metric == "heart_rate" && "exercise" !in metrics -> "no_permission"
                rows == null -> "source_unavailable"
                rows.isEmpty() -> "no_records"
                else -> "ok"
            }
            val diff = rows?.let { wearableDiff(metric, source, it, index, start, end) }.orEmpty()
            val initial = !prefs.getBoolean("ready-$metric", false)
            // The service suppresses events on the first cursor of every revision. An existing
            // exercise scope can survive a schedule edit; preserve new workouts on that cursor.
            val needsFirstCursor = metric == "exercise" && !initial && diff.isNotEmpty() && prefs.getLong("cursor", 0L) == 0L
            val changes = wearableBatches(diff)
            val chunks = (if (needsFirstCursor) sequenceOf(emptyList<WearableChange>()) + changes else changes).iterator()
            do {
                val chunk = if (chunks.hasNext()) chunks.next() else emptyList()
                val updates = JSONObject()
                val records = JSONArray()
                val deletes = JSONArray()
                for (change in chunk) {
                    updates.put(change.row.getString("id"), change.stamp)
                    (if (change.deleted) deletes else records).put(change.row)
                }
                val batch = synchronized(changes) {
                    checkCurrent(revision)
                    val wire = JSONObject().put("revision", revision).put("cursor", prefs.getLong("cursor", 0L) + 1L)
                        .put("initial", initial).put("records", records).put("deletes", deletes)
                        .put("statuses", JSONObject().put(metric, status))
                    JSONObject().put("body", wire).put("metric", metric).put("stamps", updates)
                        .put("ready", rows != null && !chunks.hasNext()).put("start", start).also {
                            check(prefs.edit().putString("batch", protected(it)).commit())
                        }
                }
                deliver(binding, batch)
            } while (chunks.hasNext())
        }
    }

    private suspend fun deliver(binding: DeviceBinding, batch: JSONObject) {
        val body = batch.getJSONObject("body")
        val revision = body.getLong("revision")
        val cursor = body.getLong("cursor")
        val metric = batch.getString("metric")
        val current = actualBinding()
        if (!binding.same(current)) {
            prepare(current)
            throw Changed()
        }
        val records = body.getJSONArray("records")
        if (records.length() > 0) {
            val permissions = health.grantedPermissions()
            if (WearableHealthSource.permissionFor(metric) !in permissions || metric == "heart_rate" &&
                WearableHealthSource.permissionFor("exercise") !in permissions ||
                WearableHealthSource.distancePermission !in permissions &&
                (0 until records.length()).any { records.getJSONObject(it).has("distanceMeters") }) {
                prepare(current)
                throw Changed()
            }
        }
        synchronized(changes) { checkCurrent(revision) }
        val result = request(binding, "sync", body)
        check(result.getLong("revision") == revision && result.getLong("cursor") == cursor)
        synchronized(changes) {
            checkCurrent(revision)
            val index = json("index-$metric") ?: JSONObject()
            val stamps = batch.getJSONObject("stamps")
            for (id in stamps.keys()) index.put(id, stamps.getJSONObject(id))
            val start = batch.getLong("start")
            index.keys().asSequence().filter { index.getJSONObject(it).getLong("end") < start }.toList().forEach(index::remove)
            val status = json("status") ?: JSONObject()
            status.put(metric, body.getJSONObject("statuses").getString(metric))
            val edit = prefs.edit().putString("index-$metric", index.toString()).putString("status", status.toString())
                .putLong("cursor", cursor).putLong("lastSync", result.getLong("lastSyncMs")).remove("batch")
            if (batch.getBoolean("ready")) edit.putBoolean("ready-$metric", true)
            check(edit.commit())
            ui.value = snapshot()
        }
    }

    companion object {
        @Volatile private var instance: AndroidWearableSync? = null
        fun get(context: Context): AndroidWearableSync = instance ?: synchronized(this) {
            instance ?: AndroidWearableSync(context.applicationContext).also { instance = it }
        }
    }
}
