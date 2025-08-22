package com.google.javascript.gents;

import java.io.*;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.CodePrinter.Builder.CodeGeneratorFactory;
import com.google.javascript.jscomp.CodePrinter.Format;
import com.google.javascript.rhino.Node;
import org.kohsuke.args4j.CmdLineException;

/**
 * A tool that transpiles {@code .js} ES6 and ES5 Closure annotated JavaScript to {@code .ts}
 * TypeScript.
 */
public class TypeScriptGenerator {

    /** Diagnostic that indicates Gents somehow produced an incorrect AST structure. */
    private static final DiagnosticType GENTS_INTERNAL_ERROR = DiagnosticType.error("CLUTZ_INTERNAL_ERROR", "Gents failed: {0}");

    /**
     * Command line clang-format string to format stdin. The filename 'a.ts' is only used to inform
     * clang-format of the file type (TS).
     */
    private static final String[] CLANG_FORMAT = { "./node_modules/.bin/clang-format", "-assume-filename=a.ts", "-style=Google" };

    static {
        // In some environments (Mac OS X programs started from Finder, like your IDE) PATH does
        // not contain "clang-format". This property allows explicitly configuring its location.
        String cfLocation = System.getProperty("gents.clangFormat");
        if (cfLocation != null) {
            CLANG_FORMAT[0] = cfLocation;
        }
    }

