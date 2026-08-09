package com.openlauncher.app.util

import android.os.Parcel
import com.syu.ipc.IRemoteModule
import java.io.File

/**
 * Asks the vendor service for a value instead of waiting for it to change.
 *
 * The subscription path only delivers on change, which is why outside
 * temperature and fuel never arrive: they are sent once and then hold the same
 * value for hours, so a launcher started afterwards waits for an event that
 * never comes. IRemoteModule.get answers immediately, and it is a read — unlike
 * cmd, it cannot alter how the CAN box is configured.
 *
 * The reply is decoded by hand rather than through the generated stub. get
 * returns ModuleObject, which is not a self-describing parcelable — the vendor
 * marshals its three arrays inline — so a stub generated from our own AIDL
 * would read it as a parcelable and get nonsense. The layout used here is taken
 * from the vendor's own generated code, so it is transcribed rather than
 * inferred.
 */
object SyuGet {

    /** Declaration order in the AIDL assigns these: cmd 1, get 2. */
    private const val TRANSACTION_GET = 2
    private const val DESCRIPTOR = "com.syu.ipc.IRemoteModule"

    data class Reply(
        val ints: List<Int>,
        val floats: List<Float>,
        val strings: List<String>
    )

    /**
     * Performs the call and decodes the reply, or null if there is none.
     *
     * Null covers a refused transaction and a service with nothing to say for
     * that id. Neither is worth telling apart at the call site: in both cases
     * there is no value to use.
     */
    fun get(remote: IRemoteModule, id: Int): Reply? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(id)
            // Null, not empty. The vendor's own callers pass get(code, null,
            // null, null), and a null array is a distinct encoding on the wire
            // (length -1) from an empty one (length 0).
            data.writeIntArray(null)
            data.writeFloatArray(null)
            data.writeStringArray(null)

            val ok = remote.asBinder().transact(TRANSACTION_GET, data, reply, 0)
            if (!ok) return null

            reply.readException()
            // A presence flag, then the three arrays in this order. Taken from
            // the vendor's own generated stub rather than guessed: it writes
            // writeInt(1), writeIntArray, writeFloatArray, writeStringArray, and
            // returns writeInt(0) alone when it has nothing.
            if (reply.readInt() == 0) return null
            Reply(
                ints = reply.createIntArray()?.toList().orEmpty(),
                floats = reply.createFloatArray()?.toList().orEmpty(),
                strings = reply.createStringArray()?.filterNotNull().orEmpty()
            )
        } catch (e: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Writes the raw reply bytes for ids that no layout fits.
     *
     * Without this a failed decode leaves nothing to work from, and the next
     * attempt is another guess. The bytes are the evidence: the layout can be
     * read off them directly rather than inferred.
     */
    fun dumpRaw(context: android.content.Context, remote: IRemoteModule, ids: List<Int>): String =
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "vendor").apply { mkdirs() }
            val file = File(dir, "syu-get.txt")
            file.writeText(buildString {
                append("IRemoteModule.get raw replies\n")
                append("=".repeat(52)).append('\n')
                append("\nEach reply is printed as bytes and as 4-byte ints, which is\n")
                append("how a parcel is laid out. Array lengths appear as a small\n")
                append("count immediately before their elements.\n")

                for (id in ids) {
                    append("\n\n--- id $id ---\n")
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(DESCRIPTOR)
                        data.writeInt(id)
                        data.writeIntArray(null)
                        data.writeFloatArray(null)
                        data.writeStringArray(null)
                        val ok = remote.asBinder().transact(TRANSACTION_GET, data, reply, 0)
                        append("transact: $ok\n")
                        if (!ok) continue
                        runCatching { reply.readException() }
                            .onFailure { append("exception: ${it.javaClass.simpleName}: ${it.message}\n") }
                        val bytes = reply.marshall()
                        append("size: ${bytes.size} bytes\n")
                        append("hex: ")
                        bytes.take(96).forEach { append("%02x ".format(it)) }
                        append('\n')
                        append("ints: ")
                        reply.setDataPosition(0)
                        repeat(minOf(16, bytes.size / 4)) {
                            append(runCatching { reply.readInt() }.getOrDefault(0)).append(' ')
                        }
                        append('\n')
                    } catch (e: Exception) {
                        append("failed: ${e.javaClass.simpleName}: ${e.message}\n")
                    } finally {
                        reply.recycle()
                        data.recycle()
                    }
                }
            })
            file.absolutePath
        }.getOrElse { "dump failed: ${it.javaClass.simpleName}" }
}
