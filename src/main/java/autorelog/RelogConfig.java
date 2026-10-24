package autorelog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class RelogConfig {
    public static int threshold = 3;           // so viele verschwundene Blöcke hintereinander lösen den Relog aus
    public static int delaySeconds = 3;        // Wartezeit zwischen Ausloggen und Einloggen
    public static int minIntervalSeconds = 30; // frühestens alle X Sekunden ein automatischer Relog

    private static class Data {
        int threshold = 3;
        int delaySeconds = 3;
        int minIntervalSeconds = 30;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("autorelog.json");
        try {
            if (Files.exists(file)) {
                Data d = GSON.fromJson(Files.readString(file), Data.class);
                if (d != null) {
                    threshold = Math.max(1, d.threshold);
                    delaySeconds = Math.max(1, d.delaySeconds);
                    minIntervalSeconds = Math.max(5, d.minIntervalSeconds);
                }
            } else {
                Files.writeString(file, GSON.toJson(new Data()));
            }
        } catch (Exception ignored) {
        }
    }
}
