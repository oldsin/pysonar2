package org.yinwang.pysonar.demos;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yinwang.pysonar.*;
import org.yinwang.pysonar.types.ModuleType;

import java.io.File;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Pattern;

/**
 * Collects per-file hyperlinks, as well as styles that require the
 * symbol table to resolve properly.
 */
class Linker {

    private static final Pattern CONSTANT = Pattern.compile("[A-Z_][A-Z0-9_]*");

    // Map of file-path to semantic styles & links for that path.
    @NotNull
    private Map<String, List<StyleRun>> fileStyles = new HashMap<String, List<StyleRun>>();

    private File outDir;  // where we're generating the output html
    private String rootPath;

    /**
     * Constructor.
     * @param root the root of the directory tree being indexed
     * @param outdir the html output directory
     */
    public Linker(String root, File outdir) {
        rootPath = root;
        outDir = outdir;
    }

    /**
     * Process all bindings across all files and record per-file semantic styles.
     * Should be called once per index.
     */
    public void findLinks(@NotNull Indexer indexer) {
        // backlinks
        for (List<Binding> lb : indexer.getBindings().values()) {
            for (Binding nb : lb) {
                addSemanticStyles(nb);
                for (Def def : nb.getDefs()) {
                    processDef(def, nb);
                }
            }
        }

        // highlight definitions
        for (Entry<Ref,List<Binding>> e : indexer.getReferences().entrySet()) {
            processRef(e.getKey(), e.getValue());
        }

//        for (List<Diagnostic> ld: indexer.semanticErrors.values()) {
//            for (Diagnostic d: ld) {
//                processDiagnostic(d);
//            }
//        }

        for (List<Diagnostic> ld: indexer.parseErrors.values()) {
            for (Diagnostic d: ld) {
                processDiagnostic(d);

            }
        }
    }

    /**
     * Returns the styles (links and extra styles) generated for a given file.
     * @param path an absolute source path
     * @return a possibly-empty list of styles for that path
     */
    public List<StyleRun> getStyles(String path) {
        return stylesForFile(path);
    }

    private List<StyleRun> stylesForFile(String path) {
        List<StyleRun> styles = fileStyles.get(path);
        if (styles == null) {
            styles = new ArrayList<StyleRun>();
            fileStyles.put(path, styles);
        }
        return styles;
    }

    private void addFileStyle(String path, StyleRun style) {
        stylesForFile(path).add(style);
    }

    /**
     * Add additional highlighting styles based on information not evident from
     * the AST.
     */
    private void addSemanticStyles(@NotNull Binding nb) {
        Def def = nb.getFirstNode();
        if (def == null || !def.isName()) {
            return;
        }

        boolean isConst = CONSTANT.matcher(def.getName()).matches();
        switch (nb.getKind()) {
            case SCOPE:
                if (isConst) {
                    addSemanticStyle(def, StyleRun.Type.CONSTANT);
                }
                break;
            case VARIABLE:
                addSemanticStyle(def, isConst ? StyleRun.Type.CONSTANT : StyleRun.Type.IDENTIFIER);
                break;
            case PARAMETER:
                addSemanticStyle(def, StyleRun.Type.PARAMETER);
                break;
            case CLASS:
                addSemanticStyle(def, StyleRun.Type.TYPE_NAME);
                break;
        }
    }

    private void addSemanticStyle(@NotNull Def def, StyleRun.Type type) {
        String path = def.getFile();
        if (path != null) {
            addFileStyle(path, new StyleRun(type, def.getStart(), def.getLength()));
        }
    }

    private void processDef(@Nullable Def def, @NotNull Binding binding) {
        if (def == null || def.isURL() || def.getStart() < 0) {
            return;
        }
        StyleRun style = new StyleRun(StyleRun.Type.ANCHOR, def.getStart(), def.getLength());
        style.message = binding.getQname() + " :: " + binding.getType();
        style.url = binding.getQname();
        style.id = "" + Math.abs(def.hashCode());

        Set<Ref> refs = binding.getRefs();
        style.highlight = new ArrayList<String>();
        for (Ref r : refs) {
            style.highlight.add(Integer.toString(Math.abs(r.hashCode())));
        }
        addFileStyle(def.getFile(), style);
    }
    
    private void processDiagnostic(@NotNull Diagnostic d) {
        StyleRun style = new StyleRun(StyleRun.Type.WARNING, d.start, d.end - d.start);
        style.message = d.msg;
        style.url = d.file;
        addFileStyle(d.file, style);
    }

    /**
     * Adds a hyperlink for a single reference.
     */
    void processRef(@NotNull Ref ref, @NotNull Binding nb) {
        String path = ref.getFile();
        StyleRun link = new StyleRun(StyleRun.Type.LINK, ref.start(), ref.length());
        link.message = nb.getQname() + " :: " + nb.getType();
        link.url = toURL(nb, path);
        link.id = nb.getQname();
        if (link.url != null) {
            addFileStyle(path, link);
        }
    }

    void processRef(@NotNull Ref ref, @NotNull List<Binding> bindings) {
        StyleRun link = new StyleRun(StyleRun.Type.LINK, ref.start(), ref.length());
        link.id = Integer.toString(Math.abs(ref.hashCode()));

        List<String> typings = new ArrayList<String>();
        for (Binding b : bindings) {
            typings.add(b.getQname() + " :: " + b.getType());
        }
        link.message = Util.joinWithSep(typings, " | ", "{", "}");

        link.highlight = new ArrayList<String>();
        for (Binding b : bindings) {
            for (Def d : b.getDefs()) {
                link.highlight.add(Integer.toString(Math.abs(d.hashCode())));
            }
        }

        
        // Currently jump to the first binding only. Should change to have a
        // hover menu or something later.
        String path = ref.getFile();
        for (Binding b : bindings) {
            if (link.url == null) {
                link.url = toURL(b, path);
            }

            if (link.url != null) {
                addFileStyle(path, link);
                break;
            }
        }
    }

    
    /**
     * Generate a URL for a reference to a binding.
     * @param binding the referenced binding
     * @param path the path containing the reference, or null if there was an error
     */
    @Nullable
    private String toURL(@NotNull Binding binding, String path) {
        Def def = binding.getFirstNode();
        if (def == null) {
            return null;
        }
        if (binding.isBuiltin()) {
            return def.getURL();
        }

        if (binding.getKind() == Binding.Kind.MODULE) {
            return toModuleUrl(binding);
        }

        String anchor = "#" + binding.getQname();
        if (binding.getFirstFile().equals(path)) {
            return anchor;
        }

        String destPath = def.getFile();
        String relpath;
        if (destPath.length() >= rootPath.length()) {
            relpath = destPath.substring(rootPath.length());
            return Util.joinPath(outDir.getAbsolutePath(), relpath) + ".html" + anchor;
        } else {
            System.err.println("dest path length is shorter than root path:  dest="
                                + destPath + ", root=" + rootPath);
            return null;
        }
    }


    /**
     * Generate an anchorless URL linking to another file in the index.
     */
    @Nullable
    private String toModuleUrl(@NotNull Binding b) {
        ModuleType mtype = b.getType().asModuleType();
        if (mtype == null) { return null; }

        String path = mtype.getFile();
        if (path == null) return null;

        if (!path.startsWith(rootPath)) {
            return "file://" + path;  // can't find file => punt & load it directly
        } else {
            String relpath = path.substring(rootPath.length());
            return Util.joinPath(outDir.getAbsolutePath(), relpath) + ".html";
        }
    }
}
