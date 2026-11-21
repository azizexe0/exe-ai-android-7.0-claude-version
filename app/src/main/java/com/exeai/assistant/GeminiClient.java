package com.exeai.assistant;

import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Google Gemini REST client. The API key is sent in a header, never in the URL. */
public final class GeminiClient {
    // Google returned 404 for gemini-2.0-flash and gemini-2.5-flash and recommended gemini-3.8-flash.
    // MODEL_PRO is set to the same model until a separate pro model name is verified.
    public static final String MODEL_FAST = "gemini-3.8-flash";
    public static final String MODEL_PRO = "gemini-3.8-flash";

    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int TIMEOUT_MS = 30000;

    public static final String SYSTEM_PROMPT =
            "Sen EXE AI'sin. Türkçe konuşursun ve kullanıcıya 'efendim' diye hitap edersin. "
          + "Kendini gerçek bir insan olarak tanıtmazsın. Sana kimin kod yazdığı sorulursa "
          + "'Beni Aziz EXE kodladı.' diye cevap verirsin. "
          + "Kullanıcının yazım hatalarını ona söylemeden bağlamdan anlamaya çalışırsın. "
          + "Mesajı gerçekten anlayamazsan tahmin uydurmaz, 'Anlamadım efendim.' dersin. "
          + "Cevapların kısa, net ve mobil ekrana uygun olsun.";

    private GeminiClient() {}

    public static final class GeminiException extends Exception {
        public GeminiException(String message) { super(message); }
    }

    /** Auto model choice: short plain questions use the fast model, long or analytical ones the pro model. */
    public static String chooseModel(String text, boolean hasImage) {
        if (hasImage) return MODEL_FAST;
        String t = text == null ? "" : text.toLowerCase();
        boolean complex = t.length() > 140
                || t.contains("neden") || t.contains("karşılaştır") || t.contains("karsilastir")
                || t.contains("kod") || t.contains("adım adım") || t.contains("analiz")
                || t.contains("planla") || t.contains("hesapla");
        return complex ? MODEL_PRO : MODEL_FAST;
    }

    /** Sends a small request to verify the key. Returns the model reply on success. */
    public static String testConnection(String apiKey, String model) throws GeminiException {
        String useModel = (model == null || model.trim().isEmpty()) ? MODEL_FAST : model.trim();
        return ask(apiKey, useModel, "Kısaca 'bağlantı tamam' yaz.", "Merhaba", null);
    }

    public static String ask(String apiKey, String model, String systemPrompt, String userText, Bitmap image)
            throws GeminiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new GeminiException("Gemini API anahtarı tanımlı değil. Gizli ayarları açıp anahtarı girebilirsin.");
        }
        try {
            JSONObject body = new JSONObject();
            body.put("system_instruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))));

            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", userText));
            if (image != null) {
                parts.put(new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", encodeImage(image))));
            }
            body.put("contents", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("parts", parts)));

            JSONObject generation = new JSONObject();
            generation.put("temperature", 0.6);
            generation.put("maxOutputTokens", 1024);
            body.put("generationConfig", generation);

            HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT + model + ":generateContent").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("x-goog-api-key", apiKey.trim());

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            int code = conn.getResponseCode();
            String raw = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            conn.disconnect();

            if (code != 200) throw new GeminiException(httpMessage(code, raw));
            return extractText(raw);
        } catch (GeminiException e) {
            throw e;
        } catch (java.net.UnknownHostException e) {
            throw new GeminiException("İnternet bağlantısı yok ya da sunucuya ulaşılamadı.");
        } catch (java.net.SocketTimeoutException e) {
            throw new GeminiException("Gemini yanıt vermedi (zaman aşımı). Tekrar deneyebilirsin.");
        } catch (Exception e) {
            throw new GeminiException("Gemini bağlantı hatası: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static String encodeImage(Bitmap bitmap) {
        // Downscale large photos so the request stays small.
        Bitmap scaled = bitmap;
        int max = 1024;
        if (bitmap.getWidth() > max || bitmap.getHeight() > max) {
            float ratio = Math.min(max / (float) bitmap.getWidth(), max / (float) bitmap.getHeight());
            scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.round(bitmap.getWidth() * ratio), Math.round(bitmap.getHeight() * ratio), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private static String extractText(String raw) throws GeminiException {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new GeminiException("Gemini boş cevap döndürdü. Sorunu biraz farklı yazabilir misin?");
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            if (content == null) throw new GeminiException("Gemini içerik döndürmedi (güvenlik filtresi olabilir).");
            JSONArray parts = content.optJSONArray("parts");
            StringBuilder out = new StringBuilder();
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    String t = parts.getJSONObject(i).optString("text", "");
                    if (!t.isEmpty()) out.append(t);
                }
            }
            String text = out.toString().trim();
            if (text.isEmpty()) throw new GeminiException("Gemini metin döndürmedi. Anlamadım efendim.");
            return text;
        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiException("Gemini cevabı okunamadı.");
        }
    }

    private static String httpMessage(int code, String raw) {
        String apiMessage = "";
        try {
            apiMessage = new JSONObject(raw).getJSONObject("error").optString("message", "");
        } catch (Exception ignored) { }
        switch (code) {
            case 400: return "Gemini isteği reddetti (400). " + apiMessage;
            case 401:
            case 403: return "API anahtarı geçersiz veya bu modele yetkin yok (" + code + "). " + apiMessage;
            case 404: return "Model bulunamadı (404). Model adı değişmiş olabilir: " + apiMessage;
            case 429: return "Gemini kota sınırına ulaşıldı (429). Biraz bekleyip tekrar dene.";
            default:
                if (code >= 500) return "Gemini sunucu hatası (" + code + "). Daha sonra tekrar dene.";
                return "Gemini hatası (" + code + "). " + apiMessage;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
