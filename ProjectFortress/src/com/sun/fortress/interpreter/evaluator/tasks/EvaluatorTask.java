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

import com.sun.fortress.interpreter.nodes.CompilationUnit;
import java.util.List;
import EDU.oswego.cs.dl.util.concurrent.FJTask;
import EDU.oswego.cs.dl.util.concurrent.FJTaskRunnerGroup;
import com.sun.fortress.interpreter.drivers.Driver;

public class EvaluatorTask extends BaseTask {
    
    CompilationUnit p;

    boolean runTests = false;

    List<String> args;

    public EvaluatorTask(CompilationUnit prog, boolean tests, List<String> args_) {
        super();
        p = prog;
        runTests = tests;
        args = args_;
    }

    public void run() {
        try {
                Driver.runProgramTask(p, runTests, args);
            } catch (Throwable e) {
                causedException = true;
                err = e;
                System.err.println("Got exception: " + e);
            }
    }

}

