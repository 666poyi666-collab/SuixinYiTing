package com.poyi.suixinyiting.network;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link SharedPreferences} for tests.
 *
 * <p>android.jar only ships the interface, so implementing it here means the
 * classes under test (e.g. {@link ShuffleBag}) run their real persist/restore
 * paths against a plain map — no Android runtime, no stub methods. {@code apply}
 * and {@code commit} both write through synchronously, which is all the tests
 * rely on.
 */
public final class FakeSharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();

    @Override public Map<String, ?> getAll() { return new HashMap<>(values); }

    @Override public String getString(String key, String def) {
        Object v = values.get(key);
        return v instanceof String ? (String) v : def;
    }

    @Override @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> def) {
        Object v = values.get(key);
        return v instanceof Set ? (Set<String>) v : def;
    }

    @Override public int getInt(String key, int def) {
        Object v = values.get(key);
        return v instanceof Integer ? (Integer) v : def;
    }

    @Override public long getLong(String key, long def) {
        Object v = values.get(key);
        return v instanceof Long ? (Long) v : def;
    }

    @Override public float getFloat(String key, float def) {
        Object v = values.get(key);
        return v instanceof Float ? (Float) v : def;
    }

    @Override public boolean getBoolean(String key, boolean def) {
        Object v = values.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @Override public boolean contains(String key) { return values.containsKey(key); }

    @Override public Editor edit() { return new FakeEditor(); }

    @Override public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {}

    @Override public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {}

    private final class FakeEditor implements Editor {
        private final Map<String, Object> staged = new HashMap<>();
        private boolean clear;

        @Override public Editor putString(String k, String v) { staged.put(k, v); return this; }
        @Override public Editor putStringSet(String k, Set<String> v) { staged.put(k, v); return this; }
        @Override public Editor putInt(String k, int v) { staged.put(k, v); return this; }
        @Override public Editor putLong(String k, long v) { staged.put(k, v); return this; }
        @Override public Editor putFloat(String k, float v) { staged.put(k, v); return this; }
        @Override public Editor putBoolean(String k, boolean v) { staged.put(k, v); return this; }
        @Override public Editor remove(String k) { staged.put(k, TOMBSTONE); return this; }
        @Override public Editor clear() { clear = true; return this; }

        @Override public boolean commit() { flush(); return true; }
        @Override public void apply() { flush(); }

        private void flush() {
            if (clear) values.clear();
            for (Map.Entry<String, Object> e : staged.entrySet()) {
                if (e.getValue() == TOMBSTONE) values.remove(e.getKey());
                else values.put(e.getKey(), e.getValue());
            }
        }
    }

    private static final Object TOMBSTONE = new Object();
}
