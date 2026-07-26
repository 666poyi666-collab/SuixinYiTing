package com.poyi.suixinyiting.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Netease session cookie handling (LIB-002) — losing these means re-login. */
public class CookiesTest {

    @Test public void mergeLetsLaterValueWin() {
        assertEquals("a=2; b=9",
                Cookies.merge("a=1; b=9", "a=2"));
    }

    @Test public void mergePreservesInsertionOrder() {
        assertEquals("x=1; y=2; z=3", Cookies.merge("x=1; y=2", "z=3"));
    }

    @Test public void mergeDropsSetCookieAttributes() {
        assertEquals("MUSIC_U=tok",
                Cookies.merge("", "MUSIC_U=tok; Path=/; Domain=.music.163.com; Max-Age=0"));
    }

    @Test public void mergeIsNullSafe() {
        assertEquals("k=v", Cookies.merge(null, "k=v"));
        assertEquals("k=v", Cookies.merge("k=v", null));
        assertEquals("", Cookies.merge(null, null));
    }

    @Test public void responseWithoutCredentialKeepsExistingLogin() {
        // The common case: a normal API response re-sends only a csrf/anon cookie.
        String login = "MUSIC_U=secret; __csrf=abc";
        String merged = Cookies.merge(login, "__csrf=def");
        assertEquals("secret", Cookies.value(merged, "MUSIC_U"));
        assertEquals("def", Cookies.value(merged, "__csrf"));
    }

    @Test public void emptyValueNeverWipesLiveCredential() {
        // Session-preservation guard: Set-Cookie: MUSIC_U=; must not drop login.
        String merged = Cookies.merge("MUSIC_U=secret", "MUSIC_U=");
        assertEquals("secret", Cookies.value(merged, "MUSIC_U"));
        assertTrue(Cookies.hasLoginCredential(merged));
    }

    @Test public void nonEmptyValueStillOverwrites() {
        assertEquals("fresh", Cookies.value(
                Cookies.merge("MUSIC_U=old", "MUSIC_U=fresh"), "MUSIC_U"));
    }

    @Test public void removeDropsNamedCookieOnly() {
        String out = Cookies.remove("MUSIC_U=u; MUSIC_A=a; __csrf=c", "MUSIC_A");
        assertEquals("u", Cookies.value(out, "MUSIC_U"));
        assertEquals("", Cookies.value(out, "MUSIC_A"));
        assertEquals("c", Cookies.value(out, "__csrf"));
    }

    @Test public void valueReturnsEmptyForMissingOrNull() {
        assertEquals("", Cookies.value("a=1", "b"));
        assertEquals("", Cookies.value(null, "a"));
    }

    @Test public void valuePreservesEqualsInsideValue() {
        // base64 / token values contain '=' padding.
        assertEquals("YWJjZA==", Cookies.value("t=YWJjZA==", "t"));
    }

    @Test public void hasLoginCredentialDetectsEitherToken() {
        assertTrue(Cookies.hasLoginCredential("MUSIC_U=x"));
        assertTrue(Cookies.hasLoginCredential("MUSIC_A=y"));
        assertFalse(Cookies.hasLoginCredential("__csrf=z"));
        assertFalse(Cookies.hasLoginCredential(""));
    }

    @Test public void attributesAreRecognizedCaseInsensitively() {
        assertTrue(Cookies.isAttribute("Path"));
        assertTrue(Cookies.isAttribute("MAX-AGE"));
        assertFalse(Cookies.isAttribute("MUSIC_U"));
    }
}
