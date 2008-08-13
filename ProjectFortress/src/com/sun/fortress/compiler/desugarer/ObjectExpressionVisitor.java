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

package com.sun.fortress.compiler.desugarer;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import com.sun.fortress.compiler.typechecker.TraitTable;
import com.sun.fortress.compiler.typechecker.TypeEnv;
import com.sun.fortress.exceptions.DesugarerError;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.ExprFactory;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;

import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;


// TODO: TypeEnv does not handle OpRef and DimRef
// TODO: Remove the turnOnTypeChecker in shell under desugar phase to false
// TODO: Getter/Setter is turned off, because it corrupts TypeEnvAtNode
//       data structure; Need to figure out how to get around that
// TODO: TypeChecker by default is turned off, which is problematic, because
//       when it's turned off, it does not create typeEnvAtNode

public class ObjectExpressionVisitor extends NodeUpdateVisitor {
    private List<ObjectDecl> newObjectDecls;
    // A map mapping from an object expr to a pair of params that are 
    // containers of mutable varRefs captured by the object expr; 
    // this pair gets updated when entering and leaving ObjectDecl (first
    // element in pair) and LocalVarDecl (second element), which are the only
    // places that can introduce new mutable vars captured by object expr 
    private Map<Span, Pair<Param,Param>> mutableParamsToLiftedObj;
    // Similar map as the mutableParamsToLiftedObj, except that they are 
    // args to invoking the constructor of lifted object exrp
    private Map<Span, Pair<VarRef,VarRef>> mutableArgsToLiftedObj;
    private Component enclosingComponent;
    private int uniqueId;
    private Map<Pair<Node,Span>, TypeEnv> typeEnvAtNode;
    private Map<Span,TypeEnv> tmpTypeEnvAtNode;
    // a stack keeping track of all nodes that can create a new lexical scope
    // this does not include the top level component
    private Stack<Node> scopeStack;
    private TraitTable traitTable;
    private TraitDecl enclosingTraitDecl;
    private ObjectDecl enclosingObjectDecl;
    private int objExprNestingLevel;
    private static final String ENCLOSING_PREFIX = "enclosing";
    private static final String MUTABLE_CONTAINER_PREFIX = "mutable";
    private static final String CONTAINER_FIELD_SUFFIX = "_container";
    private static final String MANGLE_CHAR = "$";

    /* The following two things are results returned by FreeNameCollector */
    /* Map key: object expr, value: free names captured by object expr */
    private Map<Span, FreeNameCollection> objExprToFreeNames;
    /* Map key: node which the captured mutable varRef is declared under
                (which should be either ObjectDecl or LocalVarDecl),
       value: list of pairs for which the VarRefs that needs to be boxed
              pair.first is the span of the decl node for the varRef
              pair.second is the varRef */
    private Map<Span, List<Pair<ObjectExpr, VarRef>>> declSiteToVarRefs;

    public ObjectExpressionVisitor(TraitTable traitTable,
                    Map<Pair<Node,Span>,TypeEnv> _typeEnvAtNode) {
        newObjectDecls = new LinkedList<ObjectDecl>();
        enclosingComponent = null;
        uniqueId = 0;
        typeEnvAtNode = _typeEnvAtNode;

        this.tmpTypeEnvAtNode = new HashMap<Span,TypeEnv>();
        // FIXME: Temp hack to use Map<Span,TypeEnv>
        // FIXME: Will change it when the TypeCheckResult.getTypeEnv is done
        for(Pair<Node, Span> n : typeEnvAtNode.keySet()) {
            this.tmpTypeEnvAtNode.put(n.second(), typeEnvAtNode.get(n));
        }

        scopeStack = new Stack<Node>();
        this.traitTable = traitTable;
        enclosingTraitDecl = null;
        enclosingObjectDecl = null;
        objExprNestingLevel = 0;

        mutableParamsToLiftedObj = new HashMap<Span, Pair<Param,Param>>();
        mutableArgsToLiftedObj = new HashMap<Span, Pair<VarRef,VarRef>>();
    }

