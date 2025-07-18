/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.rule.codestyle;

import java.util.regex.Pattern;

import net.sourceforge.pmd.lang.java.ast.ASTVariableId;
import net.sourceforge.pmd.properties.PropertyDescriptor;


/**
 * Enforces a naming convention for local variables and other locally scoped variables.
 *
 * @author Clément Fournier
 * @since 6.6.0
 */
public final class LocalVariableNamingConventionsRule extends AbstractNamingConventionRule<ASTVariableId> {

    // These are not exhaustive, but are chosen to be the most useful, for a start

    private final PropertyDescriptor<Pattern> localVarRegex = defaultProp("localVar", "non-final local variable").build();
    private final PropertyDescriptor<Pattern> finalVarRegex = defaultProp("finalVar", "final local variable").build();

    private final PropertyDescriptor<Pattern> exceptionBlockParameterRegex = defaultProp("catchParameter", "exception block parameter").build();


    public LocalVariableNamingConventionsRule() {
        super(ASTVariableId.class);

        definePropertyDescriptor(localVarRegex);
        definePropertyDescriptor(finalVarRegex);
        definePropertyDescriptor(exceptionBlockParameterRegex);
    }



    @Override
    public Object visit(ASTVariableId node, Object data) {
        if (node.isUnnamed()) {
            // unnamed variables do not have to match the regexes.
            return null;
        }

        if (node.isExceptionBlockParameter()) {
            checkMatches(node, exceptionBlockParameterRegex, data);
        } else if (node.isLocalVariable()) {
            checkMatches(node, node.isFinal() ? finalVarRegex : localVarRegex, data);
        }

        return data;
    }


    @Override
    String defaultConvention() {
        return CAMEL_CASE;
    }


    @Override
    String nameExtractor(ASTVariableId node) {
        return node.getName();
    }


    @Override
    String kindDisplayName(ASTVariableId node, PropertyDescriptor<Pattern> descriptor) {
        if (node.isExceptionBlockParameter()) {
            return "exception block parameter";
        } else if (node.isLocalVariable()) {
            return node.isFinal() ? "final local variable" : "local variable";
        }

        throw new UnsupportedOperationException("This rule doesn't handle this case");
    }
}
