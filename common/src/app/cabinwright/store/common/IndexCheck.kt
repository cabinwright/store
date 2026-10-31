package app.cabinwright.store.common

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Accepts or refuses an index: SHA256withRSA (PKCS#1 v1.5) over the exact bytes served,
 * against pinned keys, then sequence and expiry. The app and the helper both run this,
 * each with its own pinned keys and its own last-accepted sequence.
 */
object IndexCheck {
    /** Why an index was refused, in words the app can show as they are. */
    class Refused(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** A pinned key: its name and its X.509 SubjectPublicKeyInfo (base64 DER). */
    class Key(val id: String, spkiBase64: String) {
        val publicKey: PublicKey = try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(spkiBase64)))
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("bad pinned key $id", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad pinned key $id", e)
        }
    }

    /** Keys from pins (name, base64 SubjectPublicKeyInfo), as build.sh writes them in Keys. */
    fun keys(pins: Array<Pair<String, String>>): MutableList<Key> =
        pins.mapTo(ArrayList()) { (id, b64) -> Key(id, b64) }

    /** Returns the id of the pinned key that signed these bytes, or throws. */
    fun verifySignature(index: ByteArray, sigFile: ByteArray, keys: List<Key>): String {
        val sig = try {
            Base64.decode(String(sigFile, Charsets.US_ASCII).trim())
        } catch (ignored: IllegalArgumentException) {
            throw Refused("The index signature is malformed.")
        }
        for (k in keys) {
            try {
                val v = Signature.getInstance("SHA256withRSA")
                v.initVerify(k.publicKey)
                v.update(index)
                if (v.verify(sig)) return k.id
            } catch (ignored: Exception) {
                // a wrong-size signature for this key throws (how depends on the provider); try the next key
            }
        }
        throw Refused("The index signature doesn't match any trusted key.")
    }

    /**
     * Verifies, parses and checks an index. [lastSequence] is the highest sequence
     * accepted before (-1 for none); [nowMs] the current time.
     */
    fun check(indexBytes: ByteArray, sigFile: ByteArray, keys: List<Key>, lastSequence: Long, nowMs: Long): Index {
        val keyId = verifySignature(indexBytes, sigFile, keys)
        val index = try {
            Index(Json.parseObject(String(indexBytes, Charsets.UTF_8)))
        } catch (e: IllegalArgumentException) {
            throw Refused("The index is signed but can't be read: " + e.message, e)
        }
        if (index.keyId.isNotEmpty() && index.keyId != keyId) {
            refuse("The index names key ${index.keyId} but was signed with $keyId.")
        }
        if (index.sequence < lastSequence) {
            refuse(
                "The index is older (#${index.sequence}) than one already seen (#$lastSequence): refusing a rollback.",
            )
        }
        val expires = parseTime(index.expires)
        if (expires < 0) refuse("The index has no valid expiry date.")
        if (nowMs > expires) refuse("The index expired on ${index.expires}.")
        return index
    }

    private fun refuse(message: String): Nothing = throw Refused(message)

    /** Parses "2027-01-23T18:00:00Z"; -1 if malformed. */
    fun parseTime(s: String): Long {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        f.isLenient = false
        return try {
            f.parse(s)?.time ?: -1
        } catch (e: ParseException) {
            -1
        } catch (ignored: RuntimeException) {
            -1
        }
    }

    fun sha256(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    fun hex(b: ByteArray): String {
        val s = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt()
            s.append(Character.forDigit((v shr 4) and HEX_MASK, HEX)).append(Character.forDigit(v and HEX_MASK, HEX))
        }
        return s.toString()
    }

    private const val HEX = 16
    private const val HEX_MASK = 15
}
