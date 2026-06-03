import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class App {
    private static final String PAPER_CD_CASE_URL = "https://www.papercdcase.com/";
    private static final Path DATA_PATH = Paths.get("data", "data.txt");
    private static final Path XPATHS_PATH = Paths.get("data", "xpaths.txt");
    private static final Path OUTPUT_DIRECTORY = Paths.get("result").toAbsolutePath();
    private static final Path OUTPUT_FILE = OUTPUT_DIRECTORY.resolve("cd.pdf");
    private static final Path DOWNLOADED_FILE = OUTPUT_DIRECTORY.resolve("papercdcase.pdf");
    private static final Path TEMP_FILE = OUTPUT_DIRECTORY.resolve("cd-download.pdf");

    public static void main(String[] args) throws IOException {
        AlbumData albumData = loadAlbumData(DATA_PATH);
        Map<String, String> xpaths = loadXpaths(XPATHS_PATH);

        Files.createDirectories(OUTPUT_DIRECTORY);
        Files.deleteIfExists(TEMP_FILE);

        WebDriver driver = new ChromeDriver(createChromeOptions());
        try {
            fillFormAndDownload(driver, albumData, xpaths);
            System.out.println("CD cover saved to " + OUTPUT_FILE);
        } finally {
            driver.quit();
        }
    }

    private static ChromeOptions createChromeOptions() {
        ChromeOptions options = new ChromeOptions();
        options.setAcceptInsecureCerts(true);
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--disable-popup-blocking");

        if (Boolean.getBoolean("headless")) {
            options.addArguments("--headless=new");
        }

        Map<String, Object> preferences = new HashMap<>();
        preferences.put("download.default_directory", OUTPUT_DIRECTORY.toString());
        preferences.put("download.prompt_for_download", false);
        preferences.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", preferences);

        return options;
    }

    private static void fillFormAndDownload(WebDriver driver, AlbumData albumData, Map<String, String> xpaths)
            throws IOException {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        driver.get(PAPER_CD_CASE_URL);

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(xpaths.get("Artist"))))
                .sendKeys(albumData.artist());
        driver.findElement(By.xpath(xpaths.get("Title"))).sendKeys(albumData.album());

        List<WebElement> trackFields = driver.findElements(By.xpath(xpaths.get("Tracks")));
        for (int index = 0; index < albumData.tracks().size() && index < trackFields.size(); index++) {
            trackFields.get(index).sendKeys(albumData.tracks().get(index));
        }

        selectOption(driver, xpaths.get("Type - Jewel case"));
        selectOption(driver, xpaths.get("Paper - A4"));
        selectOption(driver, xpaths.get("Force Save As - Yes"));

        driver.findElement(By.xpath(xpaths.get("Generate button"))).submit();
        waitForPdfDownload();
    }

    private static void selectOption(WebDriver driver, String xpath) {
        WebElement element = driver.findElement(By.xpath(xpath));
        if (!element.isSelected()) {
            element.click();
        }
    }

    private static AlbumData loadAlbumData(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());

        if (lines.size() < 3) {
            throw new IllegalArgumentException("data.txt must contain artist, album title and track list.");
        }

        List<String> tracks = lines.subList(2, Math.min(lines.size(), 20));
        return new AlbumData(lines.get(0), lines.get(1), tracks);
    }

    private static Map<String, String> loadXpaths(Path path) throws IOException {
        Map<String, String> values = createDefaultXpaths();

        if (!Files.exists(path)) {
            return values;
        }

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String normalized = line.trim();
            if (normalized.isEmpty() || normalized.startsWith("#")) {
                continue;
            }

            int separator = normalized.indexOf(':');
            if (separator <= 0) {
                continue;
            }

            String key = normalized.substring(0, separator).trim();
            String value = normalized.substring(separator + 1).trim();
            values.put(key, value);
        }

        return values;
    }

    private static Map<String, String> createDefaultXpaths() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("Artist", "//input[@name='artist']");
        defaults.put("Title", "//input[@name='title']");
        defaults.put("Tracks", "//input[starts-with(@name,'track')]");
        defaults.put("Type - Jewel case", "//input[@name='template' and @value='jewel']");
        defaults.put("Paper - A4", "//input[@name='size' and @value='a4']");
        defaults.put("Force Save As - Yes", "//input[@name='force_saveas' and @value='yes']");
        defaults.put("Generate button", "//input[@name='submit']");
        return defaults;
    }

    private static void waitForPdfDownload() throws IOException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();

        while (System.currentTimeMillis() < deadline) {
            Path partialDownload = OUTPUT_DIRECTORY.resolve("papercdcase.pdf.crdownload");
            if (Files.exists(DOWNLOADED_FILE) && !Files.exists(partialDownload)) {
                Files.move(DOWNLOADED_FILE, TEMP_FILE, StandardCopyOption.REPLACE_EXISTING);
                Files.move(TEMP_FILE, OUTPUT_FILE, StandardCopyOption.REPLACE_EXISTING);
                return;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Waiting for the downloaded PDF was interrupted.", exception);
            }
        }

        throw new IllegalStateException("The PDF file was not downloaded within the expected time.");
    }

    private record AlbumData(String artist, String album, List<String> tracks) {
    }
}
