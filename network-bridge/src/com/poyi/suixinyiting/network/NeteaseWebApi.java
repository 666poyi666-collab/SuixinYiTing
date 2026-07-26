package com.poyi.suixinyiting.network;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NeteaseWebApi implements NetworkMusicSource, StreamResolver {
    private static final String BASE = "https://music.163.com";
    private static final String NONCE = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String PUBKEY = "010001";
    private static final String MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7" +
            "b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280" +
            "104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932" +
            "575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b" +
            "3ece0462db0a22b8e7";
    private final Context context;
    private final SessionStore sessions;
    private final PlaylistStore store;
    private volatile String responseCookie = "";
    private volatile String loginCookie = "";
    private volatile String loginTraceId = "";

    public NeteaseWebApi(Context context) {
        this.context = context.getApplicationContext();
        this.sessions = new SessionStore(context);
        this.store = new PlaylistStore(context);
    }

    @Override public LoginTicket beginQrLogin() throws Exception {
        loginTraceId = deviceId() + System.currentTimeMillis();
        loginCookie = mergeCookies(loginCookie, deviceCookies());
        // The watch client first establishes an anonymous device cookie. Failure is
        // non-fatal: the QR endpoint can still issue a user session on some regions.
        try {
            post("/weapi/login/anon/device", new JSONObject(), false);
        } catch (Exception e) {
            Log.w("SuixinNetease", "anonymous device login skipped: " + e.getMessage());
        }
        JSONObject r = post("/weapi/login/qrcode/unikey",
                new JSONObject().put("type", 2).put("login_traceId", loginTraceId), false);
        String key = r.optJSONObject("data") != null ? r.getJSONObject("data").optString("unikey") : r.optString("unikey");
        if (TextUtils.isEmpty(key)) throw new IllegalStateException("二维码密钥获取失败");
        String loginUrl = watchLoginUrl(key);
        try {
            JSONObject shortened = post("/weapi/middle/shorturl/generate",
                    new JSONObject().put("url", loginUrl).put("login_traceId", loginTraceId), false);
            JSONObject data = shortened.optJSONObject("data");
            if (data != null && !TextUtils.isEmpty(data.optString("shortUrl")))
                loginUrl = data.optString("shortUrl");
        } catch (Exception e) {
            Log.w("SuixinNetease", "short URL unavailable, using full watch URL: " + e.getMessage());
        }
        return new LoginTicket(key, loginUrl);
    }

    @Override public String pollQrLogin(String key) throws Exception {
        JSONObject r = post("/weapi/login/qrcode/client/login",
                new JSONObject().put("key", key).put("type", 2)
                        .put("login_traceId", loginTraceId), false);
        int code = r.optInt("code");
        Log.i("SuixinNetease", "qr poll code=" + code + " headerCookie=" + !responseCookie.isEmpty()
                + " bodyCookie=" + !r.optString("cookie").isEmpty());
        if (code == 803) {
            String bodyCookie = r.optString("cookie");
            String established = mergeCookies(loginCookie, responseCookie);
            established = mergeCookies(established, bodyCookie);
            if (!containsLoginCredential(established))
                throw new IllegalStateException("手机已确认，但手表未收到登录凭据");
            if (!cookieValue(established, "MUSIC_U").isEmpty())
                established = removeCookie(established, "MUSIC_A");
            sessions.saveCookie(established);
        }
        return Integer.toString(code);
    }

    @Override public long currentUserId() throws Exception {
        JSONObject r = post("/weapi/w/nuser/account/get", new JSONObject(), true);
        JSONObject profile = r.optJSONObject("profile");
        if (profile == null) throw new IllegalStateException("登录状态已失效");
        long userId = profile.optLong("userId");
        if (userId <= 0) throw new IllegalStateException("账号信息缺少用户 ID");
        sessions.saveUserId(userId);
        return userId;
    }

    @Override public List<Playlist> playlists(long userId) throws Exception {
        JSONObject r = post("/weapi/user/playlist",
                new JSONObject().put("uid", userId).put("limit", 1000).put("offset", 0).put("includeVideo", true), true);
        JSONArray a = r.optJSONArray("playlist");
        List<Playlist> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            out.add(new Playlist(p.optLong("id"), p.optString("name"), p.optInt("trackCount")));
        }
        store.savePlaylists(out);
        return out;
    }

    @Override public synchronized int syncPlaylist(long playlistId, PlaylistStore ignored) throws Exception {
        JSONObject r = post("/weapi/v6/playlist/detail",
                new JSONObject().put("id", playlistId).put("n", 100000).put("s", 0), true);
        JSONObject playlist = r.getJSONObject("playlist");
        JSONArray ids = playlist.optJSONArray("trackIds");
        List<NetworkTrack> all = new ArrayList<>();
        store.beginSync(playlistId);
        if (ids != null) for (int start = 0; start < ids.length(); start += 200) {
            JSONArray c = new JSONArray();
            java.util.HashMap<Long,Integer> positions = new java.util.HashMap<>();
            int end = Math.min(ids.length(), start + 200);
            for (int i = start; i < end; i++) {
                long id = ids.getJSONObject(i).optLong("id");
                c.put(new JSONObject().put("id", id));
                positions.put(id, i);
            }
            JSONObject detail = post("/weapi/v3/song/detail", new JSONObject().put("c", c.toString()), true);
            JSONArray songs = detail.optJSONArray("songs");
            java.util.HashMap<Long,JSONObject> privileges = new java.util.HashMap<>();
            JSONArray privilegeArray = detail.optJSONArray("privileges");
            if (privilegeArray != null) for (int i = 0; i < privilegeArray.length(); i++) {
                JSONObject privilege = privilegeArray.optJSONObject(i);
                if (privilege != null) privileges.put(privilege.optLong("id"), privilege);
            }
            List<NetworkTrack> batch = new ArrayList<>();
            if (songs != null) for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.getJSONObject(i);
                long id = song.optLong("id");
                Integer position = positions.get(id);
                NetworkTrack track = parseTrack(song, position == null ? start + i : position,
                        privileges.get(id));
                all.add(track); batch.add(track);
            }
            store.appendTracks(playlistId, batch);
        }
        store.finishSync(playlistId, all.size());
        return all.size();
    }

    private static NetworkTrack parseTrack(JSONObject s, int order, JSONObject privilege) {
        JSONArray ar = s.optJSONArray("ar");
        StringBuilder artists = new StringBuilder();
        long[] artistIds = new long[ar == null ? 0 : ar.length()];
        String[] artistNames = new String[artistIds.length];
        if (ar != null) for (int i = 0; i < ar.length(); i++) {
            if (i > 0) artists.append('/');
            JSONObject artist = ar.optJSONObject(i);
            artistIds[i] = artist == null ? 0 : artist.optLong("id");
            artistNames[i] = artist == null ? "" : artist.optString("name");
            artists.append(artistNames[i]);
        }
        JSONObject al = s.optJSONObject("al");
        boolean playable = privilege == null || (privilege.optInt("st", 0) >= 0
                && (privilege.optInt("pl", 1) > 0 || privilege.optInt("dl", 0) > 0));
        return new NetworkTrack(s.optLong("id"), s.optString("name"), artists.toString(),
                artistIds, artistNames, al == null ? 0 : al.optLong("id"),
                al == null ? "" : al.optString("name"), al == null ? "" : al.optString("picUrl"),
                playable, order);
    }

    @Override public List<NetworkTrack> page(long playlistId, int offset, int limit) {
        return store.page(playlistId, offset, limit);
    }

    @Override public String lyric(long songId) throws Exception {
        JSONObject r = post("/weapi/song/lyric", new JSONObject().put("id", songId)
                .put("lv", -1).put("tv", -1), true);
        JSONObject lrc = r.optJSONObject("lrc");
        return lrc == null ? "" : lrc.optString("lyric");
    }

    @Override public StreamVariant resolve(long songId, String preferredLevel) throws Exception {
        Exception last = null;
        String[] levels = QualityPolicy.levels(preferredLevel);
        for (String level : levels) {
            try {
                JSONObject r = post("/weapi/song/enhance/player/url/v1",
                        new JSONObject().put("ids", playerIds(songId))
                                .put("level", level).put("encodeType", "flac")
                                .put("trialMode", 0).put("immerseType", "ste")
                                .put("cliUserId", Long.toString(sessions.userId())), true);
                JSONArray data = r.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    Log.w("SuixinNetease", "stream level=" + level + " returned no data");
                    continue;
                }
                JSONObject d = data.getJSONObject(0);
                String url = d.optString("url");
                if (TextUtils.isEmpty(url) || "null".equals(url)) {
                    Log.w("SuixinNetease", "stream level=" + level + " no url code="
                            + d.optInt("code") + " fee=" + d.optInt("fee")
                            + " message=" + d.optString("message"));
                    continue;
                }
                int freeTrial = d.optJSONObject("freeTrialInfo") == null ? 0 : 1;
                long lifetime = d.optLong("time", 15 * 60 * 1000L);
                if (lifetime < 30000L) lifetime = 15 * 60 * 1000L;
                String actualLevel = d.optString("level", level);
                return new StreamVariant(url, preferredLevel, actualLevel,
                        d.optInt("br"), d.optString("type"), System.currentTimeMillis() + lifetime,
                        freeTrial == 1 ? "试听资源"
                                : (!actualLevel.equalsIgnoreCase(preferredLevel) ? "服务端降档" : ""));
            } catch (Exception e) {
                Log.e("SuixinNetease", "stream level=" + level + " failed", e);
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("当前歌曲没有可用播放地址") : last;
    }

    @Override public void logout() { sessions.clear(); responseCookie = ""; }

    private String playerIds(long songId) {
        long userId = sessions.userId();
        JSONArray ids = new JSONArray();
        ids.put(userId > 0 ? Long.toString(songId) + "_" + userId : Long.toString(songId));
        return ids.toString();
    }

    private JSONObject post(String path, JSONObject payload, boolean auth) throws Exception {
        if (auth) payload.put("csrf_token", cookieValue(sessions.cookie(), "__csrf"));
        String secret = randomSecret();
        String params = aes(aes(payload.toString(), NONCE), secret);
        String encSecKey = rsa(secret);
        byte[] body = ("params=" + URLEncoder.encode(params, "UTF-8") +
                "&encSecKey=" + URLEncoder.encode(encSecKey, "UTF-8")).getBytes("UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(18000); c.setRequestMethod("POST");
        c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36");
        c.setRequestProperty("Referer", BASE + "/");
        String cookie = auth ? sessions.cookie() : loginCookie;
        if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
        StringBuilder setCookies = new StringBuilder();
        for (java.util.Map.Entry<String,List<String>> entry : c.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String h : entry.getValue()) {
                String first = h.split(";", 2)[0];
                if (setCookies.length() > 0) setCookies.append("; ");
                setCookies.append(first);
            }
        }
        responseCookie = setCookies.toString();
        if (!auth && !responseCookie.isEmpty()) loginCookie = mergeCookies(loginCookie, responseCookie);
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder text = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) text.append(line);
        br.close(); c.disconnect();
        JSONObject result = new JSONObject(text.toString());
        int code = result.optInt("code", 200);
        if (code >= 400 && code != 800 && code != 801 && code != 802 && code != 803)
            throw new IllegalStateException(result.optString("message", "网络请求失败 " + code));
        return result;
    }

    private static String mergeCookies(String oldValue, String newValue) {
        return Cookies.merge(oldValue, newValue);
    }

    private static String removeCookie(String cookie, String name) {
        return Cookies.remove(cookie, name);
    }

    private String watchLoginUrl(String key) throws Exception {
        return BASE + "/st/platform/scanlogin?codekey=" + enc(key)
                + "&hdw_deviceid=" + enc(deviceId())
                + "&hdw_device=watch"
                + "&hdw_brand=" + enc(Build.BRAND)
                + "&hdw_model=" + enc(Build.MODEL)
                + "&hdw_token="
                + "&hdw_ip="
                + "&login_traceId=" + enc(loginTraceId);
    }

    private String deviceId() {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return TextUtils.isEmpty(id) ? "suixin-watch" : id;
    }

    private String deviceCookies() {
        return "os=android; appver=3.0.39; osver=" + Build.VERSION.RELEASE
                + "; deviceId=" + deviceId()
                + "; mobilename=" + Build.MODEL
                + "; channel=watch";
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static boolean containsLoginCredential(String cookie) {
        return Cookies.hasLoginCredential(cookie);
    }

    private static String cookieValue(String cookie, String name) {
        return Cookies.value(cookie, name);
    }

    private static String randomSecret() {
        final char[] alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
        SecureRandom r = new SecureRandom(); char[] out = new char[16];
        for (int i = 0; i < out.length; i++) out[i] = alphabet[r.nextInt(alphabet.length)];
        return new String(out);
    }

    private static String aes(String value, String key) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(IV.getBytes("UTF-8")));
        return Base64.encodeToString(c.doFinal(value.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private static String rsa(String secret) {
        String reversed = new StringBuilder(secret).reverse().toString();
        java.math.BigInteger value = new java.math.BigInteger(1, reversed.getBytes());
        String result = value.modPow(new java.math.BigInteger(PUBKEY, 16),
                new java.math.BigInteger(MODULUS, 16)).toString(16);
        StringBuilder out = new StringBuilder();
        for (int i = result.length(); i < 256; i++) out.append('0');
        return out.append(result).toString();
    }
}
