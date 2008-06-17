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

package com.sun.fortress.interpreter.evaluator.values;

import static com.sun.fortress.exceptions.InterpreterBug.bug;
import static com.sun.fortress.exceptions.ProgramError.error;
import static com.sun.fortress.exceptions.ProgramError.errorMsg;
import static com.sun.fortress.exceptions.UnificationError.unificationError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.sun.fortress.exceptions.FortressException;
import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.interpreter.evaluator.types.FTypeRest;
import com.sun.fortress.interpreter.glue.Glue;
import com.sun.fortress.interpreter.glue.IndexedArrayWrapper;
import com.sun.fortress.interpreter.glue.WellKnownNames;
import com.sun.fortress.useful.HasAt;
import com.sun.fortress.useful.Useful;

public abstract class NonPrimitive extends Simple_fcn {

    static final List<FValue> VOID_ARG = Collections.singletonList((FValue)FVoid.V);

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.evaluator.values.Simple_fcn#at()
     */
    @Override
    public String at() {
        return getAt().at();
    }

    protected abstract HasAt getAt();

    NonPrimitive(Environment within) {
        super(within);
    }

    private List<Parameter> params;

    private boolean lastParamIsRest;

    private volatile List<FType> cachedDomain;

    protected boolean hasRest() {
        return lastParamIsRest;
    }

    /**
     * @param params
     *            The params to set.
     */
    public final void setParams(List<Parameter> params) {
        if (this.params != null) {
            bug(this.getAt(),
                errorMsg("Attempted second set of constructor/function/method params of ",
                         this, " to ", Useful.listInParens(params)));
        }

        params = adjustParameterList(params);

        this.params = params;
        lastParamIsRest = params.size() > 0
                && (params.get(params.size() - 1).getType() instanceof FTypeRest);
        setValueType();
    }

    protected List<Parameter> adjustParameterList(List<Parameter> params2) {
        return params2;
    }

    abstract protected void setValueType();

    /**
     * @return Returns the params.
     */
    public List<Parameter> getParameters() {
        if (params==null)
            return bug(getAt(),errorMsg("getParams of NonPrimitive ",this));
        return params;
    }

    @Override
    public List<FType> getDomain() {
        if (cachedDomain == null) {
            synchronized (this) {
                if (cachedDomain == null) {
//                    if (this instanceof FunctionalMethod
//                            && ((FunctionalMethod) this).getSelfParameterType()
//                                    .toString().contains("ParRange"))
//                        System.err.println("getDomain of " + this);
                    List<FType> l = typeListFromParameters(getParameters());
                    cachedDomain = l;
                }
            }
        }
        return cachedDomain;
    }

    public static List<FValue> stripAsIf(List<FValue> args) {
        List<FValue> res = new ArrayList(args.size());
        for (FValue v : args) {
            if (v instanceof FAsIf) {
                res.add(((FAsIf)v).getValue());
            } else {
                res.add(v);
            }
        }
        return res;
    }

    /**
     * Take passed-in parameters, type check them.
     * Intended to be called from NativeApp.
     * Do not bother calling this if you also call buildEnvFromParams.
     */
    public List<FValue> typecheckParams(List<FValue> args, HasAt loc) {
        args = fixupArgCount(args,loc);
        Iterator<FValue> argsIter = args.iterator();
        Iterator<Parameter> paramsIter = params.iterator();
        boolean asif = false;   // Need to strip asif?  Avoid if not.
        for (int i = 1; paramsIter.hasNext(); i++) {
            Parameter param = paramsIter.next();
            FType paramType = param.getType();
            if (paramType instanceof FTypeRest) {
                FType restType = ((FTypeRest)paramType).getType();
                for (; argsIter.hasNext(); i++) {
                    FValue arg = argsIter.next();
                    if (arg instanceof FAsIf) asif = true;
                    if (!restType.typeMatch(arg)) {
                        error(loc, within,
                              errorMsg("Closure/Constructor for ",
                                       getAt().stringName(),
                                       " rest parameter ", i, " (",
                                       param.getName(),
                                       ":", restType,
                                       "...) got type ", arg.type()));
                    }
                }
            } else {
                // Usual case for the loop.
                FValue arg = argsIter.next();
                if (arg instanceof FAsIf) asif = true;
                if (!paramType.typeMatch(arg)) {
                    error(loc, within,
                          errorMsg("Closure/Constructor for ",
                                   getAt().stringName(),
                                   " parameter ", i, " (",
                                   param.getName(), ":",
                                   param.getType(), ") got type ",
                                   arg.type()));
                }
            }
        }
        if (asif)
            return stripAsIf(args);
        else
            return args;
    }

