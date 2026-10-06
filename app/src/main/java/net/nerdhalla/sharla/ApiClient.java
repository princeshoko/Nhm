package net.nerdhalla.sharla;

import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class ApiClient {
    private static final String BASE = "https://nerdhalla.net/";
    private String csrfToken = "";

    public void setCsrfToken(String token) {
        csrfToken = token == null ? "" : token;
    }

    public JSONObject getSession() throws Exception {
        return request(BASE + "discord-session.php", "GET", null, false);
    }

    public JSONObject exchangeMobileAuth(String code) throws Exception {
        JSONObject body = new JSONObject();
        body.put("code", code == null ? "" : code);
        return request(BASE + "mobile-auth-exchange.php", "POST", body, false);
    }

    public JSONObject api(String action) throws Exception {
        return api(action, "GET", null, "");
    }

    public JSONObject api(String action, String method, JSONObject body) throws Exception {
        return api(action, method, body, "");
    }

    public JSONObject api(String action, String method, JSONObject body, String extra) throws Exception {
        String url = BASE + "portal-api.php?action=" + java.net.URLEncoder.encode(action, "UTF-8") + (extra == null ? "" : extra);
        return request(url, method, body, !"GET".equalsIgnoreCase(method));
    }

    public JSONObject publicJson(String relativePath) throws Exception {
        String path = relativePath == null ? "" : relativePath.replaceFirst("^/+", "");
        String sep = path.contains("?") ? "&" : "?";
        return request(BASE + path + sep + "t=" + System.currentTimeMillis(), "GET", null, false);
    }

    private JSONObject request(String urlText, String method, JSONObject body, boolean csrf) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "NerdhallaAndroid/2.4.6");

        String cookie = CookieManager.getInstance().getCookie(BASE);
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }

        if (csrf && csrfToken != null && !csrfToken.isEmpty()) {
            conn.setRequestProperty("X-CSRF-Token", csrfToken);
        }

        if (body != null && !"GET".equalsIgnoreCase(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
        }

        int code = conn.getResponseCode();

        Map<String, List<String>> headers = conn.getHeaderFields();
        CookieManager cm = CookieManager.getInstance();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null || !"Set-Cookie".equalsIgnoreCase(key)) continue;
            List<String> values = entry.getValue();
            if (values == null) continue;
            for (String value : values) {
                if (value != null && !value.isEmpty()) cm.setCookie(BASE, value);
            }
        }
        cm.flush();

        InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }

        JSONObject json;
        try {
            json = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        } catch (Exception e) {
            throw new Exception("Nerdhalla returned an invalid response (HTTP " + code + ")");
        }

        if (code < 200 || code >= 300) {
            throw new Exception(json.optString("error", "HTTP " + code));
        }

        return json;
    }
}
