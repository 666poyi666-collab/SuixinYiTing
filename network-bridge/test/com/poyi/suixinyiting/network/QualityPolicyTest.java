package com.poyi.suixinyiting.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Preferred-quality ordering and label mapping (AUDIO-001/002). */
public class QualityPolicyTest {

    @Test public void hiresPrefersHiresThenDegradesInOrder() {
        assertArrayEquals(
                new String[]{"hires", "lossless", "exhigh", "higher", "standard"},
                QualityPolicy.levels("hires"));
    }

    @Test public void unknownOrLosslessFallsBackToLosslessFirst() {
        assertArrayEquals(QualityPolicy.LOSSLESS_FIRST, QualityPolicy.levels("lossless"));
        assertArrayEquals(QualityPolicy.LOSSLESS_FIRST, QualityPolicy.levels(""));
        assertArrayEquals(QualityPolicy.LOSSLESS_FIRST, QualityPolicy.levels("garbage"));
    }

    @Test public void lowerTiersNeverRequestHigherThanRequested() {
        // A "standard" preference must not try lossless first (the 1.2.1 bug).
        assertArrayEquals(new String[]{"standard"}, QualityPolicy.levels("standard"));
        assertArrayEquals(new String[]{"higher", "standard"}, QualityPolicy.levels("higher"));
        assertArrayEquals(new String[]{"exhigh", "higher", "standard"},
                QualityPolicy.levels("exhigh"));
    }

    @Test public void everyLevelListIsMonotonicallyDegrading() {
        String[] rank = {"hires", "lossless", "exhigh", "higher", "standard"};
        for (String pref : new String[]{"hires", "lossless", "exhigh", "higher", "standard", ""}) {
            String[] levels = QualityPolicy.levels(pref);
            for (int i = 1; i < levels.length; i++) {
                assertTrue("levels degrade: " + levels[i - 1] + " -> " + levels[i],
                        rankOf(rank, levels[i - 1]) < rankOf(rank, levels[i]));
            }
        }
    }

    private static int rankOf(String[] rank, String level) {
        for (int i = 0; i < rank.length; i++) if (rank[i].equals(level)) return i;
        return Integer.MAX_VALUE;
    }

    @Test public void labelsAreHumanReadableAndCaseInsensitive() {
        assertEquals("Hi-Res", QualityPolicy.label("HIRES"));
        assertEquals("无损", QualityPolicy.label("lossless"));
        assertEquals("极高", QualityPolicy.label("exhigh"));
        assertEquals("较高", QualityPolicy.label("higher"));
        assertEquals("标准", QualityPolicy.label("standard"));
    }

    @Test public void labelFallsBackForUnknownAndEmpty() {
        assertEquals("未知音质", QualityPolicy.label(""));
        assertEquals("未知音质", QualityPolicy.label(null));
        assertEquals("weird", QualityPolicy.label("weird"));
    }
}
