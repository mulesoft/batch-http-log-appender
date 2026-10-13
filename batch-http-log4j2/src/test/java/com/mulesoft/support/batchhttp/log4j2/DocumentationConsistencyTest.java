/*
 * Copyright (c) 2026, Salesforce, Inc.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mulesoft.support.batchhttp.log4j2;

import com.mulesoft.support.batchhttp.core.BatchConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the Markdown documentation and the {@code <BatchHttp>} plugin factory in lock-step.
 *
 * <p>{@code docs/configuration.md} is the single reference of every option; the vendor pages,
 * the README and the rest of {@code docs/} link into it rather than restating it. That only
 * works while the reference is complete and correct, and while the copy-paste XML samples
 * elsewhere use attributes that actually exist. Nothing else catches a renamed attribute, a
 * changed default, or a sample with a typo'd {@code overflowPolicy} — Log4j2 silently ignores
 * unknown attributes and the factory silently falls back on unknown enum values. So:
 *
 * <ol>
 *   <li>every {@code @PluginAttribute}/{@code @PluginElement} of the factory has exactly one row
 *       in {@code docs/configuration.md}, with the agreed anchor and the code's default;</li>
 *   <li>every {@code ```xml} fence in {@code README.md} and {@code docs/**}{@code /*.md} is a
 *       well-formed fragment, and every {@code <BatchHttp>} in it uses real attribute names and
 *       valid enum values (derived from the code, not hard-coded here);</li>
 *   <li>every {@code configuration.md#opt-...} link resolves, and every vendor page links the
 *       reference.</li>
 * </ol>
 *
 * Failures list every offender at once, so one run is enough to fix a whole page.
 */
class DocumentationConsistencyTest {

    private static final String CONFIG_DOC = "docs/configuration.md";
    private static final List<String> TABLE_HEADER =
            List.of("Option", "Type", "Default", "Applies to", "Description");
    private static final String NO_DEFAULT = "—"; // em dash

    /** {@code <a id="opt-xxx"></a>} anywhere in a line. */
    private static final Pattern ANCHOR = Pattern.compile("<a\\s+(?:id|name)=\"(opt-[^\"]*)\"\\s*>\\s*</a>");
    /** First cell of an option row: the anchor followed by the attribute name in backticks. */
    private static final Pattern OPTION_CELL =
            Pattern.compile("^<a\\s+id=\"(opt-[^\"]*)\"\\s*>\\s*</a>\\s*`([^`]+)`\\s*$");
    /** A link into the reference: any (relative or absolute) prefix, then configuration.md#opt-xxx. */
    private static final Pattern OPT_LINK =
            Pattern.compile("([A-Za-z0-9_:./%-]*configuration\\.md)#(opt-[A-Za-z0-9_-]*)");
    /** In-page links inside configuration.md itself: (#opt-xxx) or href="#opt-xxx". */
    private static final Pattern SELF_OPT_LINK = Pattern.compile("(?:\\(|href=\")#(opt-[A-Za-z0-9_-]*)");
    /** Inline or reference-style link to ../configuration.md (optionally with an anchor). */
    private static final Pattern VENDOR_REF_LINK = Pattern.compile(
            "(?:\\]\\(\\s*|href=\"|^\\s*\\[[^\\]]+\\]:\\s*)\\.\\./configuration\\.md(?:#[^)\"\\s]*)?",
            Pattern.MULTILINE);
    private static final Pattern FENCE_OPEN = Pattern.compile("^(\\s*)(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$");
    private static final Pattern LOOKUP = Pattern.compile("^\\$\\{[^}]+}$");

    private static Path root;
    private static Map<String, PluginOption> options;   // attribute/element name -> option
    private static List<Path> markdownFiles;

    /** One factory parameter as Log4j2 sees it. */
    private static final class PluginOption {
        final String name;
        final boolean element;
        /** The default rendered the way configuration.md writes it, or null when there is none. */
        final String expectedDefault;

        PluginOption(String name, boolean element, String expectedDefault) {
            this.name = name;
            this.element = element;
            this.expectedDefault = expectedDefault;
        }
    }

