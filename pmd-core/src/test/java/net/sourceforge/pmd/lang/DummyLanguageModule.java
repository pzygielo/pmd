/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.checkerframework.checker.nullness.qual.NonNull;

import net.sourceforge.pmd.cpd.AnyCpdLexer;
import net.sourceforge.pmd.cpd.CpdCapableLanguage;
import net.sourceforge.pmd.cpd.CpdLanguageProperties;
import net.sourceforge.pmd.cpd.CpdLexer;
import net.sourceforge.pmd.lang.ast.DummyNode;
import net.sourceforge.pmd.lang.ast.DummyNode.DummyRootNode;
import net.sourceforge.pmd.lang.ast.ParseException;
import net.sourceforge.pmd.lang.ast.Parser;
import net.sourceforge.pmd.lang.ast.Parser.ParserTask;
import net.sourceforge.pmd.lang.ast.impl.javacc.MalformedSourceException;
import net.sourceforge.pmd.lang.document.Chars;
import net.sourceforge.pmd.lang.document.FileLocation;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.document.TextPos2d;
import net.sourceforge.pmd.lang.document.TextRegion;
import net.sourceforge.pmd.lang.impl.SimpleLanguageModuleBase;
import net.sourceforge.pmd.lang.metrics.LanguageMetricsProvider;
import net.sourceforge.pmd.lang.metrics.Metric;
import net.sourceforge.pmd.lang.rule.xpath.impl.XPathFunctionDefinition;
import net.sourceforge.pmd.lang.rule.xpath.impl.XPathHandler;
import net.sourceforge.pmd.reporting.RuleViolation;
import net.sourceforge.pmd.reporting.ViolationDecorator;
import net.sourceforge.pmd.util.CollectionUtil;

/**
 * Dummy language used for testing PMD.
 */
public class DummyLanguageModule extends SimpleLanguageModuleBase implements CpdCapableLanguage {

    public static final String NAME = "Dummy";
    public static final String TERSE_NAME = "dummy";
    private static final String PARSER_THROWS = "parserThrows";

    public static final String CPD_THROW_MALFORMED_SOURCE_EXCEPTION = ":throw_malformed_source_exception:";
    public static final String CPD_THROW_LEX_EXCEPTION = ":throw_lex_source_exception:";
    public static final String CPD_THROW_OTHER_EXCEPTION = ":throw_other_exception:";

    public DummyLanguageModule() {
        super(LanguageMetadata.withId(TERSE_NAME).name(NAME).extensions("dummy", "txt")
                              .addVersion("1.0")
                              .addVersion("1.1")
                              .addVersion("1.2")
                              .addVersion("1.3")
                              .addVersion("1.4")
                              .addVersion("1.5", "5")
                              .addVersion("1.6", "6")
                              .addDefaultVersion("1.7", "7")
                              .addVersion(PARSER_THROWS)
                              .addVersion("1.8", "8"), new Handler());
    }

    public static DummyLanguageModule getInstance() {
        return (DummyLanguageModule) Objects.requireNonNull(LanguageRegistry.PMD.getLanguageByFullName(NAME));
    }

    @Override
    public LanguagePropertyBundle newPropertyBundle() {
        LanguagePropertyBundle bundle = super.newPropertyBundle();
        bundle.definePropertyDescriptor(CpdLanguageProperties.CPD_ANONYMIZE_LITERALS);
        bundle.definePropertyDescriptor(CpdLanguageProperties.CPD_ANONYMIZE_IDENTIFIERS);
        return bundle;
    }

    @Override
    public CpdLexer createCpdLexer(LanguagePropertyBundle bundle) {
        CpdLexer base = new AnyCpdLexer();
        return (doc, tokens) -> {
            Chars text = doc.getText();
            int offset = text.indexOf(CPD_THROW_LEX_EXCEPTION, 0);
            if (offset != -1) {
                TextPos2d lc = doc.lineColumnAtOffset(offset);
                throw tokens.makeLexException(lc.getLine(), lc.getColumn(), "test exception", null);
            }
            offset = text.indexOf(CPD_THROW_MALFORMED_SOURCE_EXCEPTION, 0);
            if (offset != -1) {
                FileLocation lc = doc.toLocation(TextRegion.caretAt(offset));
                throw new MalformedSourceException("test exception", null, lc);
            }
            offset = text.indexOf(CPD_THROW_OTHER_EXCEPTION, 0);
            if (offset != -1) {
                throw new IllegalArgumentException("test exception");
            }
            base.tokenize(doc, tokens);
        };
    }