    @Override
	public Node forComponent(Component that) {
        FreeNameCollector freeNameCollector =
            new FreeNameCollector(traitTable, tmpTypeEnvAtNode);
        that.accept(freeNameCollector);

        objExprToFreeNames = freeNameCollector.getObjExprToFreeNames();
        declSiteToVarRefs  = freeNameCollector.getDeclSiteToVarRefs();

        // No object expression found in this component. We are done.
        if(objExprToFreeNames.isEmpty()) {
            return that;
        }

        enclosingComponent = that;
        // Only traverse the tree if we find any object expression
        Node returnValue = super.forComponent(that);
        enclosingComponent = null;

        return returnValue;
    }

    @Override
    public Node forComponentOnly(Component that, APIName name_result,
                                     List<Import> imports_result,
                                     List<Export> exports_result,
                                     List<Decl> decls_result) {
        decls_result.addAll(newObjectDecls);
        return super.forComponentOnly(that, name_result,
                        imports_result, exports_result, decls_result);
    }

    @Override
        public Node forTraitDecl(TraitDecl that) {
        scopeStack.push(that);
        enclosingTraitDecl = that;
    	Node returnValue = super.forTraitDecl(that);
    	enclosingTraitDecl = null;
        scopeStack.pop();
    	return returnValue;
    }

    @Override
        public Node forObjectDecl(ObjectDecl that) {
        scopeStack.push(that);
        enclosingObjectDecl = that;

        ObjectDecl returnValue = that;
        List<Pair<ObjectExpr,VarRef>> rewriteList = 
                declSiteToVarRefs.get( that.getSpan() ); 

        // Some rewriting required for this ObjectDecl (i.e. it has var
        // params being captured and mutated by some object expression(s) 
        if( rewriteList != null ) {
            String containerName = MANGLE_CHAR + MUTABLE_CONTAINER_PREFIX + 
                                   "_" + that.getName(); 
            List<Expr> argsToContainerObj = new LinkedList<Expr>();
            ObjectDecl container = 
                createContainerForMutableVars(that, containerName, 
                                          rewriteList, argsToContainerObj);
            newObjectDecls.add(container);

            // TODO: Change this to append a unique ID
            String containerFieldName = MANGLE_CHAR + 
                    MUTABLE_CONTAINER_PREFIX + CONTAINER_FIELD_SUFFIX;
            Id containerFieldId = 
                    NodeFactory.makeId(container.getSpan(), containerFieldName);
            Option<Type> containerType = Option.<Type>some(
                             NodeFactory.makeTraitType(container.getName()) );

            VarDecl containerField = makeContainerField(container, 
                          containerFieldId, containerType, argsToContainerObj);
            MutableVarRefRewriteVisitor rewriter = 
                new MutableVarRefRewriteVisitor(that, containerField, containerFieldId);
            returnValue = (ObjectDecl) that.accept(rewriter);

            for(Pair<ObjectExpr,VarRef> varPair : rewriteList) {
                Span objExprSpan = varPair.first().getSpan();
                // if the obj expr span is found in mutableParamsToLiftedObj 
                // no need to do anything else; the same object expr in
                // rewriteList can be included multiple times if it captures
                // multiple varRefs   
                if(mutableParamsToLiftedObj.containsKey(objExprSpan) == false) {
                    NormalParam mutParam = new NormalParam(objExprSpan,
                                            containerFieldId, containerType);
                    mutableParamsToLiftedObj.put( objExprSpan, 
                                new Pair<Param,Param>(mutParam, null) ); 
                    VarRef mutArg = makeVarRefFromNormalParam(mutParam); 
                    mutableArgsToLiftedObj.put( objExprSpan, 
                                new Pair<VarRef,VarRef>(mutArg, null) ); 
                }
            }
        }

        // Traverse the subtree regardless rewriting is needed or not
        // returnValue = that if no rewriting required for this node
        // Note that we must update objExprToMutableDecls first before
        // before recursing on its subtree, because the info stored in that
        // data structure is relevant to lifting object expressions within
        // this ObjectDecl 
        returnValue = (ObjectDecl) super.forObjectDecl(returnValue);

        enclosingObjectDecl = null;
     	scopeStack.pop();
    	return returnValue;
    }