    /** One row of an option table in configuration.md. */
    private static final class DocRow {
        final int line;
        final String anchor;
        final String name;
        final String defaultCell;

        DocRow(int line, String anchor, String name, String defaultCell) {
            this.line = line;
            this.anchor = anchor;
            this.name = name;
            this.defaultCell = defaultCell;
        }
    }

    /** One fenced xml block. */
    private static final class XmlFence {
        final Path file;
        final int line;
        final String body;

        XmlFence(Path file, int line, String body) {
            this.file = file;
            this.line = line;
            this.body = body;
        }
    }

    @BeforeAll
    static void load() throws IOException {
        root = locateRepoRoot();
        options = readPluginOptions();
        markdownFiles = new ArrayList<>();
        Path readme = root.resolve("README.md");
        if (Files.isRegularFile(readme)) markdownFiles.add(readme);
        Path docs = root.resolve("docs");
        if (Files.isDirectory(docs)) {
            try (Stream<Path> s = Files.walk(docs)) {
                s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .forEach(markdownFiles::add);
            }
        }
    }

    // ------------------------------------------------------------------
    // (a) configuration.md documents exactly the factory's options
    // ------------------------------------------------------------------

    @Test
    void configurationReferenceDocumentsEveryOptionExactlyOnceWithTheCodeDefault() throws IOException {
        Path doc = root.resolve(CONFIG_DOC);
        assertTrue(Files.isRegularFile(doc), CONFIG_DOC + " is missing; it is the one reference of every <BatchHttp> option");
        List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
        List<DocRow> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        parseOptionTables(lines, rows, problems);
        assertFalse(rows.isEmpty() && problems.isEmpty(), CONFIG_DOC + " has no option table with header "
                + "| Option | Type | Default | Applies to | Description |");

        Map<String, List<DocRow>> byName = new LinkedHashMap<>();
        for (DocRow r : rows) byName.computeIfAbsent(r.name, k -> new ArrayList<>()).add(r);

        // Duplicate anchors anywhere in the page (a table row plus a stray <a id> would break links too).
        Map<String, List<Integer>> anchorLines = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = ANCHOR.matcher(lines.get(i));
            while (m.find()) anchorLines.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(i + 1);
        }
        anchorLines.forEach((a, at) -> {
            if (at.size() > 1) problems.add("duplicate anchor id \"" + a + "\" on lines " + at);
        });

