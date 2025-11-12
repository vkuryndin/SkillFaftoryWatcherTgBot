plugins {
    id("java")
    id("application")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.telegram:telegrambots-longpolling:9.2.0")
    implementation("org.telegram:telegrambots-client:9.2.0") // OkHttpTelegramClient + TelegramClient
    implementation("org.telegram:telegrambots-meta:9.2.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.code.gson:gson:2.11.0")

    // Логи в консоль
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // Selenium + авто-драйвер
    implementation("org.seleniumhq.selenium:selenium-java:4.23.0")
    implementation("io.github.bonigarcia:webdrivermanager:5.9.2")


}

tasks.test {
    useJUnitPlatform()
}

application {
    // замени на свой пакет/класс
    mainClass.set("org.example.BotWatcher")
}