    /**
     * Build environment for evaluation of closure.
     * Intended to be called from Closure.
     * @throws Error
     */
    public Environment buildEnvFromParams(List<FValue> args, HasAt loc)
            throws Error {
        Environment env = within.extendAt(getAt());
        return buildEnvFromParams(args, env, loc);
    }

    public Environment buildEnvFromEnvAndParams(Environment env, List<FValue> args, HasAt loc)
            throws Error {
        env = env.extendAt(getAt());
        return buildEnvFromParams(args, env, loc);
    }

    public Environment buildEnvFromParams(List<FValue> args, Environment env,
            HasAt loc) throws Error {
        // TODO Here is where we deal with rest parameters.
        args = fixupArgCount(args,loc);
        Iterator<FValue> argsIter = args.iterator();
        FValue arg = null;
        int i = 0;
        for (Parameter param : params) {
            FType paramType = param.getType();
            if (paramType instanceof FTypeRest) {
                // Finish processing args in here (we just saw the last
                // parameter)

                // Find and invoke array1[\ T, size1 \] ()
                String genericName = WellKnownNames.varargsFactoryName;
                int[] natParams = new int[1];
                natParams[0] = args.size() - i;

                Simple_fcn f = Glue.instantiateGenericConstructor(env,
                        genericName, ((FTypeRest) paramType).getType(),
                        natParams, loc);

                FValue theArray = f
                        .apply(Collections.<FValue> emptyList(), loc, env);
                if (!(theArray instanceof FObject))
                    return bug(loc,errorMsg(f," returned non-FObject ",theArray));
                // Use a wrapper to simplify our life
                IndexedArrayWrapper iaw = new IndexedArrayWrapper(theArray, loc);
                int j = 0;
                while (argsIter.hasNext()) {
                    arg = argsIter.next();
                    iaw.put(arg.getValue(), j);  // strip asif
                    j++;
                }
                // Do the copy.
                env.putValue(param.getName(), theArray);
            } else {
                // Usual case for the loop.
                arg = argsIter.next();
                i++;
                if (!paramType.typeMatch(arg)) {
                    unificationError(loc, env,
                          errorMsg("Closure/Constructor for ",
                                   getAt().stringName(),
                                   " param ", i, " (",
                                   param.getName(), ":",
                                   paramType, ") got type ",
                                   arg.type(), " with arg ", arg));
                }
                arg = arg.getValue(); // Strip asif
                try {
                    if (param.getMutable()) {
                        env.putValueUnconditionally(param.getName(), arg,
                                                    param.getType());
                    } else {
                        env.putValueUnconditionally(param.getName(), arg);
                    }
                } catch (FortressException ex) {
                    throw ex.setContext(loc,env);
                }
            }

        }
        return env;
    }

    /**
     * Return fixed up arguments; throws ProgramError if different lengths.
     */
    public List<FValue> fixupArgCount(List<FValue> args0, HasAt loc) {
        List<FValue> args = fixupArgCount(args0);
        if (args==null) {
            error(loc,
                  errorMsg("Incorrect number of arguments, expected ",
                           Useful.listInParens(params), ", got ",
                           Useful.listInParens(args0)));
        }
        return args;
    }

    /**
     * @param args
     * @return fixed up arguments, or null if different lengths.
     */
    public List<FValue> fixupArgCount(List<FValue> args) {
        if (this.params==null) {
            bug(this.getAt(),
                "Calling fixupArgCount on "+getAt().stringName()+
                " with null params");
        }
        if (args.size() == params.size()) return args;
        if (hasRest() && args.size() + 1 >= params.size()) {
            return args;
        }
        if (params.size()==1) {
            /* Obscure screw case: a type parameter was instantiated
             * with void / tuple, or we declared a single parameter of
             * void or tuple type ().  This is satisfied by a 0-ary
             * application.  Rather than checking thoroughly, we
             * return a singleton void and let the enclosing test
             * catch it. */
            if (args.size()==0) return VOID_ARG;
            return Collections.singletonList((FValue)FTuple.make(args));
        }
        return null;
    }
}