        for (PluginOption o : options.values()) {
            List<DocRow> found = byName.getOrDefault(o.name, List.of());
            if (found.isEmpty()) {
                problems.add("undocumented option `" + o.name + "`: add a row with anchor opt-"
                        + o.name.toLowerCase(Locale.ROOT) + " and default "
                        + (o.expectedDefault == null ? NO_DEFAULT : "`" + o.expectedDefault + "`"));
                continue;
            }
            if (found.size() > 1) {
                problems.add("option `" + o.name + "` has " + found.size() + " rows (lines "
                        + found.stream().map(r -> String.valueOf(r.line)).collect(Collectors.joining(", "))
                        + "); keep exactly one");
            }
            for (DocRow r : found) {
                String expectedAnchor = "opt-" + o.name.toLowerCase(Locale.ROOT);
                if (!expectedAnchor.equals(r.anchor)) {
                    problems.add("line " + r.line + ": `" + o.name + "` has anchor \"" + r.anchor
                            + "\", expected \"" + expectedAnchor + "\"");
                }
                checkDefault(o, r, problems);
            }
        }
        for (Map.Entry<String, List<DocRow>> e : byName.entrySet()) {
            if (!options.containsKey(e.getKey())) {
                DocRow r = e.getValue().get(0);
                Optional<String> caseMatch = options.keySet().stream()
                        .filter(k -> k.equalsIgnoreCase(e.getKey())).findFirst();
                problems.add("line " + r.line + ": documented option `" + e.getKey() + "` does not exist in the "
                        + "@PluginFactory" + caseMatch.map(k -> " (did you mean `" + k + "`?)").orElse(""));
            }
        }
        failIfAny(CONFIG_DOC + " is out of step with BatchHttpAppender.createAppender", problems);
    }

    private static void checkDefault(PluginOption o, DocRow r, List<String> problems) {
        String cell = r.defaultCell.trim();
        String where = "line " + r.line + ": `" + o.name + "` default cell \"" + cell + "\"";
        if (o.expectedDefault == null) {
            if (!NO_DEFAULT.equals(cell)) {
                problems.add(where + " but the code has no default; write exactly " + NO_DEFAULT);
            }
            return;
        }
        if (NO_DEFAULT.equals(cell)) {
            problems.add(where + " but the code default is `" + o.expectedDefault + "`");
            return;
        }
        Matcher m = Pattern.compile("^`([^`]*)`(.*)$").matcher(cell);
        if (!m.matches()) {
            problems.add(where + " must start with the code default in backticks: `" + o.expectedDefault + "`");
            return;
        }
        if (!o.expectedDefault.equals(m.group(1).trim())) {
            problems.add(where + " documents `" + m.group(1) + "` but the code default is `" + o.expectedDefault + "`");
        }
    }

    /** Collect option rows from every table whose header is exactly TABLE_HEADER. */
    private static void parseOptionTables(List<String> lines, List<DocRow> rows, List<String> problems) {
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (line.startsWith("|") && splitRow(line).equals(TABLE_HEADER)) {
                i++;
                if (i < lines.size() && lines.get(i).trim().matches("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")) i++;
                while (i < lines.size() && lines.get(i).trim().startsWith("|")) {
                    List<String> cells = splitRow(lines.get(i).trim());
                    int lineNo = i + 1;
                    if (cells.size() != TABLE_HEADER.size()) {
                        problems.add("line " + lineNo + ": option row has " + cells.size() + " cells, expected "
                                + TABLE_HEADER.size() + " (escape pipes inside cells as \\|)");
                    } else {
                        Matcher m = OPTION_CELL.matcher(cells.get(0));
                        if (!m.matches()) {
                            problems.add("line " + lineNo + ": first cell \"" + cells.get(0)
                                    + "\" is not <a id=\"opt-<name>\"></a>`<name>`");
                        } else {
                            rows.add(new DocRow(lineNo, m.group(1), m.group(2).trim(), cells.get(2)));
                        }
                    }
                    i++;
                }
            } else {
                i++;
            }
        }
    }

    /** Split a markdown table row on unescaped pipes, dropping the outer empty cells. */
    private static List<String> splitRow(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < row.length() && row.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        if (!cells.isEmpty() && cells.get(0).isEmpty() && row.startsWith("|")) cells.remove(0);
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty() && row.endsWith("|")) cells.remove(cells.size() - 1);
        return cells;
    }

    // ------------------------------------------------------------------
    // (b) xml samples are well-formed and use real attributes / values
    // ------------------------------------------------------------------

    @Test
    void xmlSamplesAreWellFormedAndUseRealBatchHttpAttributesAndValues() throws Exception {
        Map<String, Set<String>> validValues = enumValuedAttributes();
        Set<String> attributeNames = options.values().stream()
                .filter(o -> !o.element).map(o -> o.name).collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> problems = new ArrayList<>();
        int fences = 0;
        for (Path md : markdownFiles) {
            for (XmlFence f : xmlFences(md)) {
                fences++;
                String where = rel(f.file) + ":" + f.line;
                Document doc;
                try {
                    doc = parseFragment(f.body);
                } catch (SAXParseException e) {
                    int bodyLine = e.getLineNumber() - 1; // the synthetic root sits on its own first line
                    problems.add(where + ": ```xml block is not well-formed XML"
                            + (bodyLine > 0 ? " (block line " + bodyLine + ")" : "") + ": " + e.getMessage());
                    continue;
                }
                NodeList appenders = doc.getElementsByTagName("BatchHttp");
                for (int i = 0; i < appenders.getLength(); i++) {
                    Element el = (Element) appenders.item(i);
                    NamedNodeMap attrs = el.getAttributes();
                    for (int a = 0; a < attrs.getLength(); a++) {
                        Node attr = attrs.item(a);
                        String name = attr.getNodeName();
                        String value = attr.getNodeValue();
                        if (!attributeNames.contains(name)) {
                            Optional<String> caseMatch = attributeNames.stream()
                                    .filter(n -> n.equalsIgnoreCase(name)).findFirst();
                            problems.add(where + ": <BatchHttp> attribute \"" + name + "\" is not a plugin attribute"
                                    + caseMatch.map(n -> " (did you mean \"" + n + "\"?)").orElse(""));
                            continue;
                        }
                        Set<String> allowed = validValues.get(name);
                        if (allowed != null && !LOOKUP.matcher(value.trim()).matches()
                                && !allowed.contains(value.trim().toLowerCase(Locale.ROOT))) {
                            problems.add(where + ": <BatchHttp " + name + "=\"" + value + "\"> is not a valid value; "
                                    + "the code accepts " + allowed + " (case-insensitive) or a ${...} lookup");
                        }
                    }
                }
            }
        }
        failIfAny("XML samples in the Markdown docs are inconsistent with the <BatchHttp> plugin (" + fences
                + " ```xml blocks scanned)", problems);
    }

    /**
     * The attributes the factory parses into a closed set, with the values it accepts (lower-cased;
     * all of them are parsed case-insensitively). Derived from the code: the BatchConfig enums the
     * factory feeds them to, Log4j2's own Level registry for diagnosticsLevel, and the vendor names
     * the factory's sink switch dispatches on.
     */
    private static Map<String, Set<String>> enumValuedAttributes() throws IOException {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("overflowPolicy", lower(BatchConfig.OverflowPolicy.values()));
        m.put("spillRecoveryMode", lower(BatchConfig.SpillRecoveryMode.values()));
        m.put("spillBacklogOrder", lower(BatchConfig.SpillBacklogOrder.values()));
        m.put("spillFormat", lower(BatchConfig.SpillFormat.values()));
        m.put("diagnosticsLevel", Arrays.stream(Level.values())
                .map(l -> l.name().toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new)));
        m.put("vendor", vendorNames());
        for (String k : m.keySet()) {
            assertTrue(options.containsKey(k), "enum-valued attribute " + k + " is no longer a plugin attribute; update this test");
        }
        return m;
    }

    private static Set<String> lower(Enum<?>[] values) {
        return Arrays.stream(values).map(e -> e.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Vendor names (with aliases) are string cases in the factory's private {@code buildSink}
     * switch, which reflection cannot see, so read them from the source. An unknown vendor
     * silently falls back to generic, which is exactly why a doc typo must be caught here.
     */
    private static Set<String> vendorNames() throws IOException {
        Path src = root.resolve("batch-http-log4j2/src/main/java/com/mulesoft/support/batchhttp/log4j2/BatchHttpAppender.java");
        assertTrue(Files.isRegularFile(src), "cannot read " + src + " to derive the vendor names");
        String code = Files.readString(src, StandardCharsets.UTF_8);
        int start = code.indexOf("private static LogSink buildSink(");
        assertTrue(start >= 0, "buildSink(...) not found in BatchHttpAppender.java; update this test");
        int end = code.indexOf("\n    }\n", start);
        String body = end > start ? code.substring(start, end) : code.substring(start);
        Set<String> names = new TreeSet<>();
        Matcher c = Pattern.compile("case\\s+\"([^\"]+)\"\\s*:").matcher(body);
        while (c.find()) names.add(c.group(1).toLowerCase(Locale.ROOT));
        for (String must : List.of("datadog", "splunk", "newrelic", "dynatrace", "elasticsearch", "generic")) {
            assertTrue(names.contains(must), "vendor \"" + must + "\" not found among buildSink cases " + names
                    + "; update this test");
        }
        return names;
    }

    /** Parse a fenced block as a fragment: XML declaration dropped, contents wrapped in a synthetic root. */
    private static Document parseFragment(String body) throws Exception {
        String stripped = body.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "");
        // Keep the line count stable so SAX line numbers map back to the block (root is line 1).
        String wrapped = "<doc-fragment-root>\n" + stripped + "\n</doc-fragment-root>";
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // Not namespace-aware: Mule snippets use prefixes declared on an outer <mule> root that a
        // fragment may omit. Well-formedness (tags, quoting, escaping) is still fully checked.
        f.setNamespaceAware(false);
        f.setValidating(false);
        f.setExpandEntityReferences(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException e) { }
            @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
        });
        return b.parse(new InputSource(new StringReader(wrapped)));
    }

    /** Fenced blocks whose info string's first word is {@code xml} (``` or ~~~, indented fences allowed). */
    private static List<XmlFence> xmlFences(Path md) throws IOException {
        List<String> lines = Files.readAllLines(md, StandardCharsets.UTF_8);
        List<XmlFence> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            Matcher open = FENCE_OPEN.matcher(lines.get(i));
            if (!open.matches()) {
                i++;
                continue;
            }
            String indent = open.group(1);
            String marker = open.group(2);
            boolean isXml = "xml".equalsIgnoreCase(open.group(3));
            int startLine = i + 1;
            StringBuilder body = new StringBuilder();
            i++;
            Pattern close = Pattern.compile("^\\s*" + Pattern.quote(String.valueOf(marker.charAt(0)))
                    + "{" + marker.length() + ",}\\s*$");
            while (i < lines.size() && !close.matcher(lines.get(i)).matches()) {
                String l = lines.get(i);
                body.append(l.startsWith(indent) ? l.substring(indent.length()) : l.stripLeading()).append('\n');
                i++;
            }
            i++; // skip the closing fence
            if (isXml) out.add(new XmlFence(md, startLine, body.toString()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // (c) links into the reference resolve
    // ------------------------------------------------------------------

    @Test
    void optionLinksResolveAndEveryVendorPageLinksTheReference() throws IOException {
        Path doc = root.resolve(CONFIG_DOC);
        assertTrue(Files.isRegularFile(doc), CONFIG_DOC + " is missing");
        Set<String> anchors = new LinkedHashSet<>();
        for (String l : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            Matcher m = ANCHOR.matcher(l);
            while (m.find()) anchors.add(m.group(1));
        }
        Path configAbs = doc.toAbsolutePath().normalize();

        List<String> problems = new ArrayList<>();
        int links = 0;
        for (Path md : markdownFiles) {
            List<String> lines = Files.readAllLines(md, StandardCharsets.UTF_8);
            boolean self = md.toAbsolutePath().normalize().equals(configAbs);
            for (int i = 0; i < lines.size(); i++) {
                String where = rel(md) + ":" + (i + 1);
                Matcher m = OPT_LINK.matcher(lines.get(i));
                while (m.find()) {
                    links++;
                    String target = m.group(1);
                    String anchor = m.group(2);
                    if (!target.contains("://")) {
                        Path resolved = md.getParent().resolve(target).toAbsolutePath().normalize();
                        if (!resolved.equals(configAbs)) {
                            problems.add(where + ": \"" + target + "#" + anchor + "\" points at " + rel(resolved)
                                    + ", not " + CONFIG_DOC);
                        }
                    }
                    if (!anchors.contains(anchor)) {
                        problems.add(where + ": link to " + CONFIG_DOC + "#" + anchor + " has no such anchor"
                                + suggestAnchor(anchor, anchors));
                    }
                }
                if (self) {
                    Matcher s = SELF_OPT_LINK.matcher(lines.get(i));
                    while (s.find()) {
                        links++;
                        if (!anchors.contains(s.group(1))) {
                            problems.add(where + ": in-page link #" + s.group(1) + " has no such anchor"
                                    + suggestAnchor(s.group(1), anchors));
                        }
                    }
                }
            }
        }

        Path vendors = root.resolve("docs/vendors");
        List<Path> vendorPages = markdownFiles.stream()
                .filter(p -> p.getParent() != null && p.getParent().toAbsolutePath().normalize()
                        .equals(vendors.toAbsolutePath().normalize()))
                .collect(Collectors.toList());
        for (Path page : vendorPages) {
            String text = Files.readString(page, StandardCharsets.UTF_8);
            if (!VENDOR_REF_LINK.matcher(text).find()) {
                problems.add(rel(page) + ": vendor page does not link ../configuration.md (the shared option reference)");
            }
        }
        failIfAny("Links into " + CONFIG_DOC + " are broken (" + links + " option links, "
                + vendorPages.size() + " vendor pages checked)", problems);
    }

    private static String suggestAnchor(String anchor, Set<String> anchors) {
        String lower = anchor.toLowerCase(Locale.ROOT);
        return anchors.contains(lower) && !lower.equals(anchor) ? " (anchors are lower-case: #" + lower + ")" : "";
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Every factory parameter, keyed by its log4j2.xml name, with its default rendered as
     * configuration.md writes it: the Java string form of the annotation default for the
     * parameter's type, or null for a String with an empty defaultString and for elements.
     */
    private static Map<String, PluginOption> readPluginOptions() {
        Method factory = Arrays.stream(BatchHttpAppender.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PluginFactory.class))
                .findFirst()
                .orElse(null);
        assertNotNull(factory, "BatchHttpAppender has no @PluginFactory method");
        Map<String, PluginOption> out = new LinkedHashMap<>();
        for (Parameter p : factory.getParameters()) {
            PluginAttribute attr = p.getAnnotation(PluginAttribute.class);
            PluginElement el = p.getAnnotation(PluginElement.class);
            PluginOption o;
            if (attr != null) {
                o = new PluginOption(attr.value(), false, attributeDefault(p.getType(), attr));
            } else if (el != null) {
                o = new PluginOption(el.value(), true, null);
            } else {
                continue; // e.g. @PluginConfiguration — not a log4j2.xml option
            }
            if (out.put(o.name, o) != null) fail("factory declares option " + o.name + " twice");
        }
        assertFalse(out.isEmpty(), "@PluginFactory declares no options");
        return Collections.unmodifiableMap(out);
    }

    private static String attributeDefault(Class<?> type, PluginAttribute a) {
        if (type == int.class || type == Integer.class) return Integer.toString(a.defaultInt());
        if (type == long.class || type == Long.class) return Long.toString(a.defaultLong());
        if (type == boolean.class || type == Boolean.class) return Boolean.toString(a.defaultBoolean());
        if (type == String.class) return a.defaultString().isEmpty() ? null : a.defaultString();
        fail("@PluginAttribute " + a.value() + " has type " + type.getName()
                + " which this test does not know how to render; extend attributeDefault()");
        return null;
    }

    /** The reactor root: the directory whose pom.xml lists batch-http-log4j2 as a module. */
    private static Path locateRepoRoot() throws IOException {
        String mmpd = System.getProperty("maven.multiModuleProjectDirectory");
        if (mmpd != null && isReactorRoot(Paths.get(mmpd))) return Paths.get(mmpd).toAbsolutePath().normalize();
        for (Path d = Paths.get("").toAbsolutePath(); d != null; d = d.getParent()) {
            if (isReactorRoot(d)) return d.normalize();
        }
        fail("could not locate the repository root (a pom.xml listing <module>batch-http-log4j2</module>) "
                + "from " + Paths.get("").toAbsolutePath());
        return null;
    }

    private static boolean isReactorRoot(Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        return Files.isRegularFile(pom)
                && Files.readString(pom, StandardCharsets.UTF_8).contains("<module>batch-http-log4j2</module>");
    }

    private static String rel(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString() : abs.toString();
    }

    private static void failIfAny(String heading, List<String> problems) {
        if (!problems.isEmpty()) {
            fail(heading + " — " + problems.size() + " problem(s):\n  - " + String.join("\n  - ", problems));
        }
    }
}
