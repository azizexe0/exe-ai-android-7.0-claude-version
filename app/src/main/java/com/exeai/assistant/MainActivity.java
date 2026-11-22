package com.exeai.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final int SPEECH = 41;
    private static final int IMAGE_PICK = 42;
    private static final int CAMERA = 43;
    private static final int IMPORT_QA = 44;
    private static final int FILE_PICK = 45;
    private static final int NOTIFY_PERMISSION = 46;

    private static final int RED = Color.rgb(255, 45, 55);
    private static final int BLACK = Color.rgb(10, 10, 10);
    private static final int PANEL = Color.rgb(28, 28, 28);
    private static final int MAX_FILE_CHARS = 12000;

    private static final Pattern WAKE_WORD = Pattern.compile("^(hey\\s+)?exe\\b[,.!?]?\\s*", Pattern.CASE_INSENSITIVE);

    private LinearLayout chat;
    private EditText input;
    private ScrollView scroll;
    private TextView statusLine;
    private DB db;
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private boolean ttsReady = false;
    private boolean voiceEnabled = true;
    private boolean liveVoice = false;
    private String selectedVoiceName = "";
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private String lastImageText = "";
    private Bitmap pendingBitmap = null;
    private String pendingFileText = null;

    // ---------- lifecycle ----------

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);

        prefs = getSharedPreferences("exe_ai_settings", MODE_PRIVATE);
        voiceEnabled = prefs.getBoolean("voice_enabled", true);
        selectedVoiceName = prefs.getString("voice_name", "");
        speechRate = prefs.getFloat("speech_rate", 1.0f);
        speechPitch = prefs.getFloat("speech_pitch", 1.0f);

        db = new DB(this);
        Reminders.createChannel(this);
        worker.execute(() -> Reminders.armAll(this, db));

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                tts.setLanguage(new Locale("tr", "TR"));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) {
                        if (liveVoice) mainHandler.post(MainActivity.this::listen);
                    }
                    @Override public void onError(String utteranceId) {
                        if (liveVoice) mainHandler.post(MainActivity.this::listen);
                    }
                });
                applySavedVoice();
            }
        });

        buildUI();
        addMessage("EXE AI 7.0 hazır efendim. Komut verebilir, soru sorabilir, alarm kurabilir veya dosya ekleyebilirsin. "
                + "Gemini API anahtarını girmek için EXE AI başlığına basılı tut.\nGeliştiricim: Aziz EXE.", false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFY_PERMISSION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        db.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------- UI ----------

    private void buildUI() {
        LinearLayout base = new LinearLayout(this);
        base.setOrientation(LinearLayout.VERTICAL);
        base.setBackgroundColor(BLACK);

        TextView title = new TextView(this);
        title.setText("EXE AI");
        title.setTextColor(RED);
        title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(18, 16, 12, 0);
        title.setOnLongClickListener(v -> {
            showHiddenApiSettings();
            return true;
        });

        TextView sub = new TextView(this);
        sub.setText("7.0  •  PERSONAL AI ASSISTANT  •  RED / BLACK");
        sub.setTextColor(0xffaaaaaa);
        sub.setTextSize(10);
        sub.setPadding(20, 0, 12, 2);

        statusLine = new TextView(this);
        statusLine.setTextSize(11);
        statusLine.setPadding(20, 0, 12, 10);
        base.addView(title);
        base.addView(sub);
        base.addView(statusLine);

        scroll = new ScrollView(this);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(12, 10, 12, 10);
        scroll.addView(chat);
        base.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout row1 = new LinearLayout(this);
        row1.setPadding(6, 4, 6, 2);
        addTool(row1, "🎙 Sesli", v -> listen());
        addTool(row1, "🔁 Canlı", v -> toggleLiveVoice());
        addTool(row1, "📷 Kamera", v -> openCamera());
        addTool(row1, "🖼 Görsel", v -> pickImage());
        addTool(row1, "🔊 Ses", v -> showVoiceSettings());
        base.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(6, 0, 6, 4);
        addTool(row2, "📄 Dosya", v -> pickFile());
        addTool(row2, "📚 Bilgi", v -> importKnowledge());
        addTool(row2, "⏰ Alarmlar", v -> handleMessage("alarmları listele"));
        addTool(row2, "🧠 Hafıza", v -> handleMessage("hafızamı listele"));
        addTool(row2, "⚙ Sistem", v -> startActivity(new Intent(Settings.ACTION_SETTINGS)));
        base.addView(row2);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(8, 6, 8, 12);
        input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setHint("EXE AI'ye yaz...");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff888888);
        input.setPadding(12, 10, 12, 10);
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RED));
        bottom.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        Button send = new Button(this);
        send.setText("GÖNDER");
        send.setTextColor(Color.WHITE);
        send.setAllCaps(false);
        send.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffb00020));
        send.setOnClickListener(v -> send());
        bottom.addView(send);
        base.addView(bottom);

        setContentView(base);
    }

    private void refreshStatus() {
        if (statusLine == null) return;
        boolean hasKey = !ApiKeyStore.getGeminiKey(this).isEmpty();
        statusLine.setText(hasKey ? "Gemini: bağlı" : "Gemini: anahtar yok (EXE AI başlığına basılı tut)");
        statusLine.setTextColor(hasKey ? 0xff7fd17f : 0xffff8a80);
    }

    private void addTool(LinearLayout row, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(10);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setPadding(2, 2, 2, 2);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff242424));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(2, 0, 2, 0);
        row.addView(b, lp);
    }

    private void addMessage(String message, boolean user) {
        TextView t = new TextView(this);
        t.setText((user ? "SEN  ›  " : "EXE  ›  ") + message);
        t.setTextColor(user ? 0xffffb5b5 : Color.WHITE);
        t.setTextSize(15);
        t.setPadding(14, 12, 14, 12);
        t.setBackgroundColor(user ? 0xff351316 : PANEL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 5, 0, 5);
        chat.addView(t, lp);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Shows an EXE AI reply on screen and speaks it when voice is on. */
    private void reply(String text) {
        addMessage(text, false);
        db.addHistory("exe", text);
        if (tts != null && ttsReady && voiceEnabled) {
            tts.setSpeechRate(speechRate);
            tts.setPitch(speechPitch);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "exe_reply");
        } else if (liveVoice) {
            mainHandler.postDelayed(this::listen, 600);
        }
    }

    private void postReply(String text) {
        mainHandler.post(() -> reply(text));
    }

    private void hideKeyboard() {
        try {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    // ---------- message flow ----------

    private void send() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        input.setText("");
        hideKeyboard();
        addMessage(q, true);
        db.addHistory("user", q);
        handleMessage(q);
    }

    /** Central routing: learning command, memory, reminders, device, local knowledge, then Gemini or web. */
    private void handleMessage(String raw) {
        String learning = getLearningQuery(raw);
        if (learning != null) {
            if (learning.isEmpty()) {
                reply("Öğrenmek istediğin konuyu komuttan sonra yaz. Örnek: *** RAM nedir?");
                return;
            }
            addMessage("İnternette kaynak aranıyor efendim...", false);
            worker.execute(() -> researchAndLearn(learning));
            return;
        }

        if (isPlainMath(raw.trim())) {
            Double value = Calc.eval(raw.trim());
            if (value != null) {
                reply(Calc.format(value) + " efendim.");
                return;
            }
        }

        String fixed = ChatNormalizer.fix(raw);
        String s = TextUnderstanding.normalize(fixed);

        if (handleMemoryCommand(raw, s)) return;
        if (handleReminderCommand(raw, s)) return;
        if (handleDeviceCommand(s)) return;

        String local = localAnswer(s);
        if (local != null) {
            reply(local);
            return;
        }

        String learned = db.findLearnedFuzzy(fixed);
        if (learned != null) {
            reply("Hafızamda kayıtlı yakın eşleşme:\n" + learned);
            return;
        }
        String qa = db.knowledgeFuzzy(fixed);
        if (qa != null) {
            reply(qa);
            return;
        }

        if (!ApiKeyStore.getGeminiKey(this).isEmpty()) {
            askGemini(raw);
            return;
        }
        if (isOnlineResearchAvailable()) {
            addMessage("Gemini anahtarı yok; internetten araştırıyorum efendim...", false);
            worker.execute(() -> researchAndLearn(fixed));
            return;
        }
        reply("Anlamadım efendim.");
    }

    /**
     * True for input that is only numbers and operators with at least one real operator, e.g. "2+2" or "3 * (4 - 1)".
     * A spaced minus is needed for subtraction, so dates like 21-11-2026 or phone numbers are not calculated.
     */
    private boolean isPlainMath(String text) {
        if (!text.matches("[0-9+\\-*/().,x×÷\\s]+") || !text.matches(".*\\d.*")) return false;
        return text.matches(".*[+*/×÷x].*") || text.matches(".*\\s-\\s.*");
    }

    private String getLearningQuery(String q) {
        String trimmed = q.trim();
        if (trimmed.startsWith("***") || trimmed.startsWith("###")) {
            return trimmed.substring(3).trim();
        }
        String lower = trimmed.toLowerCase(new Locale("tr", "TR"));
        if (lower.startsWith("öğren:") || lower.startsWith("ogren:")) {
            return trimmed.substring(trimmed.indexOf(':') + 1).trim();
        }
        return null;
    }

    private boolean isOnlineResearchAvailable() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) return false;
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void researchAndLearn(String rawQuery) {
        try {
            String query = TextUnderstanding.prepareSearchQuery(rawQuery);
            WebResearcher.Result result = WebResearcher.research(query);
            if (result == null || result.answer.trim().isEmpty()) {
                postReply("Anlamadım efendim. Hangi yönünü merak ettiğini söylersen daha iyi yardımcı olabilirim.");
                return;
            }
            db.saveLearned(rawQuery, result.answer, result.sources);
            postReply(result.answer + "\n\nKaynaklar:\n" + result.sources);
        } catch (Exception e) {
            postReply("Web araştırması sırasında hata oluştu, tekrar deneyebilirsin efendim.");
        }
    }

    // ---------- Gemini ----------

    private void askGemini(String raw) {
        String key = ApiKeyStore.getGeminiKey(this);
        Bitmap image = pendingBitmap;
        pendingBitmap = null;
        String fileText = pendingFileText;
        pendingFileText = null;

        StringBuilder prompt = new StringBuilder();
        List<String> history = db.recentHistory(6);
        Collections.reverse(history);
        if (!history.isEmpty()) {
            prompt.append("Önceki konuşma:\n");
            for (String h : history) prompt.append(h).append('\n');
            prompt.append('\n');
        }
        prompt.append("Kullanıcının mesajı (yazım hatası olabilir): ").append(raw);
        if (fileText != null) prompt.append("\n\n[Ekli dosya içeriği]\n").append(fileText);
        if (!lastImageText.isEmpty() && image == null) {
            prompt.append("\n\n[Son fotoğraftan okunan yazılar]\n").append(lastImageText);
        }

        boolean complex = GeminiClient.isComplex(raw, image != null);
        // Questions longer than 50 characters may take up to 2 minutes; shorter ones get 20 seconds.
        long budget = raw.trim().length() > 50 ? GeminiClient.LONG_BUDGET_MS : GeminiClient.TOTAL_BUDGET_MS;

        worker.execute(() -> {
            try {
                String answer = GeminiClient.ask(key, complex, GeminiClient.SYSTEM_PROMPT, prompt.toString(), image, budget);
                postReply(answer);
            } catch (GeminiClient.GeminiException e) {
                postReply(e.getMessage());
            }
        });
    }

    private void showHiddenApiSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(22, 12, 22, 4);

        String saved = ApiKeyStore.getGeminiKey(this);
        TextView info = new TextView(this);
        info.setText("Gizli Gemini ayarları. Anahtar şifreli depoda saklanır ve kaynak koduna yazılmaz.\n"
                + "Kayıtlı anahtar: " + (saved.isEmpty() ? "yok" : "•••• " + saved.substring(Math.max(0, saved.length() - 4))));
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(13);
        panel.addView(info);

        EditText keyInput = new EditText(this);
        keyInput.setHint("Gemini API anahtarı");
        keyInput.setTextColor(Color.WHITE);
        keyInput.setHintTextColor(0xff888888);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(keyInput);

        Button test = new Button(this);
        test.setText("Bağlantıyı test et");
        test.setAllCaps(false);
        test.setOnClickListener(v -> {
            String k = keyInput.getText().toString().trim();
            if (k.isEmpty()) k = ApiKeyStore.getGeminiKey(this);
            final String useKey = k;
            addMessage("Gemini bağlantısı test ediliyor...", false);
            worker.execute(() -> {
                try {
                    String r = GeminiClient.testConnection(useKey);
                    mainHandler.post(() -> { addMessage("Gemini testi başarılı: " + r, false); refreshStatus(); });
                } catch (GeminiClient.GeminiException e) {
                    mainHandler.post(() -> addMessage("Gemini testi başarısız: " + e.getMessage(), false));
                }
            });
        });
        panel.addView(test);

        Button clear = new Button(this);
        clear.setText("Kayıtlı anahtarı sil");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> {
            ApiKeyStore.clearGeminiKey(this);
            Toast.makeText(this, "Anahtar silindi.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        panel.addView(clear);

        new AlertDialog.Builder(this)
                .setTitle("EXE AI • Gizli API ayarları")
                .setView(panel)
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    String newKey = keyInput.getText().toString().trim();
                    if (!newKey.isEmpty()) {
                        if (ApiKeyStore.saveGeminiKey(this, newKey)) {
                            Toast.makeText(this, "Anahtar güvenli şekilde kaydedildi.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Güvenli depo açılamadı; anahtar kaydedilmedi.", Toast.LENGTH_LONG).show();
                        }
                    }
                    refreshStatus();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    // ---------- memory ----------

    private boolean handleMemoryCommand(String raw, String s) {
        String low = raw.trim().toLowerCase(new Locale("tr", "TR"));
        if (low.startsWith("hatırla:") || low.startsWith("hatirla:")) {
            String fact = raw.substring(raw.indexOf(':') + 1).trim();
            if (fact.isEmpty()) {
                reply("Neyi hatırlamamı istediğini ':' işaretinden sonra yazabilirsin efendim.");
            } else {
                db.saveMemory(fact, "kullanici");
                reply("Kaydettim efendim: " + fact);
            }
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "hafizami listele", "hafizayi goster", "hafizami goster")) {
            List<String> items = db.listMemory(10);
            if (items.isEmpty()) reply("Hafızamda henüz kayıt yok efendim.");
            else reply("Son kayıtlar:\n• " + String.join("\n• ", items));
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "hafizayi sil", "hafizayi temizle")) {
            db.clearMemory();
            reply("Hafızadaki kayıtları sildim efendim.");
            return true;
        }
        return false;
    }

    // ---------- reminders and alarms ----------

    private boolean handleReminderCommand(String raw, String s) {
        boolean listRequest = TextUnderstanding.containsIntent(s, "alarmlari listele", "hatirlaticilari listele",
                "alarmlarimi goster", "hatirlaticilarimi goster");
        if (listRequest) {
            listReminders();
            return true;
        }

        Matcher del = Pattern.compile("(alarm|hatirlatici) sil (\\d+)").matcher(s);
        if (del.find()) {
            long id = Long.parseLong(del.group(2));
            if (db.reminderById(id) == null) {
                reply("Bu numaralı bir alarm bulamadım efendim. Önce alarmları listeleyebilirsin.");
            } else {
                Reminders.cancel(this, id);
                db.deleteReminder(id);
                reply("Alarmı sildim efendim.");
            }
            return true;
        }

        boolean mentionsAlarm = s.contains("alarm") || s.contains("hatirlat");
        if (!mentionsAlarm) return false;

        long due = Reminders.parseDue(s, System.currentTimeMillis());
        boolean asksToSet = TextUnderstanding.containsIntent(s, "kur", "ayarla", "hatirlat", "alarm kur");
        if (due < 0) {
            if (asksToSet) {
                reply("Saati anlayamadım efendim. Örnek: 'saat 18.30'a alarm kur' ya da '10 dakika sonra hatırlat'.");
                return true;
            }
            return false;
        }

        boolean repeat = s.contains("her gun") || s.contains("tekrar");
        String text = (s.contains("alarm") ? "Alarm: " : "Hatırlatıcı: ") + raw.trim();
        long id = db.addReminder(text, due, repeat);
        DB.Reminder created = db.reminderById(id);
        if (created == null || !Reminders.arm(this, created)) {
            reply("Zamanı geçmiş göründü efendim, kuramadım.");
            return true;
        }
        reply("Tamam efendim. " + Reminders.formatTime(due) + " için kurdum"
                + (repeat ? ", her gün tekrarlanacak" : "") + ". (No: " + id + ")");
        return true;
    }

    private void listReminders() {
        List<DB.Reminder> active = db.activeReminders();
        if (active.isEmpty()) {
            reply("Aktif alarm veya hatırlatıcı yok efendim.");
            return;
        }
        StringBuilder sb = new StringBuilder("Aktif alarm ve hatırlatıcılar:\n");
        for (DB.Reminder r : active) {
            sb.append("• No ").append(r.id).append(" — ").append(Reminders.formatTime(r.dueMillis))
              .append(r.repeatDaily ? " (her gün)" : "").append(" — ").append(r.text).append('\n');
        }
        sb.append("Silmek için: 'alarm sil <no>'.");
        reply(sb.toString());
    }

    // ---------- device control ----------

    private boolean handleDeviceCommand(String s) {
        if (TextUnderstanding.containsIntent(s, "sesi arttir", "sesi yukselt", "sesi artir")) {
            adjustVolume(AudioManager.ADJUST_RAISE);
            reply("Sesi artırdım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "sesi azalt", "sesi kis")) {
            adjustVolume(AudioManager.ADJUST_LOWER);
            reply("Sesi azalttım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "wifi ayarlari", "wifi ac", "wifiyi ac")) {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            reply("Wi-Fi ayarlarını açtım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "bluetooth ayarlari", "bluetooth ac", "bluetoothu ac")) {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            reply("Bluetooth ayarlarını açtım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "kamerayi ac", "kamera ac")) {
            openCamera();
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "galeriyi ac", "galeri ac")) {
            Intent i = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            try {
                startActivity(i);
                reply("Galeriyi açmayı denedim efendim.");
            } catch (Exception e) {
                reply("Galeri uygulaması bulunamadı efendim.");
            }
            return true;
        }
        if (hasOpenVerb(s)) {
            String app = launchInstalledAppFromText(s);
            if (app != null) {
                reply(app + " uygulamasını açtım efendim.");
                return true;
            }
        }
        if (TextUnderstanding.containsIntent(s, "youtube ac", "youtubeyi ac")) { launchApp("youtube"); reply("YouTube'u açmayı denedim efendim."); return true; }
        if (TextUnderstanding.containsIntent(s, "minecraft ac", "minecrafti ac")) { launchApp("minecraft"); reply("Minecraft'ı açmayı denedim efendim."); return true; }
        if (TextUnderstanding.containsIntent(s, "chrome ac", "chromeu ac")) { launchApp("chrome"); reply("Chrome'u açmayı denedim efendim."); return true; }
        return false;
    }

    private void adjustVolume(int direction) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    /** Whole-word check: "kaç" must not count as the open verb "aç". */
    private boolean hasOpenVerb(String s) {
        for (String w : s.split(" ")) {
            if (isOpenVerb(w)) return true;
        }
        return false;
    }

    private boolean isOpenVerb(String w) {
        return w.equals("ac") || w.equals("baslat") || w.equals("calistir") || w.equals("programi");
    }

    /** Opens any installed launcher app whose visible name matches the text, with approximate spelling. */
    private String launchInstalledAppFromText(String normalized) {
        String[] words = normalized.split("\\s+");
        Intent query = new Intent(Intent.ACTION_MAIN, null);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(query, 0);
        ResolveInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ResolveInfo info : apps) {
            CharSequence labelCs = info.loadLabel(getPackageManager());
            if (labelCs == null) continue;
            String label = TextUnderstanding.normalize(labelCs.toString());
            if (label.length() < 2) continue;
            if ((" " + normalized + " ").contains(" " + label + " ")) { best = info; bestScore = 0; break; }
            for (int i = 0; i < words.length; i++) {
                for (int n = 1; n <= 4 && i + n <= words.length; n++) {
                    StringBuilder phrase = new StringBuilder();
                    boolean commandWord = false;
                    for (int j = i; j < i + n; j++) {
                        String w = words[j];
                        if (isOpenVerb(w) || w.equals("uygulama") || w.equals("lutfen")) { commandWord = true; break; }
                        if (phrase.length() > 0) phrase.append(' ');
                        phrase.append(w);
                    }
                    if (commandWord || phrase.length() == 0) continue;
                    String candidate = phrase.toString();
                    int distance = TextUnderstanding.distance(candidate, label);
                    if (label.contains(candidate) && candidate.length() >= 3) distance = 0;
                    int allowed = Math.max(1, Math.min(2, label.length() / 6));
                    if (distance <= allowed && distance < bestScore) { bestScore = distance; best = info; }
                }
            }
        }
        if (best == null) return null;
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setClassName(best.activityInfo.packageName, best.activityInfo.name);
        try {
            startActivity(launch);
            return best.loadLabel(getPackageManager()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void launchApp(String name) {
        String[] packages;
        if (name.equals("youtube")) packages = new String[]{"com.google.android.youtube"};
        else if (name.equals("minecraft")) packages = new String[]{"com.mojang.minecraftpe", "com.mojang.minecrafttrialpe"};
        else packages = new String[]{"com.android.chrome"};

        for (String p : packages) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) {
                startActivity(i);
                return;
            }
        }
        Toast.makeText(this, "Uygulama bulunamadı veya kurulu değil.", Toast.LENGTH_SHORT).show();
    }

    // ---------- local answers ----------

    private String localAnswer(String s) {
        if (TextUnderstanding.containsIntent(s, "seni kim yapti", "seni kim kodladi", "sen kimsin", "ai sen kimsin"))
            return "Ben EXE AI'yım efendim. Beni Aziz EXE kodladı.";
        if (TextUnderstanding.containsIntent(s, "adin ne", "ismin ne", "senin adin ne"))
            return "Benim adım EXE AI efendim. Kişisel yapay zekâ asistanınım.";
        if (TextUnderstanding.containsIntent(s, "merhaba", "selam", "iyi gunler", "gunaydin"))
            return "Merhaba efendim! Bugün ne yapalım?";
        if (TextUnderstanding.containsIntent(s, "nasilsin", "naber", "ne haber"))
            return "İyiyim efendim, teşekkür ederim. Sen nasılsın?";
        if (TextUnderstanding.containsIntent(s, "tesekkur", "sagol", "eyvallah"))
            return "Rica ederim efendim! Başka bir konuda da yardımcı olabilirim.";
        if (TextUnderstanding.containsIntent(s, "saat kac", "saat kacta"))
            return "Cihaz saati: " + new SimpleDateFormat("HH:mm", new Locale("tr", "TR")).format(new Date()) + " efendim.";
        if (TextUnderstanding.containsIntent(s, "komik", "espiri yap", "saka yap", "beni guldur")) {
            prefs.edit().putBoolean("comedy_mode", true).apply();
            return nextJoke();
        }
        if (prefs.getBoolean("comedy_mode", false) && TextUnderstanding.containsIntent(s, "devam", "baska", "daha")) return nextJoke();
        if (TextUnderstanding.containsIntent(s, "espiri modu kapat", "normal moda gec")) {
            prefs.edit().putBoolean("comedy_mode", false).apply();
            return "Espiri modunu kapattım efendim. Normal sohbete döndüm.";
        }
        if (TextUnderstanding.containsIntent(s, "yardim", "help", "neler yapabilirsin"))
            return "Komutlar efendim:\n• *** konu — internetten öğren\n• hatırla: bilgi — hafızaya kaydet\n"
                 + "• saat 18.30'a alarm kur / 10 dakika sonra hatırlat / her gün tekrar\n"
                 + "• alarmları listele / alarm sil <no>\n• wifi ayarlarını aç / sesi artır\n"
                 + "• uygulama adı + aç — uygulama başlat\n• *komik* — espiri modu\n"
                 + "• Canlı düğmesi ile sürekli sesli sohbet. Gemini anahtarı için EXE AI başlığına basılı tut.";
        return null;
    }

    private String nextJoke() {
        String[] jokes = {
                "Bilgisayar neden doktora gitmiş? Çünkü virüs kapmış! 😂",
                "Matematik kitabı neden üzgünmüş? Çünkü çok fazla problemi varmış! 😄",
                "Telefon neden gözlük takmış? Çünkü rehberindeki isimleri daha net görmek istemiş! 🤓",
                "Programcı neden denize girmemiş? Çünkü çok fazla bug varmış! 🐛",
                "Wi-Fi neden saklambaçta iyiymiş? Çünkü herkes onu arıyormuş! 😂"
        };
        int index = prefs.getInt("joke_index", 0);
        prefs.edit().putInt("joke_index", index + 1).apply();
        return jokes[index % jokes.length];
    }

    // ---------- voice ----------

    private void toggleLiveVoice() {
        liveVoice = !liveVoice;
        Toast.makeText(this, liveVoice ? "Canlı sesli sohbet açık." : "Canlı sesli sohbet kapalı.", Toast.LENGTH_SHORT).show();
        if (liveVoice) listen();
    }

    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "EXE AI'ye konuş");
        try {
            startActivityForResult(i, SPEECH);
        } catch (Exception e) {
            liveVoice = false;
            addMessage("Bu cihazda konuşma tanıma servisi bulunamadı.", false);
        }
    }

    /** Removes a leading "EXE" or "Hey EXE" from a recognized phrase. Returns the rest, possibly empty. */
    private String stripWakeWord(String heard) {
        Matcher m = WAKE_WORD.matcher(heard.trim());
        return m.find() ? heard.trim().substring(m.end()) : heard.trim();
    }

    private void showVoiceSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(22, 12, 22, 4);

        CheckBox voiceCheck = new CheckBox(this);
        voiceCheck.setText("EXE AI sesli cevap versin");
        voiceCheck.setTextColor(Color.WHITE);
        voiceCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(RED));
        voiceCheck.setChecked(voiceEnabled);
        panel.addView(voiceCheck);

        TextView voiceLabel = new TextView(this);
        voiceLabel.setText("Ses seçimi (cihazda yüklü sesler)");
        voiceLabel.setTextColor(Color.LTGRAY);
        voiceLabel.setPadding(0, 12, 0, 4);
        panel.addView(voiceLabel);

        List<Voice> available = getAvailableVoices();
        List<String> names = new ArrayList<>();
        names.add("Varsayılan Türkçe sesi");
        for (Voice v : available) names.add(v.getName() + " (" + v.getLocale().toLanguageTag() + ")");
        Spinner voiceSpinner = new Spinner(this);
        voiceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int selected = 0;
        for (int x = 0; x < available.size(); x++) {
            if (available.get(x).getName().equals(selectedVoiceName)) selected = x + 1;
        }
        voiceSpinner.setSelection(selected);
        panel.addView(voiceSpinner);

        TextView rateLabel = new TextView(this);
        rateLabel.setText("Konuşma hızı");
        rateLabel.setTextColor(Color.LTGRAY);
        rateLabel.setPadding(0, 12, 0, 2);
        panel.addView(rateLabel);
        SeekBar rateBar = new SeekBar(this);
        rateBar.setMax(15);
        rateBar.setProgress(Math.max(0, Math.min(15, Math.round((speechRate - 0.5f) * 10))));
        panel.addView(rateBar);

        TextView pitchLabel = new TextView(this);
        pitchLabel.setText("Ses tonu");
        pitchLabel.setTextColor(Color.LTGRAY);
        pitchLabel.setPadding(0, 8, 0, 2);
        panel.addView(pitchLabel);
        SeekBar pitchBar = new SeekBar(this);
        pitchBar.setMax(15);
        pitchBar.setProgress(Math.max(0, Math.min(15, Math.round((speechPitch - 0.5f) * 10))));
        panel.addView(pitchBar);

        Button sample = new Button(this);
        sample.setText("Örnek konuşmayı dinle");
        sample.setAllCaps(false);
        sample.setOnClickListener(v -> {
            if (tts != null && ttsReady) {
                tts.setSpeechRate(0.5f + rateBar.getProgress() / 10.0f);
                tts.setPitch(0.5f + pitchBar.getProgress() / 10.0f);
                tts.speak("Merhaba efendim, ben EXE AI. Sesim böyle olacak.", TextToSpeech.QUEUE_FLUSH, null, "exe_sample");
            }
        });
        panel.addView(sample);

        new AlertDialog.Builder(this)
                .setTitle("EXE AI • Ses ayarları")
                .setView(panel)
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    voiceEnabled = voiceCheck.isChecked();
                    speechRate = 0.5f + rateBar.getProgress() / 10.0f;
                    speechPitch = 0.5f + pitchBar.getProgress() / 10.0f;
                    selectedVoiceName = voiceSpinner.getSelectedItemPosition() == 0
                            ? "" : available.get(voiceSpinner.getSelectedItemPosition() - 1).getName();
                    prefs.edit()
                            .putBoolean("voice_enabled", voiceEnabled)
                            .putString("voice_name", selectedVoiceName)
                            .putFloat("speech_rate", speechRate)
                            .putFloat("speech_pitch", speechPitch)
                            .apply();
                    applySavedVoice();
                    if (!voiceEnabled && tts != null) tts.stop();
                    Toast.makeText(this, "Ses ayarları kaydedildi.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private List<Voice> getAvailableVoices() {
        List<Voice> result = new ArrayList<>();
        if (tts == null || !ttsReady || tts.getVoices() == null) return result;
        Set<Voice> all = tts.getVoices();
        for (Voice v : all) {
            if (v.getLocale() != null && "tr".equalsIgnoreCase(v.getLocale().getLanguage())) result.add(v);
        }
        if (result.isEmpty()) result.addAll(all);
        return result;
    }

    private void applySavedVoice() {
        if (tts == null || !ttsReady) return;
        tts.setSpeechRate(speechRate);
        tts.setPitch(speechPitch);
        if (!selectedVoiceName.isEmpty() && tts.getVoices() != null) {
            for (Voice v : tts.getVoices()) {
                if (v.getName().equals(selectedVoiceName)) {
                    try {
                        tts.setVoice(v);
                    } catch (Exception ignored) { }
                    break;
                }
            }
        } else {
            tts.setLanguage(new Locale("tr", "TR"));
        }
    }

    // ---------- camera, gallery, files ----------

    private void openCamera() {
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (i.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(i, CAMERA);
        } else {
            addMessage("Bu cihazda kamera uygulaması bulunamadı.", false);
        }
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, IMAGE_PICK);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/tab-separated-values", "text/csv", "text/markdown", "application/json"});
        startActivityForResult(i, FILE_PICK);
    }

    private void importKnowledge() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, IMPORT_QA);
    }

    private void processImage(Uri uri) {
        worker.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = in == null ? null : BitmapFactory.decodeStream(in);
                if (bitmap != null) pendingBitmap = bitmap;
                InputImage image = InputImage.fromFilePath(this, uri);
                mainHandler.post(() -> runOcr(image));
            } catch (Exception e) {
                postReply("Görsel açılamadı: " + safeError(e));
            }
        });
    }

    private void processCameraBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            addMessage("Kameradan fotoğraf alınamadı. Tekrar deneyebilirsin.", false);
            return;
        }
        pendingBitmap = bitmap;
        runOcr(InputImage.fromBitmap(bitmap, 0));
    }

    private void runOcr(InputImage image) {
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    lastImageText = result.getText() == null ? "" : result.getText().trim();
                    if (lastImageText.isEmpty()) {
                        reply("Fotoğrafı aldım efendim ama içinde okunabilir yazı bulamadım. "
                                + "Yine de sorunu yazarsan fotoğrafa göre cevap vermeye çalışırım.");
                    } else {
                        reply("Fotoğrafı aldım efendim. Okunan yazılar:\n" + lastImageText
                                + "\n\nŞimdi bu fotoğraf hakkında soru sorabilirsin.");
                    }
                    recognizer.close();
                })
                .addOnFailureListener(e -> {
                    addMessage("Görsel okuma başarısız: " + safeError(e), false);
                    recognizer.close();
                });
    }

    private void readAttachedFile(Uri uri) {
        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try (InputStream in = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && sb.length() < MAX_FILE_CHARS) {
                    sb.append(line).append('\n');
                }
                pendingFileText = sb.length() > MAX_FILE_CHARS ? sb.substring(0, MAX_FILE_CHARS) : sb.toString();
                postReply("Dosyayı ekledim efendim. Şimdi bu dosya hakkında ne sormak istediğini yazabilirsin.");
            } catch (Exception e) {
                postReply("Dosya okunamadı: " + safeError(e));
            }
        });
    }

    private void importTsv(Uri uri) {
        worker.execute(() -> {
            int imported = 0;
            int skipped = 0;
            android.database.sqlite.SQLiteDatabase writable = db.getWritableDatabase();
            writable.beginTransaction();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Dosya okunamadı");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\t", 3);
                    if (parts.length < 2) { skipped++; continue; }
                    String question = parts[0].trim();
                    String answer = parts[1].trim();
                    String category = parts.length >= 3 ? parts[2].trim() : "genel";
                    if (question.isEmpty() || answer.isEmpty()) { skipped++; continue; }
                    db.putQa(writable, question, answer, category);
                    imported++;
                }
                writable.setTransactionSuccessful();
            } catch (Exception e) {
                postReply("Bilgi paketi içe aktarılamadı: " + safeError(e));
                return;
            } finally {
                if (writable.inTransaction()) writable.endTransaction();
            }
            final int ok = imported;
            final int bad = skipped;
            postReply("Bilgi paketi tamamlandı. Eklenen/güncellenen soru: " + ok + ". Atlanan satır: " + bad
                    + ". Toplam bilgi kaydı: " + db.qaCount() + ". Veriler kalıcı SQLite hafızasına kaydedildi.");
        });
    }

    private String safeError(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFY_PERMISSION && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            addMessage("Bildirim izni verilmedi; alarmlar çalışsa da bildirim gösterilemeyebilir.", false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPEECH) {
            if (resultCode != RESULT_OK || data == null) {
                liveVoice = false;
                return;
            }
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results == null || results.isEmpty()) return;
            String command = stripWakeWord(results.get(0));
            if (command.isEmpty()) {
                reply("Evet efendim?");
                return;
            }
            addMessage(command, true);
            db.addHistory("user", command);
            handleMessage(command);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMAGE_PICK) {
            processImage(uri);
        } else if (requestCode == CAMERA) {
            Bundle extras = data.getExtras();
            Bitmap bitmap = extras == null ? null : (Bitmap) extras.get("data");
            processCameraBitmap(bitmap);
        } else if (requestCode == FILE_PICK) {
            readAttachedFile(uri);
        } else if (requestCode == IMPORT_QA) {
            importTsv(uri);
        }
    }
}
