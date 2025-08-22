package com.google.javascript.gents;

import java.util.*;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import com.google.common.base.MoreObjects;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.javascript.gents.CollectModuleMetadata.FileModule;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.NodeTraversal.AbstractPreOrderCallback;
import com.google.javascript.rhino.*;

/**
 * Converts Closure-style modules into TypeScript (ES6) modules (not namespaces). All module
 * metadata must be populated before running this CompilerPass.
 */
public final class ModuleConversionPass implements CompilerPass {

    private static final String EXPORTS = "exports";

    private final AbstractCompiler compiler;
    private final PathUtil pathUtil;
    private final NameUtil nameUtil;
    private final NodeComments nodeComments;

    private final Map<String, FileModule> fileToModule;
    private final Map<String, FileModule> namespaceToModule;

    /**
     * Map of metadata about a potential export to the node that should be exported.
     *
     * <p>For example, in the case below, the {@code Node} would point to {@code class Foo}.
     * <code><pre>
     * class Foo {}
     * exports {Foo}
     * </pre><code>
     */
    private final Map<ExportedSymbol, Node> exportsToNodes = new HashMap<>();

    // Used for rewriting usages of imported symbols
    /** fileName, namespace -> local name */
    private final Table<String, String, String> valueRewrite = HashBasedTable.create();
    /** fileName, namespace -> local name */
    private final Table<String, String, String> typeRewrite = HashBasedTable.create();

    private final String alreadyConvertedPrefix;

    // Map from source file name and imported module local name to importSpecs, used to store
    // destructuring assignments like "const {a, b} = abModule;". Later on we use this map to rewrite
    // full module imports.
    private final Table<String, String, Node> destructuringAssignments = HashBasedTable.create();

    Table<String, String, String> getTypeRewrite() {
        return this.typeRewrite;
    }

    ModuleConversionPass(AbstractCompiler compiler, PathUtil pathUtil, NameUtil nameUtil, Map<String, FileModule> fileToModule,
                         Map<String, FileModule> namespaceToModule, NodeComments nodeComments, String alreadyConvertedPrefix) {
        this.compiler = compiler;
        this.pathUtil = pathUtil;
        this.nameUtil = nameUtil;
        this.nodeComments = nodeComments;

        this.fileToModule = fileToModule;
        this.namespaceToModule = namespaceToModule;
        this.alreadyConvertedPrefix = alreadyConvertedPrefix;
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(this.compiler, root, new ModuleExportConverter());
        NodeTraversal.traverse(this.compiler, root, new DestructuringCollector());
        NodeTraversal.traverse(this.compiler, root, new ModuleImportConverter());
        NodeTraversal.traverse(this.compiler, root, new ModuleImportRewriter());
    }

