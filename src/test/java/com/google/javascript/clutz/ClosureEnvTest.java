package com.google.javascript.clutz;

import java.io.*;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ClosureEnvTest {

    private final Path inputFile = DeclarationGeneratorTest.getTestInputFile("env" + File.separatorChar + "browser.js");
    private PrintStream defaultOut;

    @Before
    public void setUp() {
        this.defaultOut = System.out;
        System.setOut(this.nullPrintStream);
    }

    @After
    public void tearDown() {
        System.setOut(this.defaultOut);
    }

    private final PrintStream nullPrintStream = new PrintStream(new OutputStream() {
        @Override
        public void write(int b) throws IOException {
        }
    });

    @Test
    public void shouldHaveErrorForNoExterns() throws Exception {
        Options opts = new Options(new String[] { this.inputFile.toString(), "--debug" });
        final DeclarationGenerator generator = new DeclarationGenerator(opts);
        generator.generateDeclarations();
        assertThat(generator.hasErrors()).isTrue();
    }

    @Test
    public void shouldHaveNoErrorsForBrowserExterns() throws Exception {
        Options opts = new Options(new String[] { this.inputFile.toString(), "--debug", "--closure_env", "BROWSER" });
        final DeclarationGenerator generator = new DeclarationGenerator(opts);
        generator.generateDeclarations();
        assertThat(generator.hasErrors()).isFalse();
    }
}
