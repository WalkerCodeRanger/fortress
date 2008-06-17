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

package com.sun.fortress.syntax_abstractions.environments;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.sun.fortress.compiler.index.NonterminalIndex;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.BaseType;
import com.sun.fortress.nodes.GrammarMemberDecl;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.NonterminalDecl;
import com.sun.fortress.nodes.SyntaxDef;
import com.sun.fortress.nodes.TerminalDecl;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.parser_util.FortressUtil;
import com.sun.fortress.useful.Pair;

import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.tuple.Option;

public class MemberEnv {

    private BaseType astType;
    private Id name;
    private Id[] params;
    
    private Map<SyntaxDef, SyntaxDeclEnv> syntaxDefToEnv;
    private Map<Id, Id> argsToNonterminalMap;

	private MemberEnv() {
		this.argsToNonterminalMap = new HashMap<Id, Id>();
		this.syntaxDefToEnv = new HashMap<SyntaxDef, SyntaxDeclEnv>();
	}
	
	public MemberEnv(NonterminalIndex<? extends GrammarMemberDecl> member) {
		this();
		this.name = member.getName();
		this.astType = member.getAstType();
		
		if (member.getAst() instanceof NonterminalDecl) {
			NonterminalDecl nd = (NonterminalDecl) member.getAst();
			initEnv(nd.getHeader().getParams(), nd.getSyntaxDefs());
		}
		else if (member.getAst() instanceof TerminalDecl) {
			TerminalDecl nd = (TerminalDecl) member.getAst();
			List<SyntaxDef> ls = new LinkedList<SyntaxDef>();
			ls.add(nd.getSyntaxDef());
			initEnv(nd.getHeader().getParams(), ls);
		}
	}
	
	private void initEnv(List<Pair<Id,Id>> ls, List<SyntaxDef> syntaxDefs) {
		Id[] params = new Id[ls.size()];
		int inx = 0;
		for (Pair<Id, Id> p: ls) {
			Id var = p.getA();
			params[inx] = var;
            
			this.addArgsNonterminal(var, p.getB());
			inx++;
		}
		this.setParamArray(params);
		
		for (SyntaxDef sd: syntaxDefs) {
			SyntaxDeclEnv sdEnv = new SyntaxDeclEnv(sd, this);
			this.add(sd, sdEnv);
		}
	}

	public void add(SyntaxDef sd, SyntaxDeclEnv sdEnv) {
		this.syntaxDefToEnv.put(sd, sdEnv);		
	}
	
    public void rebuildSyntaxDeclEnvs(List<SyntaxDef> syntaxDefs) {
        this.syntaxDefToEnv.clear();
        for (SyntaxDef sd: syntaxDefs) {
            this.add(sd, new SyntaxDeclEnv(sd, this));
        }
    }

	private void addArgsNonterminal(Id var, Id t) {
		this.argsToNonterminalMap.put(var, t);		
	}
	
	private void setParamArray(Id[] params) {
		this.params = params;
	}

	/**
	 * Returns the n'th parameter counting from 0
	 * @param inx
	 * @return
	 */
	public Id getParameter(int inx) {
		if (inx < this.params.length) {
			return this.params[inx];
		}
		throw new IllegalArgumentException("Argument out of range: "+inx);
	}

	public Option<SyntaxDeclEnv> getSyntaxDeclEnv(SyntaxDef syntaxDef) {
		SyntaxDeclEnv sdEnv = null;
		if (null != (sdEnv= this.syntaxDefToEnv.get(syntaxDef))) {
		    return Option.some(sdEnv);
		}
		return Option.none();
	}

	public String toString() {
	    String s = "params: ";
	    for (Id id: params) {
	        s += id+":"+this.argsToNonterminalMap.get(id)+", ";
	    }
		return this.name+", "+s+this.syntaxDefToEnv;
	}

    public BaseType getAstType() {
        return this.astType;
    }

    /**
     * Returns the nonterminal that the given parameter is mapped to
     * @param id
     * @return
     */
    public Id getParameter(Id id) {
        return this.argsToNonterminalMap.get(id);
    }

    public boolean isParameter(Id id) {
        return this.argsToNonterminalMap.containsKey(id);
    }

    public List<Id> getParameters() {
        return Arrays.asList(this.params);
    }
    
    /**
     * Add all the bindings of the other environment to this environment
     * Assume the parameters among the two environments are the same
     * @param gntMEnv
     */
    public void merge(MemberEnv other) {
        // TODO: Add subtype check
//        if (!this.astType.equals(other.astType)) {
//            throw new RuntimeException("Incompatible member environments, return types mismatch: "+this.astType+", "+other.astType);
//        }
        if (!Arrays.deepEquals(this.params, other.params)) {
            throw new RuntimeException("Incompatible member environments, parameters mismatch");
        }
        this.argsToNonterminalMap.putAll(other.argsToNonterminalMap);
        this.syntaxDefToEnv.putAll(other.syntaxDefToEnv);
    }

    public Id getName() {
        return this.name;
    }

}
