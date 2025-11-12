package org.example;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Простой «наблюдатель изменений» страниц после логина в браузере Selenium.
 *
 * Как работает:
 *  - для каждой цели (Target) выполняет сценарий шагов: go/click/clickText/wait/waitText/snap
 *  - из SNAP-селектора извлекает нормализованный видимый текст, считает SHA-256
 *  - сравнивает с прошлым хэшем в watch-state.json; при отличии сообщает изменения
 *
 * Использование из твоего бота:
 *  - после того как ты УЖЕ залогинился и у тебя есть активный WebDriver — вызови:
 *      List<ChangeWatcher.Change> changes = ChangeWatcher.runChecks(driver);
 *  - отправь summary() каждого Change в Telegram
 *
 * Быстрый standalone-тест (без бота):
 *  - выстави ENV: WATCH_LOGIN_URL, WATCH_URL, WATCH_USERNAME, WATCH_PASSWORD
 *  - запусти main(); откроется браузер, логин → проверки → оставит окно на 60 сек
 */
public class ChangeWatcher {

    // ====== ТАРГЕТЫ (куда проваливаться и что сравнивать) ======
    // Важно: WATCH_URL берём из ENV (это твой «дом» курса).
    // Пример 1: главная курса → снимок <main>
    // Пример 2: раздел «Объявления» (можешь поправить селекторы под реальный DOM)
    // Пример 3: клик по тексту «Программирование на языке Java» → снимок <main>
    static final List<Target> TARGETS = List.of(
            new Target("Course: Home",
                    Steps.of(
                            Step.go(env("WATCH_URL")),
                            Step.waitSel("#root > *"),
                            Step.snap("main")
                    )
            ),
            new Target("Course: Announcements",
                    Steps.of(
                            Step.go(env("WATCH_URL")),
                            Step.waitSel("#root > *"),
                            Step.click("a[href*='announcements'], a[href*='news']"),
                            Step.waitSel("main, .sf-announce-list, [data-announcements]"),
                            Step.snap("main, .sf-announce-list, [data-announcements]")
                    )
            ),
            new Target("Course: Java page",
                    Steps.of(
                            Step.go(env("WATCH_URL")),
                            Step.waitSel("#root > *"),
                            Step.clickText("Программирование на языке Java"),
                            Step.waitText("Программирование на языке Java"),
                            Step.waitSel("main, #root > *"),
                            Step.snap("main")
                    )
            )
    );

    // ====== ПУБЛИЧНЫЙ API: вызвать после УСПЕШНОГО логина (driver уже авторизован) ======
    public static List<Change> runChecks(WebDriver driver) throws Exception {
        State state = State.load();
        List<Change> changes = new ArrayList<>();

        for (Target t : TARGETS) {
            String text = runScenarioAndExtractText(driver, t.steps);
            String hash = sha256(text);
            String prev = state.hashes.get(t.name);

            if (prev == null || !prev.equals(hash)) {
                changes.add(new Change(t.name, prev, hash, text));
                state.hashes.put(t.name, hash);
                state.updatedAt.put(t.name, Instant.now().toString());
            }
        }
        state.save();
        return changes;
    }

    // ====== ВЫПОЛНЕНИЕ СЦЕНАРИЯ ДЛЯ ОДНОГО ТАРГЕТА ======
    private static String runScenarioAndExtractText(WebDriver d, List<Step> steps) throws Exception {
        for (Step s : steps) {
            switch (s.type) {
                case GO -> {
                    d.get(s.arg);
                    waitDomReady(d, 25);
                }
                case CLICK -> {
                    WebElement el = findClickable(d, s.arg, 20);
                    new org.openqa.selenium.interactions.Actions(d)
                            .moveToElement(el).pause(java.time.Duration.ofMillis(120)).click(el).perform();
                    sleep(600);
                    waitDomReady(d, 25);
                }
                case CLICK_TEXT -> {
                    WebElement el = findClickableByText(d, s.arg, 25);
                    new org.openqa.selenium.interactions.Actions(d)
                            .moveToElement(el).pause(java.time.Duration.ofMillis(120)).click(el).perform();
                    sleep(700);
                    waitDomReady(d, 25);
                }
                case WAIT -> {
                    waitVisible(d, s.arg, 25);
                }
                case WAIT_TEXT -> {
                    waitTextPresent(d, s.arg, 25);
                }
                case SNAP -> {
                    waitVisible(d, s.arg, 30);
                    // перед снимком подождём «сетевую тишину» SPA (если возможно)
                    waitSpaNetworkIdle(d, 15000, 1200);
                    return extractNormalizedText(d, s.arg);
                }
            }
        }
        throw new IllegalStateException("Сценарий не завершён шагом SNAP — нечего сравнивать.");
    }