    private VarDecl makeContainerField(ObjectDecl containerObjDecl,
                                       Id containerFieldId, 
                                       Option<Type> containerType, 
                                       List<Expr> argsToContainerObj) {
        // FIXME: is this the right span to use?
        Span span = containerObjDecl.getSpan();
        List<LValueBind> lhs = new LinkedList<LValueBind>(); 

        // set the field to be immutable 
        lhs.add( new LValueBind(span, containerFieldId, containerType, false) );
        VarDecl field = new VarDecl( span, lhs, 
                                     makeCallToContainerObj(containerObjDecl, 
                                                       argsToContainerObj) );

        return field;
    }

    private TightJuxt makeCallToContainerObj(ObjectDecl containerObjDecl,
                                             List<Expr> argsToContainerObj) {
        // FIXME: is this the right span to use?
        Span span = containerObjDecl.getSpan();
        Id containerName = containerObjDecl.getName();
        List<Id> fns = new LinkedList<Id>();
        fns.add(containerName);

        List<StaticArg> staticArgs = Collections.<StaticArg>emptyList();
        FnRef fnRefToConstructor = ExprFactory.makeFnRef(span, false,
                                        containerName, fns, staticArgs);
        
        List<Expr> exprs = new LinkedList<Expr>();
        // argsToContainerObj has size greater or equal to 1; never 0
        if( argsToContainerObj.size() == 1 ) {
            exprs.add( argsToContainerObj.get(0) );
        }
        else {
            TupleExpr tuple = ExprFactory.makeTuple(span, argsToContainerObj);
            exprs.add(tuple);
        }
        exprs.add(0, fnRefToConstructor);
        
        return( ExprFactory.makeTightJuxt(span, false, exprs) );
    }

    @Override
	public Node forFnExpr(FnExpr that) {
        scopeStack.push(that);
        Node returnValue = super.forFnExpr(that);
        scopeStack.pop();
        return returnValue;
    }

    @Override
	public Node forFnDef(FnDef that) {
        scopeStack.push(that);
        Node returnValue = super.forFnDef(that);
        scopeStack.pop();
        return returnValue;
    }

    @Override
	public Node forIfClause(IfClause that) {
        scopeStack.push(that);
        Node returnValue = super.forIfClause(that);
        scopeStack.pop();
        return returnValue;
    }

    @Override
	public Node forFor(For that) {
        scopeStack.push(that);
        Node returnValue = super.forFor(that);
        scopeStack.pop();
        return returnValue;
    }

    @Override
	public Node forLetFn(LetFn that) {
        scopeStack.push(that);
        Node returnValue = super.forLetFn(that);
        scopeStack.pop();
        return returnValue;
    }

//    @Override
//	public Node forLocalVarDecl(LocalVarDecl that) {
//        scopeStack.push(that);
//
//        LocalVarDecl returnValue = that;
//        List<Pair<ObjectExpr,VarRef>> rewriteList = 
//                declSiteToVarRefs.get( that.getSpan() ); 
//
//        // Some rewriting required for this ObjectDecl (i.e. it has var
//        // params being captured and mutated by some object expression(s) 
//        if( rewriteList != null ) {
//            String containerName = MANGLE_CHAR + MUTABLE_CONTAINER_PREFIX + 
//                                   "_" + that.getName(); 
//            List<Expr> argsToContainerObj = new LinkedList<Expr>();
//            ObjectDecl container = 
//                createContainerForMutableVars(that, containerName, 
//                                              rewriteList, argsToContainerObj);
//            newObjectDecls.add(container);
//            String containerFieldName = MANGLE_CHAR +
//                                        MUTABLE_CONTAINER_PREFIX + 
//                                        CONTAINER_FIELD_SUFFIX;
//            MutableVarRefRewriteVisitor rewriter = 
//                new MutableVarRefRewriteVisitor(that, container, 
//                        containerFieldName, rewriteList, argsToContainerObj); 
//            returnValue = (LocalVarDecl) returnValue.accept(rewriter);
//        }
//        // Traverse the subtree regardless rewriting is needed or not
//        // returnValue = that if no rewriting required for this node
//        returnValue = (LocalVarDecl) super.forLocalVarDecl(returnValue);
//
//        scopeStack.pop();
//        return returnValue;
//    }

