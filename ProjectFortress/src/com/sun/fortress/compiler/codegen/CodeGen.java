/*******************************************************************************
    Copyright 2009 Sun Microsystems, Inc.,
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
package com.sun.fortress.compiler.codegen;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.util.*;

import edu.rice.cs.plt.collect.PredicateSet;
import edu.rice.cs.plt.collect.Relation;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.compiler.AnalyzeResult;
import com.sun.fortress.compiler.ByteCodeWriter;
import com.sun.fortress.compiler.GlobalEnvironment;
import com.sun.fortress.compiler.NamingCzar;
import com.sun.fortress.compiler.WellKnownNames;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.compiler.index.Function;
import com.sun.fortress.compiler.index.FunctionalMethod;
import com.sun.fortress.compiler.OverloadSet;
import com.sun.fortress.compiler.typechecker.TypeAnalyzer;
import com.sun.fortress.exceptions.CompilerError;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes_util.*;
import com.sun.fortress.repository.ForeignJava;
import com.sun.fortress.repository.ProjectProperties;
import com.sun.fortress.runtimeSystem.Naming;
import com.sun.fortress.syntax_abstractions.ParserMaker.Mangler;
import com.sun.fortress.useful.BA2Tree;
import com.sun.fortress.useful.BASet;
import com.sun.fortress.useful.BATree;
import com.sun.fortress.useful.Debug;
import com.sun.fortress.useful.DefaultComparator;
import com.sun.fortress.useful.Fn;
import com.sun.fortress.useful.MultiMap;
import com.sun.fortress.useful.Pair;
import com.sun.fortress.useful.StringHashComparer;
import com.sun.fortress.useful.TopSort;
import com.sun.fortress.useful.TopSortItemImpl;
import com.sun.fortress.useful.Useful;

// Note we have a name clash with org.objectweb.asm.Type
// and com.sun.fortress.nodes.Type.  If anyone has a better
// solution than writing out their entire types, please
// shout out.
public class CodeGen extends NodeAbstractVisitor_void implements Opcodes {
    CodeGenClassWriter cw;
    CodeGenMethodVisitor mv; // Is this a mistake?  We seem to use it to pass state to methods/visitors.
    final String packageAndClassName;
    private String traitOrObjectName; // set to name of current trait or object, as necessary.
    private String springBoardClass; // set to name of trait default methods class, if we are emitting it.

    // traitsAndObjects appears to be dead code.
    // private final Map<String, ClassWriter> traitsAndObjects =
    //     new BATree<String, ClassWriter>(DefaultComparator.normal());
    private final HashMap<String, String> aliasTable;
    private final TypeAnalyzer ta;
    private final ParallelismAnalyzer pa;
    private final FreeVariables fv;
    private final Map<IdOrOpOrAnonymousName, MultiMap<Integer, Function>> topLevelOverloads;
    private Set<String> overloadedNamesAndSigs;

    // lexEnv does not include the top level or object right now, just
    // args and local vars.  Object fields should have been translated
    // to dotted notation at this point, right?  Right?  (No, not.)
    private BATree<String, VarCodeGen> lexEnv;
    boolean inATrait = false;
    boolean inAnObject = false;
    boolean inABlock = false;
    private boolean emittingFunctionalMethodWrappers = false;
    private TraitObjectDecl currentTraitObjectDecl = null;

    private boolean fnRefIsApply = false; // FnRef is either apply or closure

    final Component component;
    private final ComponentIndex ci;
    private GlobalEnvironment env;


    public CodeGen(Component c,
                   TypeAnalyzer ta, ParallelismAnalyzer pa, FreeVariables fv,
                   ComponentIndex ci, GlobalEnvironment env) {
        component = c;
        packageAndClassName = NamingCzar.javaPackageClassForApi(c.getName());
        aliasTable = new HashMap<String, String>();
        this.ta = ta;
        this.pa = pa;
        this.fv = fv;
        this.ci = ci;
        this.topLevelOverloads =
            sizePartitionedOverloads(ci.functions());
        this.overloadedNamesAndSigs = new HashSet<String>();
        this.lexEnv = new BATree<String,VarCodeGen>(StringHashComparer.V);
        this.env = env;
        debug( "Compile: Compiling ", packageAndClassName );
    }

    // Create a fresh codegen object for a nested scope.  Technically,
    // we ought to be able to get by with a single lexEnv, because
    // variables ought to be unique by location etc.  But in practice
    // I'm not assuming we have a unique handle for any variable,
    // so we get a fresh CodeGen for each scope to avoid collisions.
    private CodeGen(CodeGen c) {
        this.component = c.component;
        this.cw = c.cw;
        this.mv = c.mv;
        this.packageAndClassName = c.packageAndClassName;
        this.aliasTable = c.aliasTable;
        this.inATrait = c.inATrait;
        this.inAnObject = c.inAnObject;
        this.inABlock = c.inABlock;
        this.ta = c.ta;
        this.pa = c.pa;
        this.fv = c.fv;
        this.ci = c.ci;
        this.env = c.env;
        this.topLevelOverloads = c.topLevelOverloads;
        this.overloadedNamesAndSigs = c.overloadedNamesAndSigs;
        this.lexEnv = new BATree<String,VarCodeGen>(c.lexEnv);

    }

    // We need to expose this because nobody else helping CodeGen can
    // understand unqualified names (esp types) without it!
    public APIName thisApi() {
        return ci.ast().getName();
    }

    /** Factor out method call path so that we do it right
        everywhere we invoke a dotted method of any kind. */
    private void methodCall(IdOrOp method,
                            TraitType receiverType,
                            Type domainType, Type rangeType) {
        int opcode;
        if (ta.typeCons(receiverType).unwrap().ast() instanceof TraitDecl &&
            !NamingCzar.fortressTypeIsSpecial(receiverType)) {
            opcode = INVOKEINTERFACE;
        } else {
            opcode = INVOKEVIRTUAL;
        }
        String sig = NamingCzar.jvmSignatureFor(domainType, rangeType, thisApi());
        String methodClass = NamingCzar.jvmTypeDesc(receiverType, thisApi(), false);
        String methodName = method.getText();
        mv.visitMethodInsn(opcode, methodClass, methodName, sig);
    }

    private void generateMainMethod() {

        // We generate two methods.  First a springboard static main()
        // method that creates an instance of the class we are
        // generating, and then invokes the runExecutable(...) method
        // on that instance---this is RTS code that sets up
        // command-line argument access and initializes the work
        // stealing infrastructure.
        //
        // The second method is the compute() method, which is invoked
        // by the work stealing infrastructure after it starts up, and
        // simply calls through to the static run() method that must
        // occur in this component.  Without this little trampoline,
        // we need to special case the run() method during code
        // generation and the result is not reentrant (ie if we call
        // run() recursively we lose).

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "main",
                            NamingCzar.stringArrayToVoid, null, null);
        mv.visitCode();
        // new packageAndClassName()
        mv.visitTypeInsn(Opcodes.NEW, packageAndClassName);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, packageAndClassName, "<init>",
                           NamingCzar.voidToVoid);

        // .runExecutable(args)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                           NamingCzar.fortressExecutable,
                           NamingCzar.fortressExecutableRun,
                           NamingCzar.fortressExecutableRunType);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore,NamingCzar.ignore);
        mv.visitEnd();
        // return

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "compute",
                            NamingCzar.voidToVoid, null, null);
        mv.visitCode();
        // Call through to static run method in this component.
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, packageAndClassName, "run",
                           NamingCzar.voidToFortressVoid);
        // Discard the FVoid that results
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }

    private void generateFieldsAndInitMethod(String classFile, String superClass, List<Param> params) {
        // Allocate fields
        for (Param p : params) {
            // TODO need to spot for "final" fields.
            String pn = p.getName().getText();
            Type pt = p.getIdType().unwrap();
            cw.visitField(Opcodes.ACC_PRIVATE, pn,
                    NamingCzar.jvmTypeDesc(pt, thisApi(), true), null /* for non-generic */, null /* instance has no value */);
        }

        String init_sig = NamingCzar.jvmSignatureFor(params, "V", thisApi());
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", init_sig, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superClass, "<init>", NamingCzar.voidToVoid);

        // Initialize fields.
        int pno = 1;
        for (Param p : params) {
            String pn = p.getName().getText();
            Type pt = p.getIdType().unwrap();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, pno);
            mv.visitFieldInsn(Opcodes.PUTFIELD, classFile, pn,
                    NamingCzar.jvmTypeDesc(pt, thisApi(), true));
            pno++;
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }


    private void cgWithNestedScope(ASTNode n) {
        CodeGen cg = new CodeGen(this);
        n.accept(cg);
    }

    private void addLocalVar( VarCodeGen v ) {
        debug("addLocalVar ", v);
        lexEnv.put(v.name.getText(), v);
    }

    private void addStaticVar( VarCodeGen v ) {
        debug("addStaticVar ", v);
        lexEnv.put(v.name.getText(), v);
    }

    private VarCodeGen addParam(Param p) {
        VarCodeGen v =
            new VarCodeGen.ParamVar(p.getName(), p.getIdType().unwrap(), this);
        addLocalVar(v);
        return v;
    }

    private VarCodeGen addParam(TraitObjectDecl x) {
        Id id = NodeFactory.makeId(NodeUtil.getSpan(x), "self");
        Id tid = (Id)  x.getHeader().getName();
        Type t = NodeFactory.makeTraitType(tid);
        VarCodeGen v =
            new VarCodeGen.ParamVar(id, t, this);
        addLocalVar(v);
        return v;
    }

    // Always needs context-sensitive null handling anyway.  TO FIX.
    // private VarCodeGen getLocalVar( ASTNode ctxt, IdOrOp nm ) {
    //     VarCodeGen r = getLocalVarOrNull(nm);
    //     if (r==null) return sayWhat(ctxt, "Can't find lexEnv mapping for local var");
    //     return r;
    // }

    private VarCodeGen getLocalVarOrNull( IdOrOp nm ) {
        debug("getLocalVar: " + nm);
        VarCodeGen r = lexEnv.get(idOrOpToString(nm));
        if (r != null)
            debug("getLocalVar:" + nm + " VarCodeGen = " + r + " of class " + r.getClass());
        else
            debug("getLocalVar:" + nm + " VarCodeGen = null");
        return r;
    }

    public void dumpClass( String file ) {
        PrintWriter pw = new PrintWriter(System.out);
        cw.visitEnd();

        if (ProjectProperties.getBoolean("fortress.bytecode.verify", false))
            CheckClassAdapter.verify(new ClassReader(cw.toByteArray()), true, pw);

        ByteCodeWriter.writeClass(NamingCzar.cache, file, cw.toByteArray());
        debug( "Writing class ", file);
    }

    private void popAll(int onStack) {
        if (onStack == 0) return;
        for (; onStack > 1; onStack -= 2) {
            mv.visitInsn(Opcodes.POP2);
        }
        if (onStack==1) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void dumpTraitDecls(List<Decl> decls) {
        debug("dumpDecls", decls);
        for (Decl d : decls) {
            if (!(d instanceof FnDecl)) {
                sayWhat(d);
                return;
            }
            d.accept(this);
        }
    }


    private void addLineNumberInfo(ASTNode x) {
        addLineNumberInfo(mv, x);
    }

    private void addLineNumberInfo(CodeGenMethodVisitor m, ASTNode x) {
        org.objectweb.asm.Label bogus_label = new org.objectweb.asm.Label();
        m.visitLabel(bogus_label);
        Span span = NodeUtil.getSpan(x);
        SourceLoc begin = span.getBegin();
        SourceLoc end = span.getEnd();
        String fileName = span.getFileName();
        m.visitLineNumber(begin.getLine(), bogus_label);
    }

    /**
     * @param x
     * @param arrow
     * @param pkgAndClassName
     * @param methodName
     */
    private void callStaticSingleOrOverloaded(FunctionalRef x,
            com.sun.fortress.nodes.Type arrow, String pkgAndClassName,
            String methodName) {

        debug("class = ", pkgAndClassName, " method = ", methodName );
        addLineNumberInfo(x);

        Pair<String, String> method_and_signature =
            resolveMethodAndSignature(x, arrow, methodName);

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, pkgAndClassName,
                method_and_signature.getA(), method_and_signature.getB());

    }

    /**
     * @param x
     * @param arrow
     * @param methodName
     * @return
     * @throws Error
     */
    private Pair<String, String> resolveMethodAndSignature(ASTNode x,
            com.sun.fortress.nodes.Type arrow, String methodName) throws Error {
        Pair<String, String> method_and_signature = null;

        if ( arrow instanceof ArrowType ) {
            // TODO should this be non-colliding single name instead?
            // answer depends upon how intersection types are normalized.
            // conservative answer is "no".
            methodName = Naming.mangleIdentifier(methodName);
            method_and_signature =
                new Pair<String, String>(methodName,
                                         NamingCzar.jvmMethodDesc(arrow, component.getName()));

        } else if (arrow instanceof IntersectionType) {
            IntersectionType it = (IntersectionType) arrow;
            methodName = OverloadSet.actuallyOverloaded(it, paramCount) ?
                    OverloadSet.oMangle(methodName) : Naming.mangleIdentifier(methodName);

            method_and_signature = new Pair<String, String>(methodName,
                    OverloadSet.getSignature(it, paramCount, ta));

        } else {
                sayWhat( x, "Neither arrow nor intersection type: " + arrow );
                throw new Error(); // not reached
        }
        return method_and_signature;
    }

    // paramCount communicates this information from call to function reference,
    // as it's needed to determine type descriptors for methods.
    private int paramCount = -1;

    /**
     * @param y
     */
    private void pushInteger(int y) {
        switch (y) {
        case 0:
            mv.visitInsn(Opcodes.ICONST_0);
            break;
        case 1:
            mv.visitInsn(Opcodes.ICONST_1);
            break;
        case 2:
            mv.visitInsn(Opcodes.ICONST_2);
            break;
        case 3:
            mv.visitInsn(Opcodes.ICONST_3);
            break;
        case 4:
            mv.visitInsn(Opcodes.ICONST_4);
            break;
        case 5:
            mv.visitInsn(Opcodes.ICONST_5);
            break;
        default:
            mv.visitLdcInsn(y);
            break;
        }

    }

    private void allSayWhats() {
        return; // This is a great place for a breakpoint!
    }

    private <T> T sayWhat(ASTNode x) {
        allSayWhats();
        throw new CompilerError(x, "Can't compile " + x);
    }

    private <T> T sayWhat(Node x) {
        if (x instanceof ASTNode)
            sayWhat((ASTNode) x);
        allSayWhats();
        throw new CompilerError("Can't compile " + x);
    }

    private <T> T sayWhat(ASTNode x, String message) {
        allSayWhats();
        throw new CompilerError(x, message + " node = " + x);
    }

    private void debug(Object... message){
        Debug.debug(Debug.Type.CODEGEN,1,message);
    }

    private void doStatements(List<Expr> stmts) {
        int onStack = 0;
        for ( Expr e : stmts ) {
            popAll(onStack);
            e.accept(this);
            onStack = 1;
            // TODO: can we have multiple values on stack in future?
            // Whither primitive types?
            // May require some tracking of abstract stack state.
            // For now we always have 1 pointer on stack and this doesn't
            // matter.
        }
    }

    public void defaultCase(Node x) {
        System.out.println("defaultCase: " + x + " of class " + x.getClass());
        sayWhat(x);
    }

    public void forImportStar(ImportStar x) {
        // do nothing, don't think there is any code go generate
    }

    public void forBlock(Block x) {
        if (x.isAtomicBlock()) {
            sayWhat(x, "Can't generate code for atomic block yet.");
        }
        boolean oldInABlock = inABlock;
        inABlock = true;
        debug("forBlock ", x);
        doStatements(x.getExprs());
        inABlock=oldInABlock;
    }
    public void forChainExpr(ChainExpr x) {
        debug( "forChainExpr", x);
        Expr first = x.getFirst();
        List<Link> links = x.getLinks();
        debug( "forChainExpr", x, " about to call accept on ",
               first, " of class ", first.getClass());
        first.accept(this);
        Iterator<Link> i = links.iterator();
        if (links.size() != 1)
            throw new CompilerError(x, x + "links.size != 1");
        Link link = i.next();
        link.getExpr().accept(this);
        debug( "forChainExpr", x, " about to call accept on ",
               link.getOp(), " of class ", link.getOp().getClass());
        int savedParamCount = paramCount;
        try {
            // TODO is this the general formula?
            paramCount = links.size() + 1;
            link.getOp().accept(this);
        } finally {
            paramCount = savedParamCount;
        }

        debug( "We've got a link ", link, " an op ", link.getOp(),
               " and an expr ", link.getExpr(), " What do we do now");
    }

    public void forComponent(Component x) {
        debug("forComponent ",x.getName(),NodeUtil.getSpan(x));
        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visitSource(packageAndClassName, null);
        boolean exportsExecutable = false;
        boolean exportsDefaultLibrary = false;

        for ( APIName export : x.getExports() ) {
            if ( WellKnownNames.exportsMain(export.getText()) )
                exportsExecutable = true;
            if ( WellKnownNames.exportsDefaultLibrary(export.getText()) )
                exportsDefaultLibrary = true;
        }

        String extendedJavaClass =
            exportsExecutable ? NamingCzar.fortressExecutable :
                                NamingCzar.fortressComponent ;

        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                 packageAndClassName, null, extendedJavaClass,
                 null);

        for ( Import i : x.getImports() ) {
            i.accept(this);
        }

        // Always generate the init method
        generateFieldsAndInitMethod(packageAndClassName, extendedJavaClass, Collections.<Param>emptyList());

        // If this component exports an executable API,
        // generate a main method.
        if ( exportsExecutable ) {
            generateMainMethod();
        }
        // determineOverloadedNames(x.getDecls() );

        // Must do this first, to get local decls right.
        overloadedNamesAndSigs = generateTopLevelOverloads(thisApi(), topLevelOverloads, ta, cw);

        List<Decl> fieldDecls = new ArrayList<Decl>();

        // Must process top-level values next to make sure fields end up in scope.
        for (Decl d : x.getDecls()) {
            if (d instanceof ObjectDecl) {
                this.forObjectDeclPrePass((ObjectDecl) d, fieldDecls);
            } else if (d instanceof VarDecl) {
                fieldDecls.add(d);
            }
        }

        // Static initializer for this class.
        mv = cw.visitMethod(Opcodes.ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null);

        // TODO: Need dependency analysis for component-level decls.
        fieldDecls = topSortDeclsByDependencies(fieldDecls);

        // Emit initialization code to create a field for
        // each singleton / top-level bindings, and generate
        // init code to fill it.
        for (Decl y : fieldDecls) {
            if (y instanceof ObjectDecl) {
                singletonObjectFieldAndInit((ObjectDecl)y);
            } else if (y instanceof VarDecl) {
                this.forVarDeclPrePass((VarDecl)y);
            } else {
                sayWhat(y,"Don't recognize "+y+" as a fieldDecl");
            }
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();

        for ( Decl d : x.getDecls() ) {
            d.accept(this);
        }

        dumpClass( packageAndClassName );
    }

    public void forDecl(Decl x) {
        sayWhat(x, "Can't handle decl class "+x.getClass().getName());
    }


    public void forDo(Do x) {
        // TODO: these ought to occur in parallel!
        debug("forDo ", x);
        int onStack = 0;
        for ( Block b : x.getFronts() ) {
            popAll(onStack);
            b.accept(this);
            onStack = 1;
        }
    }

    // TODO: arbitrary-precision version of FloatLiteralExpr, correct
    // handling of types other than double (float should probably just
    // truncate, but we want to warn if we lose bits I expect).
    public void forFloatLiteralExpr(FloatLiteralExpr x) {
        debug("forFloatLiteral ", x);
        double val = x.getIntPart().doubleValue() +
            (x.getNumerator().doubleValue() /
             Math.pow(x.getDenomBase(), x.getDenomPower()));
        mv.visitLdcInsn(val);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                           NamingCzar.internalFortressRR64, NamingCzar.make,
                           NamingCzar.makeMethodDesc(NamingCzar.descDouble,
                                                     NamingCzar.descFortressRR64));
    }

    public void forFnDecl(FnDecl x) {

        /*
         * Cases for FnDecl:
         *
         * 1. top level
         *
         * 2. trait normal method. a. for trait itself, an abstract method in
         * generated interface (this may be handled elsewhere) b. for trait
         * defaults, a static method in SpringBoard
         *
         * 3. trait functional method a. for trait itself, a mangled-name
         * abstract method with self removed from the parameter list. (this may
         * be handled elsewhere) b. at top level, a functional wrapper with self
         * in original position, which invokes the interface method with self in
         * dotted position. NOTE THE POTENTIAL FOR OVERLOADING. c. for trait
         * defaults, a mangled-name static method with self in the first
         * parameter position (in SpringBoard).
         *
         * 4. object normal method a. a normal dotted method is generated
         *
         * 5. object functional method a. a mangled-name dotted method is
         * generated with self removed from the parameter list. b. at top level,
         * a functional wrapper with self in original position, which invokes
         * the interface method with self in dotted position. NOTE THE POTENTIAL
         * FOR OVERLOADING. Static overload resolution can be an optimization.
         */

        debug("forFnDecl ", x);
        FnHeader header = x.getHeader();

        List<Param> params = header.getParams();
        int selfIndex = selfParameterIndex(params);
        boolean functionalMethod = selfIndex != -1;

        Option<Expr> body = x.getBody();
        IdOrOpOrAnonymousName name = header.getName();

        boolean hasSelf = !functionalMethod && (inAnObject || inATrait);
        boolean savedInAnObject = inAnObject;
        boolean savedInATrait = inATrait;
        boolean savedEmittingFunctionalMethodWrappers = emittingFunctionalMethodWrappers;

        boolean emittingTraitDefault = inATrait;

        if (emittingFunctionalMethodWrappers) {
            if (!functionalMethod)
                return; // Not functional = no wrapper needed.
        }

        Option<com.sun.fortress.nodes.Type> returnType = header.getReturnType();

        if (body.isNone())
            sayWhat(x, "Abstract function declarations are not supported.");
        if (returnType.isNone())
            sayWhat(x, "Return type is not inferred.");
        if (!(name instanceof IdOrOp))
            sayWhat(x, "Unhandled function name.");

        if (name instanceof Id) {
            Id id = (Id) name;
            debug("forId ", id, " class = ", NamingCzar.jvmClassForSymbol(id));
        } else if (name instanceof Op) {
            Op op = (Op) name;
            Fixity fixity = op.getFixity();
            boolean isEnclosing = op.isEnclosing();
            Option<APIName> maybe_apiName = op.getApiName();
            debug("forOp ", op, " fixity = ", fixity, " isEnclosing = ",
                    isEnclosing, " class = ", NamingCzar.jvmClassForSymbol(op));
        } else {
            sayWhat(x);
        }

        List<StaticParam> sparams = header.getStaticParams();

        boolean canCompile =
            (sparams.isEmpty() || // no static parameter
             !(inAnObject || inATrait || emittingFunctionalMethodWrappers)) &&
        header.getWhereClause().isNone() && // no where clause
        header.getThrowsClause().isNone() && // no throws clause
        header.getContract().isNone() && // no contract
        header.getMods().isEmpty() && // no modifiers
        !inABlock; // no local functions

        if (!canCompile)
            sayWhat(x);

        try {
            inAnObject = false;
            inATrait = false;

            // For now every Fortress entity is made public, with
            // namespace management happening in Fortress-land. Right?
            // [JWM:] we'll want to clamp down on this long-term, but
            // we have to get nesting right---we generate a pile of
            // class files for one Fortress component

            if (! sparams.isEmpty()) {
                /*
                 * Different plan for static parameter decls;
                 * instead of a method name, we are looking for an
                 * inner class name, similar to how these are constructed
                 * for traits.
                 *
                 * The inner class name has the form
                 *
                 * PKG.component$GEARfunction[\t1;t2;n3;o4\]ENVELOPEarrow[\d1;d2;r\]
                 *
                 * where
                 * PKG is package name
                 * component is component name
                 * GEAR is Unicode "GEAR"
                 * function is function name
                 * t1, t2, n3, o4 encode static parameter kinds
                 * ENVELOPE is unicode Envelope (just like a closure)
                 * arrow is "Arrow", the stem on a generic arrow type
                 * d1, d2, r are the type parameters of the arrow type.
                 *
                 * These classes will have all the attributes required of a
                 * closure class, except that the static parameters will be
                 * dummies to be replaced at instantiation time.
                 */

                /*
                 * Need to modify the
                 * signature, depending on
                 * circumstances.
                 */

                String sig = NamingCzar.jvmSignatureFor(NodeUtil.getParamType(x),
                        returnType.unwrap(), component.getName());

                ArrowType at = fndeclToType(x);

                String mname;

                // TODO different collision rules for top-level and for
                // methods. (choice of mname)

                if (functionalMethod) {
                    sig = Naming.removeNthSigParameter(sig, selfIndex);
                    mname = fmDottedName(singleName(name), selfIndex);
                } else {
                    mname = nonCollidingSingleName(name, sig);
                }


            } else if (emittingFunctionalMethodWrappers) {
                functionalMethodWrapper(x, params, selfIndex, name,
                        savedInATrait, returnType);
            } else {

                if (emittingTraitDefault) {

                    int modifiers = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;

                    CodeGen cg = new CodeGen(this); /*
                                                     * Need to modify the
                                                     * signature, depending on
                                                     * circumstances.
                                                     */

                    Type traitType = NodeFactory
                            .makeTraitType((Id) currentTraitObjectDecl
                                    .getHeader().getName());

                    /* Signature includes explicit leading self
                       First version of sig includes duplicate self for
                       functional methods, which is then cut out.
                     */
                    String sig = NamingCzar.jvmSignatureFor(
                            NodeUtil.getParamType(x),
                            NamingCzar.jvmTypeDesc(returnType.unwrap(),
                                    component.getName()),
                            0,
                            traitType,
                            component.getName());

                    if (functionalMethod) {
                        sig = Naming.removeNthSigParameter(sig, selfIndex+1);
                    }

                    // TODO different collision rules for top-level and for
                    // methods.
                    String mname = functionalMethod ? fmDottedName(
                            singleName(name), selfIndex)
                            : nonCollidingSingleName(name, sig);

                    // trait default OR top level.

                    cg.mv = cw.visitMethod(modifiers, mname, sig, null, null);
                    cg.mv.visitCode();

                    // Now inside method body. Generate code for the method
                    // body.
                    // Start by binding the parameters and setting up the
                    // initial
                    // locals.
                    VarCodeGen selfVar = null;
                    // TODO savedInAnObject || savedInATrait ?
                    if (hasSelf || functionalMethod) {
                        // TODO: Add proper type information here based on the
                        // enclosing trait/object decl. For now we can get away
                        // with just stashing a null as we're not using it to
                        // determine stack sizing or anything similarly crucial.
                        selfVar = cg.addParam(currentTraitObjectDecl);
                    }
                    List<VarCodeGen> paramsGen = new ArrayList<VarCodeGen>(
                            params.size());
                    int index = 0;
                    for (Param p : params) {
                        if (index != selfIndex) {
                            VarCodeGen v = cg.addParam(p);
                            paramsGen.add(v);
                        }
                        index++;
                    }
                    // Compile the body in the parameter environment

                    body.unwrap().accept(cg);
                    // Clean up the parameters
                    exitMethodScope(selfIndex, cg, selfVar, paramsGen);
                    methodReturnAndFinish(cg);

                    /*
                     * Next emit an abstract redirecter, this makes life better
                     * for our primitive type story.
                     */

                    modifiers = Opcodes.ACC_PUBLIC;

                    cg = new CodeGen(this);

                    String osig = sig;

                    String selfSig =  Naming.nthSigParameter(osig,0);
                    selfSig = Useful.substring(selfSig, 1, -1);
                    // Get rid of explicit self parameter.
                    sig = Naming.removeNthSigParameter(sig, 0);

                    // TODO different collision rules for top-level and for
                    // methods.
                    // SAME MNAME

                    // trait default OR top level.

                    cg.mv = cw.visitMethod(modifiers, mname, sig, null, null);
                    cg.mv.visitCode();

                    // We received "self" in parameter 0
                    cg.mv.visitVarInsn(ALOAD, 0);
                    // Need to downcast, maybe. this may only matter for weird primitive types.
                    cg.mv.visitTypeInsn(Opcodes.CHECKCAST, selfSig);//NamingCzar.jvmTypeDesc(ty, ifNone, false));

                    for (int i = 0; i < params.size(); i++) {
                        // 0 1 2
                        // a self b
                        // self a b
                        if (i < selfIndex) {
                            cg.mv.visitVarInsn(ALOAD, i+1);
                        } else if (i > selfIndex) {
                            cg.mv.visitVarInsn(ALOAD, i);
                        }

                    }
                    cg.mv.visitMethodInsn(INVOKESTATIC,
                                          springBoardClass,
                                          mname,
                                          osig);


                    methodReturnAndFinish(cg);

                    // END OF emitting trait default
                } else {
                    /* options here:
                     *  - functional method in object
                     *  - normal method in object
                     *  - top level
                     */
                    int modifiers = Opcodes.ACC_PUBLIC;

                    /*
                     * Need to modify the
                     * signature, depending on
                     * circumstances.
                     */
                    String sig = NamingCzar.jvmSignatureFor(NodeUtil.getParamType(x),
                            returnType.unwrap(), component.getName());

                    String mname;

                    // TODO different collision rules for top-level and for
                    // methods. (choice of mname)

                    if (functionalMethod) {
                        sig = Naming.removeNthSigParameter(sig, selfIndex);
                        mname = fmDottedName(singleName(name), selfIndex);
                    } else {
                        mname = nonCollidingSingleName(name, sig);
                    }

                    if (!savedInAnObject) {
                        // trait default OR top level.
                        // DO NOT special case run() here and make it non-static
                        // (that used to happen), as that's wrong. It's
                        // addressed
                        // in the executable wrapper code instead.
                        modifiers |= Opcodes.ACC_STATIC;
                    }

                    CodeGen cg = new CodeGen(this);

                    cg.mv = cw.visitMethod(modifiers, mname, sig, null, null);
                    cg.mv.visitCode();

                    boolean hasSelfVar = hasSelf || functionalMethod;

                    // Now inside method body. Generate code for the method
                    // body. Start by binding the parameters and setting up the
                    // initial locals.
                    VarCodeGen selfVar = null;
                    if (hasSelfVar) {
                        // TODO: Add proper type information here based on the
                        // enclosing trait/object decl. For now we can get away
                        // with just stashing a null as we're not using it to
                        // determine stack sizing or anything similarly crucial.

                        selfVar = new VarCodeGen.SelfVar(
                                NodeUtil.getSpan(name), null, cg);
                        cg.addLocalVar(selfVar);

                    }
                    List<VarCodeGen> paramsGen = new ArrayList<VarCodeGen>(
                            params.size());
                    int index = 0;
                    for (Param p : params) {
                        if (index != selfIndex) {
                            VarCodeGen v = cg.addParam(p);
                            paramsGen.add(v);
                        }
                        index++;
                    }
                    // Compile the body in the parameter environment

                    body.unwrap().accept(cg);
                    exitMethodScope(selfIndex, cg, selfVar, paramsGen);

                    methodReturnAndFinish(cg);
                }

            }

        } finally {
            inAnObject = savedInAnObject;
            inATrait = savedInATrait;
            emittingFunctionalMethodWrappers = savedEmittingFunctionalMethodWrappers;
        }

    }

    private ArrowType fndeclToType(FnDecl x) {
        FnHeader fh = x.getHeader();
        Type rt = fh.getReturnType().unwrap();
        List<Param> lp = fh.getParams();
        Type dt = null;
        switch (lp.size()) {
        case 0:
            dt = NodeFactory.makeVoidType(x.getInfo().getSpan());
            break;
        case 1:
            dt = lp.get(0).getIdType().unwrap(); // TODO varargs
            break;
        default:
            dt = NodeFactory.makeTupleType(Useful.applyToAll(lp, new Fn<Param,Type>() {
                @Override
                public Type apply(Param x) {
                    return x.getIdType().unwrap(); // TODO varargs
                }}));
            break;
        }
        return NodeFactory.makeArrowType(NodeFactory.makeSpan(dt,rt), dt, rt);
    }

    private String decorateMethodIfstaticParams(FnDecl x, String mname) {
        List<StaticParam> sparams = x.getHeader().getStaticParams();
        if (sparams.size() == 0)
            return mname;
        String frag = Naming.GEAR + mname + Naming.LEFT_OXFORD;
        for (StaticParam sp : sparams) {
            StaticParamKind spk = sp.getKind();
            IdOrOp spn = sp.getName();

        }
        // TODO Auto-generated method stub
        return frag + Naming.RIGHT_OXFORD;
    }

    /**
     * @param selfIndex
     * @param cg
     * @param selfVar
     * @param paramsGen
     */
    private void exitMethodScope(int selfIndex, CodeGen cg, VarCodeGen selfVar,
            List<VarCodeGen> paramsGen) {
        for (int i = paramsGen.size() - 1; i >= 0; i--) {
            if (i != selfIndex) {
                VarCodeGen v = paramsGen.get(i);
                v.outOfScope(cg.mv);
            }
        }
        if (selfVar != null)
            selfVar.outOfScope(cg.mv);
    }

    /**
     * @param x
     * @param params
     * @param selfIndex
     * @param name
     * @param savedInATrait
     * @param returnType
     */
    private void functionalMethodWrapper(FnDecl x, List<Param> params,
            int selfIndex, IdOrOpOrAnonymousName name, boolean savedInATrait,
            Option<com.sun.fortress.nodes.Type> returnType) {
        int modifiers = Opcodes.ACC_PUBLIC;

        CodeGen cg = new CodeGen(this);
        // Just a wrapper around the body itself
        modifiers |= Opcodes.ACC_STATIC;
        String sig = NamingCzar.jvmSignatureFor(NodeUtil
                .getParamType(x), returnType.unwrap(), component
                .getName());

        String dottedSig = Naming.removeNthSigParameter(sig, selfIndex);

        // TODO different collision rules for top-level and for methods.
        String mname = nonCollidingSingleName(name, sig);
        String dottedName = fmDottedName(singleName(name), selfIndex);

        cg.mv = cw.visitMethod(modifiers, mname, sig, null, null);
        cg.mv.visitCode();

        // Now inside method body. Generate code for the method body.
        // Start by binding the parameters and setting up the initial
        // locals.
        VarCodeGen selfVar = null;
        List<VarCodeGen> paramsGen = new ArrayList<VarCodeGen>(params
                .size());

        // Invoke the dotted method, thank you very much.
        // -----------

        // TODO will have to get smarter with unboxing
        cg.mv.visitVarInsn(ALOAD, selfIndex);
        for (int i = 0; i < params.size(); i++) {
            if (i != selfIndex)
                cg.mv.visitVarInsn(ALOAD, i);

        }
        cg.mv.visitMethodInsn(savedInATrait ? INVOKEINTERFACE
                : INVOKEVIRTUAL, traitOrObjectName, dottedName,
                dottedSig);

        // -----------
        // Method body is complete except for returning final result if any.
        // TODO: Fancy footwork here later on if we need to return a
        // non-pointer; for now every fortress functional returns a single
        // pointer result.
        methodReturnAndFinish(cg);
        // Method body complete, cg now invalid.
    }

    /**
     * @param cg
     */
    private void methodReturnAndFinish(CodeGen cg) {
        cg.mv.visitInsn(Opcodes.ARETURN);
        cg.mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        cg.mv.visitEnd();
    }

    /**
     * @param params
     * @return
     */
    private int selfParameterIndex(List<Param> params) {
        int selfIndex = -1;
        int i = 0;
        for (Param p : params) {
            if (p.getName().getText() == "self") {
                selfIndex = i;
                break;
            }
            i++;
        }
        return selfIndex;
    }

    public void forFnExpr(FnExpr x) {
        debug("forFnExpr " + x);
        FnHeader header = x.getHeader();
        Expr body = x.getBody();
        List<Param> params = header.getParams();
        Option<Type> returnType = header.getReturnType();
        if (!returnType.isSome())
            throw new CompilerError(x, "No return type");
        Type rt = returnType.unwrap();


        //      Create the Class
        String desc = NamingCzar.makeAbstractArrowDescriptor(params, rt, thisApi());
        String idesc = NamingCzar.makeArrowDescriptor(params, rt, thisApi());
        CodeGen cg = new CodeGen(this);
        cg.cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES);

        String className = NamingCzar.gensymArrowClassName(Naming.deDot(thisApi().getText()));

        debug("forFnExpr className = " + className + " desc = " + desc);
        cg.cw.visitSource(className, null);
        List<VarCodeGen> freeVars = getFreeVars(body);
        cg.lexEnv = cg.createTaskLexEnvVariables(className, freeVars);
        cg.cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                    className, null, desc, new String[] {idesc});

        // Generate the constructor (initializes captured free vars from param list)
        String init = taskConstructorDesc(freeVars);
        cg.generateTaskInit(desc, init, freeVars);

        String applyDesc = NamingCzar.jvmSignatureFor(params, NamingCzar.jvmTypeDesc(rt, thisApi()),
                                                      thisApi());

        // Generate the apply method
        // System.err.println(idesc+".apply"+applyDesc+" gen in "+className);
        cg.mv = cg.cw.visitMethod(Opcodes.ACC_PUBLIC, Naming.APPLY_METHOD, applyDesc, null, null);
        cg.mv.visitCode();

        // Since we call this virtually we need a slot for the arrow implementation this object.
        cg.mv.reserveSlot0();
        for (Param p : params) {
            cg.addParam(p);
        }

        body.accept(cg);

        methodReturnAndFinish(cg);
        cg.dumpClass(className);

        constructWithFreeVars(className, freeVars, init);
    }


    /**
     * Creates a name that will not collide with any overloaded functions
     * (the overloaded name "wins" because it if it is exported, this one is not).
     *
     * @param name
     * @param sig The jvm signature for a method, e.g., (ILjava/lang/Object;)D
     * @return
     */
    private String nonCollidingSingleName(IdOrOpOrAnonymousName name, String sig) {
        String mname = singleName(name);
        if (overloadedNamesAndSigs.contains(mname+sig)) {
            mname = NamingCzar.mangleAwayFromOverload(mname);
        }
        return mname;
    }

    /**
     * Method name, with symbolic-freedom-mangling applied
     *
     * @param name
     * @return
     */
    private String singleName(IdOrOpOrAnonymousName name) {
        String nameString = idOrOpToString((IdOrOp)name);
        String mname = Naming.mangleIdentifier(nameString);
        return mname;
    }

    // belongs in Naming perhaps
    private static String fmDottedName(String name, int selfIndex) {
        // HACK.  Need to be able to express some fmDotteds in Java source code
        // thus, must transmogrify everything that is not A-Z to something else.

//        if (!isBoring(name)) {
//            name = makeBoring(name);
//        }
        //
        name = name + Naming.INDEX + selfIndex;
        return name;
    }


    private static boolean isBoring(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (i == 0 ? Character.isJavaIdentifierStart(ch) : Character.isJavaIdentifierPart(ch))
                continue;
            return false;
        }
        return true;
    }

    private static String makeBoring(String name) {
        StringBuffer b = new StringBuffer();

        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (i == 0 ? Character.isJavaIdentifierStart(ch) : Character.isJavaIdentifierPart(ch)) {
                b.append(ch);
            } else {
                b.append('x');
                b. append(Integer.toHexString(ch));
            }
        }
        return b.toString();
    }

    // Setting up the alias table which we will refer to at runtime.
    public void forFnRef(FnRef x) {
        debug("forFnRef ", x);
        if (fnRefIsApply)
            forFunctionalRef(x);
        else {
            // Not entirely sure about this next bit; how are function-valued parameters referenced?
            VarCodeGen fn = getLocalVarOrNull(x.getOriginalName());
            if (fn == null) {
                // Get it from top level.
                Pair<String, String> pc_and_m= functionalRefToPackageClassAndMethod(x);
                // If it's an overloaded type, oy.
                com.sun.fortress.nodes.Type arrow = exprType(x);
                // Capture the overloading foo, mutilate that into the name of the thing that we want.
                Pair<String, String> method_and_signature = resolveMethodAndSignature(
                        x, arrow, pc_and_m.getB());
                /* we now have package+class, method name, and signature.
                 * Emit a static reference to a field in package/class/method+envelope+mangled_sig.
                 * Classloader will see this, and it will trigger demangling of the name, to figure
                 * out the contents of the class to be loaded.
                 */
                String arrow_desc = NamingCzar.jvmTypeDesc(arrow, thisApi(), true);
                String arrow_type = NamingCzar.jvmTypeDesc(arrow, thisApi(), false);
                String PCN = pc_and_m.getA() + "/" +
                  Naming.catMangled(
                    method_and_signature.getA() ,
                    Naming.ENVELOPE , // "ENVELOPE"
                    arrow_type);
                /* The suffix will be
                 * (potentially mangled)
                 * functionName<ENVELOPE>closureType (which is an arrow)
                 *
                 * must generate code for the class with a method apply, that
                 * INVOKE_STATICs prefix.functionName .
                 */
                mv.visitFieldInsn(Opcodes.GETSTATIC, PCN, NamingCzar.closureFieldName, arrow_desc);

            } else {
                sayWhat(x, "Haven't figured out references to local/parameter functions yet");
            }

        }
    }

    /**
     * @param x
     */
    public void forFunctionalRef(FunctionalRef x) {
        debug("forFunctionalRef " + x);

        /* Arrow, or perhaps an intersection if it is an overloaded function. */
        com.sun.fortress.nodes.Type arrow = exprType(x);

        debug("forFunctionalRef " + x + " arrow = " + arrow);

        Pair<String, String> calleeInfo = functionalRefToPackageClassAndMethod(x);

        callStaticSingleOrOverloaded(x, arrow, calleeInfo.getA(), calleeInfo.getB());
    }

    /**
     * @param x
     * @return
     */
    private Pair<String, String> functionalRefToPackageClassAndMethod(
            FunctionalRef x) {
        Pair<String, String> calleeInfo;

        String name = idOrOpToString(x.getOriginalName());
        List<IdOrOp> names = x.getNames();

        /* Note that after pre-processing in the overload rewriter,
         * there is only one name here; this is not an overload check.
         */
        String calleePackageAndClass = "";
        String method = "";

        if ( names.size() != 1) {
            sayWhat(x,"Non-unique overloading after rewrite " + x);
        } else {
            IdOrOp fnName = names.get(0);
            Option<APIName> apiName = fnName.getApiName();

            if (!apiName.isSome()) {
                // NOT Foreign, calls same component.
                // Nothing special to do.
                calleePackageAndClass = packageAndClassName;
                method = idOrOpToString(fnName);
            } else if (!ForeignJava.only.definesApi(apiName.unwrap())) {
                // NOT Foreign, calls other component.
                calleePackageAndClass =
                    NamingCzar.javaPackageClassForApi(apiName.unwrap());

                method = idOrOpToString(fnName);
            } else if ( aliasTable.containsKey(name) ) {
                // Foreign function call
                // TODO this prefix op belongs in naming czar.
                String n = Naming.NATIVE_PREFIX_DOT + aliasTable.get(name);
                // Cheating by assuming class is everything before the dot.
                int lastDot = n.lastIndexOf(".");
                calleePackageAndClass = n.substring(0, lastDot).replace(".", "/");
                method = n.substring(lastDot+1);
            } else {
                sayWhat(x, "Foreign function " + x + " missing from alias table");
            }
        }
        calleeInfo = new Pair<String, String>(calleePackageAndClass, method);
        return calleeInfo;
    }

    public void forIf(If x) {
        Debug.debug( Debug.Type.CODEGEN, 1,"forIf ", x);
        List<IfClause> clauses = x.getClauses();
        Option<Block> elseClause = x.getElseClause();

        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        org.objectweb.asm.Label falseBranch = new org.objectweb.asm.Label();
        for (IfClause ifclause : clauses) {

            GeneratorClause cond = ifclause.getTestClause();

            if (!cond.getBind().isEmpty())
                sayWhat(x, "Undesugared generalized if expression.");

            // emit code for condition and to check resulting boolean
            Expr testExpr = cond.getInit();
            debug( "about to accept ", testExpr, " of class ", testExpr.getClass());
            testExpr.accept(this);
            addLineNumberInfo(x);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                               NamingCzar.internalFortressBoolean, "getValue",
                               NamingCzar.makeMethodDesc("", NamingCzar.descBoolean));
            mv.visitJumpInsn(Opcodes.IFEQ, falseBranch);

            // emit code for condition true
            ifclause.getBody().accept(this);
            mv.visitJumpInsn(Opcodes.GOTO, done);

            // control goes to following label if condition false (and we continue tests)
            mv.visitLabel(falseBranch);
            falseBranch = new org.objectweb.asm.Label();
        }
        Option<Block> maybe_else = x.getElseClause();
        if (maybe_else.isSome()) {
            maybe_else.unwrap().accept(this);
        } else {
            pushVoid();
        }
        mv.visitLabel(done);
    }

    public void forImportNames(ImportNames x) {
        debug("forImportNames", x);
        Option<String> foreign = x.getForeignLanguage();
        if ( !foreign.isSome() ) return;
        if ( !foreign.unwrap().equals("java") ) {
            sayWhat(x, "Unrecognized foreign import type (only recognize java): "+
                       foreign.unwrap());
            return;
        }
        String apiName = x.getApiName().getText();
        for ( AliasedSimpleName n : x.getAliasedNames() ) {
            Option<IdOrOpOrAnonymousName> aliasId = n.getAlias();
            if (!(aliasId.isSome())) continue;
            debug("forImportNames ", x,
                  " aliasing ", NodeUtil.nameString(aliasId.unwrap()),
                  " to ", NodeUtil.nameString(n.getName()));
            aliasTable.put(NodeUtil.nameString(aliasId.unwrap()),
                           apiName + "." + NodeUtil.nameString(n.getName()));
        }
    }

    public void forIntLiteralExpr(IntLiteralExpr x) {
        debug("forIntLiteral ", x);
        BigInteger bi = x.getIntVal();
        // This might not work.
        int l = bi.bitLength();
        if (l < 32) {
            int y = bi.intValue();
            addLineNumberInfo(x);
            pushInteger(y);
            addLineNumberInfo(x);

            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    NamingCzar.internalFortressZZ32, NamingCzar.make,
                    NamingCzar.makeMethodDesc(NamingCzar.descInt,
                                              NamingCzar.descFortressZZ32));
        } else if (l < 64) {
            long yy = bi.longValue();
            addLineNumberInfo(x);
            mv.visitLdcInsn(yy);
            addLineNumberInfo(x);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    NamingCzar.internalFortressZZ64, NamingCzar.make,
                    NamingCzar.makeMethodDesc(NamingCzar.descLong,
                                              NamingCzar.descFortressZZ64));
        }
    }

    public void forLocalVarDecl(LocalVarDecl d) {
        debug("forLocalVarDecl", d);
        List<LValue> lhs = d.getLhs();
        if (lhs.size()!=1) {
            sayWhat(d, "Can't yet generate code for bindings of multiple lhs variables");
        }
        LValue v = lhs.get(0);
        if (v.isMutable()) {
            sayWhat(d, "Can't yet generate code for mutable variable declarations.");
        }
        if (!d.getRhs().isSome()) {
            // Just a forward declaration to be bound in subsequent
            // code.  But we need to leave a marker so that the
            // definitions down different control flow paths are
            // consistent; basically we need to create the definition
            // here, and the use that VarCodeGen object for the
            // subsequent true definitions.
            sayWhat(d, "Can't yet handle forward binding declarations.");
        }
        if (!v.getIdType().isSome()) {
            sayWhat(d, "Variable being bound lacks type information!");
        }

        // Introduce variable
        Type ty = v.getIdType().unwrap();
        VarCodeGen vcg = new VarCodeGen.LocalVar(v.getName(), ty, this);
        vcg.prepareAssignValue(mv);

        // Compute rhs
        Expr rhs = d.getRhs().unwrap();
        rhs.accept(this);

        // Perform binding
        vcg.assignValue(mv);

        // Evaluate rest of block with binding in scope
        CodeGen cg = new CodeGen(this);
        cg.addLocalVar(vcg);

        cg.doStatements(d.getBody().getExprs());

        // Dispose of binding now that we're done
        vcg.outOfScope(mv);
    }

    public void forObjectDecl(ObjectDecl x) {
        TraitTypeHeader header = x.getHeader();
        emittingFunctionalMethodWrappers = true;
        String classFile = NamingCzar.makeInnerClassName(packageAndClassName,
                                                         idToString(NodeUtil.getName(x)));
        debug("forObjectDecl ",x," classFile = ", classFile);
        traitOrObjectName = classFile;
        dumpTraitDecls(header.getDecls());
        emittingFunctionalMethodWrappers = false;
        traitOrObjectName = null;
    }

    public void forObjectDeclPrePass(ObjectDecl x, List<Decl> singletonObjects) {
        debug("forObjectDeclPrePass ", x);
        TraitTypeHeader header = x.getHeader();
        List<TraitTypeWhere> extendsC = header.getExtendsClause();

        boolean canCompile =
            // x.getParams().isNone() &&             // no parameters
            header.getStaticParams().isEmpty() && // no static parameter
            header.getWhereClause().isNone() &&   // no where clause
            header.getThrowsClause().isNone() &&  // no throws clause
            header.getContract().isNone() &&      // no contract
            //            header.getDecls().isEmpty() &&        // no members
            header.getMods().isEmpty()         // no modifiers
            // ( extendsC.size() <= 1 ); // 0 or 1 super trait
            ;

        if ( !canCompile ) sayWhat(x);

        boolean savedInAnObject = inAnObject;
        inAnObject = true;
        String [] superInterfaces =
            NamingCzar.extendsClauseToInterfaces(extendsC, component.getName());

        String classFile = NamingCzar.makeInnerClassName(packageAndClassName,
                                                         idToString(NodeUtil.getName(x)));
        traitOrObjectName = classFile;
        debug("forObjectDeclPrePass ",x," classFile = ", classFile);


        List<Param> params;
        if (x.getParams().isSome()) {
            params = x.getParams().unwrap();

             // Generate the factory method
            String classDesc = NamingCzar.internalToDesc(classFile);
            String sig = NamingCzar.jvmSignatureFor(params, classDesc, thisApi());
            String init_sig = NamingCzar.jvmSignatureFor(params, "V", thisApi());

            String mname = nonCollidingSingleName(x.getHeader().getName(), sig);

            mv = cw.visitMethod(Opcodes.ACC_STATIC,
                    mname,
                    sig,
                    null,
                    null);

            mv.visitTypeInsn(Opcodes.NEW, classFile);
            mv.visitInsn(Opcodes.DUP);

            // iterate, pushing parameters, beginning at zero.
           // TODO actually handle N>0 parameters.

            int stack_offset = 0;
            for (Param p : params) {
                // when we unbox, this will be type-dependent
                mv.visitVarInsn(Opcodes.ALOAD, stack_offset);
                stack_offset++;
            }

            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, classFile, "<init>", init_sig);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
            mv.visitEnd();
        } else { // singleton
            params = Collections.<Param>emptyList();
            // Add to list to be initialized in clinit
            singletonObjects.add(x);
        }

        CodeGenClassWriter prev = cw;

        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visitSource(classFile, null);

        // Until we resolve the directory hierarchy problem.
        //            cw.visit( Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER+ Opcodes.ACC_FINAL,
        //                      classFile, null, NamingCzar.internalObject, new String[] { parent });
        cw.visit( Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER+ Opcodes.ACC_FINAL,
                  classFile, null, NamingCzar.internalObject, superInterfaces);

        // Emit fields here, one per parameter.

        generateFieldsAndInitMethod(classFile, NamingCzar.internalObject, params);

        BATree<String, VarCodeGen> savedLexEnv = lexEnv.copy();

        // need to add locals to the environment, yes.
        // each one has name, mangled with a preceding "$"
        for (Param p : params) {
            Type param_type = p.getIdType().unwrap();
            String objectFieldName = p.getName().getText();
            Id id =
               NodeFactory.makeId(NodeUtil.getSpan(p.getName()), objectFieldName);
            addStaticVar(new VarCodeGen.FieldVar(id,
                    param_type,
                    classFile,
                    objectFieldName,
                    NamingCzar.jvmTypeDesc(param_type, component.getName(), true)));
        }

        currentTraitObjectDecl = x;
        for (Decl d : header.getDecls()) {
            // This does not work yet.
            d.accept(this);
        }

        lexEnv = savedLexEnv;
        dumpClass( classFile );
        cw = prev;
        inAnObject = savedInAnObject;
        traitOrObjectName = null;
    }

    /**
     * @param classFile
     * @param objectFieldName
     */
    private void singletonObjectFieldAndInit(ObjectDecl x) {
        String classFile = NamingCzar.makeInnerClassName(packageAndClassName,
                idToString(NodeUtil.getName(x)));

        String objectFieldName = x.getHeader().getName().stringName();


        String classDesc = NamingCzar.internalToDesc(classFile);

         // Singleton field.
        cw.visitField(Opcodes.ACC_STATIC + Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL,
                objectFieldName,
                      classDesc,
                      null,
                      null);

        mv.visitTypeInsn(Opcodes.NEW, classFile);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, classFile, "<init>", NamingCzar.voidToVoid);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, packageAndClassName, objectFieldName, classDesc);

        Id id = (Id)  x.getHeader().getName();

        addStaticVar(new VarCodeGen.StaticBinding(
                    id,
                    NodeFactory.makeTraitType(id),
                    packageAndClassName, objectFieldName, classDesc
                ));
    }

    // This returns a list rather than a set because the order matters;
    // we should guarantee that we choose a consistent order every time.
    private List<VarCodeGen> getFreeVars(Node n) {
        BASet<IdOrOp> allFvs = fv.freeVars(n);
        List<VarCodeGen> vcgs = new ArrayList<VarCodeGen>();
        if (allFvs == null) sayWhat((ASTNode)n," null free variable information!");
        for (IdOrOp v : allFvs) {
            VarCodeGen vcg = getLocalVarOrNull(v);
            if (vcg != null) vcgs.add(vcg);
        }
        return vcgs;
    }

    private BATree<String, VarCodeGen>
            createTaskLexEnvVariables(String taskClass, List<VarCodeGen> freeVars) {

        BATree<String, VarCodeGen> result =
            new BATree<String, VarCodeGen>(StringHashComparer.V);
        for (VarCodeGen v : freeVars) {
            String name = v.name.getText();
            cw.visitField(Opcodes.ACC_PUBLIC, name,
                          NamingCzar.boxedImplDesc(v.fortressType, thisApi()),
                          null, null);
            result.put(name, new TaskVarCodeGen(v, taskClass, thisApi()));
        }
        return result;
    }

    private void generateTaskInit(String baseClass,
                                  String initDesc,
                                  List<VarCodeGen> freeVars) {

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", initDesc, null, null);
        mv.visitCode();

        // Call superclass constructor
        mv.visitVarInsn(Opcodes.ALOAD, mv.getThis());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, baseClass,
                              "<init>", NamingCzar.voidToVoid);
        // mv.visitVarInsn(Opcodes.ALOAD, mv.getThis());

        // Stash away free variables Warning: freeVars contains
        // VarCodeGen objects from the parent context, we must look
        // these up again in the child context or we'll get incorrect
        // code (or more usually the compiler will complain).
        int varIndex = 1;
        for (VarCodeGen v0 : freeVars) {
            VarCodeGen v = lexEnv.get(v0.name.getText());
            v.prepareAssignValue(mv);
            mv.visitVarInsn(Opcodes.ALOAD, varIndex++);
            v.assignValue(mv);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }

    private void generateTaskCompute(String className, Expr x, String result) {
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL,
                                  "compute", "()V", null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, mv.getThis());

        x.accept(this);

        mv.visitFieldInsn(Opcodes.PUTFIELD, className, "result", result);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }

    // I'm just a stub.  Someday I'll have a body that updates the changed local variables.
    private BATree<String, VarCodeGen> restoreFromTaskLexEnv(BATree<String,VarCodeGen> old, BATree<String,VarCodeGen> task) {
        return task;
    }

    public String taskConstructorDesc(List<VarCodeGen> freeVars) {
        // And their types
        List<Type> freeVarTypes = new ArrayList(freeVars.size());
        for (VarCodeGen v : freeVars) {
            freeVarTypes.add(v.fortressType);
        }
        return NamingCzar.jvmTypeDescForGeneratedTaskInit(freeVarTypes, component.getName());
    }

    // This sets up the parallel task construct.
    // Caveat: We create separate taskClasses for every task
    public String delegate(Expr x, String result, String init, List<VarCodeGen> freeVars) {

        String className = NamingCzar.gensymTaskName(packageAndClassName);

        debug("delegate creating class ", className, " node = ", x,
              " constructor type = ", init, " result type = ", result);

        // Create a new environment, and codegen task class in it.
        CodeGen cg = new CodeGen(this);
        cg.cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES);
        cg.cw.visitSource(className,null);

        cg.lexEnv = cg.createTaskLexEnvVariables(className, freeVars);
        // WARNING: result may need mangling / NamingCzar-ing.
        cg.cw.visitField(Opcodes.ACC_PUBLIC, "result", result, null, null);

        cg.cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
                    className, null, NamingCzar.fortressBaseTask, null);

        cg.generateTaskInit(NamingCzar.fortressBaseTask, init, freeVars);

        cg.generateTaskCompute(className, x, result);

        cg.dumpClass(className);

        this.lexEnv = restoreFromTaskLexEnv(cg.lexEnv, this.lexEnv);
        return className;
    }

    public void constructWithFreeVars(String cname, List<VarCodeGen> freeVars, String sig) {
            mv.visitTypeInsn(Opcodes.NEW, cname);
            mv.visitInsn(Opcodes.DUP);
            // Push the free variables in order.
            for (VarCodeGen v : freeVars) {
                v.pushValue(mv);
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, cname, "<init>", sig);
    }

    // Evaluate args in parallel.  (Why is profitability given at a
    // level where we can't ask the qustion here?)
    // Leave the results (in the order given) on the stack.
    public void forExprsParallel(List<? extends Expr> args) {
        final int n = args.size();
        if (n <= 0) return;
        String [] tasks = new String[n];
        String [] results = new String[n];
        int [] taskVars = new int[n];

        // Push arg tasks from right to left, so
        // that local evaluation of args will proceed left to right.
        // IMPORTANT: ALWAYS fork and join stack fashion,
        // ie always join with the most recent fork first.
        for (int i = n-1; i > 0; i--) {
            Expr arg = args.get(i);
            // Make sure arg has type info (we'll need it to generate task)
            Option<Type> ot = NodeUtil.getExprType(arg);
            if (!ot.isSome())
                sayWhat(arg, "Missing type information for argument " + arg);
            Type t = ot.unwrap();
            String tDesc = NamingCzar.jvmTypeDesc(t, component.getName());
            // Find free vars of arg
            List<VarCodeGen> freeVars = getFreeVars(arg);

            // Generate descriptor for init method of task
            String init = taskConstructorDesc(freeVars);

            String task = delegate(arg, tDesc, init, freeVars);
            tasks[i] = task;
            results[i] = tDesc;

            constructWithFreeVars(task, freeVars, init);

            mv.visitInsn(Opcodes.DUP);
            int taskVar = mv.createCompilerLocal(Naming.mangleIdentifier(task), "L"+task+";");
            taskVars[i] = taskVar;
            mv.visitVarInsn(Opcodes.ASTORE, taskVar);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, task, "forkIfProfitable", "()V");
        }
        // arg 0 gets compiled in place, rather than turned into work.
        args.get(0).accept(this);
        // join / perform work locally left to right, leaving results on stack.
        for (int i = 1; i < n; i++) {
            int taskVar = taskVars[i];
            mv.visitVarInsn(Opcodes.ALOAD, taskVar);
            mv.disposeCompilerLocal(taskVar);
            mv.visitInsn(Opcodes.DUP);
            String task = tasks[i];
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, task, "joinOrRun", "()V");
            mv.visitFieldInsn(Opcodes.GETFIELD, task, "result", results[i]);
        }
    }

    public void forOpExpr(OpExpr x) {
        debug("forOpExpr ", x, " op = ", x.getOp(),
                     " of class ", x.getOp().getClass(),  " args = ", x.getArgs());
        FunctionalRef op = x.getOp();
        List<Expr> args = x.getArgs();

        if (pa.worthParallelizing(x)) {
            forExprsParallel(args);
        } else {

            for (Expr arg : args) {
                arg.accept(this);
            }
        }

        op.accept(this);

    }

    public void forOpRef(OpRef x) {
        forFunctionalRef(x);
   }

    public void forStringLiteralExpr(StringLiteralExpr x) {
        // This is cheating, but the best we can do for now.
        // We make a FString and push it on the stack.
        debug("forStringLiteral ", x);
        addLineNumberInfo(x);
        mv.visitLdcInsn(x.getText());
        addLineNumberInfo(x);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NamingCzar.internalFortressString, NamingCzar.make,
                           NamingCzar.makeMethodDesc(NamingCzar.descString, NamingCzar.descFortressString));
    }

    public void forSubscriptExpr(SubscriptExpr x) {
        // TODO: FIX!!  Only works for string subscripting.  Why does this
        // AST node still exist at all at this point in compilation??
        // It ought to be turned into a MethodInvocation.
        // JWM 9/4/09
        debug("forSubscriptExpr ", x);
        Expr obj = x.getObj();
        List<Expr> subs = x.getSubs();
        Option<Op> maybe_op = x.getOp();
        List<StaticArg> staticArgs = x.getStaticArgs();
        boolean canCompile = staticArgs.isEmpty() && maybe_op.isSome() && (obj instanceof VarRef);
        if (!canCompile) { sayWhat(x); return; }
        Op op = maybe_op.unwrap();
        VarRef var = (VarRef) obj;
        Id id = var.getVarId();

        debug("ForSubscriptExpr  ", x, "obj = ", obj,
              " subs = ", subs, " op = ", op, " static args = ", staticArgs,
              " varRef = ", idToString(id));

        addLineNumberInfo(x);
        mv.visitFieldInsn(Opcodes.GETSTATIC, NamingCzar.jvmClassForSymbol(id) ,
                          idToString(id),
                          "L" + NamingCzar.makeInnerClassName(id) + ";");

        for (Expr e : subs) {
            debug("calling accept on ", e);
            e.accept(this);
        }
        addLineNumberInfo(x);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                           NamingCzar.makeInnerClassName(id),
                           Naming.mangleIdentifier(opToString(op)),
                           "(Lcom/sun/fortress/compiler/runtimeValues/FZZ32;)Lcom/sun/fortress/compiler/runtimeValues/FString;");
    }

    public void forTraitDecl(TraitDecl x) {
        debug("forTraitDecl", x);
        TraitTypeHeader header = x.getHeader();
        List<TraitTypeWhere> extendsC = header.getExtendsClause();
        boolean canCompile =
            // NOTE: Presence of excludes or comprises clauses should not
            // affect code generation once type checking is complete.
            // x.getExcludesClause().isEmpty() &&    // no excludes clause
            header.getStaticParams().isEmpty() && // no static parameter
            header.getWhereClause().isNone() &&   // no where clause
            header.getThrowsClause().isNone() &&  // no throws clause
            header.getContract().isNone() &&      // no contract
            header.getMods().isEmpty() ; // && // no modifiers
            // extendsC.size() <= 1;
        debug("forTraitDecl", x,
                    " decls = ", header.getDecls(), " extends = ", extendsC);
        if ( !canCompile ) sayWhat(x);
        inATrait = true;
        currentTraitObjectDecl = x;
        String [] superInterfaces = NamingCzar.extendsClauseToInterfaces(extendsC, component.getName());

        // First let's do the interface class
        String classFile = NamingCzar.makeInnerClassName(packageAndClassName,
                                                         NodeUtil.getName(x).getText());
        traitOrObjectName = classFile;
        if (classFile.equals("fortress/AnyType$Any")) {
            superInterfaces = new String[0];
        }
        CodeGenClassWriter prev = cw;
        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visitSource(classFile, null);
        cw.visit( Opcodes.V1_5,
                  Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                  classFile, null, NamingCzar.internalObject, superInterfaces);
        dumpSigs(header.getDecls());
        dumpClass( classFile );

        // Now lets do the springboard inner class that implements this interface.
        springBoardClass = classFile + NamingCzar.springBoard;
        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visitSource(springBoardClass, null);
        // I think springboard can be abstract.
        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, springBoardClass,
                 null, NamingCzar.FValueType, new String[0] );
        debug("Start writing springboard class ",
              springBoardClass);
        generateFieldsAndInitMethod(springBoardClass, NamingCzar.FValueType, Collections.<Param>emptyList());
        debug("Finished init method ", springBoardClass);
        dumpTraitDecls(header.getDecls());
        debug("Finished dumpDecls ", springBoardClass);
        dumpClass(springBoardClass);
        // Now lets dump out the functional methods at top level.
        cw = prev;
        cw.visitSource(classFile, null);

        emittingFunctionalMethodWrappers = true;
        dumpTraitDecls(header.getDecls());
        emittingFunctionalMethodWrappers = false;

        debug("Finished dumpDecls for parent");
        inATrait = false;
        traitOrObjectName = null;
        springBoardClass = null;
    }

    public void forVarDecl(VarDecl v) {
        // Assumption: we already dealt with this VarDecl in pre-pass.
        // Therefore we can just skip it.
        debug("forVarDecl ",v," should have been seen during pre-pass.");
    }

    public void forVarDeclPrePass(VarDecl v) {
        List<LValue> lhs = v.getLhs();
        Option<Expr> oinit = v.getInit();
        if (lhs.size() != 1) {
            sayWhat(v,"VarDecl "+v+" tupled lhs not handled.");
        }
        if (!oinit.isSome()) {
            debug("VarDecl "+v+" skipping abs var decl.");
            return;
        }
        LValue lv = lhs.get(0);
        if (lv.isMutable()) {
            sayWhat(v,"VarDecl "+v+" mutable bindings not yet handled.");
        }
        Id var = lv.getName();
        String varName = var.stringName();
        Type ty = lv.getIdType().unwrap();
        String tyDesc = NamingCzar.jvmTypeDesc(ty, thisApi());
        Expr exp = oinit.unwrap();
        debug("VarDeclPrePass "+var+" : "+ty+" = "+exp);
        cw.visitField(Opcodes.ACC_STATIC + Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL,
                      varName, tyDesc, null, null);

        exp.accept(this);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, packageAndClassName, varName, tyDesc);

        addStaticVar(
            new VarCodeGen.StaticBinding(var, ty, packageAndClassName, varName, tyDesc));
    }

    public void forVarRef(VarRef v) {
        if (v.getStaticArgs().size() > 0) {
            sayWhat(v,"varRef with static args!  That requires non-local VarRefs");
        }
        VarCodeGen vcg = getLocalVarOrNull(v.getVarId());
        if (vcg==null) sayWhat(v, "Can't find lexEnv mapping for local var");
        debug("forVarRef ", v , " Value = " + vcg);
        vcg.pushValue(mv);
    }

    private void pushVoid() {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NamingCzar.internalFortressVoid, NamingCzar.make,
                           NamingCzar.makeMethodDesc("", NamingCzar.descFortressVoid));
    }

    public void forVoidLiteralExpr(VoidLiteralExpr x) {
        debug("forVoidLiteral ", x);
        addLineNumberInfo(x);
        pushVoid();
    }

    public void forMethodInvocation(MethodInvocation x) {
        Id method = x.getMethod();
        Expr obj = x.getObj();
        List<StaticArg> sargs = x.getStaticArgs();
        Expr arg = x.getArg();

        Option<Type> mt = x.getOverloadingType();
        Type domain_type;
        Type range_type;
        if ((mt.isSome())) {
            ArrowType sigtype = (ArrowType) mt.unwrap();
            domain_type = sigtype.getDomain();
            range_type = sigtype.getRange();
        } else {
            // TODO: some method applications (particularly those
            // introduced during getter desugaring) don't have an
            // OverloadingType.  Fix?  Or live with it?
            domain_type = exprType(arg);
            range_type = exprType(x);
        }

        Type receiverType = exprType(obj);
        if (!(receiverType instanceof TraitType)) {
            sayWhat(x, "receiver type is not TraitType in " + x);
        }

        int savedParamCount = paramCount;
        try {
            // put object on stack
            obj.accept(this);
            // put args on stack
            evalArg(arg);
            methodCall(method, (TraitType)receiverType, domain_type, range_type);
        } finally {
            paramCount = savedParamCount;
        }

    }

    /**
     * @param expr
     * @return
     */
    private Type exprType(Expr expr) {
        Option<Type> exprType = expr.getInfo().getExprType();

        if (!exprType.isSome()) {
            sayWhat(expr, "Missing type information for " + expr);
        }

        return exprType.unwrap();
    }
    private Option<Type> exprOptType(Expr expr) {
        Option<Type> exprType = expr.getInfo().getExprType();

        return exprType;
    }

    /**
     * @param arg
     */
    private void evalArg(Expr arg) {
        if (arg instanceof VoidLiteralExpr) {
            paramCount = 0;
        } else if (arg instanceof TupleExpr) {
            TupleExpr targ = (TupleExpr) arg;
            List<Expr> exprs = targ.getExprs();
            for (Expr expr : exprs) {
                expr.accept(this);
            }
            paramCount = exprs.size();
        } else {
            paramCount = 1; // for now; need to dissect tuple and do more.
            arg.accept(this);
        }
    }

    private void generateHigherOrderCall(Type t) {
        if (!(t instanceof ArrowType)) {
            sayWhat(t,"Higher-order call to non-arrow type " + t);
        }
        ArrowType at = (ArrowType)t;
        String desc = NamingCzar.makeArrowDescriptor(at, thisApi());
        String sig = NamingCzar.jvmSignatureFor(at,thisApi());
        // System.err.println(desc+".apply"+sig+" call");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, desc,
                           Naming.APPLY_METHOD, sig);
    }

    public void for_RewriteFnApp(_RewriteFnApp x) {
        debug("for_RewriteFnApp ", x,
                     " args = ", x.getArgument(), " function = ", x.getFunction() +
              " function class = " + x.getFunction());
        // This is a little weird.  If a function takes no arguments the parser gives me a void literal expr
        // however I don't want to be putting a void literal on the stack because it gets in the way.
        int savedParamCount = paramCount;
        boolean savedFnRefIsApply = fnRefIsApply;
        try {
            Expr arg = x.getArgument();
            Expr fn = x.getFunction();
            if (!(fn instanceof FunctionalRef)) {
                // Higher-order call.
                fn.accept(this); // Puts the VarRef function on the stack.
            }
            fnRefIsApply = false;
            evalArg(arg);
            fnRefIsApply = true;
            if (!(fn instanceof FunctionalRef)) {
                generateHigherOrderCall(exprType(fn));
            } else {
                x.getFunction().accept(this);
            }
        } finally {
            paramCount = savedParamCount;
            fnRefIsApply = savedFnRefIsApply;
        }
    }

    public void for_RewriteFnOverloadDecl(_RewriteFnOverloadDecl x) {
        /* Note for refactoring -- this code does it the "right" way.
         * And also, this code NEEDS refactoring.
         */
        List<IdOrOp> fns = x.getFns();
        IdOrOp name = x.getName();
        Option<com.sun.fortress.nodes.Type> ot = x.getType();
        com.sun.fortress.nodes.Type ty = ot.unwrap();
        Relation<IdOrOpOrAnonymousName, Function> fnrl = ci.functions();

        MultiMap<Integer, OverloadSet.TaggedFunctionName> byCount =
            new MultiMap<Integer,OverloadSet.TaggedFunctionName>();

        for (IdOrOp fn : fns) {

            Option<APIName> fnapi = fn.getApiName();
            PredicateSet<Function> set_of_f;
            APIName apiname;

            if (fnapi.isNone()) {
                apiname = thisApi();
                set_of_f = fnrl.matchFirst(fn);
            } else {

                IdOrOp fnnoapi = NodeFactory.makeLocalIdOrOp(fn);
                apiname = fnapi.unwrap();
                ApiIndex ai = env.api(apiname);
                set_of_f = ai.functions().matchFirst(fnnoapi);
            }

            for (Function f : set_of_f) {
                /* This guard should be unnecessary when proper overload
                   disambiguation is working.  Right now, the types are
                   "too accurate" which causes a call to an otherwise
                   non-existent static method.
                */
                if (OverloadSet.functionInstanceofType(f, ty, ta)) {
                    OverloadSet.TaggedFunctionName tagged_f = new OverloadSet.TaggedFunctionName(apiname, f);
                    byCount.putItem(f.parameters().size(), tagged_f);
                }
            }
        }

        for (Map.Entry<Integer, Set<OverloadSet.TaggedFunctionName>> entry : byCount
                .entrySet()) {
            int i = entry.getKey();
            Set<OverloadSet.TaggedFunctionName> fs = entry.getValue();
            if (fs.size() > 1) {
                OverloadSet os = new OverloadSet.AmongApis(thisApi(), name,
                        ta, fs, i);

                os.split(false);
                os.generateAnOverloadDefinition(name.stringName(), cw);

            }
        }

    }


    /**
     * Creates overloaded functions for any overloads present at the top level
     * of this component.  Top level overloads are those that might be exported;
     * Reference overloads are rewritten into _RewriteFnOverloadDecl nodes
     * and generated in the normal visits.
     */
    public static Set<String> generateTopLevelOverloads(APIName api_name,
            Map<IdOrOpOrAnonymousName,MultiMap<Integer, Function>> size_partitioned_overloads,
            TypeAnalyzer ta,
            ClassWriter cw
            ) {

        Set<String> overloaded_names_and_sigs = new HashSet<String>();

        for (Map.Entry<IdOrOpOrAnonymousName, MultiMap<Integer, Function>> entry1 : size_partitioned_overloads.entrySet()) {
            IdOrOpOrAnonymousName  name = entry1.getKey();
            MultiMap<Integer, Function> partitionedByArgCount = entry1.getValue();

            for (Map.Entry<Integer, Set<Function>> entry : partitionedByArgCount
                    .entrySet()) {
               int i = entry.getKey();
               Set<Function> fs = entry.getValue();

               OverloadSet os =
                   new OverloadSet.Local(api_name, name,
                                         ta, fs, i);

               os.split(true);

               String s = name.stringName();
               String s2 = NamingCzar.only.apiAndMethodToMethod(api_name, s);

               os.generateAnOverloadDefinition(s2, cw);

               for (Map.Entry<String, OverloadSet> o_entry : os.getOverloadSubsets().entrySet()) {
                   String ss = o_entry.getKey();
                   ss = s + ss;
                   overloaded_names_and_sigs.add(ss);
               }
           }
        }
        return overloaded_names_and_sigs;
    }

    public static Map<IdOrOpOrAnonymousName, MultiMap<Integer, Function>>
       sizePartitionedOverloads(Relation<IdOrOpOrAnonymousName, Function> fns) {

        Map<IdOrOpOrAnonymousName, MultiMap<Integer, Function>> result =
            new HashMap<IdOrOpOrAnonymousName, MultiMap<Integer, Function>>();

        for (IdOrOpOrAnonymousName name : fns.firstSet()) {
            Set<Function> defs = fns.matchFirst(name);
            if (defs.size() <= 1) continue;

            MultiMap<Integer, Function> partitionedByArgCount =
                new MultiMap<Integer, Function>();

            for (Function d : defs) {
                partitionedByArgCount.putItem(d.parameters().size(), d);
            }

            for (Function d : defs) {
                Set<Function> sf = partitionedByArgCount.get(d.parameters().size());
                if (sf != null && sf.size() <= 1)
                    partitionedByArgCount.remove(d.parameters().size());
            }
            if (partitionedByArgCount.size() > 0)
                result.put(name, partitionedByArgCount);
        }

        return result;
    }

    private List<Decl> topSortDeclsByDependencies(List<Decl> decls) {
        HashMap<IdOrOp, TopSortItemImpl<Decl>> varToNode =
            new HashMap<IdOrOp, TopSortItemImpl<Decl>>(2 * decls.size());
        List<TopSortItemImpl<Decl>> nodes = new ArrayList(decls.size());
        for (Decl d : decls) {
            TopSortItemImpl<Decl> node =
                new TopSortItemImpl<Decl>(d);
            nodes.add(node);
            if (d instanceof VarDecl) {
                VarDecl vd = (VarDecl)d;
                for (LValue lv : vd.getLhs()) {
                    varToNode.put(lv.getName(), node);
                }
            } else if (d instanceof TraitObjectDecl) {
                TraitObjectDecl tod = (TraitObjectDecl)d;
                IdOrOpOrAnonymousName name = tod.getHeader().getName();
                if (name instanceof IdOrOp) {
                    varToNode.put((IdOrOp)name, node);
                }
            } else {
                sayWhat(d, " can't sort non-value-creating decl by dependencies.");
            }
        }
        for (TopSortItemImpl<Decl> node : nodes) {
            for (IdOrOp freeVar : fv.freeVars(node.x)) {
                TopSortItemImpl<Decl> dest = varToNode.get(freeVar);
                if (dest != null && dest != node) {
                    node.edgeTo(dest);
                }
            }
        }
        // TODO: can't handle cycles!
        nodes = TopSort.depthFirst(nodes);
        List<Decl> result = new ArrayList(nodes.size());
        for (TopSortItemImpl<Decl> node : nodes) {
            result.add(node.x);
        }
        return result;
    }

    /**
     * Traits compile to interfaces.  These are all the abstract methods that
     * the interface will require.
     *
     * @param decls
     */
    private void dumpSigs(List<Decl> decls) {
        debug("dumpSigs", decls);
        for (Decl d : decls) {
            debug("dumpSigs decl =", d);
            if (!(d instanceof FnDecl)) {
                sayWhat(d);
                return;
            }

            FnDecl f = (FnDecl) d;
            FnHeader h = f.getHeader();

            List<Param> params = h.getParams();
            int selfIndex = selfParameterIndex(params);
            boolean  functionalMethod = selfIndex != -1;

            IdOrOpOrAnonymousName xname = h.getName();
            IdOrOp name = (IdOrOp) xname;

            String desc = NamingCzar.jvmSignatureFor(f,component.getName());
            if (functionalMethod) {
                desc = Naming.removeNthSigParameter(desc, selfIndex);
            }

            // TODO what about overloading collisions in an interface?
            // it seems wrong to publicly mangle.
            String mname = functionalMethod ? fmDottedName(
                            singleName(name), selfIndex) : nonCollidingSingleName(
                                    name, desc);

            mv = cw.visitMethod(Opcodes.ACC_ABSTRACT + Opcodes.ACC_PUBLIC,
                                mname, desc, null, null);

            mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
            mv.visitEnd();
        }
    }

    /**
     * @param fnName
     * @return
     */
    private String idOrOpToString(IdOrOp fnName) {
        if (fnName instanceof Op)
            return opToString((Op) fnName);
        else if (fnName instanceof Id)
            return idToString((Id) fnName);
        else
            return fnName.getText();

    }

    /**
     * @param op
     * @return
     */
    private String opToString(Op op) {
        Fixity fixity = op.getFixity();
        if (fixity instanceof PreFixity) {
            return op.getText() + Naming.BOX;
        } else if (fixity instanceof PostFixity) {
            return Naming.BOX + op.getText();
        } else {
          return op.getText();
        }
    }

    /**
     * @param method
     * @return
     */
    private String idToString(Id id) {
        return id.getText();
    }

}
