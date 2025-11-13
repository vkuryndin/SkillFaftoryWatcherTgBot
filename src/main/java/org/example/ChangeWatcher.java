package org.example;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Наблюдатель изменений страниц в Skillfactory после ЛОГИНА (через уже авторизованный Selenium WebDriver).
 *
 * Идея:
 *  - На каждую «цель» (Target) выполняем сценарий шагов: GO/CLICK/CLICK_TEXT(_ANY/_OR_GO)/WAIT/WAIT_TEXT(_ANY)/SNAP
 *  - По SNAP-селектору берём видимый нормализованный текст -> считаем SHA-256
 *  - Предыдущее состояние (хэши) храним в watch-state.json; если хэш изменился — добавляем Change
 *
 * Вызов из бота после логина:
 *   var changes = ChangeWatcher.runChecks(driver);
 *   if (changes.isEmpty()) send("✓ Нет изменений"); else send(changes.get(i).summary());
 */
public class ChangeWatcher {

    /* ======================= ТАРГЕТЫ (под себя) ======================= */

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
                            Step.clickTextAny("Объявления", "Announcements", "Новости"),
                            Step.waitTextAny("Объявления", "Announcements", "Новости"),
                            Step.waitSel("main, .sf-announce-list, [data-announcements], #root > *"),
                            Step.snap("main, .sf-announce-list, [data-announcements]")
                    )
            ),
            new Target("Course: Java page",
                    Steps.of(
                            Step.go(env("WATCH_URL")),
                            Step.waitSel("#root > *"),
                            // Пытаемся кликнуть по тексту; если не нашли — переходим по прямому URL из ENV WATCH_JAVA_URL (если задан)
                            Step.clickTextOrGo("Программирование на языке Java", getenvOrEmpty("WATCH_JAVA_URL")),
                            Step.waitTextAny("Программирование на языке Java", "Java", "Джава"),
                            Step.waitSel("main, #root > *"),
                            Step.snap("main")
                    )
            )
    );

    /* ======================= Публичный API ======================= */

    /**
     * Старый API: оставлен для совместимости — возвращает только список изменений.
     */
    public static List<Change> runChecks(WebDriver driver) throws Exception {
        return runChecksWithHtml(driver).changes();
    }

    /**
     * Новый API: запускает все таргеты и возвращает:
     * - список изменений
     * - карту HTML-снимков по имени цели.
     */
    public static RunResult runChecksWithHtml(WebDriver driver) throws Exception {
        State state = State.load();
        List<Change> changes = new ArrayList<>();
        Map<String, String> htmlByTarget = new LinkedHashMap<>();

        for (Target t : TARGETS) {
            try {
                Snapshot snap = runScenarioAndExtractSnapshot(driver, t.steps);
                String text = snap.text();
                String html = snap.html();

                // сохраняем HTML для дебага/отправки в бота
                htmlByTarget.put(t.name(), html);

                String hash = sha256(text);
                String prev = state.hashes.get(t.name());

                if (prev == null || !prev.equals(hash)) {
                    changes.add(new Change(t.name(), prev, hash, text, html));
                    state.hashes.put(t.name(), hash);
                    state.updatedAt.put(t.name(), Instant.now().toString());
                }
            } catch (Exception ex) {
                // Не валим всю проверку из-за одной цели
                System.err.println("Target failed: " + t.name() + " — " + ex.getMessage());
            }
        }
        state.save();
        return new RunResult(changes, htmlByTarget);
    }

    /* ======================= Выполнение сценария ======================= */

    private static Snapshot runScenarioAndExtractSnapshot(WebDriver d, List<Step> steps) throws Exception {
        for (Step s : steps) {
            switch (s.type) {
                case GO -> {
                    d.get(s.arg);
                    waitDomReady(d, 25);
                }
                case CLICK -> {
                    WebElement el = findClickable(d, s.arg, 25);
                    new org.openqa.selenium.interactions.Actions(d)
                            .moveToElement(el).pause(java.time.Duration.ofMillis(120)).click(el).perform();
                    sleep(600);
                    waitDomReady(d, 25);
                }
                case CLICK_TEXT -> {
                    waitSpaNetworkIdle(d, 15000, 900);
                    WebElement el = findClickableByTextSmart(d, s.arg, 40);
                    jsClick(d, el);
                    sleep(700);
                    waitDomReady(d, 25);
                    waitSpaNetworkIdle(d, 15000, 900);
                }
                case CLICK_TEXT_ANY -> {
                    waitSpaNetworkIdle(d, 15000, 900);
                    WebElement el = findClickableByAnyText(d, splitAny(s.arg), 40);
                    jsClick(d, el);
                    sleep(700);
                    waitDomReady(d, 25);
                    waitSpaNetworkIdle(d, 15000, 900);
                }
                case CLICK_TEXT_OR_GO -> {
                    waitSpaNetworkIdle(d, 15000, 900);
                    List<String> parts = splitAny(s.arg); // [text, fallbackUrl?]
                    String text = parts.isEmpty() ? "" : parts.get(0);
                    String fallback = parts.size() >= 2 ? parts.get(1) : "";
                    try {
                        WebElement el = findClickableByTextSmart(d, text, 40);
                        jsClick(d, el);
                        sleep(800);
                        waitDomReady(d, 25);
                        waitSpaNetworkIdle(d, 15000, 900);
                    } catch (Exception miss) {
                        if (fallback != null && !fallback.isBlank()) {
                            ((JavascriptExecutor) d).executeScript("window.location.href = arguments[0];", fallback);
                            waitDomReady(d, 25);
                            waitSpaNetworkIdle(d, 15000, 900);
                        } else {
                            throw miss;
                        }
                    }
                }
                case WAIT -> waitVisible(d, s.arg, 30);
                case WAIT_TEXT -> waitTextPresent(d, s.arg, 30);
                case WAIT_TEXT_ANY -> waitAnyTextPresent(d, splitAny(s.arg), 30);
                case SNAP -> {
                    // 1) Ждём, чтобы целевой блок стал видимым и страница "успокоилась"
                    waitVisible(d, s.arg, 30);
                    waitSpaNetworkIdle(d, 15000, 1200);

                    // 2) Берём нормализованный текст — как и раньше
                    String text = extractNormalizedText(d, s.arg);
                    // 3) Параллельно берём HTML-кусок (или всю страницу, если селектор не найден)
                    String html = extractHtml(d, s.arg);

                    return new Snapshot(text, html);
                }
            }
        }
        throw new IllegalStateException("Сценарий не завершён шагом SNAP — нечего сравнивать.");
    }


    /* ======================= Selenium утилиты ======================= */

    private static String extractHtml(WebDriver d, String css) {
        String script = """
                  const sel = arguments[0];
                  if (sel) {
                    const el = document.querySelector(sel);
                    if (el) {
                      return el.outerHTML;
                    }
                  }
                  const root = document.documentElement || document.body;
                  return root ? root.outerHTML : "";
                """;
        Object res = ((JavascriptExecutor) d).executeScript(script, css);
        return res == null ? "" : res.toString();
    }


    private static void waitDomReady(WebDriver d, int sec) {
        new WebDriverWait(d, java.time.Duration.ofSeconds(sec))
                .until(wd -> "complete".equals(((JavascriptExecutor) wd).executeScript("return document.readyState")));
    }

    private static void waitVisible(WebDriver d, String css, int sec) {
        new WebDriverWait(d, java.time.Duration.ofSeconds(sec))
                .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(css)));
    }

    private static WebElement findClickable(WebDriver d, String css, int sec) {
        return new WebDriverWait(d, java.time.Duration.ofSeconds(sec))
                .until(ExpectedConditions.elementToBeClickable(By.cssSelector(css)));
    }

    private static String escapeXpath(String t) {
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

    /**
     * ВАЖНО: умный поиск кликабельного блока по ТОЧНОМУ тексту.
     * Используем:
     *   //*[normalize-space(.) = 'Программирование на языке Java']
     * и поднимаемся к ближайшему кликабельному предку: a | button | li | div.
     */
    private static WebElement findClickableByTextSmart(WebDriver d, String text, int sec) {
        String X = "//*[normalize-space(.) = " + escapeXpath(text) + "]";

        WebDriverWait wait = new WebDriverWait(d, java.time.Duration.ofSeconds(sec));
        return wait.until(w -> {
            List<WebElement> nodes = w.findElements(By.xpath(X));
            for (WebElement n : nodes) {
                try {
                    // ищем ближайшего предка, который выглядит как кликабельный контейнер
                    List<WebElement> ab = n.findElements(By.xpath(
                            "ancestor-or-self::a | ancestor-or-self::button | ancestor-or-self::li | ancestor-or-self::div"
                    ));
                    WebElement clickTarget = ab.isEmpty() ? n : ab.get(0);

                    ((JavascriptExecutor) w).executeScript(
                            "arguments[0].scrollIntoView({block:'center'});", clickTarget);

                    if (clickTarget.isDisplayed() && clickTarget.isEnabled()) {
                        return clickTarget;
                    }
                } catch (Throwable ignore) {
                }
            }
            return null;
        });
    }

    private static List<String> splitAny(String arg) {
        if (arg == null || arg.isBlank()) return List.of();
        String[] parts = arg.split("\\|\\|");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static WebElement findClickableByAnyText(WebDriver d, List<String> texts, int sec) {
        WebDriverWait wait = new WebDriverWait(d, java.time.Duration.ofSeconds(sec));
        return wait.until(w -> {
            for (String t : texts) {
                String X = "//*[normalize-space(.) = " + escapeXpath(t) + "]";
                List<WebElement> nodes = w.findElements(By.xpath(X));
                for (WebElement n : nodes) {
                    try {
                        List<WebElement> ab = n.findElements(By.xpath(
                                "ancestor-or-self::a | ancestor-or-self::button | ancestor-or-self::li | ancestor-or-self::div"
                        ));
                        WebElement clickTarget = ab.isEmpty() ? n : ab.get(0);
                        ((JavascriptExecutor) w).executeScript(
                                "arguments[0].scrollIntoView({block:'center'});", clickTarget);
                        if (clickTarget.isDisplayed() && clickTarget.isEnabled()) {
                            return clickTarget;
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
            return null;
        });
    }

    private static void waitTextPresent(WebDriver d, String text, int sec) {
        String X = "//*[contains(normalize-space(.), " + escapeXpath(text) + ")]";
        new WebDriverWait(d, java.time.Duration.ofSeconds(sec))
                .until(ExpectedConditions.presenceOfElementLocated(By.xpath(X)));
    }

    private static void waitAnyTextPresent(WebDriver d, List<String> texts, int sec) {
        new WebDriverWait(d, java.time.Duration.ofSeconds(sec))
                .until(web -> {
                    for (String t : texts) {
                        String X = "//*[contains(normalize-space(.), " + escapeXpath(t) + ")]";
                        if (!web.findElements(By.xpath(X)).isEmpty()) return true;
                    }
                    return false;
                });
    }

    private static void jsClick(WebDriver d, WebElement el) {
        ((JavascriptExecutor) d).executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        ((JavascriptExecutor) d).executeScript("arguments[0].click();", el);
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

    /**
     * Ждём «сетевую тишину» SPA: нет fetch/XHR и нет skeleton-элементов.
     */
    private static void waitSpaNetworkIdle(WebDriver d, long timeoutMs, long stableMs) {
        JavascriptExecutor js = (JavascriptExecutor) d;
        try {
            js.executeScript("""
                        (function(){
                          if (window.__netmonInstalled) return;
                          window.__netmonInstalled = true;
                          window.__pendingRequests = 0;
                          const of = window.fetch;
                          if (of) {
                            window.fetch = function(){
                              window.__pendingRequests++;
                              return of.apply(this, arguments).finally(function(){ window.__pendingRequests--; });
                            };
                          }
                          const os = XMLHttpRequest.prototype.send;
                          XMLHttpRequest.prototype.send = function(){
                            window.__pendingRequests++;
                            this.addEventListener('loadend', function(){ window.__pendingRequests--; });
                            return os.apply(this, arguments);
                          };
                        })();
                    """);
        } catch (Throwable ignore) {
        }

        long end = System.currentTimeMillis() + timeoutMs;
        long quietSince = -1L;
        while (System.currentTimeMillis() < end) {
            try {
                Long pending = ((Number) js.executeScript("return (window.__pendingRequests||0);")).longValue();
                Boolean hasSkeleton = (Boolean) js.executeScript(
                        "return !!document.querySelector('.sf-skeleton, .skeleton, [data-loading=\\\"true\\\"], [aria-busy=\\\"true\\\"]);"
                );
                if (pending == 0 && !Boolean.TRUE.equals(hasSkeleton)) {
                    if (quietSince < 0) quietSince = System.currentTimeMillis();
                    if (System.currentTimeMillis() - quietSince >= stableMs) return;
                } else {
                    quietSince = -1L;
                }
            } catch (Throwable ignore) {
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String sha256(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256")
                .digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("ENV " + key + " не задан");
        return v;
    }

    private static String getenvOrEmpty(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v;
    }

    /* ======================= Модели шагов/таргетов ======================= */

    enum Type {GO, CLICK, CLICK_TEXT, CLICK_TEXT_ANY, CLICK_TEXT_OR_GO, WAIT, WAIT_TEXT, WAIT_TEXT_ANY, SNAP}

    record Step(Type type, String arg) {
        static Step go(String url) {
            return new Step(Type.GO, url);
        }

        static Step click(String css) {
            return new Step(Type.CLICK, css);
        }

        static Step clickText(String text) {
            return new Step(Type.CLICK_TEXT, text);
        }

        static Step clickTextAny(String... texts) {
            return new Step(Type.CLICK_TEXT_ANY, String.join("||", texts));
        }

        static Step clickTextOrGo(String text, String fallbackUrl) {
            return new Step(Type.CLICK_TEXT_OR_GO, text + "||" + (fallbackUrl == null ? "" : fallbackUrl));
        }

        static Step waitSel(String css) {
            return new Step(Type.WAIT, css);
        }

        static Step waitText(String text) {
            return new Step(Type.WAIT_TEXT, text);
        }

        static Step waitTextAny(String... texts) {
            return new Step(Type.WAIT_TEXT_ANY, String.join("||", texts));
        }

        static Step snap(String css) {
            return new Step(Type.SNAP, css);
        }
    }

    record Steps(List<Step> list) {
        static List<Step> of(Step... steps) {
            return Arrays.asList(steps);
        }
    }

    record Target(String name, List<Step> steps) {
    }

    /* ======================= Состояние (watch-state.json) ======================= */

    static class State {
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, String> updatedAt = new LinkedHashMap<>();

        static final File FILE = new File(System.getProperty("user.dir"), "watch-state.json");
        static final Gson G = new Gson();
        static final java.lang.reflect.Type STATE_JSON_TYPE = new TypeToken<State>() {
        }.getType();

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

    /**
     * Результат одного сценария: текст для хэширования + HTML-снимок.
     */
    record Snapshot(String text, String html) {
    }

    /**
     * Итог выполнения всех таргетов: изменения + HTML по каждой цели.
     */
    public record RunResult(List<Change> changes, Map<String, String> htmlByTarget) {
    }

    /* ======================= Описание изменения ======================= */

    public record Change(String name,
                         String prevHash,
                         String newHash,
                         String newText,
                         String renderedHtml) {
        public String summary() {
            int len = newText == null ? 0 : newText.length();
            return "🔔 Изменения: " + name + "\n" +
                    "hash: " + shortHash(prevHash) + " → " + shortHash(newHash) + "\n" +
                    "len: " + len + " символов";
        }

        private static String shortHash(String h) {
            return (h == null ? "—" : h.substring(0, 8));
        }
    }
}
