package com.realitycheck.simulation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The engine's determinism rests on what it does not use. This scans its source for the things
 * simulation-rules.md section 1 forbids, so that adding one fails the build with the reason.
 * (ArchitectureTest separately checks that the package imports nothing from Spring, JPA, Jackson
 * or the rest of the application.)
 */
class EnginePurityTest {

    private static final Path ENGINE = Path.of("src/main/java/com/realitycheck/simulation/engine");

    private static final Map<String, Pattern> FORBIDDEN = Map.of(
            "floating point (use BigDecimal or long)", Pattern.compile("\\b(double|float|Double|Float)\\b"),
            "randomness", Pattern.compile("\\bRandom\\b|Math\\.random|ThreadLocalRandom|\\bUUID\\.randomUUID"),
            "the real clock", Pattern.compile("currentTimeMillis|nanoTime|Clock\\.system"
                    + "|\\b(Instant|LocalDate|LocalTime|LocalDateTime|ZonedDateTime|OffsetDateTime|Clock)\\.now\\("),
            "hash-ordered collections (use TreeMap, TreeSet or a list)",
            Pattern.compile("\\bHash(Map|Set)\\b|\\bHashtable\\b|ConcurrentHashMap|\\bIdentityHashMap\\b"),
            "threads", Pattern.compile("\\bThread\\b|\\bExecutor|CompletableFuture|parallelStream|\\.parallel\\("),
            "input or output", Pattern.compile("java\\.io\\.|java\\.nio\\.|java\\.net\\.|System\\.(out|err|in)\\b"
                    + "|System\\.getenv|System\\.getProperty"));

    @Test
    void theEngineSourceUsesNothingThatCouldMakeARunDependOnAnythingButItsInput() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.list(ENGINE)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertThat(sources).as("engine source files").hasSizeGreaterThan(10);

        for (Path source : sources) {
            String code = stripComments(Files.readString(source));
            FORBIDDEN.forEach((what, pattern) -> assertThat(pattern.matcher(code).find())
                    .as("%s must not use %s", source.getFileName(), what)
                    .isFalse());
        }
    }

    @Test
    void theEngineImportsOnlyTheJdkAndItself() throws IOException {
        Pattern foreignImport = Pattern.compile("^import (?!java\\.|com\\.realitycheck\\.simulation\\.engine\\.)",
                Pattern.MULTILINE);
        try (Stream<Path> files = Files.list(ENGINE)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertThat(foreignImport.matcher(Files.readString(source)).find())
                        .as("%s imports something outside the JDK and the engine", source.getFileName())
                        .isFalse();
            }
        }
    }

    /** Comments may mention the forbidden things; code may not. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
