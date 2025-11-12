package org.example;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;

import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BotWatcher implements LongPollingSingleThreadUpdateConsumer {

    // ENV
    private static final String BOT_TOKEN      = requireEnv("BOT_TOKEN");
    private static final String WATCH_URL      = requireEnv("WATCH_URL");
    private static final String WATCH_COOKIES  = getenvOrDefault("WATCH_COOKIES", ""); // "name=value; name2=value2"
    private static final String WATCH_SELECTOR = getenvOrDefault("WATCH_SELECTOR", ""); // напр. "main"

    // ЕДИНСТВЕННОЕ поле клиента
    private final TelegramClient client = new OkHttpTelegramClient(BOT_TOKEN);

    public static void main(String[] args) throws Exception {
        try (TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication()) {
            app.registerBot(BOT_TOKEN, new BotWatcher());
            System.out.println("✅ Bot started. URL=" + WATCH_URL + ", cookies=" + (!WATCH_COOKIES.isBlank()));
            Thread.currentThread().join();
        }
    }

    @Override
    public void consume(Update u) {
        if (u == null || !u.hasMessage() || !u.getMessage().hasText()) return;

        long chatId = u.getMessage().getChatId();
        String cmd = u.getMessage().getText().trim();

        switch (cmd) {
            case "/start" -> send(chatId, """
                    Привет! Команды:
                    /status — показать конфиг
                    /check  — хеш страницы
                    /debug  — HTML в чат (кусками)
                    /html   — полный HTML файлом
                    /zip    — HTML в ZIP
                    /why    — диагностика (status, finalUrl, title, первые 500 символов текста)
                    """);

            case "/status" -> send(chatId, """
                    🌐 URL: %s
                    Cookies заданы: %s
                    Селектор: %s
                    Время: %s
                    """.formatted(WATCH_URL, WATCH_COOKIES.isBlank() ? "нет" : "да",
                    WATCH_SELECTOR.isBlank() ? "(вся страница)" : WATCH_SELECTOR,
                    Instant.now()));

            case "/check" -> {
                try {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    if (r.loginPage) {
                        send(chatId, "🔒 Похоже, логин-страница. Обновите WATCH_COOKIES (с того же домена, что и WATCH_URL).");
                    } else {
                        send(chatId, "🔎 Хеш: `" + r.hash + "`");
                    }
                } catch (Exception e) { send(chatId, "⚠️ Ошибка: " + safe(e)); }
            }

            case "/debug" -> {
                try {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    sendHtmlPreview(chatId, r.content);
                } catch (Exception e) { send(chatId, "⚠️ Ошибка: " + safe(e)); }
            }

            case "/html" -> {
                try {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    File f = writeTemp("page-", ".html", r.content);
                    sendFile(chatId, f, "page.html", "Полный HTML");
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } catch (Exception e) { send(chatId, "⚠️ Ошибка: " + safe(e)); }
            }

            case "/zip" -> {
                try {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    File f = writeZippedHtml("page.html", r.content);
                    sendFile(chatId, f, "page.zip", "Полный HTML (ZIP)");
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } catch (Exception e) { send(chatId, "⚠️ Ошибка: " + safe(e)); }
            }

            case "/why" -> {
                try {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    String text500 = first(r.text, 500);
                    String diag = """
                            🧪 Диагностика:
                            status: %d
                            finalUrl: %s
                            title: %s
                            loginHeuristics: %s
                            ---- headers (resp): %s
                            ---- first 500 chars of text(): 
                            %s
                            """.formatted(r.status, r.finalUrl, nullToEmpty(r.title),
                            r.loginPage ? "YES" : "no", r.responseHeadersSummary, text500);
                    send(chatId, diag);
                } catch (Exception e) { send(chatId, "⚠️ Ошибка: " + safe(e)); }
            }

            default -> send(chatId, "Команды: /status /check /debug /html /zip /why");
        }
    }

    /* ================== сетевые штуки ================== */

    private static class FetchResult {
        final int status;
        final String finalUrl;
        final String title;
        final String content;
        final String text;
        final String hash;
        final boolean loginPage;
        final String responseHeadersSummary;
        FetchResult(int status, String finalUrl, String title, String content, String text,
                    String hash, boolean loginPage, String responseHeadersSummary) {
            this.status = status;
            this.finalUrl = finalUrl;
            this.title = title;
            this.content = content;
            this.text = text;
            this.hash = hash;
            this.loginPage = loginPage;
            this.responseHeadersSummary = responseHeadersSummary;
        }
    }

    /** Запрос, максимально похожий на браузер + ставим и cookies(map), и raw Cookie header. */
    private static FetchResult fetchLikeBrowser(String url, String cookieHeader, String selector) throws Exception {
        Connection conn = Jsoup.connect(url)
                .method(Connection.Method.GET)
                .followRedirects(true)
                .timeout(25_000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Cache-Control", "no-cache")
                .referrer(originOf(url));

        if (cookieHeader != null && !cookieHeader.isBlank()) {
            conn.header("Cookie", cookieHeader.trim());
            conn.cookies(parseCookieHeader(cookieHeader));
        }

        Connection.Response resp = conn.execute();
        int status = resp.statusCode();
        String finalUrl = resp.url().toString();
        Map<String, String> respHeaders = resp.headers();
        String headersSummary = summarizeHeaders(respHeaders);

        Document doc = resp.parse();
        String title = doc.title();
        String content = (selector != null && !selector.isBlank() && !doc.select(selector).isEmpty())
                ? doc.select(selector).outerHtml()
                : doc.outerHtml();
        String text = doc.text();
        boolean login = looksLikeLoginPage(finalUrl, title, doc);
        String hash = sha256(content);

        return new FetchResult(status, finalUrl, title, content, text, hash, login, headersSummary);
    }

    private static String summarizeHeaders(Map<String,String> h) {
        if (h == null || h.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var e : h.entrySet()) {
            if (n++ >= 12) { sb.append(" ..."); break; }
            sb.append(e.getKey()).append(": ").append(first(e.getValue(), 120)).append("\n");
        }
        return sb.toString();
    }

    private static boolean looksLikeLoginPage(String finalUrl, String title, Document doc) {
        String l = finalUrl == null ? "" : finalUrl.toLowerCase(Locale.ROOT);
        String t = title    == null ? "" : title.toLowerCase(Locale.ROOT);
        if (l.contains("/login") || l.contains("/signin") || l.contains("auth")) return true;
        if (t.contains("вход") || t.contains("логин") || t.contains("login") || t.contains("sign in")) return true;
        return !doc.select("input[type=password]").isEmpty();
    }

    /* ================== отправка ================== */

    private void send(long chatId, String text) {
        try {
            client.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendHtmlPreview(long chatId, String html) {
        final int CHUNK = 3500;
        if (html == null) html = "";
        int total = html.length();
        if (total == 0) { send(chatId, "ℹ️ HTML пустой."); return; }
        int parts = (total + CHUNK - 1) / CHUNK;
        for (int i = 0; i < parts; i++) {
            int from = i * CHUNK, to = Math.min(from + CHUNK, total);
            String header = parts > 1 ? "📄 HTML [" + (i + 1) + "/" + parts + "]\n" : "📄 HTML\n";
            send(chatId, header + html.substring(from, to));
        }
    }

    private void sendFile(long chatId, File file, String name, String caption) throws TelegramApiException {
        SendDocument sd = SendDocument.builder()
                .chatId(chatId)
                .document(new InputFile(file, name))
                .caption(caption)
                .build();
        client.execute(sd);
    }

    private static File writeTemp(String prefix, String suffix, String content) throws Exception {
        File tmp = File.createTempFile(prefix, suffix);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(content == null ? "" : content);
        }
        return tmp;
    }

    private static File writeZippedHtml(String entryName, String content) throws Exception {
        File zip = File.createTempFile("page-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zip;
    }

    /* ================== утилиты ================== */

    private static String sha256(String s) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static Map<String, String> parseCookieHeader(String cookieHeader) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cookieHeader == null || cookieHeader.isBlank()) return map;
        for (String p : cookieHeader.split(";")) {
            String s = p.trim();
            int eq = s.indexOf('=');
            if (eq > 0) {
                String name = s.substring(0, eq).trim();
                String val  = s.substring(eq + 1).trim();
                if (!name.isEmpty()) map.put(name, val);
            }
        }
        return map;
    }

    private static String originOf(String url) {
        try {
            var u = new java.net.URI(url);
            String scheme = u.getScheme() == null ? "https" : u.getScheme();
            int port = u.getPort();
            String host = u.getHost();
            String p = (port == -1 ? "" : ":" + port);
            return scheme + "://" + host + p + "/";
        } catch (Exception e) {
            return url;
        }
    }

    private static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Переменная окружения " + key + " обязательна");
        return v;
    }
    private static String getenvOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String first(String s, int n) { return (s == null || s.length() <= n) ? (s == null ? "" : s) : s.substring(0, n) + "..."; }
    private static String safe(Throwable t) { String m = t.getMessage(); return (m == null || m.isBlank()) ? t.toString() : m; }
}
