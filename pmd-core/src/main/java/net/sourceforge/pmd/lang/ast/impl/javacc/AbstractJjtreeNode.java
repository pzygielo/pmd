/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.ast.impl.javacc;

import net.sourceforge.pmd.annotation.Experimental;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.ast.impl.AbstractNode;
import net.sourceforge.pmd.lang.document.Chars;
import net.sourceforge.pmd.lang.document.FileLocation;
import net.sourceforge.pmd.lang.document.TextRegion;

/**
 * Base class for node produced by JJTree. JJTree specific functionality
 * present on the API of {@link Node} and {@link AbstractNode} will be
 * moved here for 7.0.0.
 *
 * <p>This is experimental because it's expected to change for 7.0.0 in
 * unforeseeable ways. Don't use it directly, use the node interfaces.
 */
@Experimental
public abstract class AbstractJjtreeNode<B extends AbstractJjtreeNode<B, N>, N extends JjtreeNode<N>> extends AbstractNode<B, N> implements JjtreeNode<N> {
    protected final int id;
    private JavaccToken firstToken;
    private JavaccToken lastToken;

    private String image;

    /**
     * The id is an index in the constant names array generated by jjtree,
     * it must be set to some value that depends on the node type, not some
     * arbitrary "1" or "2", and not necessarily a unique value.
     */
    protected AbstractJjtreeNode(int id) {
        super();
        this.id = id;
    }

    @Override
    // @Deprecated // todo deprecate, will change tree dump tests
    public String getImage() {
        return image;
    }

    protected void setImage(String image) {
        this.image = image;
    }

    @Override
    public final Chars getText() {
        return getTextDocument().sliceText(getTextRegion());
    }

    @Override
    public final TextRegion getTextRegion() {
        return TextRegion.fromBothOffsets(getFirstToken().getStartOffset(),
                                          getLastToken().getEndOffset());
    }

    @Override
    public FileLocation getReportLocation() {
        return getTextDocument().toLocation(getTextRegion());
    }

    @Override
    public final int compareLocation(Node other) {
        if (other instanceof JjtreeNode<?>) {
            return getTextRegion().compareTo(((JjtreeNode<?>) other).getTextRegion());
        }
        return super.compareLocation(other);
    }

    /**
     * This method is called after the node has been made the current node. It
     * indicates that child nodes can now be added to it.
     */
    protected void jjtOpen() {
        // to be overridden
    }

    /**
     * This method is called after all the child nodes have been added.
     */
    protected void jjtClose() {
        // to be overridden
    }

    @Override // override to make it protected
    protected void addChild(B child, int index) {
        super.addChild(child, index);
    }


    @Override
    protected void insertChild(B child, int index) {
        super.insertChild(child, index);
        fitTokensToChildren(index);
    }

    /**
     * Ensures that the first (resp. last) token of this node is before
     * (resp. after) the first (resp. last) token of the child at the
     * given index. The index
     */
    protected void fitTokensToChildren(int index) {
        if (index == 0) {
            enlargeLeft((B) getChild(index));
        }
        if (index == getNumChildren()) {
            enlargeRight((B) getChild(index));
        }
    }

    private void enlargeLeft(B child) {
        JavaccToken thisFst = this.getFirstToken();
        JavaccToken childFst = child.getFirstToken();

        if (childFst.compareTo(thisFst) < 0) {
            this.setFirstToken(childFst);
        }
    }

    private void enlargeRight(B child) {
        JavaccToken thisLast = this.getLastToken();
        JavaccToken childLast = child.getLastToken();

        if (childLast.compareTo(thisLast) > 0) {
            this.setLastToken(childLast);
        }
    }

    @Override
    public JavaccToken getFirstToken() {
        return firstToken;
    }

    @Override
    public JavaccToken getLastToken() {
        return lastToken;
    }

    // the super methods query line & column, which we want to avoid

    protected void setLastToken(JavaccToken token) {
        this.lastToken = token;
    }

    protected void setFirstToken(JavaccToken token) {
        this.firstToken = token;
    }

    /**
     * This toString implementation is only meant for debugging purposes.
     */
    @Override
    public String toString() {
        FileLocation loc = getReportLocation();
        return "[" + getXPathNodeName() + ":" + loc.getBeginLine() + ":" + loc.getBeginColumn() + "]" + getText();
    }
}
