/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.apex.ast;

import com.google.summit.ast.Node;

public final class ASTJavaMethodCallExpression extends AbstractApexNode.Single<Node> {

    ASTJavaMethodCallExpression(Node javaMethodCallExpression) {
        super(javaMethodCallExpression);
    }


    @Override
    protected <P, R> R acceptApexVisitor(ApexVisitor<? super P, ? extends R> visitor, P data) {
        return visitor.visit(this, data);
    }
}
