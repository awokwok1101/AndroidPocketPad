package com.dathaze.pocketpad.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Registers the phone as a Bluetooth HID gamepad (peripheral role) and
 * pushes input reports to the connected host (your TV).
 *
 * Flow:
 *  1. [start] — obtains the HID_DEVICE profile proxy.
 *  2. registerApp — announces the phone as a gamepad via SDP.
 *  3. The TV pairs/connects (or we call [connectTo] for an already-paired TV).
 *  4. [send] — 7-byte input reports on every state change.
 *
 * All permission-guarded calls are wrapped: on Android 12+ the caller must
 * hold BLUETOOTH_CONNECT before calling [start].
 */
@SuppressLint("MissingPermission")
class HidGamepadManager(private val context: Context, private val listener: Listener) {

    /** Typed connection lifecycle events; the UI maps these to localized text. */
    enum class HidStatus {
        BLUETOOTH_OFF,
        REGISTERING,
        READY,
        CONNECTING,
        UNREGISTERED,
        PERMISSION_MISSING,

        /**
         * Android refused to register PocketPad as a HID device at all, so there is nothing
         * for a host to find. Distinct from [UNREGISTERED], which means a registration we
         * once had was taken away.
         */
        REGISTRATION_FAILED,

        /**
         * The HID profile is registered, but the host turned the controller connection down.
         * Almost always means the two are not paired yet, or the host does not accept
         * Bluetooth controllers.
         */
        CONNECT_REFUSED,

        /** The phone cannot talk to the Bluetooth stack — no profile proxy. */
        PROFILE_UNAVAILABLE,

        /** The link dropped and the app is trying to get it back by itself. */
        RECONNECTING
    }

    interface Listener {
        fun onHidStatus(status: HidStatus, deviceName: String?)
        fun onHidConnected(device: BluetoothDevice?)
        fun onHidDisconnected()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var reRegisterAttempts = 0

    /** A shut-down executor cannot accept callbacks, so replace it if needed. */
    private fun liveExecutor(): ExecutorService {
        if (executor.isShutdown) executor = Executors.newSingleThreadExecutor()
        return executor
    }

    private var adapter: BluetoothAdapter? = null
    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    private var registered = false
    private var pendingConnect: BluetoothDevice? = null
    private var started = false
    // What each channel last delivered. A host may ask for the state instead of
    // waiting for a change, and the answer has to be the real one — see
    // [HidReportRequests.getReport].
    @Volatile private var lastGamepadReport: ByteArray? = null
    @Volatile private var lastKeyReport: ByteArray? = null

    // Where to come back to after an unexpected drop. Cleared by [disconnect],
    // so asking to disconnect is not immediately undone by the retry below.
    @Volatile private var reconnectTarget: BluetoothDevice? = null
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { attemptReconnect() }

    // The gamepad report is handed to a sending thread instead of being pushed
    // from the caller (the UI thread): see [send] and [ReportCoalescer].
    private val coalescer = ReportCoalescer()

    /**
     * Keys for TV navigation. Same queue, same sending thread, same rules: a
     * key report sent from the touch path was a blocking binder call on the UI
     * thread, which is the one thing this class exists to keep out of there.
     */
    private val keyCoalescer = ReportCoalescer()
    private val senderLock = Any()
    private var senderQueued = false

    /**
     * When the oldest report still waiting was offered while the link was down.
     * A press made during a short drop is worth delivering the moment the host
     * is back; one from several seconds ago is a press nobody is still making.
     */
    private var heldWhileDownAt = 0L
    @Volatile private var sender: ExecutorService? = null

    val isConnected: Boolean get() = connectedDevice != null
    val currentDevice: BluetoothDevice? get() = connectedDevice

