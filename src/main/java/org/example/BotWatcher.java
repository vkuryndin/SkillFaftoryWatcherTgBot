package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public class BotWatcher implements LongPollingSingleThreadUpdateConsumer {
    private final TelegramClient telegramClient;
    private final String url;

    public BotWatcher(String botToken, String watchUrl) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.url = watchUrl;
    }

    public static void main(String[] args) throws Exception {
        String token = env("BOT_TOKEN");
        String watch = env("WATCH_URL"); // например: https://apps.skillfactory.ru/learning/course/...
        long periodSec = Long.parseLong(System.getenv().getOrDefault("CHECK_PERIOD_SECONDS", "600"));

        BotWatcher bot = new BotWatcher(token, watch);

        try (TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication()) {
            app.registerBot(token, bot);
            // Простая «демо»-проверка один раз при старте
            bot.sendStatusTo(chatIdFromEnv());
            System.out.println("Started. Watching: " + watch + " Period: " + periodSec + "s");
            new CountDownLatch(1).await();
        }
    }

    @Override
    public void consume(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) return;

        long chatId = update.getMessage().getChatId();
        String txt = update.getMessage().getText().trim();

        switch (txt) {
            case "/start" -> sendText(chatId, "✅ Подписка не хранится в этом минимале.\nКоманды: /check /status");
            case "/check" -> {
                try { sendText(chatId, "🔎 Хеш: `" + hashOf(url) + "`"); }
                catch (Exception e) { sendText(chatId, "⚠️ Ошибка: " + e.getMessage()); }
            }
            case "/status" -> sendStatusTo(chatId);
            default -> sendText(chatId, "Команды: /check /status");
        }
    }

    private void sendStatusTo(long chatId) {
        try {
            String h = hashOf(url);
            sendText(chatId, "🌐 " + url + "\nХеш: " + h + "\nВремя: " + Instant.now());
        } catch (Exception e) {
            sendText(chatId, "⚠️ Ошибка статуса: " + e.getMessage());
        }
    }

    private void sendText(long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            System.err.println("Send error: " + e.getMessage());
        }
    }

    private static String hashOf(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (WatcherBot/1.0)")
                .timeout(20000)
                .followRedirects(true)
                .get();
        byte[] bytes = doc.outerHtml().getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Env " + key + " is required");
        return v;
    }

    private static long chatIdFromEnv() {
        try { return Long.parseLong(System.getenv().getOrDefault("TEST_CHAT_ID", "0")); }
        catch (NumberFormatException e) { return 0L; }
    }
}