    @Override
	public Node forLabel(Label that) {
        scopeStack.push(that);
        Node returnValue = super.forLabel(that);
        scopeStack.pop();
        return returnValue;
    }

    @Override
	public Node forCatch(Catch that) {
        scopeStack.push(that);
        Node returnValue = super.forCatch(that);
        scopeStack.pop();
        return returnValue;
    }

    @Override
	public Node forTypecase(Typecase that) {
        scopeStack.push(that);
        Node returnValue = super.forTypecase(that);
        scopeStack.pop();
        return returnValue;
    }

    @Override
	public Node forGeneratedExpr(GeneratedExpr that) {
        scopeStack.push(that);
        Node returnValue = super.forGeneratedExpr(that);
        scopeStack.pop();
        return returnValue;
    }

    @Override
	public Node forWhile(While that) {
		scopeStack.push(that);
		Node returnValue = super.forWhile(that);
		scopeStack.pop();
		return returnValue;
	}

	@Override
    public Node forObjectExpr(ObjectExpr that) {
	    objExprNestingLevel++;
		scopeStack.push(that);

        FreeNameCollection freeNames = objExprToFreeNames.get(that.getSpan());

       // System.err.println("Free names: " + freeNames);

        ObjectDecl lifted = liftObjectExpr(that, freeNames);
        newObjectDecls.add(lifted);
        TightJuxt callToLifted = makeCallToLiftedObj(lifted, that, freeNames);

        scopeStack.pop();
        objExprNestingLevel--;

        return callToLifted;
    }

    private ObjectDecl 
    createContainerForMutableVars(Node originalContainer, 
                                  String name,
                                  List<Pair<ObjectExpr,VarRef>> rewriteList,
                                  List<Expr> argsToContainerObj) {
        // FIXME: Is this the right span to use?
        Span containerSpan = originalContainer.getSpan(); 
        Id containerId = NodeFactory.makeId(containerSpan, name);
        List<StaticParam> staticParams = Collections.<StaticParam>emptyList();
        List<TraitTypeWhere> extendClauses = 
            Collections.<TraitTypeWhere>emptyList();
        List<Decl> decls = Collections.emptyList(); 

        List<Param> params = new LinkedList<Param>();

        // TODO: We can do something fancier later to group varRefs
        // differently depending on which obj exprs captures them so that the 
        // grouping reflects the "correct" life span each var should have. 
        for(Pair<ObjectExpr,VarRef> var : rewriteList) {
            ObjectExpr objExpr = var.first();
            VarRef varRef = var.second();
            // If multiple obj exprs refer to the same varRef, there will 
            // be duplicates in the rewriteList; don't generate params for 
            // duplicates
            if( argsToContainerObj.contains(varRef) == false ) {
                argsToContainerObj.add(varRef);
                TypeEnv typeEnv = typeEnvAtNode.get( objExpr.getSpan() );
                Option<Node> declNodeOp = 
                    typeEnv.declarationSite( varRef.getVar() );
                NormalParam param = null;
                if( declNodeOp.isSome() ) {                    
                    param = makeVarParamFromVarRef( varRef, 
                                declNodeOp.unwrap().getSpan(), 
                                varRef.getExprType() ); 
                } else {
                    param = makeVarParamFromVarRef( varRef, varRef.getSpan(),
                                        varRef.getExprType() );   
                }
                params.add(param);
            }
        }
        
        ObjectDecl container = new ObjectDecl(containerSpan, 
                                        containerId, staticParams, 
                                        extendClauses, 
                                        Option.<WhereClause>none(),
                                        Option.<List<Param>>some(params), 
                                        decls);
                                    
        return container;
    }
    