    public LanguageVersion getVersionWhereParserThrows() {
        return getVersion(PARSER_THROWS);
    }

    public static class Handler extends AbstractPmdLanguageVersionHandler {

        @Override
        public Parser getParser() {
            return task -> {
                if (task.getLanguageVersion().getVersion().equals(PARSER_THROWS)) {
                    throw new ParseException("ohio");
                }
                return readLispNode(task);
            };
        }

        @Override
        public ViolationDecorator getViolationDecorator() {
            return (node, data) -> data.put(RuleViolation.PACKAGE_NAME, "foo");
        }

        @Override
        public XPathHandler getXPathHandler() {
            return XPathHandler.getHandlerForFunctionDefs(imageIsFunction());
        }

        @Override
        public LanguageMetricsProvider getLanguageMetricsProvider() {
            return () -> CollectionUtil.setOf(
                    Metric.of((node, options) -> 1, (node) -> node,
                    "Constant value metric", "const1"));
        }
    }

    /**
     * Creates a tree of nodes that corresponds to the nesting structures
     * of parentheses in the text. The image of each node is also populated.
     * This is useful to create non-trivial trees with all the relevant
     * data (eg coordinates) set properly.
     *
     * Eg {@code (a(b)x(c))} will create a tree with a node "a", with two
     * children "b" and "c". "x" is ignored. The node "a" is not the root
     * node, it has a {@link DummyRootNode} as parent, whose image is "".
     */
    public static DummyRootNode readLispNode(ParserTask task) {
        TextDocument document = task.getTextDocument();
        final DummyRootNode root = new DummyRootNode().withTaskInfo(task);
        root.setRegion(document.getEntireRegion());

        DummyNode top = root;
        int lastNodeStart = 0;
        Chars text = document.getText();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                DummyNode node;
                if (text.startsWith("#text", i + 1)) {
                    node = new DummyNode.DummyTextNode();
                } else if (text.startsWith("#comment", i + 1)) {
                    node = new DummyNode.DummyCommentNode();
                } else {
                    node = new DummyNode();
                }
                node.setParent(top);
                top.addChild(node, top.getNumChildren());
                // setup coordinates, temporary (will be completed when node closes)
                node.setRegion(TextRegion.caretAt(i));

                // cut out image
                if (top.getImage() == null) {
                    // image may be non null if this is not the first child of 'top'
                    // eg in (a(b)x(c)), the image of the parent is set to "a".
                    // When we're processing "(c", we ignore "x".
                    String image = text.substring(lastNodeStart, i);
                    top.setImage(image);
                }
                lastNodeStart = i + 1;
                // node is the top of the stack now
                top = node;
            } else if (c == ')') {
                if (top == null) {
                    throw new ParseException("Unbalanced parentheses: " + text);
                }

                top.setRegion(TextRegion.fromBothOffsets(top.getTextRegion().getStartOffset(), i + 1));

                if (top.getImage() == null) {
                    // cut out image (if node doesn't have children it hasn't been populated yet)
                    String image = text.substring(lastNodeStart, i);
                    top.setImage(image);
                    lastNodeStart = i + 1;
                }
                top = top.getParent();
            }
        }
        if (top != root) {
            throw new ParseException("Unbalanced parentheses: " + text);
        }
        return root;
    }

    @NonNull
    public static XPathFunctionDefinition imageIsFunction() {
        return new XPathFunctionDefinition("imageIs", DummyLanguageModule.getInstance()) {
            @Override
            public Type[] getArgumentTypes() {
                return new Type[] {Type.SINGLE_STRING};
            }

            @Override
            public Type getResultType() {
                return Type.SINGLE_BOOLEAN;
            }

            @Override
            public FunctionCall makeCallExpression() {
                return (contextNode, arguments) -> StringUtils.equals(arguments[0].toString(), contextNode.getImage());
            }
        };
    }
}