    /**
     * Converts "exports" assignments into TypeScript export statements. This also builds a map of all
     * the declared modules.
     */
    private class ModuleExportConverter extends AbstractTopLevelCallback {
        @Override
        public void visit(NodeTraversal t, Node n, Node parent) {
            String fileName = n.getSourceFileName();
            if (n.isScript()) {
                if (com.google.javascript.gents.ModuleConversionPass.this.fileToModule.containsKey(fileName)) {
                    // Module is declared purely for side effects
                    FileModule module = com.google.javascript.gents.ModuleConversionPass.this.fileToModule.get(fileName);
                    if (!module.hasImports() && !module.hasExports()) {
                        // export {};
                        Node commentNode = new Node(Token.EMPTY);
                        commentNode.useSourceInfoFrom(n);
                        com.google.javascript.gents.ModuleConversionPass.this.nodeComments.addComment(commentNode, "\n// gents: force this file to be an ES6 module (no imports or exports)");

                        Node exportNode = new Node(Token.EXPORT, new Node(Token.EXPORT_SPECS, commentNode));
                        commentNode.useSourceInfoFromForTree(n);

                        if (n.hasChildren() && n.getFirstChild().isModuleBody()) {
                            n.getFirstChild().addChildToFront(exportNode);
                        } else {
                            n.addChildToFront(exportNode);
                        }
                    }
                }
            }

            if (!n.isExprResult()) {
                if (n.isConst() || n.isClass() || n.isFunction() || n.isLet() || n.isConst() || n.isVar()) {
                    collectMetdataForExports(n, fileName);
                }
                return;
            }

            Node child = n.getFirstChild();
            switch (child.getToken()) {
                case CALL -> {
                    String callName = child.getFirstChild().getQualifiedName();
                    if ("goog.module".equals(callName) || "goog.provide".equals(callName)) {
                        // Remove the goog.module and goog.provide calls.
                        if (com.google.javascript.gents.ModuleConversionPass.this.nodeComments.hasComment(n)) {
                            com.google.javascript.gents.ModuleConversionPass.this.nodeComments.replaceWithComment(n, new Node(Token.EMPTY));
                        } else {
                            com.google.javascript.gents.ModuleConversionPass.this.compiler.reportChangeToEnclosingScope(n);
                            n.detach();
                        }
                    }
                }
                case GETPROP -> {
                    JSDocInfo jsdoc = NodeUtil.getBestJSDocInfo(child);
                    if (jsdoc == null || !jsdoc.containsTypeDefinition()) {
                        // GETPROPs on the root level are only exports for @typedefs
                        break;
                    }
                    if (!com.google.javascript.gents.ModuleConversionPass.this.fileToModule.containsKey(fileName)) {
                        break;
                    }
                    FileModule module = com.google.javascript.gents.ModuleConversionPass.this.fileToModule.get(fileName);
                    Map<String, String> symbols = module.exportedNamespacesToSymbols;
                    String exportedNamespace = com.google.javascript.gents.ModuleConversionPass.this.nameUtil.findLongestNamePrefix(child, symbols.keySet());
                    if (exportedNamespace != null) {
                        String localName = symbols.get(exportedNamespace);
                        Node export = new Node(Token.EXPORT, createExportSpecs(Node.newString(Token.NAME, localName)));
                        export.useSourceInfoFromForTree(child);
                        parent.addChildAfter(export, n);
                        // Registers symbol for rewriting local uses.
                        registerLocalSymbol(child.getSourceFileName(), exportedNamespace, exportedNamespace, localName);
                    }
                }
                case ASSIGN -> {
                    if (!com.google.javascript.gents.ModuleConversionPass.this.fileToModule.containsKey(fileName)) {
                        break;
                    }
                    FileModule module = com.google.javascript.gents.ModuleConversionPass.this.fileToModule.get(fileName);
                    Node lhs = child.getFirstChild();
                    Map<String, String> symbols = module.exportedNamespacesToSymbols;

                    // We export the longest valid prefix
                    String exportedNamespace = com.google.javascript.gents.ModuleConversionPass.this.nameUtil.findLongestNamePrefix(lhs, symbols.keySet());
                    if (exportedNamespace != null) {
                        convertExportAssignment(child, exportedNamespace, symbols.get(exportedNamespace), fileName);
                        // Registers symbol for rewriting local uses
                        registerLocalSymbol(child.getSourceFileName(), exportedNamespace, exportedNamespace, symbols.get(exportedNamespace));
                    }
                }
                default -> {
                }
            }
        }

        private void collectMetdataForExports(Node namedNode, String fileName) {
            if (!com.google.javascript.gents.ModuleConversionPass.this.fileToModule.containsKey(fileName)) {
                return;
            }

            String nodeName = namedNode.getFirstChild().getQualifiedName();
            if (nodeName == null) {
                return;
            }
            com.google.javascript.gents.ModuleConversionPass.this.exportsToNodes.put(ExportedSymbol.of(fileName, nodeName, nodeName), namedNode);
        }
    }

    /**
     * Collects all top-level destructuring assignments. If later we find that the right hand side is
     * a local name of an import we will remove this destructuring assignment and fix the import specs
     * to use the destructuring imports.
     */
    private class DestructuringCollector extends AbstractTopLevelCallback {
        @Override
        public void visit(NodeTraversal t, Node n, Node parent) {
            @Nullable
            Node child = n.getFirstChild();
            if (child != null && child.isDestructuringLhs()) {
                Node potentialImportSpec = child.getFirstChild();
                Node potentialModuleName = child.getSecondChild();
                if (potentialImportSpec != null && potentialModuleName != null && potentialModuleName.isName()) {
                    com.google.javascript.gents.ModuleConversionPass.this.destructuringAssignments.put(potentialModuleName.getSourceFileName(), potentialModuleName.getQualifiedName(), potentialImportSpec);
                }
            }
        }
    }

