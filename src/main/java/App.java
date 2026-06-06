import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class App {
    private static final String PAGE_URL = "https://www.papercdcase.com/";
    private static final Path INPUT_FILE = Paths.get("data", "data.txt");
    private static final Path OUTPUT_DIR = Paths.get("result").toAbsolutePath();
    private static final Path FINAL_PDF = OUTPUT_DIR.resolve("cd.pdf");
    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(70);

    private enum Field {
        ARTIST("input[name='artist']"),
        TITLE("input[name='title']"),
        TRACKS("input[name^='track']"),
        JEWEL_TEMPLATE("input[name='template'][value='jewel']"),
        A4_PAPER("input[name='size'][value='a4']"),
        FORCE_DOWNLOAD("input[name='force_saveas'][value='yes']"),
        SUBMIT("input[name='submit']");

        private final By locator;

        Field(String cssSelector) {
            this.locator = By.cssSelector(cssSelector);
        }
    }

    public static void main(String[] args) throws IOException {
        CoverRequest request = CoverRequest.fromFile(INPUT_FILE);
        Files.createDirectories(OUTPUT_DIR);
        removeStaleDownloads();

        ChromeOptions options = buildBrowserOptions();
        WebDriver driver = new ChromeDriver(options);

        try {
            openGenerator(driver);
            fillCoverForm(driver, request);
            submitForm(driver);

            Path downloadedPdf = waitForDownloadedPdf();
            Files.move(downloadedPdf, FINAL_PDF, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Created CD cover: " + FINAL_PDF);
        } finally {
            driver.quit();
        }
    }

    private static ChromeOptions buildBrowserOptions() {
        ChromeOptions options = new ChromeOptions();
        options.setAcceptInsecureCerts(true);
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--disable-popup-blocking");

        if (Boolean.getBoolean("headless")) {
            options.addArguments("--headless=new");
        }

        Map<String, Object> preferences = new HashMap<>();
        preferences.put("download.default_directory", OUTPUT_DIR.toString());
        preferences.put("download.prompt_for_download", false);
        preferences.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", preferences);

        return options;
    }

    private static void openGenerator(WebDriver driver) {
        driver.get(PAGE_URL);
        WebDriverWait wait = new WebDriverWait(driver, PAGE_TIMEOUT);
        wait.until(ExpectedConditions.elementToBeClickable(Field.ARTIST.locator));
    }

    private static void fillCoverForm(WebDriver driver, CoverRequest request) {
        type(driver, Field.ARTIST, request.artist());
        type(driver, Field.TITLE, request.album());

        List<WebElement> trackInputs = driver.findElements(Field.TRACKS.locator);
        for (int i = 0; i < request.tracks().size() && i < trackInputs.size(); i++) {
            trackInputs.get(i).clear();
            trackInputs.get(i).sendKeys(request.tracks().get(i));
        }

        choose(driver, Field.JEWEL_TEMPLATE);
        choose(driver, Field.A4_PAPER);
        choose(driver, Field.FORCE_DOWNLOAD);
    }

    private static void submitForm(WebDriver driver) {
        driver.findElement(Field.SUBMIT.locator).submit();
    }

    private static void type(WebDriver driver, Field field, String value) {
        WebElement element = driver.findElement(field.locator);
        element.clear();
        element.sendKeys(value);
    }

    private static void choose(WebDriver driver, Field field) {
        WebElement element = driver.findElement(field.locator);
        if (!element.isSelected()) {
            element.click();
        }
    }

    private static void removeStaleDownloads() throws IOException {
        Files.deleteIfExists(FINAL_PDF);

        try (DirectoryStream<Path> files = Files.newDirectoryStream(OUTPUT_DIR, "papercdcase.pdf*")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static Path waitForDownloadedPdf() {
        long deadline = System.nanoTime() + DOWNLOAD_TIMEOUT.toNanos();
        Path download = OUTPUT_DIR.resolve("papercdcase.pdf");
        Path partialDownload = OUTPUT_DIR.resolve("papercdcase.pdf.crdownload");

        while (System.nanoTime() < deadline) {
            if (Files.exists(download) && !Files.exists(partialDownload)) {
                return download;
            }

            sleepBriefly();
        }

        throw new IllegalStateException("PDF download was not completed in " + DOWNLOAD_TIMEOUT.getSeconds()
                + " seconds.");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF waiting thread was interrupted.", exception);
        }
    }

    private record CoverRequest(String artist, String album, List<String> tracks) {
        private static CoverRequest fromFile(Path path) throws IOException {
            List<String> values = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toList());

            if (values.size() < 3) {
                throw new IllegalArgumentException("Expected artist, album title and at least one track in "
                        + path + ".");
            }

            List<String> tracks = values.subList(2, Math.min(values.size(), 18));
            return new CoverRequest(values.get(0), values.get(1), tracks);
        }
    }
}
