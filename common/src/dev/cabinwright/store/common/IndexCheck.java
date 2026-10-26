package dev.cabinwright.store.common;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Accepts or refuses an index: SHA256withRSA (PKCS#1 v1.5) over the exact bytes served,
 * against pinned keys, then sequence and expiry. The app and the helper both run this,
 * each with its own pinned keys and its own last-accepted sequence.
 */
public final class IndexCheck {
    /** Why an index was refused, in words the app can show as they are. */
    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    /** A pinned key: its name and its X.509 SubjectPublicKeyInfo (base64 DER). */
    public static final class Key {
        public final String id;
        public final PublicKey key;

        public Key(String id, String spkiBase64) {
            this.id = id;
            try {
                key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(spkiBase64)));
            } catch (Exception e) {
                throw new IllegalArgumentException("bad pinned key " + id, e);
            }
        }
    }

    private IndexCheck() {
    }

    public static List<Key> keys(String[][] pins) {
        List<Key> out = new ArrayList<Key>();
        for (String[] p : pins) out.add(new Key(p[0], p[1]));
        return out;
    }

    /** Returns the id of the pinned key that signed these bytes, or throws. */
    public static String verifySignature(byte[] index, byte[] sigFile, List<Key> keys) throws Refused {
        byte[] sig;
        try {
            sig = Base64.decode(new String(sigFile, "US-ASCII").trim());
        } catch (Exception e) {
            throw new Refused("The index signature is malformed.");
        }
        for (Key k : keys) {
            try {
                Signature v = Signature.getInstance("SHA256withRSA");
                v.initVerify(k.key);
                v.update(index);
                if (v.verify(sig)) return k.id;
            } catch (Exception ignored) {
                // a wrong-size signature for this key throws; try the next key
            }
        }
        throw new Refused("The index signature doesn't match any trusted key.");
    }

    /**
     * Verifies, parses and checks an index. {@code lastSequence} is the highest sequence
     * accepted before (-1 for none); {@code nowMs} the current time.
     */
    public static Index check(byte[] indexBytes, byte[] sigFile, List<Key> keys, long lastSequence, long nowMs)
            throws Refused {
        String keyId = verifySignature(indexBytes, sigFile, keys);
        Index index;
        try {
            index = new Index(Json.parseObject(new String(indexBytes, "UTF-8")));
        } catch (Exception e) {
            throw new Refused("The index is signed but can't be read: " + e.getMessage());
        }
        if (!index.keyId.isEmpty() && !index.keyId.equals(keyId)) {
            throw new Refused("The index names key " + index.keyId + " but was signed with " + keyId + ".");
        }
        if (index.sequence < lastSequence) {
            throw new Refused("The index is older (#" + index.sequence + ") than one already seen (#"
                    + lastSequence + "): refusing a rollback.");
        }
        long expires = parseTime(index.expires);
        if (expires < 0) throw new Refused("The index has no valid expiry date.");
        if (nowMs > expires) throw new Refused("The index expired on " + index.expires + ".");
        return index;
    }

    /** Parses "2027-01-23T18:00:00Z"; -1 if malformed. */
    public static long parseTime(String s) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        f.setLenient(false);
        try {
            return f.parse(s).getTime();
        } catch (ParseException e) {
            return -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return s.toString();
    }
}
