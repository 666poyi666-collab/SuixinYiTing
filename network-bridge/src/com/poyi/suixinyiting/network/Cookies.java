package com.poyi.suixinyiting.network;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cookie string helpers for the netease session (LIB-002).
 *
 * <p>Extracted verbatim from {@link NeteaseWebApi} so the merge/remove/lookup
 * behaviour that guards the login credential can be regression-tested. Merging
 * keeps insertion order and lets the later source win; Set-Cookie attributes
 * (path/domain/expires/max-age/samesite) are never stored as credentials.
 */
public final class Cookies {
    private Cookies() {}

    /**
     * Merge two cookie strings; values from {@code newValue} override
     * {@code oldValue}.
     *
     * <p>Session-preservation guard (LIB-002): a later empty value never
     * overwrites an existing non-empty one. Without this, a response carrying
     * {@code Set-Cookie: MUSIC_U=; Max-Age=0} would wipe a live login. Logout is
     * an explicit local action, so the app never wants a server-sent blank to
     * silently drop the credential.
     */
    public static String merge(String oldValue, String newValue) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String source : new String[]{oldValue, newValue}) {
            if (source == null) continue;
            for (String part : source.split(";")) {
                String item = part.trim();
                int split = item.indexOf('=');
                if (split <= 0) continue;
                String key = item.substring(0, split);
                if (isAttribute(key)) continue;
                String value = item.substring(split + 1);
                if (value.isEmpty() && !map.getOrDefault(key, "").isEmpty()) continue;
                map.put(key, value);
            }
        }
        return join(map);
    }

    /** Drop a named cookie (e.g. the anonymous MUSIC_A once MUSIC_U is present). */
    public static String remove(String cookie, String name) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String item = part.trim();
                int split = item.indexOf('=');
                if (split > 0 && !name.equals(item.substring(0, split))) {
                    map.put(item.substring(0, split), item.substring(split + 1));
                }
            }
        }
        return join(map);
    }

    public static String value(String cookie, String name) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String item = part.trim();
            int split = item.indexOf('=');
            if (split > 0 && name.equals(item.substring(0, split))) {
                return item.substring(split + 1);
            }
        }
        return "";
    }

    public static boolean hasLoginCredential(String cookie) {
        return !value(cookie, "MUSIC_U").isEmpty() || !value(cookie, "MUSIC_A").isEmpty();
    }

    public static boolean isAttribute(String name) {
        return "path".equalsIgnoreCase(name) || "domain".equalsIgnoreCase(name)
                || "expires".equalsIgnoreCase(name) || "max-age".equalsIgnoreCase(name)
                || "samesite".equalsIgnoreCase(name);
    }

    private static String join(Map<String, String> map) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }
}
