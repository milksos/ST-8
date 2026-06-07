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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class App {
    private static final String SERVICE_URL = "https://www.papercdcase.com/";
    private static final int MAX_TRACKS_ON_FORM = 18;
    private static final Duration FORM_LOAD_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration FILE_DOWNLOAD_TIMEOUT = Duration.ofSeconds(75);

    private static final Path DATA_FILE = Paths.get("data", "data.txt");
    private static final Path RESULT_FOLDER = Paths.get("result").toAbsolutePath();
    private static final Path RESULT_PDF = RESULT_FOLDER.resolve("cd.pdf");

    public static void main(String[] args) throws IOException {
        Album album = Album.readFrom(DATA_FILE);
        BrowserSettings browserSettings = new BrowserSettings(RESULT_FOLDER);

        Files.createDirectories(RESULT_FOLDER);
        clearPreviousPdfFiles();

        WebDriver driver = new ChromeDriver(browserSettings.toChromeOptions());
        try {
            PaperCasePage page = new PaperCasePage(driver);
            page.open();
            page.fill(album);
            page.generatePdf();

            Path downloaded = waitForDownload();
            Files.move(downloaded, RESULT_PDF, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Saved paper case PDF: " + RESULT_PDF);
        } finally {
            driver.quit();
        }
    }

    private static void clearPreviousPdfFiles() throws IOException {
        Files.deleteIfExists(RESULT_PDF);
        Files.deleteIfExists(RESULT_FOLDER.resolve("papercdcase.pdf"));
        Files.deleteIfExists(RESULT_FOLDER.resolve("papercdcase.pdf.crdownload"));
    }

    private static Path waitForDownload() {
        Path completeFile = RESULT_FOLDER.resolve("papercdcase.pdf");
        Path browserTempFile = RESULT_FOLDER.resolve("papercdcase.pdf.crdownload");
        long finishAt = System.currentTimeMillis() + FILE_DOWNLOAD_TIMEOUT.toMillis();

        while (System.currentTimeMillis() < finishAt) {
            if (Files.exists(completeFile) && !Files.exists(browserTempFile)) {
                return completeFile;
            }
            pause();
        }

        throw new IllegalStateException("Browser did not finish PDF download in "
                + FILE_DOWNLOAD_TIMEOUT.getSeconds() + " seconds.");
    }

    private static void pause() {
        try {
            Thread.sleep(350);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Waiting for browser download was interrupted.", exception);
        }
    }

    private static final class PaperCasePage {
        private final WebDriver driver;
        private final WebDriverWait wait;

        private PaperCasePage(WebDriver driver) {
            this.driver = driver;
            this.wait = new WebDriverWait(driver, FORM_LOAD_TIMEOUT);
        }

        private void open() {
            driver.get(SERVICE_URL);
            wait.until(ExpectedConditions.visibilityOfElementLocated(Selectors.artist()));
        }

        private void fill(Album album) {
            write(Selectors.artist(), album.artist);
            write(Selectors.title(), album.title);
            writeTracks(album.tracks);
            clickIfNeeded(Selectors.jewelCase());
            clickIfNeeded(Selectors.a4Paper());
            clickIfNeeded(Selectors.saveAsPdf());
        }

        private void generatePdf() {
            driver.findElement(Selectors.submit()).submit();
        }

        private void write(By locator, String value) {
            WebElement input = driver.findElement(locator);
            input.clear();
            input.sendKeys(value);
        }

        private void writeTracks(List<String> tracks) {
            List<WebElement> inputs = driver.findElements(Selectors.trackInputs());
            int limit = Math.min(Math.min(tracks.size(), inputs.size()), MAX_TRACKS_ON_FORM);

            for (int index = 0; index < limit; index++) {
                WebElement input = inputs.get(index);
                input.clear();
                input.sendKeys(tracks.get(index));
            }
        }

        private void clickIfNeeded(By locator) {
            WebElement control = driver.findElement(locator);
            if (!control.isSelected()) {
                control.click();
            }
        }
    }

    private static final class Selectors {
        private static By artist() {
            return By.name("artist");
        }

        private static By title() {
            return By.name("title");
        }

        private static By trackInputs() {
            return By.cssSelector("input[name^='track']");
        }

        private static By jewelCase() {
            return By.cssSelector("input[name='template'][value='jewel']");
        }

        private static By a4Paper() {
            return By.cssSelector("input[name='size'][value='a4']");
        }

        private static By saveAsPdf() {
            return By.cssSelector("input[name='force_saveas'][value='yes']");
        }

        private static By submit() {
            return By.name("submit");
        }
    }

    private static final class BrowserSettings {
        private final Path downloadFolder;

        private BrowserSettings(Path downloadFolder) {
            this.downloadFolder = downloadFolder;
        }

        private ChromeOptions toChromeOptions() {
            ChromeOptions options = new ChromeOptions();
            options.setAcceptInsecureCerts(true);
            options.addArguments("--ignore-certificate-errors");
            options.addArguments("--disable-popup-blocking");

            if (Boolean.getBoolean("headless")) {
                options.addArguments("--headless=new");
            }

            Map<String, Object> preferences = new HashMap<>();
            preferences.put("download.default_directory", downloadFolder.toString());
            preferences.put("download.prompt_for_download", false);
            preferences.put("plugins.always_open_pdf_externally", true);
            options.setExperimentalOption("prefs", preferences);

            return options;
        }
    }

    private static final class Album {
        private final String artist;
        private final String title;
        private final List<String> tracks;

        private Album(String artist, String title, List<String> tracks) {
            this.artist = artist;
            this.title = title;
            this.tracks = tracks;
        }

        private static Album readFrom(Path path) throws IOException {
            List<String> rows = new ArrayList<>();
            for (String row : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                Optional<String> normalized = normalizeRow(row);
                normalized.ifPresent(rows::add);
            }

            if (rows.size() < 3) {
                throw new IllegalArgumentException("Expected artist, title and track list in " + path + ".");
            }

            List<String> tracks = new ArrayList<>();
            for (int index = 2; index < rows.size() && tracks.size() < MAX_TRACKS_ON_FORM; index++) {
                tracks.add(rows.get(index));
            }

            return new Album(rows.get(0), rows.get(1), tracks);
        }

        private static Optional<String> normalizeRow(String row) {
            String value = row.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                return Optional.empty();
            }
            return Optional.of(value);
        }
    }
}
