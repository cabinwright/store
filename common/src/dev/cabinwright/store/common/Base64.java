package dev.cabinwright.store.common;

/** Standard base64 decoding (Android 4.4 has no java.util.Base64; the helper avoids android.*). */
public final class Base64 {
    private Base64() {
    }

    /** Decodes standard or URL-safe base64; whitespace is skipped, padding optional. */
    public static byte[] decode(String s) {
        byte[] out = new byte[s.length() * 3 / 4 + 3];
        int n = 0, bits = 0, acc = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+' || c == '-') v = 62;
            else if (c == '/' || c == '_') v = 63;
            else if (c == '=') break;
            else if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            else throw new IllegalArgumentException("not base64");
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) (acc >> bits);
            }
        }
        byte[] r = new byte[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }
}
