/*******************************************************************************
    Copyright 2011 Sun Microsystems, Inc.,
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

package com.sun.fortress.useful;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class TopSortItemImpl<T> implements TopSortItem<TopSortItemImpl<T>> {

    /*
     * Typical use is by creating a subtype, like so:

     static class POType extends TopSortItemImpl<Type> {
        public POType(Type x) {
            super(x);
        }
     }

     * and to create an array list of that subtype, like so:

     TopSortItemImpl<Type>[] potypes = new POType[dispatchTypes.size()];

     * Notice that the generic type is on the variable, not on the array
     * allocation, and that the type itself is so boring that it can be
     * ignored.  Attempting to work with the subtype will get you into
     * trouble later with type inference when depthFirst/breadthFirst is
     * is invoked.

     */

    public T x;
    List<TopSortItemImpl<T>> succs;
    int pcount;

    public void edgeTo(TopSortItemImpl<T> other) {
        succs.add(other);
        other.pcount++;
    }

    public TopSortItemImpl(T x) {
        this.x = x;
        succs = new ArrayList<TopSortItemImpl<T>>();
    }

    public Iterator<TopSortItemImpl<T>> successors() {
        return succs.iterator();
    }

    public int predecessorCount() {
        return pcount;
    }

    public int decrementPredecessors() {
        return --pcount;
    }

    public String toString() {
        return x.toString();
    }

}
