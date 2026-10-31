package app.cabinwright.store.daemon

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * For a binder service that answers through a listener, which `service call` can't take: adds one,
 * makes the request, and gives back the 32-bit words of the first callback with that code. The
 * caller decodes them; nothing here knows what the service is.
 */
object BinderListen {
    private const val ANSWER_MS = 1_800L

    private class Listener(val descriptor: String, val callback: Int) : Binder() {
        val done = CountDownLatch(1)

        @Volatile var words: List<Int>? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptor)
            if (code == callback && words == null) {
                val out = ArrayList<Int>()
                while (data.dataAvail() >= Int.SIZE_BYTES) out.add(data.readInt())
                words = out
                done.countDown()
            }
            reply?.writeNoException()
            return true
        }
    }

    private fun call(service: IBinder, code: Int, listener: IBinder?) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(service.interfaceDescriptor)
            if (listener != null) data.writeStrongBinder(listener)
            service.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Null if the service is missing, refused or didn't answer in time. */
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    fun ask(name: String, add: Int, remove: Int, request: Int, descriptor: String, callback: Int): List<Int>? = try {
        val service = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, name) as? IBinder
        if (service == null) {
            null
        } else {
            val listener = Listener(descriptor, callback)
            call(service, add, listener)
            try {
                call(service, request, null)
                listener.done.await(ANSWER_MS, TimeUnit.MILLISECONDS)
            } finally {
                call(service, remove, listener)
            }
            listener.words
        }
    } catch (e: RemoteException) {
        log("listen $name: $e")
        null
    } catch (e: ReflectiveOperationException) {
        log("listen $name: $e")
        null
    } catch (e: RuntimeException) {
        log("listen $name: $e")
        null
    }
}
