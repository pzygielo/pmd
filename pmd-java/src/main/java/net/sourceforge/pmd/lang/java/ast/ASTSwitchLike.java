/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.ast;

import java.util.Iterator;
import java.util.Set;

import org.apache.commons.lang3.EnumUtils;

import net.sourceforge.pmd.lang.ast.NodeStream;


/**
 * Common supertype for {@linkplain ASTSwitchStatement switch statements}
 * and {@linkplain ASTSwitchExpression switch expressions}. Their grammar
 * is identical, and is described below. The difference is that switch
 * expressions need to be exhaustive.
 *
 * <pre class="grammar">
 *
 * SwitchLike        ::= {@link ASTSwitchExpression SwitchExpression}
 *                     | {@link ASTSwitchStatement SwitchStatement}
 *
 *                   ::= "switch" "(" {@link ASTExpression Expression} ")" SwitchBlock
 *
 * SwitchBlock       ::= SwitchArrowBlock | SwitchNormalBlock
 *
 * SwitchArrowBlock  ::= "{" {@link ASTSwitchArrowBranch SwitchArrowBranch}* "}"
 * SwitchNormalBlock ::= "{" {@linkplain ASTSwitchFallthroughBranch SwitchFallthroughBranch}* "}"
 *
 * </pre>
 */
public interface ASTSwitchLike extends JavaNode, Iterable<ASTSwitchBranch> {

    /**
     * Returns true if this switch has a {@code default} case.
     */
    default boolean hasDefaultCase() {
        ASTSwitchBranch last = getBranches().last();
        return last != null && last.getLabel().isDefault();
    }


    /**
     * Returns a stream of all branches of this switch.
     */
    default NodeStream<ASTSwitchBranch> getBranches() {
        return children(ASTSwitchBranch.class);
    }


    /**
     * Gets the expression tested by this switch.
     * This is the expression between the parentheses.
     */
    default ASTExpression getTestedExpression() {
        return (ASTExpression) jjtGetChild(0);
    }


    /**
     * Returns true if this switch statement tests an expression
     * having an enum type and all the constants of this type
     * are covered by a switch case. Returns false if the type of
     * the tested expression could not be resolved.
     */
    default boolean isExhaustiveEnumSwitch() {
        ASTExpression expression = getTestedExpression();

        if (expression.getType() == null) {
            return false;
        }

        if (Enum.class.isAssignableFrom(expression.getType())) {

            @SuppressWarnings("unchecked")
            Set<String> constantNames = EnumUtils.getEnumMap((Class<? extends Enum>) expression.getType()).keySet();

            for (ASTSwitchBranch branch : this) {
                // since this is an enum switch, the labels are necessarily
                // the simple name of some enum constant.

                constantNames.remove(branch.getLabel().getFirstDescendantOfType(ASTName.class).getImage());

            }

            return constantNames.isEmpty();
        }

        return false;
    }


    @Override
    default Iterator<ASTSwitchBranch> iterator() {
        return children(ASTSwitchBranch.class).iterator();
    }
}