    // ====== УТИЛИТЫ SELENIUM ======
    private static void waitDomReady(WebDriver d, int sec) {
        new org.openqa.selenium.support.ui.WebDriverWait(d, java.time.Duration.ofSeconds(sec))
                .until(wd -> "complete".equals(((JavascriptExecutor) wd).executeScript("return document.readyState")));
    }

    private static void waitVisible(WebDriver d, String css, int sec) {
        new org.openqa.selenium.support.ui.WebDriverWait(d, java.time.Duration.ofSeconds(sec))
                .until(org.openqa.selenium.support.ui.ExpectedConditions.visibilityOfElementLocated(By.cssSelector(css)));
    }

    private static WebElement findClickable(WebDriver d, String css, int sec) {
        var wait = new org.openqa.selenium.support.ui.WebDriverWait(d, java.time.Duration.ofSeconds(sec));
        return wait.until(org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable(By.cssSelector(css)));
    }

    private static String escapeXpath(String t){
        if (!t.contains("'")) return "'" + t + "'";
        String[] parts = t.split("'");
        StringBuilder sb = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", \"'\", ");
            sb.append("'").append(parts[i]).append("'");
        }
        sb.append(")");
        return sb.toString();
    }

    private static WebElement findClickableByText(WebDriver d, String text, int sec){
        String X = "//*[contains(normalize-space(.), " + escapeXpath(text) + ")]";
        var wait = new org.openqa.selenium.support.ui.WebDriverWait(d, java.time.Duration.ofSeconds(sec));
        return wait.until(w -> {
            List<WebElement> nodes = w.findElements(By.xpath(X));
            if (nodes.isEmpty()) return null;
            for (WebElement n : nodes) {
                try {
                    WebElement clickTarget = n;
                    List<WebElement> ab = n.findElements(By.xpath("ancestor-or-self::a | ancestor-or-self::button"));
                    if (!ab.isEmpty()) clickTarget = ab.get(0);
                    ((JavascriptExecutor) w).executeScript("arguments[0].scrollIntoView({block:'center'});", clickTarget);
                    if (clickTarget.isDisplayed() && clickTarget.isEnabled()) return clickTarget;
                } catch (Throwable ignore) {}
            }
            return null;
        });
    }

    private static void waitTextPresent(WebDriver d, String text, int sec){
        String X = "//*[contains(normalize-space(.), " + escapeXpath(text) + ")]";
        new org.openqa.selenium.support.ui.WebDriverWait(d, java.time.Duration.ofSeconds(sec))
                .until(org.openqa.selenium.support.ui.ExpectedConditions.presenceOfElementLocated(By.xpath(X)));
    }

    private static String extractNormalizedText(WebDriver d, String css) {
        String script = """
          const sel = arguments[0];
          const el = document.querySelector(sel);
          if(!el) return "";
          const clone = el.cloneNode(true);
          clone.querySelectorAll('script,style,link,noscript').forEach(n=>n.remove());
          const text = clone.innerText || clone.textContent || "";
          return text;
        """;
        String raw = (String) ((JavascriptExecutor) d).executeScript(script, css);
        return normalize(raw);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\u00A0", " "); // nbsp
        t = t.replaceAll("[\\t\\r]+", " ");
        t = t.replaceAll("\\s{2,}", " ");
        return t.trim();
    }

    private static void waitSpaNetworkIdle(WebDriver d, long timeoutMs, long stableMs) {
        JavascriptExecutor js = (JavascriptExecutor) d;
        try {
            js.executeScript("""
                (function(){
                  if (window.__netmonInstalled) return;
                  window.__netmonInstalled = true;
                  window.__pendingRequests = 0;
                  const origFetch = window.fetch;
                  if (origFetch) {
                    window.fetch = function(){
                      window.__pendingRequests++;
                      return origFetch.apply(this, arguments).finally(function(){ window.__pendingRequests--; });
                    };
                  }
                  const origOpen = XMLHttpRequest.prototype.open;
                  const origSend = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function(){ return origOpen.apply(this, arguments); };
                  XMLHttpRequest.prototype.send = function(){
                    window.__pendingRequests++;
                    this.addEventListener('loadend', function(){ window.__pendingRequests--; });
                    return origSend.apply(this, arguments);
                  };
                })();
            """);
        } catch (Throwable ignore) {}

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
                    if (System.currentTimeMillis() - quietSince >= stableMs) return;
                } else {
                    quietSince = -1L;
                }
            } catch (Throwable ignore) {}
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("ENV " + key + " не задан");
        return v;
    }

    // ====== МОДЕЛИ ДАННЫХ ДЛЯ ШАГОВ И ТАРГЕТОВ ======
    record Step(Type type, String arg) {
        static Step go(String url){ return new Step(Type.GO, url); }
        static Step click(String css){ return new Step(Type.CLICK, css); }
        static Step clickText(String text){ return new Step(Type.CLICK_TEXT, text); }
        static Step waitSel(String css){ return new Step(Type.WAIT, css); }
        static Step waitText(String text){ return new Step(Type.WAIT_TEXT, text); }
        static Step snap(String css){ return new Step(Type.SNAP, css); }
    }
    enum Type { GO, CLICK, CLICK_TEXT, WAIT, WAIT_TEXT, SNAP }
    record Steps(List<Step> list) {
        static List<Step> of(Step... steps){ return Arrays.asList(steps); }
    }
    record Target(String name, List<Step> steps) {}

    // ====== ХРАНИЛКА СОСТОЯНИЯ ======
    static class State {
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, String> updatedAt = new LinkedHashMap<>();

        static final File FILE = new File(System.getProperty("user.dir"), "watch-state.json");
        static final Gson G = new Gson();
        static final java.lang.reflect.Type STATE_JSON_TYPE = new TypeToken<State>(){}.getType();

        static State load() {
            if (!FILE.exists()) return new State();
            try (Reader r = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
                State s = G.fromJson(r, STATE_JSON_TYPE);
                if (s == null) s = new State();
                if (s.hashes == null) s.hashes = new LinkedHashMap<>();
                if (s.updatedAt == null) s.updatedAt = new LinkedHashMap<>();
                return s;
            } catch (Exception e) {
                e.printStackTrace();
                return new State();
            }
        }

        void save() {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
                G.toJson(this, w);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ====== ОПИСАНИЕ ИЗМЕНЕНИЙ (готово к отправке в Telegram) ======
    public record Change(String name, String prevHash, String newHash, String newText) {
        public String summary() {
            int len = newText == null ? 0 : newText.length();
            return "🔔 Изменения: " + name + "\n" +
                    "hash: " + shortHash(prevHash) + " → " + shortHash(newHash) + "\n" +
                    "len: " + len + " символов";
        }
        private static String shortHash(String h) { return (h == null? "—" : h.substring(0, 8)); }
    }

    // ====== Пример самостоятельного запуска (отладка) ======
    public static void main(String[] args) throws Exception {
        // ENV: WATCH_LOGIN_URL, WATCH_URL, WATCH_USERNAME, WATCH_PASSWORD
        System.setProperty("webdriver.http.factory", "jdk-http-client");
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--no-sandbox","--disable-dev-shm-usage","--window-size=1366,3000");
        WebDriver d = new ChromeDriver(opts);

        try {
            String login = System.getenv().getOrDefault("WATCH_LOGIN_URL", "https://apps.skillfactory.ru/learning/login");
            String target = env("WATCH_URL");
            String user = env("WATCH_USERNAME");
            String pass = env("WATCH_PASSWORD");

            // 1) login?next=<target>
            String loginStart = login + (login.contains("?")? "&" : "?")
                    + "next=" + java.net.URLEncoder.encode(target, StandardCharsets.UTF_8);
            d.get(loginStart);
            new org.openqa.selenium.support.ui.WebDriverWait(d, java.time.Duration.ofSeconds(20))
                    .until(org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable(By.cssSelector("input[name='email']")));
            d.findElement(By.cssSelector("input[name='email']")).sendKeys(user);
            d.findElement(By.cssSelector("input[name='password']")).sendKeys(pass + Keys.ENTER);
            new org.openqa.selenium.support.ui.WebDriverWait(d, java.time.Duration.ofSeconds(35))
                    .until(web -> !web.getCurrentUrl().toLowerCase(Locale.ROOT).contains("/learning/login"));

            // 2) проверки
            List<Change> changes = runChecks(d);
            if (changes.isEmpty()) {
                System.out.println("✓ Нет изменений");
            } else {
                for (Change c : changes) System.out.println(c.summary());
            }

            // оставим окно на минуту, чтобы посмотреть глазами
            TimeUnit.SECONDS.sleep(60);

        } finally {
            d.quit();
        }
    }
}