    fun start() {
        // A registration can be dropped while the app is still running — the
        // profile is exclusive, so anything else claiming the HID Device role
        // knocks us out. Recover instead of sitting there unregistered.
        val live = hidDevice
        if (started && live != null && !registered) {
            reRegisterAttempts = 0
            registerSdpApp(live)
            return
        }
        if (started) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            post { listener.onHidStatus(HidStatus.BLUETOOTH_OFF, null) }
            return
        }
        started = true
        // Returns false when the stack will not hand over the HID_DEVICE proxy, in which case
        // onServiceConnected never fires. Latching `started` on that would leave the app
        // stuck on "registering" with nothing on the way and no retry.
        if (!bt.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)) {
            started = false
            post { listener.onHidStatus(HidStatus.PROFILE_UNAVAILABLE, null) }
        }
    }

    fun stop() {
        val proxy = hidDevice
        if (proxy != null) {
            try {
                connectedDevice?.let { proxy.disconnect(it) }
                if (registered) proxy.unregisterApp()
            } catch (_: SecurityException) {
            }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        }
        hidDevice = null
        connectedDevice = null
        registered = false
        started = false
        reconnectTarget = null
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        synchronized(senderLock) {
            coalescer.reset()
            keyCoalescer.reset()
            senderQueued = false
        }
        sender?.shutdown()
        sender = null
        // The callback executor owns a live thread; let it go with the app.
        executor.shutdown()
    }

    /**
     * Bonded (paired) devices, for the "connect to paired device" picker.
     *
     * Resolves the adapter on demand. It used to read only the field that [start] fills in,
     * so if the Bluetooth permission was granted after launch the picker reported "no paired
     * devices yet" with a phone full of them — which looks exactly like a pairing problem.
     */
    fun bondedDevices(): List<BluetoothDevice> =
        try {
            val bt = adapter ?: systemAdapter()?.also { adapter = it }
            bt?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

    private fun systemAdapter(): BluetoothAdapter? = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }.getOrNull()

    /**
     * Ask [device] to accept this phone as its controller.
     *
     * The phone has to be the one to open the HID channel. A host that paired with a phone
     * filed it away as a phone — it has no reason to go looking for a controller service on
     * it — so nothing happens until PocketPad connects outwards. That makes this the one call
     * standing between "paired" and "working", which is why its result is no longer thrown
     * away: `connect()` returning false is how the stack says the host refused, and the app
     * used to answer that by showing "connecting…" until the user gave up.
     */
    fun connectTo(device: BluetoothDevice) {
        // A connection the user asked for replaces any retry in flight.
        reconnectTarget = device
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        val proxy = hidDevice
        if (proxy == null) {
            // No proxy yet: remember the request and get one.
            pendingConnect = device
            start()
            return
        }
        if (!registered) {
            // Registered once, not any more — the profile is exclusive, so something else
            // claimed the HID Device role. Re-register and connect when that lands, rather
            // than queueing the request against a registration nothing will restore.
            pendingConnect = device
            reRegisterAttempts = 0
            registerSdpApp(proxy)
            return
        }
        post { listener.onHidStatus(HidStatus.CONNECTING, safeName(device)) }
        try {
            if (!proxy.connect(device)) {
                post { listener.onHidStatus(HidStatus.CONNECT_REFUSED, safeName(device)) }
            }
        } catch (_: SecurityException) {
            post { listener.onHidStatus(HidStatus.PERMISSION_MISSING, null) }
        }
    }

    fun disconnect() {
        // An explicit disconnect has to stay disconnected. The target is
        // cleared before the stack reports the drop, because that report is
        // what arms the retry.
        reconnectTarget = null
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        val proxy = hidDevice ?: return
        try {
            connectedDevice?.let { proxy.disconnect(it) }
        } catch (_: SecurityException) {
        }
    }

    /**
     * Come back from a drop without the user having to open the menu.
     *
     * A controller link does not only end when someone turns the TV off: a TV
     * entering standby, an input switch, or a burst of 2.4 GHz interference all
     * drop it. Until now the app answered that by sitting on "Disconnected"
     * until the user went into Settings → Connect to a paired device, which is
     * the difference between a controller that recovers in a second and one
     * that looks broken.
     *
     * Retries back off and stop after [MAX_RECONNECT_ATTEMPTS], so a host that
     * is switched off or out of range does not keep the radio awake for ever.
     * [disconnect] and a fresh [connectTo] cancel this.
     */
    private fun scheduleReconnect() {
        val target = reconnectTarget ?: return
        if (!started) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            // Out of attempts. Saying "reconnecting" any longer would be a lie.
            post { listener.onHidDisconnected() }
            return
        }
        val index = reconnectAttempts.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
        reconnectAttempts++
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(connectWatchdog)
        post { listener.onHidStatus(HidStatus.RECONNECTING, safeName(target)) }
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAYS_MS[index])
    }

    private fun attemptReconnect() {
        val target = reconnectTarget ?: return
        val proxy = hidDevice ?: return
        if (!started || !registered || connectedDevice != null) return
        val adapter = adapter ?: systemAdapter() ?: return
        if (!adapter.isEnabled) return
        try {
            if (!proxy.connect(target)) {
                scheduleReconnect()
            } else {
                // True only means the stack took the request; the host still
                // has to answer. If it never does, nothing else would retry.
                mainHandler.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS)
            }
        } catch (_: SecurityException) {
        }
    }

    /** A connect request the host never answered must not end the retrying. */
    private val connectWatchdog = Runnable {
        if (connectedDevice == null) scheduleReconnect()
    }

    /**
     * Send the current gamepad state to the connected host.
     *
     * Returns immediately: the report is left with [coalescer] and a sending
     * thread picks it up. `sendReport` is a synchronous binder call into the
     * Bluetooth stack that blocks while the radio's queue is full, so waiting
     * for it here — on the thread that is also drawing the pad and handling the
     * next touch — is how one slow host turns into a stuttering controller. If
     * reports arrive faster than the radio takes them, only the newest survives:
     * the host gets the current state rather than a queue of old ones.
     *
     * Identical consecutive states are still dropped, so the radio carries
     * changes rather than a constant stream.
     */
    fun send(state: GamepadState) {
        if (hidDevice == null) return
        val report = state.toReport()
        val connected = connectedDevice != null
        synchronized(senderLock) {
            // Offered even with the link down. A press made in the moment a
            // dropped link takes to come back used to be thrown away here, and
            // the player felt that as a button that had to be pressed twice.
            // It waits in the same two-slot queue and goes out on reconnect;
            // [MAX_REPLAY_AGE_MS] is what keeps an old one from arriving as a
            // press nobody is making.
            coalescer.offer(report)
            if (!connected && heldWhileDownAt == 0L) {
                heldWhileDownAt = SystemClock.uptimeMillis()
            }
        }
        // Checked again rather than trusting the read above: the link can come
        // up between offering the state and this line, and that connect already
        // kicked a sender that found an empty queue. Without this the press
        // would wait for the next touch.
        if (connected || connectedDevice != null) kickSender()
    }

    /** Make sure exactly one sending task is on its way. */
    private fun kickSender() {
        synchronized(senderLock) {
            if (senderQueued) return
            senderQueued = true
        }
        try {
            liveSender().execute { drainReports() }
        } catch (_: RejectedExecutionException) {
            // Shutting down: the sender is gone and there is nothing left to
            // deliver. Clear the flag so a later [send] can start a new one
            // instead of believing a task is already queued.
            synchronized(senderLock) { senderQueued = false }
        }
    }

    /** Runs on the sending thread until the newest report has been handed over. */
    private fun drainReports() {
        try {
            while (true) {
                // Gamepad first: when both are waiting it is the one the game is
                // reading, and only one key can ever be queued behind it.
                val gamepad = synchronized(senderLock) { coalescer.take() }
                val key = if (gamepad == null) {
                    synchronized(senderLock) { keyCoalescer.take() }
                } else {
                    null
                }
                val report = gamepad ?: key ?: break
                val reportId =
                    if (gamepad != null) HidConstants.REPORT_ID
                    else HidConstants.REPORT_ID_KEYBOARD
                val proxy = hidDevice ?: continue
                val device = connectedDevice ?: continue
                val delivered = try {
                    proxy.sendReport(device, reportId, report)
                } catch (_: SecurityException) {
                    false
                }
                if (delivered) {
                    if (gamepad != null) lastGamepadReport = report else lastKeyReport = report
                    continue
                }

                // Refused, not delivered: put it back at the front of the queue,
                // so the press the player is waiting for is the next thing tried
                // rather than being taken for a duplicate and dropped.
                synchronized(senderLock) {
                    if (gamepad != null) coalescer.failed(report) else keyCoalescer.failed(report)
                }
                // The radio's queue is full. Trying again straight away would
                // spin this thread on the same refused report and take the CPU
                // the radio needs in order to drain — the stall this thread
                // exists to prevent, just moved off the UI thread. Anything
                // newer that arrives meanwhile replaces the retry anyway.
                try {
                    Thread.sleep(REFUSED_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } finally {
            val moreToDo = synchronized(senderLock) {
                senderQueued = false
                coalescer.hasPending() || keyCoalescer.hasPending()
            }
            // A report can land between the loop finding nothing and the flag
            // being cleared; that one would otherwise wait for the next touch.
            if (moreToDo) kickSender()
        }
    }

    private fun liveSender(): ExecutorService {
        val current = sender
        if (current != null && !current.isShutdown) return current
        // A report that leaves late is a press the player feels late. This
        // thread does nothing but hand reports to the radio, so it runs at
        // display priority rather than queueing behind background work for the
        // CPU — scheduling jitter here is latency the app adds by itself.
        val created = Executors.newSingleThreadExecutor { task ->
            Thread({
                // Urgent-display rather than display: this thread's only job is
                // a binder call, and every millisecond it waits its turn is a
                // press the player feels late.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                task.run()
            }, "pocketpad-hid-send")
        }
        sender = created
        return created
    }

    /**
     * Send a keyboard key (or [HidConstants.KEY_NONE] to release) so the pad
     * can drive TV menus. Queued like the gamepad reports, not sent here: this
     * runs on the thread that draws the pad and reads the next touch, and
     * `sendReport` is a binder call that blocks while the radio's queue is full.
     *
     * Repeats are still suppressed — the queue drops a state identical to the
     * one the host is already heading for. A press and its release can arrive
     * within one radio turnaround, which is exactly why the queue keeps two
     * states: a single slot would let the release overwrite the press and the
     * menu would never open.
     */
    fun sendKey(key: Byte) {
        if (hidDevice == null || connectedDevice == null) return
        val report = HidConstants.keyboardReport(key)
        synchronized(senderLock) { keyCoalescer.offer(report) }
        kickSender()
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            registerSdpApp(proxy)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            registered = false
            connectedDevice = null
            // Not started any more: the proxy is gone. Leaving this true made start() take
            // its "already running" path and return without asking for a new proxy, so the
            // controller stayed dead for the rest of the session.
            started = false
            post { listener.onHidDisconnected() }
        }
    }

    private fun registerSdpApp(proxy: BluetoothHidDevice) {
        post { listener.onHidStatus(HidStatus.REGISTERING, null) }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "PocketPad Controller",
            "Wireless Controller",
            "PocketPad",
            SUBCLASS,
            HidConstants.REPORT_DESCRIPTOR
        )
        try {
            // registerApp returns false when the stack will not take the registration — most
            // often because something else already holds the exclusive HID Device role. The
            // callback then never fires, so without checking this the app sat on "registering"
            // for good and the user had no way to tell that from a pairing problem.
            if (!proxy.registerApp(sdp, null, outgoingQos(), liveExecutor(), hidCallback)) {
                post { listener.onHidStatus(HidStatus.REGISTRATION_FAILED, null) }
            }
        } catch (_: SecurityException) {
            post { listener.onHidStatus(HidStatus.PERMISSION_MISSING, null) }
        } catch (_: IllegalArgumentException) {
            // Already registered under this SDP record; nothing to do.
        }
    }

    /**
     * The outgoing L2CAP quality-of-service request, or null to take the stack default.
     *
     * This used to always ask for a custom flow spec (best-effort, ~11 ms latency budget).
     * It is the only non-default thing PocketPad asks of the Bluetooth stack, and a flow spec
     * is negotiated during L2CAP channel configuration — a host that will not accept the one
     * being asked for can refuse the channel rather than fall back to something it likes.
     * Embedded TV stacks are forgiving about this; desktop stacks are documented as stricter,
     * and every Android HID-device implementation known to work with a PC passes null here.
     *
     * So the default is now the stack default: ask for nothing unusual. The custom spec was
     * never measured to help — the latency the user actually noticed came from sending reports
     * before triggering haptics and from halving the number of reports, both unaffected here.
     *
     * [REQUEST_CUSTOM_QOS] flips it back in one line if a host turns out to need it.
     */
    private fun outgoingQos(): BluetoothHidDeviceAppQosSettings? =
        if (!REQUEST_CUSTOM_QOS) null else BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )

    /** Re-register a dropped gamepad, backing off so it cannot spin. */
    private fun scheduleReRegister() {
        if (reRegisterAttempts >= MAX_REREGISTER_ATTEMPTS) return
        reRegisterAttempts++
        mainHandler.postDelayed({
            val proxy = hidDevice
            if (proxy != null && !registered) registerSdpApp(proxy)
        }, 1000L * reRegisterAttempts)
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (isRegistered) {
                reRegisterAttempts = 0
                post { listener.onHidStatus(HidStatus.READY, null) }
                val pending = pendingConnect
                pendingConnect = null
                if (pending != null) {
                    connectTo(pending)
                } else if (pluggedDevice != null) {
                    connectTo(pluggedDevice)
                }
            } else {
                post { listener.onHidStatus(HidStatus.UNREGISTERED, null) }
                scheduleReRegister()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    // Whatever brought us here — the host, the user, or the
                    // retry below — this is the device to come back to, and
                    // the attempt counter starts again.
                    reconnectTarget = device
                    reconnectAttempts = 0
                    mainHandler.removeCallbacks(reconnectRunnable)
                    mainHandler.removeCallbacks(connectWatchdog)
                    // Anything held through the drop goes out now — unless the
                    // drop was long enough that delivering it would invent a
                    // press nobody made. The state the pad is in right now is
                    // asked for by onHidConnected, and that one always goes.
                    val heldTooLong = synchronized(senderLock) {
                        val held = heldWhileDownAt
                        heldWhileDownAt = 0L
                        held != 0L && SystemClock.uptimeMillis() - held > MAX_REPLAY_AGE_MS
                    }
                    if (heldTooLong) synchronized(senderLock) { coalescer.reset() }
                    kickSender()
                    post { listener.onHidConnected(device) }
                }
                BluetoothProfile.STATE_CONNECTING ->
                    post { listener.onHidStatus(HidStatus.CONNECTING, safeName(device)) }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice == device) connectedDevice = null
                    synchronized(senderLock) {
                        coalescer.reset()
                        keyCoalescer.reset()
                        heldWhileDownAt = 0L
                    }
                    lastGamepadReport = null
                    lastKeyReport = null
                    post { listener.onHidDisconnected() }
                    // Only the device we are meant to be on: a refused or
                    // abandoned attempt at some other host must not start a
                    // retry loop against the wrong one.
                    if (device == reconnectTarget) scheduleReconnect()
                }
            }
        }

        /**
         * The host polled us. Answer with the collection it actually asked
         * for — replying with gamepad bytes to a keyboard request makes the
         * host parse the hat byte as a keycode and register a phantom key.
         */
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val proxy = hidDevice ?: return
            try {
                // The newest state *offered*, not the last one the radio
                // accepted: sendReport returning true only means the stack
                // took it, so `lastGamepadReport` can already be a state or two
                // behind what the pad is actually doing.
                val current = when (id.toInt() and 0xFF) {
                    HidConstants.REPORT_ID ->
                        synchronized(senderLock) { coalescer.newestOffered() } ?: lastGamepadReport
                    HidConstants.REPORT_ID_KEYBOARD ->
                        synchronized(senderLock) { keyCoalescer.newestOffered() } ?: lastKeyReport
                    else -> null
                }
                val outcome = HidReportRequests.getReport(
                    type, id, bufferSize, BluetoothHidDevice.REPORT_TYPE_INPUT, current
                )
                when (outcome) {
                    is HidRequestOutcome.Reply ->
                        proxy.replyReport(device, type, id, outcome.payload)
                    HidRequestOutcome.UnsupportedType ->
                        proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                    HidRequestOutcome.UnknownReportId ->
                        proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                    HidRequestOutcome.Acknowledge ->
                        proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
                }
            } catch (_: SecurityException) {
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            val proxy = hidDevice ?: return
            try {
                val outcome = HidReportRequests.setReport(
                    type, id, BluetoothHidDevice.REPORT_TYPE_INPUT
                )
                val response = when (outcome) {
                    HidRequestOutcome.UnsupportedType ->
                        BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ
                    HidRequestOutcome.UnknownReportId ->
                        BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID
                    else -> BluetoothHidDevice.ERROR_RSP_SUCCESS
                }
                proxy.reportError(device, response)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        /**
         * HID device subclass advertised over SDP. Hosts — TVs especially —
         * filter their "input device" lists by this byte, so it must be one of
         * the values the Bluetooth HID profile defines. COMBO (keyboard+pointer
         * capable) is what off-the-shelf controllers and TV remotes report, and
         * it is what Samsung's Input Device Manager expects to see; a bare
         * gamepad subclass is skipped by some firmware.
         */
        const val SUBCLASS: Byte = BluetoothHidDevice.SUBCLASS1_COMBO
        const val MAX_REREGISTER_ATTEMPTS = 4

        /** Five tries spread over ~15 s: long enough for a TV to finish waking. */
        const val MAX_RECONNECT_ATTEMPTS = 5
        val RECONNECT_DELAYS_MS = longArrayOf(500L, 1000L, 2000L, 4000L, 8000L)

        /** How long to wait for a host to answer a connect request before retrying. */
        const val CONNECT_WATCHDOG_MS = 4000L

        /**
         * Pause after the radio refuses a report. `sendReport` returns false when
         * its queue is full; retrying on the next instruction would spin. Three
         * milliseconds is enough to stop the spin and short enough to be a
         * rounding error next to a radio turnaround — this sleep used to be a
         * visible floor under the latency of every congested report.
         */
        const val REFUSED_RETRY_DELAY_MS = 3L

        /**
         * How long a state may wait for a link that is down before it stops
         * being worth sending. A tap during a one-second drop is still a tap
         * the host should see; one from ten seconds ago is not.
         */
        const val MAX_REPLAY_AGE_MS = 1500L

        /**
         * Whether to ask for a custom L2CAP flow spec instead of the stack default.
         *
         * False is the compatible choice; see [outgoingQos]. Set to true only to restore the
         * pre-2.5 behaviour, and only if a host is found that actually needs it.
         */
        const val REQUEST_CUSTOM_QOS = false
    }
}
