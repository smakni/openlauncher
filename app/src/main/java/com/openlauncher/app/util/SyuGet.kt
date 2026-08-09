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
 * What stands in the way is the reply. get returns a ModuleObject, a type the
 * vendor defined and whose field layout is not published, and a parcel read
 * against the wrong layout does not fail — it returns plausible numbers. So the
 * generated stub is bypassed entirely: the transaction is made by hand and the
 * reply parcel is decoded by trying candidate layouts, with the result treated
 * as untrusted until [SyuGetLayout] has been proven against a value already
 * known from the callback.
 */
object SyuGet {

    /** Declaration order in the AIDL assigns these: cmd 1, get 2. */
    private const val TRANSACTION_GET = 2
    private const val DESCRIPTOR = "com.syu.ipc.IRemoteModule"

    /** Beyond this an "array length" is being read out of the wrong offset. */
    private const val MAX_SANE_ARRAY = 64

    data class Reply(
        val ints: List<Int>,
        val floats: List<Float>,
        val strings: List<String>,
        /** Which candidate layout parsed it, for the record. */
        val layout: String
    )

    /**
     * Performs the call and decodes the reply, or null if nothing parsed.
     *
     * Null covers both a service that refused the transaction and a reply no
     * candidate layout fits. Neither is worth distinguishing at the call site:
     * in both cases there is no value to use.
     */
    fun get(remote: IRemoteModule, id: Int): Reply? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(id)
            // The three argument arrays the interface declares. Empty rather
            // than null: a null array is a distinct wire encoding and some
            // vendor stubs dereference it without checking.
            data.writeIntArray(IntArray(0))
            data.writeFloatArray(FloatArray(0))
            data.writeStringArray(emptyArray())

            val ok = remote.asBinder().transact(TRANSACTION_GET, data, reply, 0)
            if (!ok) return null

            reply.readException()
            // AIDL writes a presence flag before a nullable parcelable return.
            if (reply.readInt() == 0) return null

            val body = reply.dataPosition()
            LAYOUTS.firstNotNullOfOrNull { (name, parse) ->
                reply.setDataPosition(body)
                runCatching { parse(reply)?.copy(layout = name) }.getOrNull()
            }
        } catch (e: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Candidate layouts, most likely first.
     *
     * The callback delivers (int[], float[], String[]) and a return type built
     * to carry the same payload is the obvious shape, with a leading id as the
     * next most likely variation. Each returns null rather than throwing when
     * the numbers it reads are not credible, so an unfit layout is rejected
     * instead of producing a value.
     */
    private val LAYOUTS: List<Pair<String, (Parcel) -> Reply?>> = listOf(
        "ints,floats,strings" to { p -> readTriple(p) },
        "id,ints,floats,strings" to { p -> p.readInt(); readTriple(p) },
        "ints" to { p ->
            val ints = p.createIntArray()
            if (ints == null || ints.size > MAX_SANE_ARRAY) null
            else Reply(ints.toList(), emptyList(), emptyList(), "")
        }
    )

    private fun readTriple(p: Parcel): Reply? {
        val ints = p.createIntArray() ?: return null
        if (ints.size > MAX_SANE_ARRAY) return null
        val floats = p.createFloatArray() ?: return null
        if (floats.size > MAX_SANE_ARRAY) return null
        val strings = p.createStringArray() ?: return null
        if (strings.size > MAX_SANE_ARRAY) return null
        return Reply(ints.toList(), floats.toList(), strings.filterNotNull(), "")
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
                        data.writeIntArray(IntArray(0))
                        data.writeFloatArray(FloatArray(0))
                        data.writeStringArray(emptyArray())
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
