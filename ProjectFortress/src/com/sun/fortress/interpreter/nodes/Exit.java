/*******************************************************************************
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
 ******************************************************************************/

package com.sun.fortress.interpreter.nodes;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import com.sun.fortress.interpreter.nodes_util.*;
import com.sun.fortress.interpreter.useful.*;

public class Exit extends FlowExpr {
  private final Option<Id> _name;
  private final Option<Expr> _returnExpr;

  /**
   * Constructs a Exit.
   * @throw java.lang.IllegalArgumentException if any parameter to the constructor is null.
   */
  public Exit(Span in_span, Option<Id> in_name, Option<Expr> in_returnExpr) {
    super(in_span);

    if (in_name == null) {
      throw new java.lang.IllegalArgumentException("Parameter 'name' to the Exit constructor was null. This class may not have null field values.");
    }
    _name = in_name;

    if (in_returnExpr == null) {
      throw new java.lang.IllegalArgumentException("Parameter 'returnExpr' to the Exit constructor was null. This class may not have null field values.");
    }
    _returnExpr = in_returnExpr;
  }

    @Override
    public <T> T accept(NodeVisitor<T> v) {
        return v.forExit(this);
    }

    Exit(Span span) {
        super(span);
        _name = null;
        _returnExpr = null;
    }


  final public Option<Id> getName() { return _name; }
  final public Option<Expr> getReturnExpr() { return _returnExpr; }

  public <RetType> RetType visit(NodeVisitor<RetType> visitor) { return visitor.forExit(this); }
  public void visit(NodeVisitor_void visitor) { visitor.forExit(this); }

  /**
   * Implementation of toString that uses
   * {@see #output} to generated nicely tabbed tree.
   */
  public java.lang.String toString() {
    java.io.StringWriter w = new java.io.StringWriter();
    output(w);
    return w.toString();
  }

  /**
   * Prints this object out as a nicely tabbed tree.
   */
  public void output(java.io.Writer writer) {
    outputHelp(new TabPrintWriter(writer, 2), false);
  }

  public void outputHelp(TabPrintWriter writer, boolean lossless) {
    writer.print("Exit:");
    writer.indent();

    Span temp_span = getSpan();
    writer.startLine();
    writer.print("span = ");
    if (lossless) {
      writer.printSerialized(temp_span);
      writer.print(" ");
      writer.printEscaped(temp_span);
    } else { writer.print(temp_span); }

    Option<Id> temp_name = getName();
    writer.startLine();
    writer.print("name = ");
    if (lossless) {
      writer.printSerialized(temp_name);
      writer.print(" ");
      writer.printEscaped(temp_name);
    } else { writer.print(temp_name); }

    Option<Expr> temp_returnExpr = getReturnExpr();
    writer.startLine();
    writer.print("returnExpr = ");
    if (lossless) {
      writer.printSerialized(temp_returnExpr);
      writer.print(" ");
      writer.printEscaped(temp_returnExpr);
    } else { writer.print(temp_returnExpr); }
    writer.unindent();
  }

  /**
   * Implementation of equals that is based on the values
   * of the fields of the object. Thus, two objects
   * created with identical parameters will be equal.
   */
  public boolean equals(java.lang.Object obj) {
    if (obj == null) return false;
    if ((obj.getClass() != this.getClass()) || (obj.hashCode() != this.hashCode())) {
      return false;
    } else {
      Exit casted = (Exit) obj;
      if (! (getName().equals(casted.getName()))) return false;
      if (! (getReturnExpr().equals(casted.getReturnExpr()))) return false;
      return true;
    }
  }

  /**
   * Implementation of hashCode that is consistent with
   * equals. The value of the hashCode is formed by
   * XORing the hashcode of the class object with
   * the hashcodes of all the fields of the object.
   */
  public int generateHashCode() {
    int code = getClass().hashCode();
    code ^= 0;
    code ^= getName().hashCode();
    code ^= getReturnExpr().hashCode();
    return code;
  }
}