    private TightJuxt makeCallToLiftedObj(ObjectDecl lifted,
                                          ObjectExpr objExpr,
                                          FreeNameCollection freeNames) {
        Span span = objExpr.getSpan();
        Id originalName = lifted.getName();
        List<Id> fns = new LinkedList<Id>();
        fns.add(originalName);
        // TODO: Need to figure out what Static params are captured.
        List<StaticArg> staticArgs = Collections.<StaticArg>emptyList();
        List<FnRef> freeMethodRefs = freeNames.getFreeMethodRefs();
        VarRef enclosingSelf = null;

        /* Now make the call to construct the lifted object */
        /* Use default value for parenthesized and exprType */
        // FIXME: I didn't initialize its exprType
        FnRef fnRef = ExprFactory.makeFnRef(span, false,
                                            originalName, fns, staticArgs);

        if (freeMethodRefs != null && freeMethodRefs.size() != 0) {
            enclosingSelf = ExprFactory.makeVarRef(span, "self");
        }

        List<Expr> exprs = makeArgsForCallToLiftedObj(objExpr,
                                                      freeNames, enclosingSelf);
        exprs.add(0, fnRef);

        TightJuxt callToConstructor =
            ExprFactory.makeTightJuxt(span, objExpr.isParenthesized(), exprs);

        return callToConstructor;
    }

    private List<Expr> makeArgsForCallToLiftedObj(ObjectExpr objExpr,
                                                  FreeNameCollection freeNames,
                                                  VarRef enclosingSelf) {
        Span span = objExpr.getSpan();

        List<VarRef> freeVarRefs = freeNames.getFreeVarRefs();
        List<FnRef> freeFnRefs = freeNames.getFreeFnRefs();
        List<Expr> exprs = new LinkedList<Expr>();

        // FIXME: Need to handle mutated vars
        if(freeVarRefs != null) {
            for(VarRef var : freeVarRefs) {
                // FIXME: is it ok to get rid of parenthesis around it?
                VarRef newVar = 
                    ExprFactory.makeVarRef(var.getSpan(), var.getVar());
                exprs.add(newVar);
            }
        }

        if(freeFnRefs != null) {
            for(FnRef fn : freeFnRefs) {
                exprs.add(fn);
            }
        }

        if(enclosingSelf != null) {
            exprs.add(enclosingSelf);
        }

        Pair<VarRef,VarRef> mutables = mutableArgsToLiftedObj.get(span); 
        if(mutables != null) {
            if( mutables.first() != null ) 
                exprs.add( mutables.first() );
            if( mutables.second() != null ) 
                exprs.add( mutables.second() );
        }
        
        if( exprs.size() == 0 ) {
            VoidLiteralExpr voidLit = ExprFactory.makeVoidLiteralExpr(span);
            exprs.add(voidLit);
        } else if( exprs.size() > 1 ) {
            TupleExpr tuple = ExprFactory.makeTuple(span, exprs);
            exprs = new LinkedList<Expr>();
            exprs.add(tuple);
        }

        return exprs;
    }

    private ObjectDecl liftObjectExpr(ObjectExpr target,
                                      FreeNameCollection freeNames) {
        String name = getMangledName(target);
        Span span = target.getSpan();
        Id liftedObjId = NodeFactory.makeId(span, name);
        List<StaticParam> staticParams =
            makeStaticParamsForLiftedObj(freeNames);
        List<TraitTypeWhere> extendsClauses = target.getExtendsClause();
        // FIXME: need to rewrite all decls w/ the freeNames.
        List<Decl> decls = target.getDecls();

        NormalParam enclosingSelf = null;
        Option<List<Param>> params = null;
        List<FnRef> freeMethodRefs = freeNames.getFreeMethodRefs();
        TypeEnv typeEnv = tmpTypeEnvAtNode.get(scopeStack.peek().getSpan());
        enclosingSelf = makeEnclosingSelfParam(typeEnv, target, freeMethodRefs);

        params = makeParamsForLiftedObj(target, freeNames, 
                                        typeEnv, enclosingSelf);
        /* Use default value for modifiers, where clauses,
           throw clauses, contract */
        ObjectDecl lifted = new ObjectDecl(span, liftedObjId, staticParams,
                                           extendsClauses,
                                           Option.<WhereClause>none(),
                                           params, decls);

        if(enclosingSelf != null) {
            VarRef receiver = makeVarRefFromNormalParam(enclosingSelf);
            DottedMethodRewriteVisitor rewriter =
                new DottedMethodRewriteVisitor(receiver, freeMethodRefs);
            lifted = (ObjectDecl) lifted.accept(rewriter);
        }

        return lifted;
    }