    /** Converts goog.require statements into TypeScript import statements. */
    private class ModuleImportConverter extends AbstractTopLevelCallback {
        @Override
        public void visit(NodeTraversal t, Node n, Node parent) {
            if (NodeUtil.isNameDeclaration(n)) {
                Node node = n.getFirstFirstChild();

                // var x = goog.require(...);
                if (isARequireLikeCall(node)) {
                    Node callNode = node;
                    String requiredNamespace = callNode.getLastChild().getString();
                    String localName = n.getFirstChild().getQualifiedName();
                    ModuleImport moduleImport = new ModuleImport(n, Collections.singletonList(localName), requiredNamespace, false);
                    if (moduleImport.validateImport()) {
                        convertNonDestructuringRequireToImportStatements(n, moduleImport);
                    }
                    return;
                }

                // var {foo, bar} = goog.require(...);
                if (isADestructuringRequireCall(node)) {
                    Node importedNode = node.getFirstChild();
                    // For multiple destructuring imports, there are multiple full local names.
                    // This does not support renaming imports of the sort
                    // const {a: b} = goog.require
                    // TODO(rado): add support for that pattern.
                    ArrayList<String> namedExports = new ArrayList<String>();
                    while (importedNode != null) {
                        namedExports.add(importedNode.getString());
                        importedNode = importedNode.getNext();
                    }
                    String requiredNamespace = node.getNext().getFirstChild().getNext().getString();
                    ModuleImport moduleImport = new ModuleImport(n, namedExports, requiredNamespace, true);
                    if (moduleImport.validateImport()) {
                        convertDestructuringRequireToImportStatements(n, moduleImport);
                    }
                }
            } else if (n.isExprResult()) {
                // goog.require(...);
                Node callNode = n.getFirstChild();
                if (isARequireLikeCall(callNode)) {
                    String requiredNamespace = callNode.getLastChild().getString();
                    // For goog.require(...) imports, the full local name is just the required namespace/module.
                    // We use the suffix from the namespace as the local name, i.e. for
                    // goog.require("a.b"), requiredNamespace = "a.b", fullLocalName = ["a.b"], localName = ["b"]
                    ModuleImport moduleImport = new ModuleImport(n, Collections.singletonList(requiredNamespace), requiredNamespace, false);
                    if (moduleImport.validateImport()) {
                        convertNonDestructuringRequireToImportStatements(n, moduleImport);
                    }
                }
            }
        }

        private boolean isADestructuringRequireCall(Node node) {
            return node != null && node.isObjectPattern() && node.getNext().getFirstChild() != null && isARequireLikeCall(node.getNext());
        }

        private boolean isARequireLikeCall(Node node) {
            return node != null && node.isCall() && (node.getFirstChild().matchesQualifiedName("goog.require") || node.getFirstChild()
                                                                                                                      .matchesQualifiedName("goog.requireType")
                            || node.getFirstChild().matchesQualifiedName("goog.forwardDeclare"));
        }
    }

