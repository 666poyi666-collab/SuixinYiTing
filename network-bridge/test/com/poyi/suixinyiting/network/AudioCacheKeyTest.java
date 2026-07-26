package com.poyi.suixinyiting.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The cache key must be stable across process restarts (so cached bytes are
 * reused, NET-005) yet distinct whenever the underlying stream differs, or a
 * stale/short read could be served as a different track's audio.
 */
public class AudioCacheKeyTest {

    private static AudioCacheKey key(long id, String q, String f, long len, String v) {
        return new AudioCacheKey(id, q, f, len, v);
    }

    @Test public void identicalInputsProduceIdenticalId() {
        assertEquals(
                key(1, "lossless", "flac", 100, "etag").id,
                key(1, "lossless", "flac", 100, "etag").id);
    }

    @Test public void qualityFormatValidatorAreNormalized() {
        // Trim + lowercase so "FLAC " and "flac" share a cache slot.
        assertEquals(
                key(1, "Lossless", " FLAC ", 100, " etag ").id,
                key(1, "lossless", "flac", 100, "etag").id);
    }

    @Test public void differentTrackIdChangesId() {
        assertNotEquals(key(1, "lossless", "flac", 100, "e").id,
                key(2, "lossless", "flac", 100, "e").id);
    }

    @Test public void differentContentLengthChangesId() {
        assertNotEquals(key(1, "lossless", "flac", 100, "e").id,
                key(1, "lossless", "flac", 101, "e").id);
    }

    @Test public void differentValidatorChangesId() {
        assertNotEquals(key(1, "lossless", "flac", 100, "etag-a").id,
                key(1, "lossless", "flac", 100, "etag-b").id);
    }

    @Test public void nullFieldsAreTreatedAsEmptyNotCrashing() {
        AudioCacheKey k = key(5, null, null, 0, null);
        assertEquals("", k.quality);
        assertEquals("", k.format);
        assertEquals("", k.validator);
        assertTrue("id is a 64-hex sha-256", k.id.matches("[0-9a-f]{64}"));
    }

    @Test public void idIsSha256HexShaped() {
        assertTrue(key(9, "exhigh", "mp3", 42, "w/\"x\"").id.matches("[0-9a-f]{64}"));
    }
}
