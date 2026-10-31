package app.cabinwright.store.daemon

import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Wire
import java.io.File
import java.io.IOException

/**
 * The `vehicle` and `conditioning` capabilities (design.md "Vehicle"): typed reads of the car's
 * state, and a battery conditioning session. Conditioning is a daemon job, not an app's, because
 * the request lasts only while it is refreshed and must outlive the app that started it.
 *
 * The car starts conditioning when it believes it's on the way to a charger, so a session
 * reports a made-up distance and time to one, a request the driver's route doesn't see. Every
 * binder code here was read from this firmware's own interfaces, and only "active" from the car
 * itself means it's running: on another firmware nothing is called.
 */
object VehicleOps {
    const val OFF = "off"
    const val WAITING = "waiting"
    const val ACTIVE = "active"
    const val STOPPING = "stopping"

    private val FIRMWARE = setOf("CVEV.USA.0000.V015.012.260507")

    private const val HEV = "com.hkmc.micom.hev"
    private const val BCM = "com.hkmc.micom.bcm"
    private const val TELEMATICS = "tm_hscan_service"
    private const val STATION = 62
    private const val ROUTE_DISTANCE = 65
    private const val REMAINING_TIME = 66
    private const val INDICATOR = 109
    private const val BATTERY_12V = 137
    private const val BATTERY_MAIN = 74
    private const val BATTERY_HEALTH = 214
    private const val READY = 118

    private const val DISTANCE_KM = 9
    private const val MINUTES = 18
    private const val STATION_ON_A = 2
    private const val STATION_ON_B = 3
    private const val NO_DISTANCE = 2047
    private const val NO_TIME = 1023

    private const val REFRESH_MS = 5_000L
    private const val WAIT_LIMIT_MS = 5 * 60_000L
    private const val OFF_LIMIT_MS = 60_000L
    private const val OFF_POLL_MS = 1_000L
    private const val HEX = 16

    private val REPLY = Regex("Parcel\\(00000000 ([0-9a-f]{8})")
    private val SESSION = File(Paths.BASE, "conditioning")
    private val lock = Object()
    private var phase = OFF
    private var ended = ""

    fun supported(): Boolean = Car.firmware() in FIRMWARE

    private fun reply(service: String, code: Int): Long? {
        val m = REPLY.find(root("service call $service $code").out) ?: return null
        return m.groupValues[1].toLong(HEX)
    }

    private fun read(service: String, code: Int): Int? = reply(service, code)?.toInt()

    private fun on(service: String, code: Int): Boolean? = read(service, code)?.let { it == 1 }

    fun state(): Map<String, Any?> {
        if (!supported()) return Json.of("supported" to false)
        return Json.of(
            "supported" to true,
            "parked" to Vehicle.parked(),
            "ready" to on(TELEMATICS, READY),
            "battery_12v" to read(TELEMATICS, BATTERY_12V)?.toLong(),
            "battery_main" to reply(TELEMATICS, BATTERY_MAIN)?.let {
                java.lang.Float.intBitsToFloat(it.toInt()).toDouble()
            },
            "battery_health" to read(TELEMATICS, BATTERY_HEALTH)?.toLong(),
        )
    }

    fun conditioning(): Map<String, Any?> = synchronized(lock) { Json.of("phase" to phase, "ended" to ended) }

    private fun send(code: Int, vararg values: Int) {
        root("service call $HEV $code" + values.joinToString("") { " i32 $it" })
    }

    private fun report() {
        send(ROUTE_DISTANCE, DISTANCE_KM)
        send(REMAINING_TIME, MINUTES)
    }

    private fun withdraw() {
        send(ROUTE_DISTANCE, NO_DISTANCE)
        send(REMAINING_TIME, NO_TIME)
        send(STATION, 0, 0)
    }

    /** Refuses with the reason for the driver unless the car can take the request. */
    @Suppress("ThrowsCount")
    fun start() {
        if (!supported()) throw Broker.Refused(Wire.UNSUPPORTED, "This car's software hasn't been tested with this.")
        synchronized(lock) {
            if (phase != OFF) throw Broker.Refused(Wire.FAILED, "The battery is already being conditioned.")
            if (on(TELEMATICS, READY) != true) throw Broker.Refused(Wire.FAILED, "The car has to be ready to drive.")
            if (read(BCM, INDICATOR) != 0) {
                throw Broker.Refused(Wire.FAILED, "The car is already conditioning the battery.")
            }
            SESSION.writeText("")
            phase = WAITING
            ended = ""
        }
        report()
        send(STATION, STATION_ON_A, STATION_ON_B)
        Thread({ session() }, "conditioning").apply { isDaemon = true }.start()
    }

    fun stop() {
        synchronized(lock) {
            if (phase != WAITING && phase != ACTIVE) return
            phase = STOPPING
            ended = "stopped"
            lock.notifyAll()
        }
    }

    /** Runs until the session ends; only the car's own indicator makes it active. */
    private fun session() {
        val began = System.currentTimeMillis()
        try {
            while (true) {
                synchronized(lock) { if (phase != STOPPING) lock.wait(REFRESH_MS) }
                val reason = check(began)
                if (reason != null) {
                    synchronized(lock) {
                        phase = STOPPING
                        if (ended.isEmpty()) ended = reason
                    }
                    break
                }
                report()
            }
        } catch (e: IOException) {
            log("conditioning: $e")
            synchronized(lock) {
                phase = STOPPING
                ended = "failed"
            }
        }
        finish()
    }

    /** Why the session should end, or null to keep going. */
    @Suppress("ReturnCount")
    private fun check(began: Long): String? {
        if (synchronized(lock) { phase } == STOPPING) return "stopped"
        if (on(TELEMATICS, READY) == false) return "not_ready"
        val indicator = read(BCM, INDICATOR)
        synchronized(lock) {
            if (phase == WAITING && indicator == 1) phase = ACTIVE
            if (phase == ACTIVE && indicator == 0) return "finished"
            if (phase == WAITING && System.currentTimeMillis() - began >= WAIT_LIMIT_MS) return "timeout"
        }
        return null
    }

    /** Withdraws the request and waits for the car to agree. */
    private fun finish() {
        try {
            withdraw()
            val limit = System.currentTimeMillis() + OFF_LIMIT_MS
            while (System.currentTimeMillis() < limit && read(BCM, INDICATOR) != 0) Thread.sleep(OFF_POLL_MS)
        } catch (e: IOException) {
            log("conditioning: $e")
        }
        SESSION.delete()
        synchronized(lock) { phase = OFF }
    }

    /** A session this daemon didn't start has nobody refreshing it: never resume it, end it. */
    fun recover() {
        if (!SESSION.isFile) return
        log("conditioning: ending the session an earlier daemon left")
        try {
            withdraw()
        } catch (e: IOException) {
            log("conditioning: $e")
        }
        SESSION.delete()
    }
}
