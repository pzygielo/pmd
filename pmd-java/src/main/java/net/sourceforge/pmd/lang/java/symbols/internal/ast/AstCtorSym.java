/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.symbols.internal.ast;

import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JConstructorSymbol;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeSystem;

/**
 * @author Clément Fournier
 */
final class AstCtorSym extends AbstractAstExecSymbol<ASTConstructorDeclaration> implements JConstructorSymbol {


    AstCtorSym(ASTConstructorDeclaration node, AstSymFactory factory, JClassSymbol owner) {
        super(node, factory, owner);
    }

    @Override
    protected JTypeMirror makeReturnType(Substitution subst) {
        TypeSystem ts = getTypeSystem();
        return ts.declaration(getEnclosingClass()).subst(subst);
    }
}
