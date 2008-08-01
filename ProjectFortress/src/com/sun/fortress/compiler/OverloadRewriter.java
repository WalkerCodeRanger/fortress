package com.sun.fortress.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Decl;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOpName;
import com.sun.fortress.nodes.OpName;
import com.sun.fortress.nodes._RewriteFnOverloadDecl;
import com.sun.fortress.nodes_util.NodeFactory;

public class OverloadRewriter {


    /** Please remove this flag once overload rewriting is fully implemented */
    private static final boolean killSwitch = true;
    
    public static class ComponentResult extends StaticPhaseResult {
        private final Map<APIName, ComponentIndex> _components;
        public ComponentResult(Map<APIName, ComponentIndex> components,
                               Iterable<? extends StaticError> errors) {
            super(errors);
            _components = components;
        }
        public Map<APIName, ComponentIndex> components() { return _components; }
    }

    /** Statically check the given components. */
    public static ComponentResult
        rewriteComponents(Map<APIName, ComponentIndex> components,
                          GlobalEnvironment env)
    {
        HashSet<Component> rewrittenComponents = new HashSet<Component>();
        Iterable<? extends StaticError> errors = new HashSet<StaticError>();

        for (APIName componentName : components.keySet()) {
            Component rewrite = rewriteComponent(components.get(componentName), env);
            rewrittenComponents.add(rewrite);
        }
        return new ComponentResult
            (IndexBuilder.buildComponents(rewrittenComponents,
                                          System.currentTimeMillis()).
                 components(), errors);
    }

    public static Component rewriteComponent(ComponentIndex component,
                                             GlobalEnvironment env) {
        Component comp = (Component) component.ast();
        OverloadRewriteVisitor visitor = new OverloadRewriteVisitor();
        if (!killSwitch) {
            comp = (Component) comp.accept(visitor);
        }
        List<Decl> decls = comp.getDecls();

        Map<String, List<Id>> overloadedFunctions = visitor.getOverloadedFunctions();
        for (Map.Entry<String, List<Id>> overload : overloadedFunctions.entrySet()) {
            List<IdOrOpName> overloadings = new ArrayList<IdOrOpName>(overload.getValue().size());
            for (Id overloadId : overload.getValue()) {
                overloadings.add(overloadId);
            }
            Id overloadingId = NodeFactory.makeId(overload.getKey());
            _RewriteFnOverloadDecl newDecl = new _RewriteFnOverloadDecl(overloadingId, overloadings);
            decls.add(newDecl);
        }
        
        Map<String, List<OpName>> overloadedOperators = visitor.getOverloadedOperators();
        for (Map.Entry<String, List<OpName>> overload : overloadedOperators.entrySet()) {
            List<IdOrOpName> overloadings = new ArrayList<IdOrOpName>(overload.getValue().size());
            for (OpName overloadId : overload.getValue()) {
                overloadings.add(overloadId);
            }
            OpName overloadingOpName = NodeFactory.makeOp(overload.getKey());
            _RewriteFnOverloadDecl newDecl = new _RewriteFnOverloadDecl(overloadingOpName, overloadings);
            decls.add(newDecl);
        }                
        return comp;
    }    
    
}