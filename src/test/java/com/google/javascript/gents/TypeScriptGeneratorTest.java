package com.google.javascript.gents;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;

import static com.google.common.truth.Truth.assertThat;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.javascript.clutz.DeclarationGeneratorTest;
import com.google.javascript.gents.TypeScriptGenerator.GentsResult;
import com.google.javascript.jscomp.SourceFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TypeScriptGeneratorTest {

    static final String singleTestPath = "singleTests";

    static final String TEST_EXTERNS_MAP = TypeScriptGeneratorTest.getTestDirPath("test_externs_map.json").toString();

    @Parameters(name = "{index}: {0}")
    public static Iterable<File> testCases() {
        return getTestInputFiles(DeclarationGeneratorTest.JS, singleTestPath);
    }

    static List<File> getTestInputFiles(FilenameFilter filter, String... dir) {
        File[] testFiles = getTestDirPath(dir).toFile().listFiles(filter);
        return Arrays.asList(testFiles);
    }

    static Path getTestDirPath(String... testDir) {
        Path p = getPackagePath();
        for (String dir : testDir) {
            p = p.resolve(dir);
        }
        return p;
    }

    static final String SOURCE_ROOT = "";

    static Path getPackagePath() {
        Path root = FileSystems.getDefault().getPath(SOURCE_ROOT);
        Path testDir = root.resolve("src").resolve("test").resolve("java");
        String packageName = TypeScriptGeneratorTest.class.getPackage().getName();
        return testDir.resolve(packageName.replace('.', File.separatorChar));
    }

    static String getFileText(final File input) throws IOException {
        String text = Files.asCharSource(input, Charsets.UTF_8).read();
        String cleanText = DeclarationGeneratorTest.GOLDEN_FILE_COMMENTS_REGEXP.matcher(text).replaceAll("");
        return cleanText;
    }

    private final File input;

    public TypeScriptGeneratorTest(File input) {
        this.input = input;
    }

    @Test
    public void runTest() throws Exception {
        Options options = this.input.getName().equals("externs_map.js") ? new Options(TypeScriptGeneratorTest.TEST_EXTERNS_MAP) : new Options();

        TypeScriptGenerator gents = new TypeScriptGenerator(options);

        String basename = gents.pathUtil.getFilePathWithoutExtension(this.input.getName());
        String sourceText = getFileText(this.input);
        File goldenFile = DeclarationGeneratorTest.getGoldenFile(this.input, ".ts");
        String goldenText = getFileText(goldenFile);

        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        gents.setErrorStream(new PrintStream(errStream));

        GentsResult gentsResult = gents.generateTypeScript(Collections.singleton(this.input.getName()),
                                                           Collections.singletonList(SourceFile.fromCode(this.input.getName(), sourceText)),
                                                           Collections.emptyList());
        Map<String, String> transpiledSource = gentsResult.sourceFileMap;

        String errors = errStream.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(errors).isEmpty();
        assertThat(gents.hasErrors()).isFalse();

        assertThat(transpiledSource).hasSize(1);
        assertThat(transpiledSource).containsKey(basename);
        assertThat(transpiledSource.get(basename)).isEqualTo(goldenText);
    }
}
