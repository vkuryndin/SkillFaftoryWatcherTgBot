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
    private static final String WATCH_USERNAME = getenvOrDefault("WATCH_USERNAME", "");
    private static final String WATCH_PASSWORD = getenvOrDefault("WATCH_PASSWORD", "");
    private static final String WATCH_LOGIN_URL = getenvOrDefault("WATCH_LOGIN_URL", "");

    // держим последнее окно браузера открытым (чтобы GC не прибил)
    private static volatile org.openqa.selenium.WebDriver LAST_DRIVER;

     private static final long WAIT_AFTER_LOGIN_MS =
            Long.parseLong(getenvOrDefault("WAIT_AFTER_LOGIN_MS", "6000"));       // пауза после логина

    private static final long WAIT_TARGET_TIMEOUT_MS =
            Long.parseLong(getenvOrDefault("WAIT_TARGET_TIMEOUT_MS", "15000"));    // общий таймаут ожидания добрузки

    private static final long WAIT_TARGET_STABLE_MS =
            Long.parseLong(getenvOrDefault("WAIT_TARGET_STABLE_MS", "1200"));      // «тишина» сети перед снимком

    private static final boolean KEEP_BROWSER_OPEN =
            Boolean.parseBoolean(getenvOrDefault("KEEP_BROWSER_OPEN", "true"));

    private static final boolean RENDER_HEADLESS =
            Boolean.parseBoolean(getenvOrDefault("RENDER_HEADLESS", "false"));

    private static volatile org.openqa.selenium.WebDriver CURRENT_DRIVER;


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
                        /render  — РЕНДЕР через Chrome: прислать rendered.html + rendered.png (только страница логина)
                        /checkjs — ПРОЙТИ таргеты с логином (Selenium) и прислать изменения (все поддерживаемые модули)
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
                    if (WATCH_USERNAME.isBlank() || WATCH_PASSWORD.isBlank()) {
                        send(chatId, "⚠️ Для /render с логином задайте WATCH_USERNAME и WATCH_PASSWORD в ENV.");
                        break;
                    }
                    RenderResult rr = renderWithLogin(WATCH_URL, WATCH_COOKIES, WATCH_LOGIN_URL,
                            WATCH_USERNAME, WATCH_PASSWORD, WATCH_SELECTOR, WATCH_WAIT_SELECTOR);
                    if (rr.htmlFile != null) {
                        sendFile(chatId, rr.htmlFile, "rendered.html", "Рендер после логина (HTML)");
                        //noinspection ResultOfMethodCallIgnored
                        rr.htmlFile.delete();
                    }
                    if (rr.pngFile != null) {
                        sendFile(chatId, rr.pngFile, "rendered.png", "Скриншот после логина");
                        //noinspection ResultOfMethodCallIgnored
                        rr.pngFile.delete();
                    }
                    send(chatId, "✅ render: finalUrl=" + rr.finalUrl +
                            (rr.selectorMatched ? ", selector OK" : ", selector NOT FOUND"));
                }
                case "checkjs" -> {
                    // 1) гарантируем, что есть живой авторизованный Chrome
                    WebDriver d = ensureLoggedInDriver();

                    // 2) прогон таргетов
                    ChangeWatcher.RunResult res = ChangeWatcher.runChecksWithHtml(d);
                    List<ChangeWatcher.Change> changes = res.changes();
                    Map<String, String> htmlByTarget = res.htmlByTarget();
                    Map<String, File> screenshotsByTarget = res.screenshotByTarget();

                    // Множество имён таргетов, где были изменения
                    Set<String> changedNames = new HashSet<>();
                    for (ChangeWatcher.Change c : changes) {
                        changedNames.add(c.name());
                    }

                    // 3) Для каждого таргета шлём статус + html + скрин
                    for (Map.Entry<String, String> e : htmlByTarget.entrySet()) {
                        String targetName = e.getKey();
                        String html = e.getValue();
                        File screenshot = screenshotsByTarget.get(targetName);

                        boolean changed = changedNames.contains(targetName);
                        String statusMsg = changed
                                ? "🔔 Изменения в цели: " + targetName
                                : "✓ Без изменений: " + targetName;
                        send(chatId, statusMsg);

                        // Если есть Change-объект — шлём краткое summary
                        for (ChangeWatcher.Change c : changes) {
                            if (c.name().equals(targetName)) {
                                send(chatId, c.summary());
                                break;
                            }
                        }

                        // HTML-фрагмент
                        if (html != null && !html.isBlank()) {
                            try {
                                File f = writeTemp(
                                        "checkjs-" + safeFileName(targetName) + "-",
                                        ".html",
                                        html
                                );
                                sendFile(
                                        chatId,
                                        f,
                                        "checkjs-" + safeFileName(targetName) + ".html",
                                        "JS-rendered HTML для цели: " + targetName
                                );
                                //noinspection ResultOfMethodCallIgnored
                                f.delete();
                            } catch (Exception e1) {
                                e1.printStackTrace();
                                send(chatId, "Не удалось отправить HTML для " + targetName + ": " + safe(e1));
                            }
                        }

                        // PNG-скриншот
                        if (screenshot != null && screenshot.exists()) {
                            try {
                                sendFile(
                                        chatId,
                                        screenshot,
                                        "checkjs-" + safeFileName(targetName) + ".png",
                                        "Скриншот для цели: " + targetName
                                );
                                // Можно удалить temp-файл
                                //noinspection ResultOfMethodCallIgnored
                                screenshot.delete();
                            } catch (Exception e2) {
                                e2.printStackTrace();
                                send(chatId, "Не удалось отправить скриншот для " + targetName + ": " + safe(e2));
                            }
                        }
                    }


                    // 4) Отправляем JSON с состоянием (watch-state.json)
                    try {
                        File stateFile = new File(System.getProperty("user.dir"), "watch-state.json");
                        if (stateFile.exists()) {
                            sendFile(chatId, stateFile, "watch-state.json",
                                    "Текущее состояние хэшей по всем целям");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        send(chatId, "Не удалось отправить watch-state.json: " + safe(e));
                    }
                }
                case "info" -> {
                    send(chatId,"This is a small bot, which is checking whether there are some changes in the Skillfactory pages."
                    + "Login name and password are not configurable in this version."
                            + "It checks for Программирование на языке Java, Основы конвейерной разработки и Алгоритмы и структуры данных."
                    );
                }

                default -> send(chatId, "Команды: /status /check /why /html /iframes /open N /render /checkjs  /info");
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

    private static RenderResult renderWithLogin(String targetUrl,
                                                String cookieHeader,          // не используем
                                                String loginUrl,
                                                String username,
                                                String password,
                                                String contentSelector,
                                                String waitSelectorFallback) throws Exception {
        io.github.bonigarcia.wdm.WebDriverManager.chromedriver().setup();

        org.openqa.selenium.chrome.ChromeOptions opts = new org.openqa.selenium.chrome.ChromeOptions();
        if (RENDER_HEADLESS) opts.addArguments("--headless=new");
        opts.addArguments(
                "--no-sandbox","--disable-dev-shm-usage","--disable-gpu",
                "--window-size=1366,3000","--lang=ru-RU","--disable-blink-features=AutomationControlled",
                "--user-agent=" + RENDER_UA
        );
        opts.setExperimentalOption("excludeSwitches", java.util.List.of("enable-automation"));
        opts.setExperimentalOption("useAutomationExtension", false);

        org.openqa.selenium.WebDriver driver = new org.openqa.selenium.chrome.ChromeDriver(opts);
        LAST_DRIVER = driver;

        java.io.File diagHtml = null, diagPng = null;

        try {
            if (loginUrl == null || loginUrl.isBlank())
                throw new IllegalStateException("WATCH_LOGIN_URL не задан.");

            // 1) login?next=<WATCH_URL>
            String loginStart = loginUrl.contains("?")
                    ? loginUrl + "&next=" + java.net.URLEncoder.encode(targetUrl, java.nio.charset.StandardCharsets.UTF_8)
                    : loginUrl + "?next=" + java.net.URLEncoder.encode(targetUrl, java.nio.charset.StandardCharsets.UTF_8);

            driver.get(loginStart);
            waitDomReady(driver, 25);
            driver.switchTo().defaultContent();

            // 2) поля логина
            org.openqa.selenium.support.ui.WebDriverWait wait =
                    new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(20));
            var emailSel = org.openqa.selenium.By.cssSelector("input[name='email']");
            var passSel  = org.openqa.selenium.By.cssSelector("input[name='password']");
            var emailInput = wait.until(org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable(emailSel));
            var passInput  = wait.until(org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable(passSel));

            // 3) ввод и submit (кнопка → ENTER → JS)
            emailInput.click(); emailInput.clear(); emailInput.sendKeys(username);
            passInput.click();  passInput.clear();  passInput.sendKeys(password);

            var submitBtnSel = org.openqa.selenium.By.cssSelector(
                    "button.sf-auth-page-layout__submit-btn, button[type='submit'], input[type='submit']"
            );
            java.util.List<org.openqa.selenium.WebElement> submitBtns = driver.findElements(submitBtnSel);
            boolean submitted = false;
            if (!submitBtns.isEmpty()) {
                try {
                    var btn = wait.until(org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable(submitBtns.get(0)));
                    new org.openqa.selenium.interactions.Actions(driver)
                            .moveToElement(btn).pause(java.time.Duration.ofMillis(100)).click(btn).perform();
                    submitted = true;
                } catch (Exception ignored) {}
            }
            if (!submitted) {
                try { passInput.sendKeys(org.openqa.selenium.Keys.ENTER); submitted = true; } catch (Exception ignored) {}
            }
            if (!submitted) {
                var js = (org.openqa.selenium.JavascriptExecutor) driver;
                Object r = js.executeScript("""
              (function(){
                const $e=document.querySelector("input[name='email']");
                const $p=document.querySelector("input[name='password']");
                if(!$e||!$p) return "NO_FIELDS";
                const form=$e.closest("form")||$p.closest("form")||document.querySelector("form");
                if(form&&typeof form.requestSubmit==='function'){form.requestSubmit(); return "SUBMIT_FORM";}
                const btn=document.querySelector("button.sf-auth-page-layout__submit-btn,button[type='submit'],input[type='submit']");
                if(btn){btn.click(); return "CLICK_BUTTON";}
                return "NO_SUBMIT";
              })();
            """);
                System.out.println("Submit fallback JS: " + r);
            }

            // 4) ждём успех логина (уход с /learning/login или пропало password или появились "session"-куки)
            org.openqa.selenium.support.ui.WebDriverWait longWait =
                    new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(35));
            boolean success;
            try {
                success = longWait.until(d -> {
                    String href = d.getCurrentUrl().toLowerCase(java.util.Locale.ROOT);
                    boolean leftLogin = !href.contains("/learning/login");
                    boolean noPwd = d.findElements(org.openqa.selenium.By.cssSelector("input[type='password']")).isEmpty();
                    boolean hasSess = false;
                    try {
                        String cookies = (String)((org.openqa.selenium.JavascriptExecutor)d).executeScript("return document.cookie||'';");
                        hasSess = cookies.matches("(?i).*\\b(session|sess|csrftoken|jwt|edx)\\b.*");
                    } catch (Throwable ignore) {}
                    return leftLogin || noPwd || hasSess;
                });
            } catch (org.openqa.selenium.TimeoutException te) {
                success = false;
            }
            if (!success) {
                try { diagHtml = writeTemp("login-fail-", ".html", driver.getPageSource()); } catch (Throwable ignore) {}
                try {
                    byte[] shot = ((org.openqa.selenium.TakesScreenshot) driver).getScreenshotAs(org.openqa.selenium.OutputType.BYTES);
                    diagPng = java.io.File.createTempFile("login-fail-", ".png");
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(diagPng)) { fos.write(shot); }
                } catch (Throwable ignore) {}
                if (diagHtml != null) System.err.println("Saved " + diagHtml.getAbsolutePath());
                if (diagPng  != null) System.err.println("Saved " + diagPng.getAbsolutePath());
                throw new IllegalStateException("Не удалось пройти логин автоматически.");
            }

            // >>> ДОПОЛНИТЕЛЬНАЯ ПАУЗА ПОСЛЕ ЛОГИНА <<<
            try { Thread.sleep(WAIT_AFTER_LOGIN_MS); } catch (InterruptedException ignored) {}

            // 5) открываем целевую страницу
            driver.switchTo().defaultContent();
            driver.get(targetUrl);
            waitDomReady(driver, 25);

            // 6) ждём первичный контент (селектор или #root > *)
            String primary = (contentSelector != null && !contentSelector.isBlank()) ? contentSelector : null;
            String fallbackSel = (waitSelectorFallback == null || waitSelectorFallback.isBlank()) ? "#root > *" : waitSelectorFallback;
            boolean matched = waitForSelectorOrRoot(driver, primary, fallbackSel, 25);

            // 7) ЖДЁМ «СЕТЕВУЮ ТИШИНУ» SPA перед снимком (XHR/fetch закончились)
            waitSpaNetworkIdle(driver, WAIT_TARGET_TIMEOUT_MS, WAIT_TARGET_STABLE_MS);

            // 8) лёгкий скролл + съёмка
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
            try { ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);"); Thread.sleep(400); } catch (Throwable ignored) {}

            String html = driver.getPageSource();
            java.io.File htmlFile = writeTemp("rendered-", ".html", html);
            java.io.File pngFile = null;
            try {
                byte[] shot = ((org.openqa.selenium.TakesScreenshot) driver).getScreenshotAs(org.openqa.selenium.OutputType.BYTES);
                pngFile = java.io.File.createTempFile("rendered-", ".png");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(pngFile)) { fos.write(shot); }
            } catch (Throwable ignored) {}

            if (!KEEP_BROWSER_OPEN) {
                try { driver.quit(); } catch (Throwable ignored) {}
            }
            CURRENT_DRIVER = driver;
            return new RenderResult(200, driver.getCurrentUrl(), matched, htmlFile, pngFile);

        } catch (Exception e) {
            try { diagHtml = writeTemp("login-fail-", ".html", driver.getPageSource()); } catch (Throwable ignore) {}
            try {
                byte[] shot = ((org.openqa.selenium.TakesScreenshot) driver).getScreenshotAs(org.openqa.selenium.OutputType.BYTES);
                diagPng = java.io.File.createTempFile("login-fail-", ".png");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(diagPng)) { fos.write(shot); }
            } catch (Throwable ignore) {}
            if (diagHtml != null) System.err.println("Saved " + diagHtml.getAbsolutePath());
            if (diagPng  != null) System.err.println("Saved " + diagPng.getAbsolutePath());

            if (!KEEP_BROWSER_OPEN) {
                try { driver.quit(); } catch (Throwable ignore) {}
            }
            throw e;
        }
    }




    /* ================== утилиты/отправка ================== */
    private WebDriver ensureLoggedInDriver() throws Exception {
        if (CURRENT_DRIVER != null) return CURRENT_DRIVER;

        if (WATCH_USERNAME.isBlank() || WATCH_PASSWORD.isBlank() || WATCH_LOGIN_URL.isBlank()) {
            throw new IllegalStateException("Для авторизации нужны WATCH_LOGIN_URL / WATCH_USERNAME / WATCH_PASSWORD.");
        }

        // Логинимся «как в /render», но без отправки файлов и т.п.
        RenderResult rr = renderWithLogin(
                WATCH_URL,
                WATCH_COOKIES,
                WATCH_LOGIN_URL,
                WATCH_USERNAME,
                WATCH_PASSWORD,
                WATCH_SELECTOR,
                WATCH_WAIT_SELECTOR
        );
        // renderWithLogin уже положит драйвер в CURRENT_DRIVER — просто вернём его:
        if (CURRENT_DRIVER == null) {
            throw new IllegalStateException("Логин не дал активный драйвер.");
        }
        return CURRENT_DRIVER;
    }


    private static void clickIfPresent(WebDriver d, String... selectors) {
        for (String s : selectors) {
            try {
                List<WebElement> els = d.findElements(By.cssSelector(s));
                if (!els.isEmpty()) { els.get(0).click(); return; }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean samePath(String a, String b) {
        try {
            var ua = new java.net.URL(a); var ub = new java.net.URL(b);
            return ua.getHost().equalsIgnoreCase(ub.getHost()) && ua.getPath().equals(ub.getPath());
        } catch (Exception e) { return false; }
    }

    private static String guessLoginUrl(String current) {
        try {
            var u = new java.net.URL(current);
            // Часто у Skillfactory логин на lms.skillfactory.ru
            if (u.getHost().endsWith("skillfactory.ru")) {
                return "https://lms.skillfactory.ru/login";
            }
        } catch (Exception ignored) {}
        return null;
    }


    private static void waitDomReady(WebDriver d, int sec) {
        new WebDriverWait(d, Duration.ofSeconds(sec)).until(
                wd -> "complete".equals(((JavascriptExecutor) wd).executeScript("return document.readyState"))
        );
    }

    private static boolean pageLooksLikeLogin(WebDriver d) {
        try {
            // пароль на странице? часто верный индикатор
            return d.findElements(By.cssSelector("input[type='password']")).size() > 0
                    || ((String)((JavascriptExecutor) d).executeScript("return document.title||'';"))
                    .toLowerCase(Locale.ROOT).contains("login");
        } catch (Throwable t) { return false; }
    }

    private static boolean waitForSelectorOrRoot(WebDriver d, String primarySelector, String fallbackRootChild, int sec) {
        try {
            return new WebDriverWait(d, Duration.ofSeconds(sec)).until(dr -> {
                JavascriptExecutor js = (JavascriptExecutor) dr;
                if (primarySelector != null) {
                    Boolean ok = (Boolean) js.executeScript("return document.querySelector(arguments[0])!=null;", primarySelector);
                    if (Boolean.TRUE.equals(ok)) return true;
                }
                return (Boolean) js.executeScript(
                        "const r=document.getElementById('root'); return !!(r && r.children && r.children.length>0) || !!document.querySelector(arguments[0]);",
                        fallbackRootChild
                );
            });
        } catch (TimeoutException te) {
            return false;
        }
    }

    private static WebElement findAny(WebDriver d, String... selectors) {
        for (String s : selectors) {
            try {
                List<WebElement> els = d.findElements(By.cssSelector(s));
                if (!els.isEmpty()) return els.get(0);
            } catch (Throwable ignored) {}
        }
        return null;
    }


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

    private static void waitSpaNetworkIdle(org.openqa.selenium.WebDriver d, long timeoutMs, long stableMs) {
        org.openqa.selenium.JavascriptExecutor js = (org.openqa.selenium.JavascriptExecutor) d;
        // инъекция мониторинга fetch/XHR (если ещё не стоит)
        try {
            js.executeScript("""
            (function(){
              if (window.__netmonInstalled) return;
              window.__netmonInstalled = true;
              window.__pendingRequests = 0;
              const origFetch = window.fetch;
              window.fetch = function(){
                window.__pendingRequests++;
                return origFetch.apply(this, arguments).finally(function(){ window.__pendingRequests--; });
              };
              const origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function(){
                window.__pendingRequests++;
                this.addEventListener('loadend', function(){ window.__pendingRequests--; });
                return origSend.apply(this, arguments);
              };
            })();
        """);
        } catch (Throwable ignored) {}

        long end = System.currentTimeMillis() + timeoutMs;
        long quietSince = -1L;
        while (System.currentTimeMillis() < end) {
            try {
                Long pending = ((Number) js.executeScript("return (window.__pendingRequests||0);")).longValue();
                Boolean hasSkeleton = (Boolean) js.executeScript(
                        "return !!document.querySelector('.sf-skeleton, .skeleton, [data-loading=\"true\"], [aria-busy=\"true\"]);"
                );
                if (pending == 0 && !Boolean.TRUE.equals(hasSkeleton)) {
                    if (quietSince < 0) quietSince = System.currentTimeMillis();
                    if (System.currentTimeMillis() - quietSince >= stableMs) return; // «тишина» достаточно долго
                } else {
                    quietSince = -1L;
                }
            } catch (Throwable ignored) {}
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }
    private static String safeFileName(String s) {
        if (s == null) return "target";
        String cleaned = s.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return cleaned.isBlank() ? "target" : cleaned;
    }



}