    /** Rewrites variable names used in the file to correspond to the newly imported symbols. */
    private class ModuleImportRewriter extends AbstractPreOrderCallback {
        @Override
        public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
            // Rewrite all imported variable name usages
            if (n.isName() || n.isGetProp()) {
                if (!com.google.javascript.gents.ModuleConversionPass.this.valueRewrite.containsRow(n.getSourceFileName())) {
                    return true;
                }

                Map<String, String> rewriteMap = com.google.javascript.gents.ModuleConversionPass.this.valueRewrite.rowMap().get(n.getSourceFileName());
                String importedNamespace = com.google.javascript.gents.ModuleConversionPass.this.nameUtil.findLongestNamePrefix(n, rewriteMap.keySet());
                if (importedNamespace != null) {
                    com.google.javascript.gents.ModuleConversionPass.this.nameUtil.replacePrefixInName(n, importedNamespace, rewriteMap.get(importedNamespace));
                    return false;
                }
            }
            return true;
        }
    }

    /** A single statement containing {@code goog.require(...)}. */
    private class ModuleImport {
        /** require statement Node. */
        private final Node originalImportNode;

        /**
         * LHS of the requirement statement. If the require statement has no LHS, then local name is the
         * suffix of the required namespace/module.
         */
        private final List<String> localNames;

        /**
         * Full local name is used for rewriting imported variables in the file later on. There's a one
         * to one relationship between fullLocalNames and localNames.
         */
        private final List<String> fullLocalNames;

        /**
         * For {@code goog.require(...)} with no LHS and no side effects, then we use the required
         * namespace/module's suffix as the local name. The backup name is useful in this case to avoid
         * conflicts when the required namespace/module's suffix is the same with a named exported from
         * the same file.
         */
        private String backupName;

        /** The required namespace or module name */
        private final String requiredNamespace;

        /**
         * {@code true}, if the original require statement contains destructuring imports. For
         * destructuring imports, there are one or more local names and full local names. For non
         * destructuring imports, there is exactly one local name and one full local name.
         */
        private final boolean isDestructuringImport;

        /** FileModule for the imported file */
        private final FileModule module;

        /** Referenced file */
        private String referencedFile;

        /**
         * The last part of the required namespace and module, used to detect local name conflicts and
         * calculate the backup name
         */
        private final String moduleSuffix;

        private ModuleImport(Node originalImportNode, List<String> fullLocalNames, String requiredNamespace, boolean isDestructuringImport) {
            this.originalImportNode = originalImportNode;
            this.fullLocalNames = fullLocalNames;
            this.requiredNamespace = requiredNamespace;
            this.isDestructuringImport = isDestructuringImport;
            this.module = com.google.javascript.gents.ModuleConversionPass.this.namespaceToModule.get(requiredNamespace);
            if (this.module != null) {
                this.referencedFile = com.google.javascript.gents.ModuleConversionPass.this.pathUtil.getImportPath(originalImportNode.getSourceFileName(), this.module.file);
            }
            this.moduleSuffix = com.google.javascript.gents.ModuleConversionPass.this.nameUtil.lastStepOfName(requiredNamespace);
            this.backupName = this.moduleSuffix;
            this.localNames = new ArrayList<String>();
            for (String fullLocalName : fullLocalNames) {
                String localName = com.google.javascript.gents.ModuleConversionPass.this.nameUtil.lastStepOfName(fullLocalName);
                this.localNames.add(localName);
                if (this.moduleSuffix.equals(localName)) {
                    this.backupName = this.moduleSuffix + "Exports";
                }
            }
        }

        /** Validate the module import assumptions */
        private boolean validateImport() {
            if (!this.isDestructuringImport && this.fullLocalNames.size() != 1) {
                com.google.javascript.gents.ModuleConversionPass.this.compiler.report(JSError.make(this.originalImportNode, GentsErrorManager.GENTS_MODULE_PASS_ERROR,
                                                                                                   String.format("Non destructuring imports should have exactly one local name, got [%s]",
                                                           String.join(", ", this.fullLocalNames))));
                return false;
            }

            if (this.module == null && !isAlreadyConverted()) {
                com.google.javascript.gents.ModuleConversionPass.this.compiler.report(JSError.make(this.originalImportNode, GentsErrorManager.GENTS_MODULE_PASS_ERROR,
                                                                                                   String.format("Module %s does not exist.", this.requiredNamespace)));
                return false;
            }
            return true;
        }

        /** Returns {@code true} if the imported file is already in TS */
        private boolean isAlreadyConverted() {
            return this.requiredNamespace.startsWith(com.google.javascript.gents.ModuleConversionPass.this.alreadyConvertedPrefix + ".");
        }

        /** Returns {@code true} if is full module import, for example const x = goog.require(...) */
        private boolean isFullModuleImport() {
            Node requireLHS = this.originalImportNode.getFirstChild();
            return requireLHS != null && requireLHS.isName();
        }
    }

    /**
     * Converts a non destructuring Closure goog.require call into a TypeScript import statement.
     *
     * <p>The resulting node is dependent on the exports by the module being imported:
     *
     * <pre>
     *   import localName from "goog:old.namespace.syntax";
     *   import {A as localName, B} from "./valueExports";
     *   import * as localName from "./objectExports";
     *   import "./sideEffectsOnly"
     * </pre>
     */
    private void convertNonDestructuringRequireToImportStatements(Node n, ModuleImport moduleImport) {
        // The imported file is already in TS
        if (moduleImport.isAlreadyConverted()) {
            convertRequireForAlreadyConverted(moduleImport);
            return;
        }

        // The imported file is kept in JS
        if (moduleImport.module.shouldUseOldSyntax()) {
            convertRequireToImportsIfImportedIsKeptInJs(moduleImport);
            return;
        }
        // For the rest of the function, the imported and importing files are migrating together

        // For non destructuring imports, there is exactly one local name and full local name
        String localName = moduleImport.localNames.get(0);
        String fullLocalName = moduleImport.fullLocalNames.get(0);
        // If not imported then this is a side effect only import.
        boolean imported = false;

        if (moduleImport.module.importedNamespacesToSymbols.containsKey(moduleImport.requiredNamespace)) {
            // import {value as localName} from "./file"
            Node importSpec = new Node(Token.IMPORT_SPEC, IR.name(moduleImport.moduleSuffix));
            importSpec.setShorthandProperty(true);
            // import {a as b} only when a != b
            if (!moduleImport.moduleSuffix.equals(localName)) {
                importSpec.addChildToBack(IR.name(localName));
            }

            Node importNode = new Node(Token.IMPORT, IR.empty(), new Node(Token.IMPORT_SPECS, importSpec), Node.newString(moduleImport.referencedFile));
            addImportNode(n, importNode);
            imported = true;

            registerLocalSymbol(n.getSourceFileName(), fullLocalName, moduleImport.requiredNamespace, localName);
            // Switch to back up name if necessary
            localName = moduleImport.backupName;
        }

        if (moduleImport.module.providesObjectChildren.get(moduleImport.requiredNamespace).size() > 0) {
            // import * as var from "./file"
            Node importNode = new Node(Token.IMPORT, IR.empty(), Node.newString(Token.IMPORT_STAR, localName), Node.newString(moduleImport.referencedFile));
            addImportNode(n, importNode);
            imported = true;

            for (String child : moduleImport.module.providesObjectChildren.get(moduleImport.requiredNamespace)) {
                if (!this.valueRewrite.contains(n.getSourceFileName(), child)) {
                    String fileName = n.getSourceFileName();
                    registerLocalSymbol(fileName, fullLocalName + '.' + child, moduleImport.requiredNamespace + '.' + child, localName + '.' + child);
                }
            }
        }

        if (!imported) {
            // Convert the require to "import './sideEffectOnly';"
            convertRequireForSideEffectOnlyImport(moduleImport);
        }

        this.compiler.reportChangeToEnclosingScope(n);
        n.detach();
    }

    /**
     * Converts a destructuring Closure goog.require call into a TypeScript import statement.
     *
     * <p>The resulting node is dependent on the exports by the module being imported:
     *
     * <pre>
     *   import {A as localName, B} from "./valueExports";
     * </pre>
     */
    private void convertDestructuringRequireToImportStatements(Node n, ModuleImport moduleImport) {
        // The imported file is already in TS
        if (moduleImport.isAlreadyConverted()) {
            convertRequireForAlreadyConverted(moduleImport);
            return;
        }

        // The imported file is kept in JS
        if (moduleImport.module.shouldUseOldSyntax()) {
            convertRequireToImportsIfImportedIsKeptInJs(moduleImport);
            return;
        }
        // For the rest of the function, the imported and importing files are migrating together

        // import {localName} from "./file"
        Node importSpecs = new Node(Token.IMPORT_SPECS);
        for (String localName : moduleImport.localNames) {
            Node importSpec = new Node(Token.IMPORT_SPEC);
            importSpec.setShorthandProperty(true);
            importSpec.addChildToBack(IR.name(localName));
            importSpecs.addChildToBack(importSpec);
        }
        Node importNode = new Node(Token.IMPORT, IR.empty(), importSpecs, Node.newString(moduleImport.referencedFile));
        addImportNode(n, importNode);

        for (int i = 0; i < moduleImport.fullLocalNames.size(); i++) {
            registerLocalSymbol(n.getSourceFileName(), moduleImport.fullLocalNames.get(i), moduleImport.requiredNamespace, moduleImport.localNames.get(i));
        }

        this.compiler.reportChangeToEnclosingScope(n);
        n.detach();
    }

    /** If the imported file is kept in JS, then use the special "goog:namespace" syntax */
    private void convertRequireToImportsIfImportedIsKeptInJs(ModuleImport moduleImport) {
        Node nodeToImport = null;
        // For destructuring imports use `import {foo} from 'goog:bar';`
        if (moduleImport.isDestructuringImport) {
            nodeToImport = new Node(Token.OBJECTLIT);
            for (String localName : moduleImport.localNames) {
                nodeToImport.addChildToBack(Node.newString(Token.STRING_KEY, localName));
            }
            // For non destructuring imports, it is safe to assume there's only one localName
        } else if (moduleImport.module.namespaceHasDefaultExport.getOrDefault(moduleImport.requiredNamespace, false)) {
            // If it has a default export then use `import foo from 'goog:bar';`
            nodeToImport = Node.newString(Token.NAME, moduleImport.localNames.get(0));
        } else {
            // If it doesn't have a default export then use `import * as foo from 'goog:bar';`
            nodeToImport = Node.newString(Token.IMPORT_STAR, moduleImport.localNames.get(0));
        }

        Node importNode = new Node(Token.IMPORT, IR.empty(), nodeToImport, Node.newString("goog:" + moduleImport.requiredNamespace));
        this.nodeComments.replaceWithComment(moduleImport.originalImportNode, importNode);
        this.compiler.reportChangeToEnclosingScope(importNode);

        for (int i = 0; i < moduleImport.fullLocalNames.size(); i++) {
            registerLocalSymbol(moduleImport.originalImportNode.getSourceFileName(), moduleImport.fullLocalNames.get(i), moduleImport.requiredNamespace,
                                moduleImport.localNames.get(i));
        }
    }

    private void convertRequireForAlreadyConverted(ModuleImport moduleImport) {
        // we cannot use referencedFile here, because usually it points to the ES5 js file that is
        // the output of TS, and not the original source TS file.
        // However, we can reverse map the goog.module name to a file name.
        // TODO(rado): sync this better with the mapping done in tsickle.
        String originalPath = moduleImport.requiredNamespace.replace(this.alreadyConvertedPrefix + ".", "").replace(".", "/");
        String sourceFileName = moduleImport.originalImportNode.getSourceFileName();
        String referencedFile = this.pathUtil.getImportPath(sourceFileName, originalPath);
        // goog.require('...'); -> import '...';
        Node importSpec = IR.empty();
        if (moduleImport.isDestructuringImport) {
            importSpec = new Node(Token.IMPORT_SPECS);
            importSpec.setShorthandProperty(true);
            for (String fullLocalName : moduleImport.fullLocalNames) {
                Node spec = new Node(Token.IMPORT_SPEC, IR.name(fullLocalName));
                spec.setShorthandProperty(true);
                importSpec.addChildToBack(spec);
            }
        } else if (moduleImport.isFullModuleImport()) {
            // It is safe to assume there's one full local name because this is validated before.
            String fullLocalName = moduleImport.fullLocalNames.get(0);
            if (!this.destructuringAssignments.contains(sourceFileName, fullLocalName)) {
                // const A = goog.require('...'); -> import * as A from '...';
                importSpec = Node.newString(Token.IMPORT_STAR, fullLocalName);
            } else {
                // const A = goog.require('...');
                // const {destructuringA} = A;
                // ->
                // import {destructuringA} from '...';
                importSpec = this.destructuringAssignments.get(sourceFileName, fullLocalName);
                Node destructuringLhs = importSpec.getParent();
                Node assignmentNode = destructuringLhs.getParent();
                importSpec.detach();
                assignmentNode.getParent().removeChild(assignmentNode);
            }
        }
        Node importNode = new Node(Token.IMPORT, IR.empty(), importSpec, Node.newString(referencedFile));
        this.nodeComments.replaceWithComment(moduleImport.originalImportNode, importNode);
        this.compiler.reportChangeToEnclosingScope(importNode);
    }

    private void convertRequireForSideEffectOnlyImport(ModuleImport moduleImport) {
        Node importNode = new Node(Token.IMPORT, IR.empty(), IR.empty(), Node.newString(moduleImport.referencedFile));
        addImportNode(moduleImport.originalImportNode, importNode);
    }

    /**
     * Converts a Closure assignment on a goog.module or goog.provide namespace into a TypeScript
     * export statement. This method should only be called on a node within a module.
     *
     * @param assign
     *                 Assignment node
     * @param exportedNamespace
     *                 The prefix of the assignment name that we are exporting
     * @param exportedSymbol
     *                 The symbol that we want to export from the file For example,
     *                 convertExportAssignment(pre.fix = ..., "pre.fix", "name") <-> export const name = ...
     *                 convertExportAssignment(pre.fix.foo = ..., "pre.fix", "name") <-> name.foo = ...
     */
    private void convertExportAssignment(Node assign, String exportedNamespace, String exportedSymbol, String fileName) {
        checkState(assign.isAssign());
        checkState(assign.getParent().isExprResult());
        Node grandParent = assign.getGrandparent();
        checkState(grandParent.isScript() || grandParent.isModuleBody(), "export assignment must be in top level script or module body");

        Node exprNode = assign.getParent();
        Node lhs = assign.getFirstChild();
        Node rhs = assign.getLastChild();
        JSDocInfo jsDoc = NodeUtil.getBestJSDocInfo(assign);

        if (lhs.matchesQualifiedName(exportedNamespace)) {
            rhs.detach();
            if (isObjLitWithSimpleRefs(rhs)) {
                for (Node child : rhs.children()) {
                    ExportedSymbol symbolToExport = ExportedSymbol.fromExportAssignment(child.getFirstChild(), exportedNamespace, child.getString(), fileName);
                    Node exportNode = this.exportsToNodes.get(symbolToExport);
                    if (exportNode != null) {
                        moveExportStmtToADeclKeyword(assign, exportNode);
                    }
                }
                exprNode.detach();
                return;
            }
            ExportedSymbol symbolToExport = ExportedSymbol.fromExportAssignment(rhs, exportedNamespace, exportedSymbol, fileName);
            if (rhs.isName() && this.exportsToNodes.containsKey(symbolToExport)) {
                moveExportStmtToADeclKeyword(assign, this.exportsToNodes.get(symbolToExport));
                exprNode.detach();
                return;
            }

            // Below the export node stays but is modified.
            Node exportSpecNode;
            if (rhs.isName() && exportedSymbol.equals(rhs.getString())) {
                // Rewrite the export line to: <code>export {rhs}</code>.
                exportSpecNode = createExportSpecs(rhs);
                exportSpecNode.useSourceInfoFrom(rhs);
            } else {
                // Rewrite the export line to: <code>export const exportedSymbol = rhs</code>.
                exportSpecNode = IR.constNode(IR.name(exportedSymbol), rhs);
                exportSpecNode.useSourceInfoFrom(rhs);
            }
            exportSpecNode.setJSDocInfo(jsDoc);
            Node exportNode = new Node(Token.EXPORT, exportSpecNode);
            this.nodeComments.replaceWithComment(exprNode, exportNode);

        } else {
            // Assume prefix has already been exported and just trim the prefix
            this.nameUtil.replacePrefixInName(lhs, exportedNamespace, exportedSymbol);
        }
    }

    /**
     * Returns true is the object is an object literal where all values are symbols that match the
     * name:
     *
     * <p>Ex: {A, B: B} -> true {A: C} -> false {A: A + 1} -> false
     *
     * <p>TODO(rado): see if we can also support simple renaming objects like {NewName: OldName}.
     */
    private boolean isObjLitWithSimpleRefs(Node node) {
      if (!node.isObjectLit()) {
        return false;
      }
        for (Node child : node.children()) {
            if (!child.isStringKey() || !child.getFirstChild().isName()) {
                return false;
            }
            if (!child.getString().equals(child.getFirstChild().getString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Takes an assignment statement (export or goog.provide one), removes it and instead finds the
     * matching declaration and adds an 'export' keyword.
     *
     * <p>Before: class C {} exports.C = C;
     *
     * <p>After: export class C {};
     */
    private void moveExportStmtToADeclKeyword(Node assignmentNode, Node declarationNode) {
        // Rewrite the AST to export the symbol directly using information from the export
        // assignment.
        Node next = declarationNode.getNext();
        Node parent = declarationNode.getParent();
        declarationNode.detach();

        Node export = new Node(Token.EXPORT, declarationNode);
        export.useSourceInfoFrom(assignmentNode);

        this.nodeComments.moveComment(declarationNode, export);
        parent.addChildBefore(export, next);
        this.compiler.reportChangeToEnclosingScope(parent);
    }

    /** Creates an ExportSpecs node, which is the {...} part of an "export {name}" node. */
    private Node createExportSpecs(Node name) {
        Node exportSpec = new Node(Token.EXPORT_SPEC, name);
        // Set the "is shorthand" property so that we "export {x}", not "export {x as x}".
        exportSpec.setShorthandProperty(true);
        return new Node(Token.EXPORT_SPECS, exportSpec);
    }

    /** Saves the local name for imported symbols to be used for code rewriting later. */
    void registerLocalSymbol(String sourceFile, String fullLocalName, String requiredNamespace, String localName) {
        this.valueRewrite.put(sourceFile, fullLocalName, localName);
        this.typeRewrite.put(sourceFile, fullLocalName, localName);
        this.typeRewrite.put(sourceFile, requiredNamespace, localName);
    }

    private void addImportNode(Node n, Node importNode) {
        importNode.useSourceInfoFromForTree(n);
        n.getParent().addChildBefore(importNode, n);
        this.nodeComments.moveComment(n, importNode);
    }

    /** Metadata about an exported symbol. */
    private static final class ExportedSymbol {
        /** The name of the file exporting the symbol. */
        final String fileName;

        /**
         * The name that the symbol is declared in the module.
         *
         * <p>For example, {@code Foo} in: <code> class Foo {}</code>
         */
        final String localName;

        /**
         * The name that the symbol is exported under via named exports.
         *
         * <p>For example, {@code Bar} in: <code> exports {Bar}</code>.
         *
         * <p>If a symbol is directly exported, as in the case of <code>export class Foo {}</code>, the
         * {@link #localName} and {@link #exportedName} will both be {@code Foo}.
         */
        final String exportedName;

        private ExportedSymbol(String fileName, String localName, String exportedName) {
            this.fileName = checkNotNull(fileName);
            this.localName = checkNotNull(localName);
            this.exportedName = checkNotNull(exportedName);
        }

        static ExportedSymbol of(String fileName, String localName, String exportedName) {
            return new ExportedSymbol(fileName, localName, exportedName);
        }

        static ExportedSymbol fromExportAssignment(Node rhs, String exportedNamespace, String exportedSymbol, String fileName) {
            String localName = (rhs.getQualifiedName() != null) ? rhs.getQualifiedName() : exportedSymbol;

            String exportedName;
            if (exportedNamespace.equals(EXPORTS)) { // is a default export
                exportedName = localName;
            } else if (exportedNamespace.startsWith(EXPORTS)) { // is a named export
                exportedName = exportedNamespace.substring(EXPORTS.length() + 1);
            } else { // exported via goog.provide
                exportedName = exportedSymbol;
            }

            return new ExportedSymbol(fileName, localName, exportedName);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(getClass()).add("fileName", this.fileName).add("localName", this.localName).add("exportedName", this.exportedName).toString();
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.fileName, this.localName, this.exportedName);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ExportedSymbol that = (ExportedSymbol) obj;
            return Objects.equals(this.fileName, that.fileName) && Objects.equals(this.localName, that.localName) && Objects.equals(this.exportedName,
                                                                                                                                    that.exportedName);
        }
    }
}
