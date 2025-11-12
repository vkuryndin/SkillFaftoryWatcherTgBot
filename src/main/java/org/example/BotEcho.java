package org.example;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;

import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Simmple echo bot. Uses BOT_TOKEN from the environment variable.
 */
public class BotEcho implements LongPollingSingleThreadUpdateConsumer {
    private final TelegramClient client;
    private final String botName = requireEnv("BOT_USERNAME");

    public BotEcho(String token) {
        this.client = new OkHttpTelegramClient(token);
    }

    public static void main(String[] args) throws Exception {
        String token = requireEnv("BOT_TOKEN");

        BotEcho bot = new BotEcho(token);
        try (TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication()) {
            app.registerBot(token, bot);
            System.out.println("Echo bot started. Send /start or any text to your bot.");
            Thread.currentThread().join(); // держим процесс
        }
    }

    @Override
    public void consume(Update u) {
        if (u == null || !u.hasMessage() || !u.getMessage().hasText()) return;

        long chatId = u.getMessage().getChatId();
        String text = u.getMessage().getText();

        String reply = "/start".equalsIgnoreCase(text)
                ? "Привет! Я эхо-бот. Напиши что-нибудь — я повторю!"
                : "echo: " + text;

        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(reply)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Переменная окружения " + key + " не задана!");
        return v;
    }
}
