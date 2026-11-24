package dev.polarzen.chatbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A transmission-ready, immutable copy of a successfully verified connection. */
internal class DesktopSendTarget internal constructor(
    val profileId: String,
    val address: String,
    val token: String,
    val allowLoopbackHttp: Boolean,
    internal val bindingGeneration: Long,
    private val targetCheck: () -> Unit,
) {
    fun checkTarget() = targetCheck()

    override fun toString(): String =
        "DesktopSendTarget(profileId=$profileId, address=$address, token=[redacted], allowLoopbackHttp=$allowLoopbackHttp)"
}

/** Compose-facing owner of profile selection, key verification, and send-target snapshots. */
internal class DesktopConnection internal constructor(
    val profileId: String,
    val address: String,
    val profiles: List<Profile>,
    val instances: List<LauncherInstance>,
    val keyList: KeyList,
    val connected: Boolean,
    val verifying: Boolean,
    val pendingKeyId: String?,
    val error: String?,
    val keyErrors: Map<String, String>,
    val activeLabel: String?,
    private val selectProfileAction: (String, String) -> Unit,
    private val adoptSelectedProfileAction: (Profile) -> Unit,
    private val useAddressAction: (String) -> Unit,
    private val verifyAction: () -> Unit,
    private val selectKeyAction: (String) -> Unit,
    private val addKeyAction: (String, String) -> Unit,
    private val renameKeyAction: (String, String) -> Unit,
    private val removeKeyAction: (String) -> Unit,
    private val snapshotAction: () -> DesktopSendTarget?,
    private val invalidateAction: (DesktopSendTarget) -> Unit,
) {
    fun selectProfile(id: String, address: String) = selectProfileAction(id, address)
    /** Call on the UI thread inside the same synchronized(store) block as selectReplyIfPresent. */
    fun adoptSelectedProfileForReply(profile: Profile) = adoptSelectedProfileAction(profile)
    fun useAddress(address: String) = useAddressAction(address)
    fun verify() = verifyAction()
    fun selectKey(id: String) = selectKeyAction(id)
    fun addKey(name: String, secret: String) = addKeyAction(name, secret)
    fun renameKey(id: String, name: String) = renameKeyAction(id, name)
    fun removeKey(id: String) = removeKeyAction(id)
    fun snapshot(): DesktopSendTarget? = snapshotAction()
    fun invalidateIfCurrent(target: DesktopSendTarget) = invalidateAction(target)
}

internal data class ConnectionSelection(val profileId: String, val address: String, val keyId: String?)

/** Shared generation gate for slow verification results and their durable active-key commit. */
internal class DesktopConnectionRequestGate {
    private var generation = 0L
    private var selection: ConnectionSelection? = null

    @Synchronized
    fun begin(selection: ConnectionSelection): Long {
        generation++
        this.selection = selection
        return generation
    }

    @Synchronized
    fun currentGeneration(): Long = generation

    @Synchronized
    fun isCurrent(generation: Long, selection: ConnectionSelection): Boolean =
        this.generation == generation && this.selection == selection

    @Synchronized
    fun commitIfCurrent(generation: Long, selection: ConnectionSelection, commit: () -> Unit): Boolean {
        if (this.generation != generation || this.selection != selection) return false
        commit()
        return true
    }
}

private data class VerifiedBinding(
    val generation: Long,
    val profileId: String,
    val address: String,
    val keyId: String,
    val token: String,
    val allowLoopbackHttp: Boolean,
    val launcherIdentity: String?,
)

private data class VerifiedCandidate(
    val address: String,
    val allowLoopbackHttp: Boolean,
    val launcherIdentity: String?,
)

private data class VerificationCommit(
    val binding: VerifiedBinding,
    val keyList: KeyList,
    val profiles: List<Profile>,
)

private data class VerificationProbe(
    val binding: VerifiedBinding,
    val sessions: Set<String>,
)

/**
 * Keeps connection state observable by Compose. Filesystem, process discovery, DPAPI, and network
 * work are kept on IO threads; a generation check prevents an old result from replacing newer UI
 * selection or reactivating a key after a later request has superseded it.
 */