    private Option<List<Param>>
    makeParamsForLiftedObj(ObjectExpr target, FreeNameCollection freeNames,
                           TypeEnv typeEnv, NormalParam enclosingSelfParam) {
        // TODO: need to figure out shadowed self via FnRef
        // need to box any var that's mutabl

        Option<Type> type = null;
        NormalParam param = null;
        List<Param> params = new LinkedList<Param>();
        List<VarRef> freeVarRefs = freeNames.getFreeVarRefs();
        List<FnRef> freeFnRefs = freeNames.getFreeFnRefs();

        // FIXME: Need to handle mutated vars
        if(freeVarRefs != null) {
            for(VarRef var : freeVarRefs) {
                // Default value for modifier and default expression
                // FIXME: What if it has a type that's not visible at top level?
                // FIXME: what span should I use?
                type = typeEnv.type(var.getVar());
                param = new NormalParam(var.getSpan(), var.getVar(), type);

                params.add(param);
            }
        }

        if(freeFnRefs != null) {
            for(FnRef fn : freeFnRefs) {
                // Default value for modifier and default expression
                // FIXME: What if it has a type that's not visible at top level?
                // FIXME: what span should I use?
                type = typeEnv.type(fn.getOriginalName());
                param = new NormalParam(fn.getSpan(),
                                        fn.getOriginalName(), type);
                params.add(param);
            }
        }

        if(enclosingSelfParam != null) {
            params.add(enclosingSelfParam);
        }

        Pair<Param,Param> mutables = 
            mutableParamsToLiftedObj.get(target.getSpan()); 
        if(mutables != null) {
            if( mutables.first() != null ) 
                params.add( mutables.first() );
            if( mutables.second() != null ) 
                params.add( mutables.second() );
        }
        
        return Option.<List<Param>>some(params);
    }

    private NormalParam makeVarParamFromVarRef(VarRef var, 
                                          Span paramSpan,
                                          Option<Type> typeOp) {
        List<Modifier> mods = new LinkedList<Modifier>();
        mods.add( new ModifierSettable(paramSpan) );
        NormalParam param = new NormalParam(paramSpan, mods,
                                            var.getVar(), typeOp);
        return param;
    }

    private VarRef makeVarRefFromNormalParam(NormalParam param) {
        VarRef varRef = ExprFactory.makeVarRef( param.getSpan(),
                                                param.getName(),
                                                param.getType() );
        return varRef;
    }

    private NormalParam makeEnclosingSelfParam(TypeEnv typeEnv,
                                               ObjectExpr objExpr,
                                               List<FnRef> freeMethodRefs) {
        Option<Type> type;
        NormalParam param = null;

        if(freeMethodRefs != null && freeMethodRefs.size() != 0) {
            // Just sanity check
            if(enclosingTraitDecl == null && enclosingObjectDecl == null) {
                throw new DesugarerError("No enclosing trait or object " +
                            "decl found when a dotted method is referenced.");
            }

            // Use the span for the obj expr that we are lifting
            // FIXME: Is this the right span to use??
            Span paramSpan = objExpr.getSpan();

            // use the "self" id to get the right type of the
            // enclosing object / trait decl
            type = typeEnv.type( new Id("self") );

            // id of the newly created param for implicit self
            Id enclosingParamId = NodeFactory.makeId(paramSpan,
                    MANGLE_CHAR + ENCLOSING_PREFIX + "_" + objExprNestingLevel);
            param = new NormalParam(paramSpan, enclosingParamId, type);
        }

        return param;
    }

    private List<StaticParam>
        makeStaticParamsForLiftedObj(FreeNameCollection freeNames) {
        // TODO: Fill this in - get the VarTypes(?) that's free and
        // generate static param using it
        return Collections.<StaticParam>emptyList();
    }

    private String getMangledName(ObjectExpr target) {
        String compName = NodeUtil.nameString(enclosingComponent.getName());
        String mangled = MANGLE_CHAR + compName + "_" + nextUniqueId();
        return mangled;
    }

    private int nextUniqueId() {
        return uniqueId++;
    }

}


