
/********************************************************************************
    Copyright 2007 Sun Microsystems, Inc., 
    4150 Network Circle, Santa Clara, California 95054, U.S.A. 
    All rights reserved.

    U.S. Government Rights - Commercial software. 
    Government users are subject to the Sun Microsystems, Inc. standard 
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered 
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
********************************************************************************/


package com.sun.fortress.interpreter.evaluator.tasks;

import com.sun.fortress.interpreter.nodes.Expr;
import com.sun.fortress.interpreter.evaluator.values.FGenerator;
import com.sun.fortress.interpreter.evaluator.Evaluator;

public class ForLoopTask extends BaseTask {
    FGenerator fgen;

    Expr body;

    Evaluator eval;

    public void run() {
        if (fgen.isSequential() || fgen.hasOne()) {
            while (fgen.update(body, new Evaluator(eval, body))) {
          /* update does all the work. */
	    }
        } else {
            // coInvoke enqueues its first argument, then executes its
            // second argument. This apparently backwards call should
            // preserve apparent sequential semantics in the absence of
            // steals.
            coInvoke(new ForLoopTask(fgen.secondHalf(), body, eval),
                    new ForLoopTask(fgen.firstHalf(), body, eval));
        }
    }

    public ForLoopTask(FGenerator f, Expr b, Evaluator e) {
        super();
        fgen = f;
        body = b;
        eval = e;
    }
}