@Composable
internal fun rememberDesktopConnection(
    store: DesktopStore, keys: NamedKeys,
    withLegacyDraftsSaved: suspend (Set<String>, Set<String>, suspend () -> Set<String>) -> Set<String> = { _, _, adopt -> adopt() },
): DesktopConnection {
    val scope = rememberCoroutineScope()
    val firstProfile = remember(store) {
        store.activeProfileId.takeIf { store.profile(it) != null } ?: store.profiles.firstOrNull()?.id.orEmpty()
    }
    var profileId by remember(store) { mutableStateOf(firstProfile) }
    var address by remember(store) { mutableStateOf(store.profile(firstProfile)?.address ?: store.lastServer) }
    var profiles by remember(store) { mutableStateOf(store.profiles) }
    var instances by remember { mutableStateOf(emptyList<LauncherInstance>()) }
    var keyList by remember { mutableStateOf(KeyList(emptyList(), null)) }
    var verified by remember { mutableStateOf<VerifiedBinding?>(null) }
    var verifyingRequest by remember { mutableLongStateOf(0L) }
    var pendingKeyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingLegacySession by remember { mutableStateOf<String?>(null) }
    var autoRejectedFor by remember { mutableStateOf<String?>(null) }
    var viewingLocalHistory by remember { mutableStateOf(false) }
    var discoveryRevision by remember { mutableLongStateOf(0L) }

    val requestGate = remember { DesktopConnectionRequestGate() }
    val storeMutex = remember { Mutex() }

    fun nextRequest(selection: ConnectionSelection = ConnectionSelection(profileId, address, pendingKeyId)): Long {
        val generation = requestGate.begin(selection)
        verifyingRequest = 0L
        return generation
    }

    fun isCurrent(generation: Long, selection: ConnectionSelection): Boolean =
        requestGate.isCurrent(generation, selection)

    fun safeFailureMessage(failure: Throwable, secret: String): String {
        val message = failure.message?.trim().orEmpty()
        if (message.isBlank()) return "连接验证失败，请检查服务地址和密钥。"
        // The probe emits fixed, reviewed messages rather than server response bodies. Unknown
        // exceptions are deliberately collapsed so URLs, fragments, or server details cannot leak.
        val safeProbeMessages = setOf(
            "请输入有效的 HTTPS 地址，或本机 127.0.0.1 HTTP 地址。",
            "请输入有效的聊天 API 密钥。",
            "聊天身份为空，无法查询会话。",
            "AstrBot 会话接口返回了无法识别的响应。",
            "AstrBot 拒绝了会话查询，请检查身份和 API 密钥。",
            "AstrBot 会话接口响应缺少数据。",
            "AstrBot 会话接口未返回会话列表。",
            "AstrBot 会话接口返回了不支持的分页数据。",
            "AstrBot 会话列表在查询期间发生变化，请重试。",
            "AstrBot 会话接口返回了无效的会话记录。",
            "无法连接到 AstrBot 会话接口，请检查地址和网络。",
            "聊天 API 密钥无效或已撤销。",
            "API 密钥缺少聊天权限。",
            "找不到会话查询接口，请检查 AstrBot 版本和服务地址。",
            "服务返回重定向，请直接填写最终服务地址。",
        )
        if (secret.isNotEmpty() && message.contains(secret)) return "连接验证失败，请检查服务地址和密钥。"
        if (message in safeProbeMessages) return message
        return when (message) {
            "Named credential storage is unavailable." -> "无法读取或保存密钥，请检查 Windows 凭据存储。"
            "A key with that name already exists." -> "已有同名密钥，请选择其他名称。"
            "Invalid key name." -> "请输入有效的密钥名称。"
            else -> "连接验证失败，请检查服务地址和密钥。"
        }
    }

    fun canLoadKeys(id: String) = id.isNotBlank() && store.profile(id)?.offlineOnly != true

    fun refreshKeys(forProfile: String, expectedGeneration: Long) {
        if (!canLoadKeys(forProfile)) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { keys.list(forProfile) } }
            if (requestGate.currentGeneration() != expectedGeneration || profileId != forProfile) return@launch
            result.fold(
                onSuccess = { keyList = it },
                onFailure = { error = "无法读取已保存的密钥。" },
            )
        }
    }

    LaunchedEffect(profileId) {
        val selectedProfileId = profileId
        if (!canLoadKeys(selectedProfileId)) return@LaunchedEffect
        val generation = requestGate.currentGeneration()
        val result = withContext(Dispatchers.IO) { runCatching { keys.list(selectedProfileId) } }
        if (requestGate.currentGeneration() == generation && profileId == selectedProfileId) {
            result.fold({ keyList = it }, { error = "无法读取已保存的密钥。" })
        }
    }

    fun selectProfile(id: String, requestedAddress: String) {
        viewingLocalHistory = false
        val oldProfile = store.profile(profileId)
        pendingLegacySession = if (oldProfile?.offlineOnly == true && id != profileId) oldProfile.currentSession else null
        val cleanAddress = runCatching { canonicalConnectionAddress(requestedAddress) }.getOrDefault(requestedAddress)
        profileId = id
        address = cleanAddress
        pendingKeyId = null
        keyList = KeyList(emptyList(), null)
        keyErrors = emptyMap()
        error = null
        autoRejectedFor = null
        val generation = nextRequest(ConnectionSelection(id, cleanAddress, null))
        scope.launch {
            val selected = storeMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        synchronized(store) {
                            if (requestGate.currentGeneration() != generation) kotlin.error("stale")
                            if (store.profile(id) == null) store.ensureProfile(id, cleanAddress)
                            store.selectProfile(id)
                            store.readProfile(id)
                            store.profiles
                        }
                    }
                }
            }
            if (requestGate.currentGeneration() != generation || profileId != id) return@launch
            selected.fold({ profiles = it }, { if (it.message != "stale") error = "无法切换本地聊天档案。" })
        }
        refreshKeys(id, generation)
    }

    fun adoptSelectedProfileForReply(selected: Profile) {
        val profileChanged = profileId != selected.id
        val changed = profileChanged || address != selected.address
        val wasVerifying = verifyingRequest != 0L
        val generation = nextRequest(ConnectionSelection(selected.id, selected.address, null))
        pendingLegacySession = null
        if (changed || wasVerifying) {
            viewingLocalHistory = true
            pendingKeyId = null
            error = null
        }
        if (changed) {
            verified = null
            profileId = selected.id
            address = selected.address
            keyList = KeyList(emptyList(), null)
            keyErrors = emptyMap()
            autoRejectedFor = null
        }
        if (!profileChanged && keyList.keys.isEmpty()) {
            // An initial key-list read may have been superseded by the navigation.
            refreshKeys(selected.id, generation)
        }
        profiles = store.profiles
    }

    fun useAddress(input: String) {
        val canonical = runCatching { canonicalConnectionAddress(input) }
            .getOrElse {
                error = "请输入有效的 HTTPS 地址，或本机 127.0.0.1 HTTP 地址。"
                return
            }
        val launcher = instances.singleOrNull { it.url == canonical }
        val matchedProfile = profiles.firstOrNull {
            it.kind == "manual" && !it.offlineOnly && it.address == canonical
        }
        val nextProfile = launcher?.let { "launcher:${it.identity}" } ?: matchedProfile?.id ?: "manual:$canonical"
        selectProfile(nextProfile, launcher?.url ?: canonical)
    }

    fun verifyCandidate(candidateId: String?, automatic: Boolean) {
        if (!automatic) viewingLocalHistory = false
        val profileAtStart = profileId
        val addressAtStart = address
        if (profileAtStart.isBlank()) {
            error = "请输入服务地址，然后选择“使用地址”。"
            return
        }
        val profile = store.profile(profileAtStart)
        if (profile?.offlineOnly == true) {
            error = "此档案仅保存离线历史。请选择运行中的 Launcher 实例或输入新的服务地址。"
            return
        }
        if (candidateId.isNullOrBlank()) {
            error = "请先添加或选择一个密钥。"
            return
        }
        val selection = ConnectionSelection(profileAtStart, addressAtStart, candidateId)
        val generation = nextRequest(selection)
        verifyingRequest = generation
        pendingKeyId = candidateId
        error = null
        if (!automatic) autoRejectedFor = null
        val legacySessionAtStart = pendingLegacySession
        var secretForFailure = ""
        scope.launch {
            val probeResult = withContext(Dispatchers.IO) {
                runCatching {
                    check(isCurrent(generation, selection)) { "stale" }
                    val normalizedAddress = canonicalConnectionAddress(addressAtStart)
                    if (!profileAtStart.startsWith("launcher:")) {
                        val storedAddress = store.profile(profileAtStart)?.address
                            ?.let { runCatching { canonicalConnectionAddress(it) }.getOrNull() }
                        check(storedAddress == normalizedAddress) { "endpoint-changed" }
                    }
                    val token = keys.secret(profileAtStart, candidateId)
                    secretForFailure = token
                    check(token.isNotBlank() && token.none(Char::isWhitespace)) { "secret-invalid" }
                    val candidate = resolveConnectionCandidate(profileAtStart, normalizedAddress)
                    val sessions = fetchDesktopSessions(candidate.address, token, store.username)
                    check(isCurrent(generation, selection)) { "stale" }
                    val currentCandidate = resolveConnectionCandidate(profileAtStart, normalizedAddress)
                    check(currentCandidate == candidate) { "endpoint-changed" }
                    val binding = VerifiedBinding(
                        generation, profileAtStart, candidate.address, candidateId, token,
                        candidate.allowLoopbackHttp, candidate.launcherIdentity,
                    )
                    VerificationProbe(binding, sessions)
                }
            }
            if (!isCurrent(generation, selection)) return@launch
            probeResult.fold(
                onSuccess = probeSuccess@{ probe ->
                    if (!isCurrent(generation, selection)) return@probeSuccess
                    val preparedResult = runCatching {
                        storeMutex.withLock {
                            val latestKeys = withContext(Dispatchers.IO) {
                                check(isCurrent(generation, selection)) { "stale" }
                                val latest = keys.list(profileAtStart)
                                check(latest.keys.any { it.id == candidateId }) { "key-removed" }
                                synchronized(store) {
                                    check(isCurrent(generation, selection)) { "stale" }
                                    if (store.profile(profileAtStart) == null) store.ensureProfile(profileAtStart, probe.binding.address)
                                }
                                latest
                            }
                            val adopt: suspend () -> Set<String> = {
                                withContext(Dispatchers.IO) {
                                    synchronized(store) {
                                        check(isCurrent(generation, selection)) { "stale" }
                                        store.adoptVerifiedSessions(profileAtStart, probe.binding.address, probe.sessions)
                                    }
                                }
                            }
                            val offlineIds = profiles.filter { it.offlineOnly }.map { it.id }.toSet()
                            val adoptedSessions = if (offlineIds.isEmpty() || probe.sessions.isEmpty()) adopt()
                                else withLegacyDraftsSaved(offlineIds, probe.sessions, adopt)
                            withContext(Dispatchers.IO) {
                                synchronized(store) {
                                    check(isCurrent(generation, selection)) { "stale" }
                                    legacySessionAtStart?.takeIf { it in adoptedSessions }?.let { legacySession ->
                                        runCatching { store.selectSession(profileAtStart, legacySession) }
                                    }
                                    VerificationCommit(probe.binding, latestKeys, store.profiles)
                                }
                            }
                        }
                    }
                    if (!isCurrent(generation, selection)) return@probeSuccess
                    verifyingRequest = 0L
                    preparedResult.fold({ prepared ->
                        // This short metadata write is serialized with selection events on Compose's
                        // dispatcher. DPAPI, network, history adoption, and metadata reads are done.
                        val activation = runCatching {
                            check(requestGate.commitIfCurrent(generation, selection) {
                                keys.activate(profileAtStart, candidateId)
                            }) { "stale" }
                        }
                        if (!isCurrent(generation, selection)) return@probeSuccess
                        activation.fold(onSuccess = {
                            val commit = prepared.copy(keyList = prepared.keyList.copy(activeId = candidateId))
                            verified = commit.binding
                            keyList = commit.keyList
                            profiles = commit.profiles
                            pendingLegacySession = null
                            pendingKeyId = null
                            keyErrors = keyErrors - candidateId
                            error = null
                            autoRejectedFor = null
                            if (profileAtStart.startsWith("launcher:")) {
                                address = commit.binding.address
                            }
                        }, onFailure = activationFailure@{ failure ->
                            if (failure.message == "stale") return@activationFailure
                            val message = safeFailureMessage(failure, "")
                            error = message
                            keyErrors = keyErrors + (candidateId to message)
                        })
                    }, onFailure = preparedFailure@{ failure ->
                        if (failure.message == "stale") return@preparedFailure
                        val message = when (failure.message) {
                            "key-removed" -> "所选密钥已不存在，请重新选择密钥。"
                            "legacy-draft-save" -> "无法保存离线会话草稿，请检查本地存储后重新验证。"
                            else -> safeFailureMessage(failure, "")
                        }
                        error = message
                        keyErrors = keyErrors + (candidateId to message)
                    })
                },
                onFailure = probeFailure@{ failure ->
                    verifyingRequest = 0L
                    if (failure.message == "stale") return@probeFailure
                    val message = when (failure.message) {
                        "endpoint-changed" -> "服务地址或 Launcher 所属关系已变化，请重新连接验证。"
                        "secret-invalid" -> "保存的密钥无效，请输入新的聊天 API 密钥。"
                        else -> safeFailureMessage(failure, secretForFailure)
                    }
                    error = message
                    keyErrors = keyErrors + (candidateId to message)
                    if (automatic && (message.contains("密钥无效") || message.contains("缺少聊天权限"))) {
                        autoRejectedFor = "$profileAtStart@$addressAtStart@$candidateId"
                    }
                    // A failed candidate remains pending and inactive. Any prior successful
                    // binding and the previously active key are left untouched.
                },
            )
        }
    }

    fun verify() = verifyCandidate(pendingKeyId ?: keyList.activeId, automatic = false)

    fun selectKey(id: String) {
        if (keyList.keys.none { it.id == id }) {
            error = "找不到所选密钥。"
            return
        }
        pendingKeyId = id
        keyErrors = keyErrors - id
        error = null
        autoRejectedFor = null
        verifyCandidate(id, automatic = false)
    }

    fun addKey(name: String, secret: String) {
        if (profileId.isBlank() || store.profile(profileId)?.offlineOnly == true) {
            error = "请先选择可连接的档案或服务地址。"
            return
        }
        val profileAtStart = profileId
        val addressAtStart = address
        val selection = ConnectionSelection(profileAtStart, addressAtStart, null)
        val generation = nextRequest(selection)
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(isCurrent(generation, selection)) { "stale" }
                    val added = keys.add(profileAtStart, name, secret)
                    added to keys.list(profileAtStart)
                }
            }
            if (!isCurrent(generation, selection)) return@launch
            result.fold(
                onSuccess = { (added, updatedKeyList) ->
                    keyList = updatedKeyList
                    pendingKeyId = added.id
                    keyErrors = keyErrors - added.id
                    verifyCandidate(added.id, automatic = false)
                },
                onFailure = { failure ->
                    if (failure.message != "stale") error = safeFailureMessage(failure, secret)
                },
            )
        }
    }

    fun renameKey(id: String, name: String) {
        val profileAtStart = profileId
        val selection = ConnectionSelection(profileAtStart, address, pendingKeyId)
        val generation = nextRequest(selection)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(isCurrent(generation, selection)) { "stale" }
                    keys.rename(profileAtStart, id, name)
                    keys.list(profileAtStart)
                }
            }
            if (!isCurrent(generation, selection)) return@launch
            result.fold({ keyList = it }, { error = safeFailureMessage(it, "") })
        }
    }

    fun removeKey(id: String) {
        val profileAtStart = profileId
        if (verified?.let { it.profileId == profileAtStart && it.keyId == id } == true) {
            verified = null
            keyList = keyList.copy(activeId = null)
        }
        if (pendingKeyId == id) pendingKeyId = null
        val selection = ConnectionSelection(profileAtStart, address, pendingKeyId)
        val generation = nextRequest(selection)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val removal = runCatching {
                    check(isCurrent(generation, selection)) { "stale" }
                    keys.remove(profileAtStart, id)
                }
                val authoritativeKeys = runCatching { keys.list(profileAtStart) }
                removal to authoritativeKeys
            }
            if (!isCurrent(generation, selection)) return@launch
            val (removal, authoritativeKeys) = result
            authoritativeKeys.onSuccess { updated ->
                keyList = updated
                if (id !in updated.keys.map { it.id }) {
                    keyErrors = keyErrors - id
                    if (pendingKeyId == id) pendingKeyId = null
                }
            }
            if (removal.isFailure) {
                val failure = removal.exceptionOrNull()!!
                if (failure.message != "stale") error = safeFailureMessage(failure, "")
            } else if (authoritativeKeys.isFailure) {
                error = "密钥已移除，但无法刷新密钥列表。"
            } else {
                error = null
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val found = withContext(Dispatchers.IO) {
                runCatching { LauncherInstanceResolver.runningInstances() }.getOrDefault(emptyList())
            }
            instances = found
            discoveryRevision++
            delay(10_000)
        }
    }

    LaunchedEffect(profileId, address, instances, viewingLocalHistory) {
        if (viewingLocalHistory) return@LaunchedEffect
        if (profileId.startsWith("launcher:")) {
            val selectedProfileId = profileId
            val identity = selectedProfileId.removePrefix("launcher:")
            val current = instances.singleOrNull { it.identity == identity }
            if (current == null) {
                if (verified?.profileId == profileId) verified = null
                error = "AstrBot Launcher 实例离线；本地会话仍可查看。"
            } else if (current.url != address) {
                address = current.url
                val generation = nextRequest(ConnectionSelection(profileId, current.url, pendingKeyId))
                error = "AstrBot Launcher 地址已变化，请重新连接验证。"
                scope.launch {
                    val updated = storeMutex.withLock {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                synchronized(store) {
                                    if (requestGate.currentGeneration() == generation) {
                                        store.updateProfileAddress(selectedProfileId, current.url)
                                    }
                                    store.profiles
                                }
                            }
                        }
                    }
                    if (requestGate.currentGeneration() == generation && profileId == selectedProfileId) {
                        updated.getOrNull()?.let { profiles = it }
                    }
                }
            }
        }
    }

    LaunchedEffect(profileId, keyList.activeId, address, instances, verified, discoveryRevision, viewingLocalHistory) {
        if (viewingLocalHistory) return@LaunchedEffect
        val activeId = keyList.activeId ?: return@LaunchedEffect
        val profile = store.profile(profileId)
        if (profile == null || profile.offlineOnly) return@LaunchedEffect
        val safeForAutomaticAuth = profileId.startsWith("launcher:") || runCatching {
            endpoint(address, allowLoopbackHttp = true).scheme.equals("https", ignoreCase = true)
        }.getOrDefault(false)
        val bound = verified?.let {
            it.profileId == profileId && it.address == runCatching { canonicalConnectionAddress(address) }.getOrNull() &&
                it.keyId == activeId && (it.launcherIdentity == null || instances.any { instance ->
                    instance.identity == it.launcherIdentity && instance.url == it.address
                })
        } == true
        val attemptKey = "$profileId@$address@$activeId"
        if (!safeForAutomaticAuth || bound || verifyingRequest != 0L || autoRejectedFor == attemptKey) return@LaunchedEffect
        verifyCandidate(activeId, automatic = true)
    }

    val active = verified
    val connected = active != null && active.profileId == profileId &&
        active.address == runCatching { canonicalConnectionAddress(address) }.getOrNull() &&
        active.keyId == keyList.activeId &&
        (active.launcherIdentity == null || instances.any { it.identity == active.launcherIdentity && it.url == active.address })

    fun snapshot(): DesktopSendTarget? {
        val binding = verified ?: return null
        if (binding.profileId != profileId || binding.keyId != keyList.activeId ||
            binding.address != runCatching { canonicalConnectionAddress(address) }.getOrNull()
        ) return null
        if (binding.launcherIdentity != null && instances.none {
                it.identity == binding.launcherIdentity && it.url == binding.address
            }
        ) return null
        return DesktopSendTarget(
            profileId = binding.profileId,
            address = binding.address,
            token = binding.token,
            allowLoopbackHttp = binding.allowLoopbackHttp,
            bindingGeneration = binding.generation,
        ) {
            val storedProfile = store.profile(binding.profileId)
                ?: kotlin.error("连接档案已不存在，请重新连接验证。")
            val storedAddress = runCatching { canonicalConnectionAddress(storedProfile.address) }.getOrNull()
            check(storedAddress == binding.address) { "服务地址已变化，请重新连接验证。" }
            if (binding.launcherIdentity != null) {
                val currentUrl = LauncherInstanceResolver.currentUrl(binding.launcherIdentity)
                    ?.let { runCatching { canonicalConnectionAddress(it) }.getOrNull() }
                check(currentUrl == binding.address) { "AstrBot Launcher 所属实例或服务地址已变化，请重新连接验证。" }
            } else if (binding.allowLoopbackHttp) {
                check(LauncherInstanceResolver.matchEnteredUrl(binding.address) == null) {
                    "本机服务所属关系已变化，请重新连接验证。"
                }
            }
        }
    }

    fun invalidateIfCurrent(target: DesktopSendTarget) {
        val binding = verified ?: return
        if (binding.profileId == target.profileId && binding.address == target.address &&
            binding.token == target.token && binding.allowLoopbackHttp == target.allowLoopbackHttp &&
            binding.generation == target.bindingGeneration
        ) {
            verified = null
            error = "连接验证已失效，请重新连接。"
        }
    }

    return DesktopConnection(
        profileId = profileId,
        address = address,
        profiles = profiles,
        instances = instances,
        keyList = keyList,
        connected = connected,
        verifying = verifyingRequest != 0L,
        pendingKeyId = pendingKeyId,
        error = error,
        keyErrors = keyErrors,
        activeLabel = keyList.keys.firstOrNull { it.id == keyList.activeId }?.name,
        selectProfileAction = ::selectProfile,
        adoptSelectedProfileAction = ::adoptSelectedProfileForReply,
        useAddressAction = ::useAddress,
        verifyAction = ::verify,
        selectKeyAction = ::selectKey,
        addKeyAction = ::addKey,
        renameKeyAction = ::renameKey,
        removeKeyAction = ::removeKey,
        snapshotAction = ::snapshot,
        invalidateAction = ::invalidateIfCurrent,
    )
}

