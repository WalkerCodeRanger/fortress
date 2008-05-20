/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************/

package com.sun.fortress.interpreter.rewrite;

import static com.sun.fortress.interpreter.evaluator.InterpreterBug.bug;

import java.util.List;

import com.sun.fortress.nodes.AbstractNode;
import com.sun.fortress.nodes.FnRef;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.NodeUpdateVisitor;
import com.sun.fortress.nodes.StaticArg;
import com.sun.fortress.nodes.TraitType;
import com.sun.fortress.nodes.VarRef;
import com.sun.fortress.nodes.VarType;
import com.sun.fortress.nodes._RewriteFnRef;

public class RewriteInPresenceOfTypeInfoVisitor extends NodeUpdateVisitor {

    public static RewriteInPresenceOfTypeInfoVisitor Only = new RewriteInPresenceOfTypeInfoVisitor();

    public Node visit(Node n) {
        return n.accept(this);
    }

    @Override
    public Node forFnRef(FnRef fr) {
        List<Id> fns = fr.getFns();
        List<StaticArg> sargs = fr.getStaticArgs();
        Id idn = fns.get(0);

        if (fns.size() != 1) {
            return bug("Overloaded function in FnRef " + fr.toStringVerbose());
        }

        if (sargs.size() > 0)
            return (new _RewriteFnRef(fr.getSpan(),
                fr.isParenthesized(),
                                           new VarRef(idn.getSpan(),
                                                      idn),
                sargs)).accept(this);

        else
            return (new VarRef(idn.getSpan(), fr.isParenthesized(), idn)).accept(this);
    }

    @Override
    public Node forId(Id q) {
        if (q.getApi().isSome()) {
            q = new Id(q.getSpan(), q.getText());
        }
        // Recursive bits to visit?
        return super.forId(q);
    }

    @Override
    public Node forTraitType(TraitType it) {
        if (it.getArgs().size() == 0) {
            return (new VarType(it.getSpan(), it.getName())).accept(this);
        }
        return super.forTraitType(it);
    }

    private RewriteInPresenceOfTypeInfoVisitor() {

    }

}