    public static void main(String[] args) {
        Options options = null;
        try {
            options = new Options(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: gents [options...] arguments...");
            e.getParser().printUsage(System.err);
            System.err.println();
            System.exit(1);
        }
        TypeScriptGenerator generator = null;
        try {
            generator = new TypeScriptGenerator(options);
            generator.generateTypeScript();
            if (generator.hasErrors()) {
                // Already reported through the print stream.
                System.exit(2);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.err.println("Uncaught exception in gents, exiting.");
            System.exit(3);
        }
        System.exit(0);
    }

    private final Options opts;
    private final Compiler compiler;

    final PathUtil pathUtil;
    private final NameUtil nameUtil;
    private GentsErrorManager errorManager;

    TypeScriptGenerator(Options opts) {
        this.opts = opts;
        this.compiler = new Compiler();
        this.compiler.disableThreads();
        setErrorStream(System.err);

        this.pathUtil = new PathUtil(opts.root, opts.absolutePathPrefix);
        this.nameUtil = new NameUtil(this.compiler);
    }

    void setErrorStream(PrintStream errStream) {
        this.errorManager = new GentsErrorManager(errStream, ErrorFormat.MULTILINE.toFormatter(this.compiler, true), this.opts.debug);
        this.compiler.setErrorManager(this.errorManager);
    }

    public boolean hasErrors() {
        return this.compiler.getErrorManager().getErrorCount() > 0;
    }

    private void generateTypeScript() {
        List<SourceFile> srcFiles = getFiles(this.opts.srcFiles);
        List<SourceFile> externFiles = getFiles(this.opts.externs);
        Set<String> filesToConvert = Sets.newLinkedHashSet(this.opts.filesToConvert);

        GentsResult result = generateTypeScript(filesToConvert, srcFiles, externFiles);
        Map<String, String> resultFileMap = result.sourceFileMap;

        for (String filename : filesToConvert) {
            String relativePath = this.pathUtil.getRelativePath(".", filename);
            String filepath = this.pathUtil.getFilePathWithoutExtension(relativePath);
            String tsCode = resultFileMap.get(filepath);
            if ("-".equals(this.opts.output)) {
                System.out.println("========================================");
                System.out.println("File: " + relativePath);
                System.out.println("========================================");
                System.out.println(tsCode);
            } else {
                String tsFilename = this.pathUtil.removeExtension(relativePath) + ".ts";
                File output = new File(new File(this.opts.output), tsFilename);
                if (!output.getParentFile().exists() && !output.getParentFile().mkdirs()) {
                    throw new IllegalArgumentException("Unable to make directories " + output.getParent());
                }
                try {
                    Files.asCharSink(output, UTF_8).write(tsCode);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Unable to write to file " + output.getName(), e);
                }
            }
        }
        try {
            if (this.opts.moduleRewriteLog != null) {
                Files.asCharSink(new File(this.opts.moduleRewriteLog), UTF_8).write(result.moduleRewriteLog);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to write to file " + this.opts.moduleRewriteLog, e);
        }
    }

    /** Returns a map from the basename to the TypeScript code generated for the file. */
    public GentsResult generateTypeScript(Set<String> filesToConvert, List<SourceFile> srcFiles, List<SourceFile> externs) throws AssertionError {
        GentsResult result = new GentsResult();

        final CompilerOptions compilerOpts = this.opts.getCompilerOptions();
        // Compile javascript code
        this.compiler.compile(externs, srcFiles, compilerOpts);

        Node externRoot = this.compiler.getRoot().getFirstChild();
        Node srcRoot = this.compiler.getRoot().getLastChild();

        new RemoveGoogScopePass(this.compiler).process(externRoot, srcRoot);

        CollectModuleMetadata modulePrePass = new CollectModuleMetadata(this.compiler, this.nameUtil, filesToConvert);
        modulePrePass.process(externRoot, srcRoot);

        // Strips all file nodes that we are not compiling.
        stripNonCompiledNodes(srcRoot, filesToConvert);

        CommentLinkingPass commentsPass = new CommentLinkingPass(this.compiler);
        commentsPass.process(externRoot, srcRoot);
        final NodeComments comments = commentsPass.getComments();

        ModuleConversionPass modulePass = new ModuleConversionPass(this.compiler, this.pathUtil, this.nameUtil, modulePrePass.getFileMap(), modulePrePass.getNamespaceMap(),
                                                                   comments, this.opts.alreadyConvertedPrefix);
        modulePass.process(externRoot, srcRoot);

        new TypeConversionPass(this.compiler, modulePrePass, comments).process(externRoot, srcRoot);

        new TypeAnnotationPass(this.compiler, this.pathUtil, this.nameUtil, modulePrePass.getSymbolMap(), modulePass.getTypeRewrite(), comments, this.opts.externsMap).process(
                        externRoot, srcRoot);

        new StyleFixPass(this.compiler, comments).process(externRoot, srcRoot);

        // We only use the source root as the extern root is ignored for codegen
        for (Node file : srcRoot.children()) {
            try {
                String filepath = this.pathUtil.getFilePathWithoutExtension(file.getSourceFileName());
                CodeGeneratorFactory factory = new CodeGeneratorFactory() {
                    @Override
                    public CodeGenerator getCodeGenerator(Format outputFormat, CodeConsumer cc) {
                        return new GentsCodeGenerator(cc, compilerOpts, comments, com.google.javascript.gents.TypeScriptGenerator.this.opts.externsMap);
                    }
                };

                String tsCode = new CodePrinter.Builder(file).setCompilerOptions(this.opts.getCompilerOptions())
                                                             .setTypeRegistry(this.compiler.getTypeRegistry())
                                                             .setCodeGeneratorFactory(factory)
                                                             .setPrettyPrint(true)
                                                             .setLineBreak(true)
                                                             .setOutputTypes(true)
                                                             .build();

                // For whatever reason closure sometimes prefixes the emit with an empty new line. Strip
                // newlines not present in the original source.
                CharSequence originalSourceCode = this.compiler.getSourceFileContentByName(file.getSourceFileName());

                Integer originalCount = countBeginningNewlines(originalSourceCode);
                Integer newCount = countBeginningNewlines(tsCode);

                if (newCount > originalCount) {
                    tsCode = tsCode.substring(newCount - originalCount);
                }

                result.sourceFileMap.put(filepath, tryClangFormat(tsCode));
            } catch (Throwable t) {
                System.err.println("Failed while converting " + file.getSourceFileName());
                t.printStackTrace(System.err);
                this.compiler.report(JSError.make(file.getSourceFileName(), -1, -1, GENTS_INTERNAL_ERROR, t.getMessage()));
            }
        }

        result.moduleRewriteLog = new ModuleRenameLogger().generateModuleRewriteLog(filesToConvert, modulePrePass.getNamespaceMap());
        this.errorManager.doGenerateReport();
        return result;
    }

    private Integer countBeginningNewlines(CharSequence originalSourceCode) {
        Integer originalCount = 0;
        for (Integer i = 0; i < originalSourceCode.length(); i++) {
            // There's a terrible hack in GentsCodeGenerator that it sometimes adds " \n" instead of "\n".
            // Count and strip that too.
            if (originalSourceCode.charAt(i) == '\n' || (originalSourceCode.charAt(i) == ' ' && i + 1 < originalSourceCode.length()
                            && originalSourceCode.charAt(i + 1) == '\n')) {
                originalCount += 1;
            } else {
                break;
            }
        }
        return originalCount;
    }

    /**
     * Attempts to format the generated TypeScript using clang-format. On failure to format (i.e.
     * clang-format does not exist), return the inputed string.
     */
    private static String tryClangFormat(String code) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(CLANG_FORMAT);
            final OutputStream stdin = process.getOutputStream();
            // stdout must be final for the nested object byteSource to return it.
            final InputStream stdout = process.getInputStream();

            // Write TypeScript code to stdin of the process
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stdin, UTF_8))) {
                writer.write(code);
                writer.close();
            }
            return readStream(stdout);
        } catch (IOException e) {
            System.err.println("clang-format has failed to execute: " + e.getMessage());
            return code;
        } finally {
            if (process != null) {
                try {
                    System.err.println(readStream(process.getErrorStream()));
                } catch (@SuppressWarnings("unused") IOException ignored) {
                    // Ignored.
                }
                // TODO(renez): Use .waitFor(n, TimeUnit.SECONDS) and .destroyForcibly() once we moved to
                // Java 8.
                process.destroy();
            }
        }
    }

    private static String readStream(final InputStream stream) throws IOException {
        ByteSource byteSource = new ByteSource() {
            @Override
            public InputStream openStream() throws IOException {
                return stream;
            }
        };
        return byteSource.asCharSource(UTF_8).read();
    }

    /** Removes the root nodes for all the library files from the source node. */
    private static void stripNonCompiledNodes(Node n, Set<String> filesToCompile) {
        for (Node child : n.children()) {
            if (!filesToCompile.contains(child.getSourceFileName())) {
                child.detach();
            }
        }
    }

    /** Returns a list of source files from a list of file names. */
    private static List<SourceFile> getFiles(Collection<String> fileNames) {
        List<SourceFile> files = new ArrayList<>(fileNames.size());
        for (String fileName : fileNames) {
            files.add(SourceFile.fromFile(fileName, UTF_8));
        }
        return files;
    }

    static class GentsResult {

        public Map<String, String> sourceFileMap = new LinkedHashMap<>();
        public String moduleRewriteLog = "";
    }
}
