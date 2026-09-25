package org.vortex.resourceloader.validation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PackValidator {
    private static final Gson GSON = new Gson();
    private final List<ValidationIssue> issues = new ArrayList<>();

    public ValidationResult validate(File packFile) {
        this.issues.clear();
        boolean isCritical = false;

        if (!packFile.exists()) {
            this.addIssue("Pack file does not exist", true);
            return new ValidationResult(false, this.issues);
        }

        if (!packFile.getName().toLowerCase().endsWith(".zip")) {
            this.addIssue("Pack file must be a ZIP file", true);
            return new ValidationResult(false, this.issues);
        }

        try (ZipFile zip = new ZipFile(packFile)) {
            ZipEntry mcmetaEntry = zip.getEntry("pack.mcmeta");
            if (mcmetaEntry == null) {
                this.addIssue("Missing pack.mcmeta file", true);
                isCritical = true;
            } else {
                validateMcMeta(zip, mcmetaEntry);
            }

            boolean hasAssets = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("assets/")) {
                    hasAssets = true;
                    break;
                }
            }

            if (!hasAssets) {
                this.addIssue("Missing assets/ directory", true);
                isCritical = true;
            }

            Map<String, Set<String>> textureReferences = new HashMap<>();
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".json")) {
                    validateJsonFile(zip, entry, textureReferences);
                }
            }

            validateTextureReferences(zip, textureReferences);

            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                if (name.contains("__macosx") || name.contains(".ds_store")) {
                    this.addIssue("Contains unnecessary system files: " + entry.getName(), false);
                }
            }
        } catch (IOException e) {
            this.addIssue("Failed to read ZIP file: " + e.getMessage(), true);
            isCritical = true;
        }

        return new ValidationResult(!isCritical, new ArrayList<>(this.issues));
    }

    @SuppressWarnings("unchecked")
    private void validateMcMeta(ZipFile zip, ZipEntry entry) {
        try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry))) {
            Map<String, Object> mcmeta = GSON.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
            if (mcmeta == null || !mcmeta.containsKey("pack")) {
                this.addIssue("pack.mcmeta is missing 'pack' section", true);
                return;
            }
            Map<String, Object> pack = (Map<String, Object>) mcmeta.get("pack");
            if (!pack.containsKey("pack_format")) {
                this.addIssue("pack.mcmeta is missing 'pack_format'", true);
            }
            if (!pack.containsKey("description")) {
                this.addIssue("pack.mcmeta is missing 'description'", false);
            }
        } catch (Exception e) {
            this.addIssue("Invalid pack.mcmeta JSON: " + e.getMessage(), true);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateJsonFile(ZipFile zip, ZipEntry entry, Map<String, Set<String>> textureReferences) {
        try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry))) {
            Map<String, Object> json = GSON.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
            if (json != null && entry.getName().contains("/models/") && json.containsKey("textures")) {
                Set<String> textures = new HashSet<>();
                Object texObj = json.get("textures");
                if (texObj instanceof Map) {
                    Map<String, Object> textureMap = (Map<String, Object>) texObj;
                    textureMap.values().forEach(tex -> {
                        if (tex instanceof String s && !s.startsWith("#")) {
                            textures.add(s);
                        }
                    });
                }
                if (!textures.isEmpty()) {
                    textureReferences.put(entry.getName(), textures);
                }
            }
        } catch (Exception e) {
            this.addIssue("Invalid JSON in " + entry.getName() + ": " + e.getMessage(), false);
        }
    }

    private void validateTextureReferences(ZipFile zip, Map<String, Set<String>> textureReferences) {
        textureReferences.forEach((model, textures) -> textures.forEach(texture -> {
            String texturePath = "assets/minecraft/textures/" + texture + ".png";
            if (zip.getEntry(texturePath) == null) {
                this.addIssue("Missing texture '" + texture + "' referenced in " + model, false);
            }
        }));
    }

    private void addIssue(String message, boolean critical) {
        this.issues.add(new ValidationIssue(message, critical));
    }

    public record ValidationResult(boolean isValid, List<ValidationIssue> issues) {
        public List<String> getFormattedIssues() {
            List<String> formatted = new ArrayList<>();
            List<ValidationIssue> criticalIssues = issues.stream().filter(ValidationIssue::isCritical).toList();
            List<ValidationIssue> warnings = issues.stream().filter(i -> !i.isCritical()).toList();

            if (!criticalIssues.isEmpty()) {
                formatted.add("§cCritical Issues:");
                criticalIssues.forEach(i -> formatted.add(" §c- " + i.message()));
            }
            if (!warnings.isEmpty()) {
                formatted.add("§eWarnings:");
                warnings.forEach(i -> formatted.add(" §e- " + i.message()));
            }
            if (issues.isEmpty()) {
                formatted.add("§aNo issues found! Pack is valid.");
            }
            return formatted;
        }
    }

    public record ValidationIssue(String message, boolean isCritical) {}
}
