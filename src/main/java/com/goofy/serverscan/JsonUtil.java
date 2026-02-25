package com.goofy.serverscan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class JsonUtil {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static List<ServerEntry> load(Path path) {
        try (FileReader reader = new FileReader(path.toFile())) {
            Type listType = new TypeToken<ArrayList<ServerEntry>>(){}.getType();
            List<ServerEntry> list = GSON.fromJson(reader, listType);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void save(Path path, List<ServerEntry> entries) {
        try (FileWriter writer = new FileWriter(path.toFile())) {
            GSON.toJson(entries, writer);
        } catch (IOException e) {
            System.err.println("[Scanner] Error saving JSON: " + e.getMessage());
        }
    }
}