private fun canonicalConnectionAddress(input: String): String {
    endpoint(input, allowLoopbackHttp = true)
    return DesktopStore.normalizeAddress(input)
}

/** Resolve the currently usable address, validating Launcher identity both before and after probe. */
private fun resolveConnectionCandidate(profileId: String, address: String): VerifiedCandidate {
    val canonical = canonicalConnectionAddress(address)
    if (profileId.startsWith("launcher:")) {
        val identity = profileId.removePrefix("launcher:")
        val current = LauncherInstanceResolver.currentUrl(identity)
            ?: error("AstrBot Launcher 实例当前离线，消息未发送。")
        val resolved = canonicalConnectionAddress(current)
        return VerifiedCandidate(resolved, allowLoopbackHttp = true, launcherIdentity = identity)
    }
    val uri = endpoint(canonical, allowLoopbackHttp = true)
    val matched = if (uri.scheme.equals("http", ignoreCase = true)) {
        LauncherInstanceResolver.matchEnteredUrl(canonical)
    } else null
    if (matched != null) {
        val current = LauncherInstanceResolver.currentUrl(matched.identity)
            ?: error("AstrBot Launcher 实例当前离线，消息未发送。")
        val resolved = canonicalConnectionAddress(current)
        check(resolved == canonical) { "AstrBot Launcher 服务地址已变化，请重新连接验证。" }
        return VerifiedCandidate(resolved, allowLoopbackHttp = true, launcherIdentity = matched.identity)
    }
    return VerifiedCandidate(
        address = canonical,
        allowLoopbackHttp = uri.scheme.equals("http", ignoreCase = true),
        launcherIdentity = null,
    )
}
