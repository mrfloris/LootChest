package fr.black_eyes.lootchest.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseJarContentsIT {
    private static final int JAVA_25_CLASS_MAJOR = 69;
    private static final Pattern VERSION_ADAPTER =
            Pattern.compile("(^|.*/)v_\\d+(?:_\\d+)*/.*\\.class$");

    @Test
    void packagedReleaseContainsNoRetiredOrUnsupportedClasses() throws IOException {
        Path releaseJar = Path.of(System.getProperty("lootchest.release.jar", ""));
        assertTrue(Files.isRegularFile(releaseJar), () -> "Release jar not found: " + releaseJar);

        List<String> violations = new ArrayList<>();
        try (JarFile jar = new JarFile(releaseJar.toFile())) {
            jar.stream()
                    .map(entry -> entry.getName())
                    .forEach(entry -> forbiddenReason(entry)
                            .ifPresent(reason -> violations.add(reason + ": " + entry)));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Forbidden release-jar entries:\n - " + String.join("\n - ", violations));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fr/black_eyes/lootchest/simpleJavaPlugin/Updater.class",
            "org/bstats/bukkit/Metrics.class",
            "eu/decentsoftware/holograms/api/DHAPI.class",
            "fr/black_eyes/lootchest/proxy/BungeeChannel.class",
            "net/minecraft/network/protocol/Packet.class",
            "org/bukkit/craftbukkit/CraftWorld.class",
            "fr/black_eyes/lootchest/fall_effect/v_26_2/FallingPackage.class",
            "fr/black_eyes/lootchest/v_1_21/LegacyAdapter.class",
            "fr/black_eyes/lootchest/simpleJavaPlugin/Files.class"
    })
    void policyRecognizesEveryForbiddenClassFamily(String entry) {
        assertTrue(forbiddenReason(entry).isPresent(), entry);
    }

    @Test
    void policyAllowsMaintainedLootChestClasses() {
        assertFalse(forbiddenReason("fr/black_eyes/lootchest/Main.class").isPresent());
        assertFalse(forbiddenReason(
                "fr/black_eyes/lootchest/compat/CompatibilityMigrations.class").isPresent());
        assertFalse(forbiddenReason(
                "fr/black_eyes/lootchest/LootChestHologram.class").isPresent());
    }

    @Test
    void packagedReleaseEmitsJava25Bytecode() throws IOException {
        Path releaseJar = Path.of(System.getProperty("lootchest.release.jar", ""));
        assertTrue(Files.isRegularFile(releaseJar), () -> "Release jar not found: " + releaseJar);

        try (JarFile jar = new JarFile(releaseJar.toFile());
                InputStream classStream = jar.getInputStream(
                        jar.getJarEntry("fr/black_eyes/lootchest/Main.class"));
                DataInputStream data = new DataInputStream(classStream)) {
            assertEquals(0xCAFEBABE, data.readInt(), "invalid Main.class header");
            data.readUnsignedShort();
            assertEquals(JAVA_25_CLASS_MAJOR, data.readUnsignedShort());
        }
    }

    private static Optional<String> forbiddenReason(String entry) {
        if (!entry.endsWith(".class")) {
            return Optional.empty();
        }

        String lower = entry.toLowerCase(Locale.ROOT);
        String simpleName = lower.substring(lower.lastIndexOf('/') + 1);

        if (lower.contains("/simplejavaplugin/")) {
            return Optional.of("shaded configuration framework");
        }
        if (simpleName.matches("(updater|updatechecker)(\\$.*)?\\.class")) {
            return Optional.of("updater");
        }
        if (lower.startsWith("org/bstats/")
                || simpleName.matches("metrics(\\$.*)?\\.class")) {
            return Optional.of("metrics");
        }
        if (lower.startsWith("eu/decentsoftware/")
                || lower.startsWith("fr/black_eyes/holograms/")
                || lower.contains("decenthologram")) {
            return Optional.of("DecentHolograms");
        }
        if (lower.contains("/bungee")
                || lower.contains("/velocity")
                || lower.contains("/proxy/")
                || simpleName.matches("(bytestreams|bytearraydataoutput)(\\$.*)?\\.class")) {
            return Optional.of("proxy");
        }
        if (lower.startsWith("net/minecraft/")
                || lower.startsWith("org/bukkit/craftbukkit/")
                || lower.contains("/nms/")) {
            return Optional.of("NMS");
        }
        if (lower.contains("/fall_effect/")
                || VERSION_ADAPTER.matcher(lower).matches()) {
            return Optional.of("legacy adapter");
        }
        return Optional.empty();
    }
}
