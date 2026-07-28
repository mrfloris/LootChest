package fr.black_eyes.lootchest.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ReleaseMetadataDriftTest {
    @Test
    void maintainedMetadataAndDocumentationMatchGeneratedReleaseProperties() throws IOException {
        Properties release = loadReleaseProperties();
        String version = required(release, "plugin.version");
        String build = required(release, "build.number");
        String paperTarget = required(release, "paper.target");
        String paperBuild = required(release, "paper.build");
        String paperChannel = required(release, "paper.channel");
        String paperApi = required(release, "paper.api");
        String javaTarget = required(release, "java.target");
        String artifact = required(release, "artifact.name");

        assertEquals(
                paperTarget + ".build." + paperBuild + "-" + paperChannel.toLowerCase(),
                paperApi,
                "Paper API must describe the configured target/build/channel");
        assertEquals(
                "1MB-LootChest-v" + version + "-" + build
                        + "-CMI-j" + javaTarget + "-" + paperTarget + ".jar",
                artifact);

        Path root = projectRoot();
        String readme = Files.readString(root.resolve("README.md"));
        assertContains(readme, "| Server | Paper " + paperTarget + " build " + paperBuild
                + " (`" + paperChannel + "`) |", "README Paper release");
        assertContains(readme, "| Paper API | `" + paperApi + "` |", "README Paper API");
        assertContains(readme, "| Java runtime and bytecode | Java " + javaTarget + " |",
                "README Java target");
        assertContains(readme, "| Plugin version | `" + version + "` |", "README version");
        assertContains(readme, "| Candidate build | `" + build + "` |", "README build");
        assertContains(readme, "target/" + artifact, "README candidate artifact");

        String installation = Files.readString(root.resolve("docs/installation.md"));
        assertContains(installation, "Paper " + paperTarget + " build " + paperBuild
                + " from the `" + paperChannel + "` channel", "installation Paper release");
        assertContains(installation, "`" + artifact + "`", "installation artifact");
        assertContains(installation, "Paper API `" + paperApi + "`", "installation Paper API");

        String publicManifest = Files.readString(root.resolve("docs/plugin-docs.yml"));
        assertContains(publicManifest, "java_target: \"" + javaTarget + "\"", "docs Java target");
        assertContains(publicManifest, "paper_target: \"" + paperTarget + "\"", "docs Paper target");
        assertContains(publicManifest, "paper_build: \"" + paperBuild + "\"", "docs Paper build");
        assertContains(publicManifest, "paper_channel: " + paperChannel, "docs Paper channel");
        assertContains(publicManifest, "paper_api: " + paperApi, "docs Paper API");
        assertContains(publicManifest, "plugin_version: " + version, "docs plugin version");
        assertContains(publicManifest, "build_number: \"" + build + "\"", "docs build number");

        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(stream, "filtered plugin.yml is missing");
            YamlConfiguration descriptor = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(version + "-" + build, descriptor.getString("version"));
            assertEquals(paperTarget, descriptor.getString("api-version"));
        }
    }

    private static Properties loadReleaseProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = ReleaseMetadataDriftTest.class.getClassLoader()
                .getResourceAsStream("lootchest-build.properties")) {
            assertNotNull(stream, "generated lootchest-build.properties is missing");
            properties.load(stream);
        }
        return properties;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertNotNull(value, "missing generated release property " + key);
        assertTrue(!value.isBlank(), "blank generated release property " + key);
        return value.trim();
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("README.md"))
                    && Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("lootchest"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate the LootChest project root");
    }

    private static void assertContains(String content, String expected, String label) {
        assertTrue(content.contains(expected), () -> label + " drifted; missing: " + expected);
    }
}
