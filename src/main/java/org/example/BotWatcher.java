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

    private final TelegramClient client = new OkHttpTelegramClient(BOT_TOKEN);
    private final Map<String,String> cookieMap = parseCookieHeader(WATCH_COOKIES);

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
                    /debug  — показать HTML в чат (кусками)
                    /html   — полный HTML файлом
                    /zip    — полный HTML в ZIP
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
                    FetchResult r = fetch(WATCH_URL, cookieMap, WATCH_SELECTOR);
                    if (r.loginPage) {
                        send(chatId, "🔒 Похоже, это страница логина. Обновите WATCH_COOKIES.");
                    } else {
                        send(chatId, "🔎 Хеш: `" + r.hash + "`");
                    }
                } catch (Exception e) {
                    send(chatId, "⚠️ Ошибка: " + safeMsg(e));
                }
            }

            case "/debug" -> {
                try {
                    FetchResult r = fetch(WATCH_URL, cookieMap, WATCH_SELECTOR);
                    if (r.loginPage) send(chatId, "🔒 Похоже, логин. Обновите WATCH_COOKIES.");
                    sendHtmlPreview(chatId, r.content); // в чат, порезанное на части, чтобы влезало
                } catch (Exception e) {
                    send(chatId, "⚠️ Ошибка: " + safeMsg(e));
                }
            }

            case "/html" -> {
                try {
                    FetchResult r = fetch(WATCH_URL, cookieMap, WATCH_SELECTOR);
                    File f = writeTempHtml(r.content);
                    sendFile(chatId, f, "page.html", "Полный HTML страницы");
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } catch (Exception e) {
                    send(chatId, "⚠️ Ошибка: " + safeMsg(e));
                }
            }

            case "/zip" -> {
                try {
                    FetchResult r = fetch(WATCH_URL, cookieMap, WATCH_SELECTOR);
                    File f = writeZippedHtml("page.html", r.content);
                    sendFile(chatId, f, "page.zip", "Полный HTML (ZIP)");
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } catch (Exception e) {
                    send(chatId, "⚠️ Ошибка: " + safeMsg(e));
                }
            }

            default -> send(chatId, "Команды: /status /check /debug /html /zip");
        }
    }

    /* ================== сетевые штуки ================== */

    private static class FetchResult {
        final String content;
        final String hash;
        final boolean loginPage;
        FetchResult(String content, String hash, boolean loginPage) {
            this.content = content;
            this.hash = hash;
            this.loginPage = loginPage;
        }
    }

    private static FetchResult fetch(String url, Map<String,String> cookies, String selector) throws Exception {
        Connection conn = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; WatcherBot/1.0)")
                .timeout(20_000)
                .followRedirects(true);
        if (!cookies.isEmpty()) conn.cookies(cookies);

        Document doc = conn.get();

        String content;
        if (selector != null && !selector.isBlank() && !doc.select(selector).isEmpty()) {
            content = doc.select(selector).outerHtml();
        } else {
            content = doc.outerHtml();
        }

        boolean login = looksLikeLoginPage(doc.location(), doc.title(), doc);
        return new FetchResult(content, sha256(content), login);
    }

    private static boolean looksLikeLoginPage(String location, String title, Document doc) {
        String l = location == null ? "" : location.toLowerCase(Locale.ROOT);
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
        // Telegram ограничивает ~4096 символов — режем на куски (здесь 3500).
        final int CHUNK = 3500;
        if (html == null) html = "";
        int total = html.length();
        if (total == 0) { send(chatId, "ℹ️ HTML пустой."); return; }
        int parts = (total + CHUNK - 1) / CHUNK;
        for (int i = 0; i < parts; i++) {
            int from = i * CHUNK;
            int to = Math.min(from + CHUNK, total);
            String piece = html.substring(from, to);
            String header = parts > 1 ? "📄 HTML [" + (i + 1) + "/" + parts + "]\n" : "📄 HTML\n";
            send(chatId, header + piece);
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

    private static File writeTempHtml(String content) throws Exception {
        File tmp = File.createTempFile("page-", ".html");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(content == null ? "" : content);
        }
        return tmp;
    }

    private static File writeZippedHtml(String entryName, String content) throws Exception {
        File zip = File.createTempFile("page-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            zos.write(bytes);
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

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.toString() : m;
    }
}
