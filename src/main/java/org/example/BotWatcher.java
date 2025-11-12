package org.example;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;

import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BotWatcher implements LongPollingSingleThreadUpdateConsumer {

    // ==== ENV ====
    private static final String BOT_TOKEN      = requireEnv("BOT_TOKEN");
    private static final String WATCH_URL      = requireEnv("WATCH_URL");
    private static final String WATCH_COOKIES  = getenvOrDefault("WATCH_COOKIES", ""); // "name=value; name2=value2"
    private static final String WATCH_SELECTOR = getenvOrDefault("WATCH_SELECTOR", ""); // напр. "main"
    private static final String RENDER_UA      = getenvOrDefault("RENDER_USER_AGENT",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
    private static final String WATCH_WAIT_SELECTOR = getenvOrDefault("WATCH_WAIT_SELECTOR", "#root > *");

    // ЕДИНСТВЕННЫЙ клиент Telegram
    private final TelegramClient client = new OkHttpTelegramClient(BOT_TOKEN);

    // кэш последнего HTML (нерендеренного), чтобы не терять прошлую логику
    private volatile FetchResult lastFetch;

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
        String text = u.getMessage().getText().trim();

        try {
            if ("/start".equals(text)) {
                send(chatId, """
                        Команды:
                        /status  — конфиг
                        /check   — хеш (без JS)
                        /why     — диагностика (без JS)
                        /html    — HTML (без JS)
                        /iframes — поиск iframe (без JS)
                        /open N  — скачать iframe N (без JS)
                        /render  — РЕНДЕР через Chrome: прислать rendered.html + rendered.png
                        """);
                return;
            }

            switch (cmd(text)) {
                case "status" -> send(chatId, """
                        🌐 URL: %s
                        Cookies заданы: %s
                        Селектор: %s
                        Время: %s
                        """.formatted(WATCH_URL, WATCH_COOKIES.isBlank() ? "нет" : "да",
                        WATCH_SELECTOR.isBlank() ? "(вся страница)" : WATCH_SELECTOR,
                        Instant.now()));

                case "check" -> {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    lastFetch = r;
                    send(chatId, "🔎 Хеш (без JS): `" + r.hash + "`");
                }

                case "why" -> {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    lastFetch = r;
                    String diag = """
                            🧪 Диагностика (без JS):
                            status: %d
                            finalUrl: %s
                            title: %s
                            loginHeuristics: %s
                            ---- headers (resp): %s
                            ---- first 500 chars of text(): 
                            %s
                            """.formatted(r.status, r.finalUrl, nullToEmpty(r.title),
                            r.loginPage ? "YES" : "no", r.responseHeadersSummary, first(r.text, 500));
                    send(chatId, diag);
                }

                case "html" -> {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    lastFetch = r;
                    File f = writeTemp("page-", ".html", r.content);
                    sendFile(chatId, f, "page.html", "HTML без JS");
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }

                case "iframes" -> {
                    FetchResult r = fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    lastFetch = r;
                    if (r.iframeUrls.isEmpty()) {
                        send(chatId, "ℹ️ На основной странице iframe не найдены (без JS).");
                    } else {
                        StringBuilder sb = new StringBuilder("🪟 iframe (без JS) — ").append(r.iframeUrls.size()).append(" шт:\n");
                        for (int i = 0; i < r.iframeUrls.size(); i++) {
                            sb.append("#").append(i + 1).append(": ").append(r.iframeUrls.get(i)).append("\n");
                        }
                        send(chatId, sb.toString());
                    }
                }

                case "open" -> {
                    int idx = parseIndex(text);
                    if (idx < 1) { send(chatId, "Использование: /open N"); return; }
                    FetchResult base = (lastFetch != null) ? lastFetch : fetchLikeBrowser(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    if (base.iframeUrls.isEmpty()) { send(chatId, "Iframe нет."); return; }
                    if (idx > base.iframeUrls.size()) { send(chatId, "Нет такого iframe. Их всего: " + base.iframeUrls.size()); return; }
                    String iframeUrl = base.iframeUrls.get(idx - 1);
                    FetchResult r = fetchLikeBrowser(iframeUrl, WATCH_COOKIES, "");
                    File f = writeTemp("iframe-", ".html", r.content);
                    sendFile(chatId, f, "iframe-" + idx + ".html", "HTML iframe (без JS)");
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }

                case "render" -> {
                    RenderResult rr = renderWithChrome(WATCH_URL, WATCH_COOKIES, WATCH_SELECTOR);
                    if (rr.htmlFile != null) {
                        sendFile(chatId, rr.htmlFile, "rendered.html", "Рендеренный HTML (с JS)");
                        //noinspection ResultOfMethodCallIgnored
                        rr.htmlFile.delete();
                    }
                    if (rr.pngFile != null) {
                        sendFile(chatId, rr.pngFile, "rendered.png", "Скриншот (с JS)");
                        //noinspection ResultOfMethodCallIgnored
                        rr.pngFile.delete();
                    }
                    send(chatId, "✅ render: status=" + rr.status + ", finalUrl=" + rr.finalUrl +
                            (rr.selectorMatched ? ", selector OK" : ", selector NOT FOUND"));
                }

                default -> send(chatId, "Команды: /status /check /why /html /iframes /open N /render");
            }
        } catch (Exception e) {
            e.printStackTrace();
            send(chatId, "⚠️ Ошибка: " + safe(e));
        }
    }

    /* ================== fetch (без JS) ================== */

    private static class FetchResult {
        final int status;
        final String finalUrl;
        final String title;
        final String content;
        final String text;
        final String hash;
        final boolean loginPage;
        final String responseHeadersSummary;
        final List<String> iframeUrls;

        FetchResult(int status, String finalUrl, String title, String content, String text,
                    String hash, boolean loginPage, String responseHeadersSummary, List<String> iframeUrls) {
            this.status = status;
            this.finalUrl = finalUrl;
            this.title = title;
            this.content = content;
            this.text = text;
            this.hash = hash;
            this.loginPage = loginPage;
            this.responseHeadersSummary = responseHeadersSummary;
            this.iframeUrls = iframeUrls;
        }
    }

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
        String headersSummary = summarizeHeaders(resp.headers());

        Document doc = resp.parse();

        String content = (selector != null && !selector.isBlank() && !doc.select(selector).isEmpty())
                ? doc.select(selector).outerHtml()
                : doc.outerHtml();

        String title = doc.title();
        String text = doc.text();
        boolean login = looksLikeLoginPage(finalUrl, title, doc);
        String hash = sha256(content);

        List<String> iframes = new ArrayList<>();
        for (Element el : doc.select("iframe[src]")) {
            String src = el.attr("src").trim();
            if (!src.isEmpty()) iframes.add(resolveUrl(finalUrl, src));
        }

        return new FetchResult(status, finalUrl, title, content, text, hash, login, headersSummary, iframes);
    }

    /* ================== RENDER (с JS) ================== */

    private static class RenderResult {
        final int status;
        final String finalUrl;
        final boolean selectorMatched;
        final File htmlFile;
        final File pngFile;
        RenderResult(int status, String finalUrl, boolean selectorMatched, File htmlFile, File pngFile) {
            this.status = status;
            this.finalUrl = finalUrl;
            this.selectorMatched = selectorMatched;
            this.htmlFile = htmlFile;
            this.pngFile = pngFile;
        }
    }

    private static RenderResult renderWithChrome(String url, String cookieHeader, String selector) throws Exception {
        WebDriverManager.chromedriver().setup();

        ChromeOptions opts = new ChromeOptions();
        opts.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--window-size=1366,3000",
                "--lang=ru-RU",
                "--user-agent=" + RENDER_UA
        );
        // Чуть «нативнее» поведение
        opts.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        opts.setExperimentalOption("useAutomationExtension", false);

        WebDriver driver = new ChromeDriver(opts);
        try {
            // 1) Зайти на origin, чтобы иметь право ставить куки
            String origin = originOf(url); // https://apps.skillfactory.ru/
            driver.get(origin);

            // 2) Установить куки (если заданы)
            Map<String,String> ck = parseCookieHeader(cookieHeader);
            String domain = new java.net.URI(origin).getHost(); // apps.skillfactory.ru
            for (var e : ck.entrySet()) {
                Cookie c = new Cookie.Builder(e.getKey(), e.getValue())
                        .domain(domain)
                        .path("/")
                        .isHttpOnly(false)
                        .isSecure(true)
                        .build();
                driver.manage().addCookie(c);
            }

            // 3) Открыть целевой URL
            driver.get(url);

            // 4) Дождаться полной загрузки документа
            new WebDriverWait(driver, Duration.ofSeconds(25)).until(
                    wd -> "complete".equals(((JavascriptExecutor) wd).executeScript("return document.readyState"))
            );

            // 5) Дождаться, пока React отрендерит контент:
            //    — либо конкретный селектор (WATCH_SELECTOR),
            //    — либо общий '#root > *' (WATCH_WAIT_SELECTOR),
            //    — либо просто ненулевой innerText у root.
            String waitSelector = (selector != null && !selector.isBlank())
                    ? selector
                    : WATCH_WAIT_SELECTOR;

            boolean selectorMatched = false;
            try {
                selectorMatched = new WebDriverWait(driver, Duration.ofSeconds(20)).until(d ->
                        (Boolean) ((JavascriptExecutor) d).executeScript(
                                "const s=arguments[0];" +
                                        "const el=document.querySelector(s);" +
                                        "if(el) return true;" +
                                        "const root=document.getElementById('root');" +
                                        "return !!(root && root.children && root.children.length>0);",
                                waitSelector
                        )
                );
            } catch (TimeoutException ignored) { }

            // 6) Чуть подождать сеть (простая «idle» эвристика)
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}

            // 7) Прокрутить вниз, чтобы догрузить lazy-блоки
            try {
                ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
                Thread.sleep(400);
            } catch (Throwable ignored) {}

            // 8) Снять HTML
            String html = driver.getPageSource();
            File htmlFile = writeTemp("rendered-", ".html", html);

            // 9) Скриншот экрана
            File pngFile = null;
            try {
                byte[] shot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                pngFile = File.createTempFile("rendered-", ".png");
                try (FileOutputStream fos = new FileOutputStream(pngFile)) { fos.write(shot); }
            } catch (Throwable ignored) {}

            // В WebDriver нет прямого HTTP-статуса — считаем 200, если DOM живой
            int status = 200;
            String finalUrl = driver.getCurrentUrl();

            return new RenderResult(status, finalUrl, selectorMatched, htmlFile, pngFile);
        } finally {
            driver.quit();
        }
    }


    /* ================== утилиты/отправка ================== */

    private void send(long chatId, String text) {
        try {
            client.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendFile(long chatId, File file, String name, String caption) throws TelegramApiException {
        if (file == null || !file.exists()) { send(chatId, "Файл не создан."); return; }
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

    private static String resolveUrl(String base, String rel) {
        try {
            if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
            URL b = new URL(base);
            return new URL(b, rel).toString();
        } catch (Exception e) {
            try { return new URI(base).resolve(rel).toString(); }
            catch (Exception ex) { return rel; }
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
    private static String first(String s, int n) { if (s == null) return ""; return s.length() <= n ? s : s.substring(0, n) + "..."; }
    private static String safe(Throwable t) { String m = t.getMessage(); return (m == null || m.isBlank()) ? t.toString() : m; }
    private static String cmd(String text) { String t = text.startsWith("/") ? text.substring(1) : text; int sp = t.indexOf(' '); return (sp < 0 ? t : t.substring(0, sp)).toLowerCase(Locale.ROOT); }
    private static int parseIndex(String text) { try { String[] p = text.trim().split("\\s+"); if (p.length < 2) return -1; return Integer.parseInt(p[1]); } catch (Exception e) { return -1; } }
}
