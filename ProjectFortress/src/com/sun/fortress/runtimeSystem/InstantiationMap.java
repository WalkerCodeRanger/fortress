/*******************************************************************************
    Copyright 2009,2010, Oracle and/or its affiliates.
    All rights reserved.


    Use is subject to license terms.

    This distribution may include materials developed by third parties.

 ******************************************************************************/

package com.sun.fortress.runtimeSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InstantiationMap  {
    
    private final static InstantiationMap EMPTY = new InstantiationMap(new HashMap<String, String>());
    
    Map<String, String> p;
    Map<String, String> q;

    public InstantiationMap(Map<String, String> p) {
        this.p = p;
        // Copy tags to front, makes it easier to do tag replacement.
        q = new HashMap<String,String>();
        for (Map.Entry<String, String> e : p.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            q.put(v.substring(0,1)+k, v);
            
        }
    }
    
    
    public String getName(String s) {
        // TODO will need to rewrite into type, desc, and method variants.
        if (s == null)
            return s;
        s = Naming.demangleFortressIdentifier(s);
        StringBuilder b = new StringBuilder();
        maybeBareVar(s, 0, b, false, false);
        
        s = b.toString();
       
        return s;
    }
    
    public String getTypeName(String s) {
        // TODO will need to rewrite into type, desc, and method variants.
        String t = s;
        if (s == null)
            return s;
        s = Naming.demangleFortressIdentifier(s);
        StringBuilder b = new StringBuilder();
        maybeBareVar(s, 0, b, false, false);
        
        s =  b.toString();

        return s;
    }

    public String getUnmangledTypeDesc(String s) {
        // TODO will need to rewrite into type, desc, and method variants.
        String t = s;
        if (s == null)
            return s;
        StringBuilder b = new StringBuilder();
        maybeVarInTypeDesc(s, 0, b);
        
        s =  b.toString();

        return s;
    }

    public String getFieldDesc(String s) {           
        if (s == null)
            return s;        
        s = Naming.demangleFortressDescriptor(s);

        StringBuilder b = new StringBuilder();
        maybeVarInTypeDesc(s, 0, b);

        s = b.toString();
       
        return s;
        }

    public String getMethodDesc(String s) {           
        if (s == null)
            return s;
        s = Naming.demangleFortressDescriptor(s);
        
        StringBuilder b = new StringBuilder();
        maybeVarInMethodSig(s, 0, b);

        s = b.toString();
       
        return s;
        }


    /**
     * @param s
     * @return
     * @throws Error
     */
    public String oxfordSensitiveSubstitution(String s) throws Error {
        int l = s.length();
        
        int oxLevel = 0;
        
        
        for (int i = 0; i < l; i++) {
            char ch = s.charAt(i);
            
            if (Naming.GENERIC_TAGS.indexOf(ch) != -1) {
                // Found a variable
                StringBuilder b = new StringBuilder();
                int j = 0;
                while (j < l) {
                     ch = s.charAt(j);
                     j++;
                     if (ch == Naming.LEFT_OXFORD_CHAR) {
                         oxLevel++;
                         b.append(ch);
                     } else if (ch == Naming.RIGHT_OXFORD_CHAR)  {
                         oxLevel--;
                         b.append(ch);
                     } else if (Naming.GENERIC_TAGS.indexOf(ch) != -1) {
                         StringBuilder v = new StringBuilder(8);
                         v.append(ch);
                         while (j < l) {
                             ch = s.charAt(j);
                             if ('0' <= ch && ch <= '9') {
                                 v.append(ch);
                                 j++;
                             } else {
                                 break;
                             }
                         }
                         // j addresses next character or the end.
                         // v contains the variable.
                         String vs = v.toString();
                         String t = p.get(vs);
                         if (t == null)
                             throw new Error("Missing generic binding for " + vs + " map = " + p);
                         if (oxLevel == 0) {
                             b.append(t.substring(1));
                         } else {
                             b.append(t);
                         }
                         
                     } else {
                         b.append(ch);
                     }
                }
                s = b.toString();
                break;
            }
        }
        return s;
    }

    
    // Looking for  Lvariable; in method signatures,
    // Looking for  LOXvariable;variableROX as part of types
    
    /* states:
     *
     *  1) scanning potential variable
     *    LOX -> push suffix; 
     *    / -> 
     *    (a) in Oxfords
     *      ; -> check; (1a)
     *      ROX -> check; pop suffix;
     *    (b) in L;
     *      ; -> check; (3)
     *    (c) bare
     *    
     *    
     *  2) scanning known non-variable
     *    (a) in Oxfords
     *    (b) in L;
     *    (c) bare
     *    
     *  3) scanning in Parens
     *     BCDIJSZV -> (3)
     *     L -> 1, suffix=b
     *     
     *  4) scanning after Parens
     *     BCDIJSZV -> (3)
     *     L -> 1, suffix=b
     *     
     *  Outer context is bare/var/L;
     *  Possibly in ()
     *  Then some number of nested Oxfords.
     *     
     */
    
    /**
     * Come here after seeing a left oxford.  Process characters until an
     * unnested right Oxford is seen.  At semicolons, check to see if the
     * previous string is a variable, if it has not been disqualified.
     */
    int maybeVarInOxfords(String input, int begin, StringBuilder accum) {
//         int at = maybeBareVar(input, begin, accum, true);
//         char ch = input.charAt(at++);
//         
//         if (ch == ';' || ch == '=') {
//             accum.append(ch);
//             /* This recursion will never be very deep, so it does not
//              * need a tail-call, though golly, that would be nice.
//              */
//             return maybeVarInOxfords(input, at, accum);
//         } else if (ch == Naming.RIGHT_OXFORD_CHAR) {
//             accum.append(ch);
//             return at;
//         } else {
//             throw new Error();
//         }
        return mVIO(input, "", begin, accum);
     }
    
    int mVIO(String input, String tag, int begin, StringBuilder accum) {
        ArrayList<String> params = new ArrayList<String>();
        while (true) {
            StringBuilder one_accum = new StringBuilder();
            int at = maybeBareVar(input, begin, one_accum, true, false);
            char ch = input.charAt(at++);
        
            if (ch == ';') {
                params.add(one_accum.toString());
                                
                begin = at; 
                
            } else if (ch == Naming.RIGHT_OXFORD_CHAR) {
                params.add(one_accum.toString());

                /* 
                 * Next, process params onto accum, but first check
                 * for the case where tag is Arrow, the number of
                 * params is 2, and the first one is Tuple[ something ].
                 * In that case, normalize to remove the Tuple from the
                 * Arrow.
                 */
                if (tag.equals("Arrow") && params.size() == 2) {
                     String domain = params.get(0);
                     if (domain.startsWith("Tuple" + Naming.LEFT_OXFORD)) {
                         params.set(0, domain.substring(6, domain.length()-1));
                     }
                }
                
                int l = params.size(); 
                for (int i = 0; i < l; i++) {
                    accum.append(params.get(i));
                    accum.append( i < (l-1) ? ';' : Naming.RIGHT_OXFORD_CHAR);
                }
                
                return at;
            } else {
                throw new Error();
            }
        }
    }
    
    /**
     * Come here after seeing L where a type signature is expected.
     * Process characters until an unnested ';' is seen.  Consume the ';' ,
     * return the index of the next character.
     * 
     * If none of the processed characters is disqualifying, check 
     * for replacement.
     * 
     * @param input
     * @param at
     * @param accum
     */
     int maybeVarInLSemi(String input, int begin, StringBuilder accum) {
        int at = maybeBareVar(input, begin, accum, false, true);
        char ch = input.charAt(at++);
        if (ch != ';')
            throw new Error("Expected semicolon, saw " + ch +
                    " instead at index " + (at-1) + " in " + input) ;
        
        accum.append(';');
        
        return at;
    }
    
     /**
      * 
      * @param ch
      * @return
      */
     private static boolean nonVar(char ch) {
        return ch == '/' || ch == '$' || ch == ';' || 
               ch == Naming.LEFT_OXFORD_CHAR || ch == Naming.RIGHT_OXFORD_CHAR;
    }

    /**
     * 
     * @param input
     * @param begin
     * @param accum
     * @param inOxfords
     * @param xlate_specials
     * @return
     */
    int maybeBareVar(String input, int begin, StringBuilder accum, boolean inOxfords, boolean xlate_specials) {
        int at = begin;
        char ch = input.charAt(at++);
        boolean maybeVar = true;
        boolean eol = false;
        boolean disabled = false;
        
        while (ch != ';' && ch != Naming.RIGHT_OXFORD_CHAR) {
            if (ch == Naming.HEAVY_X_CHAR)
                disabled = true;
            
//            if (ch == '=') {
//                if (maybeVar)
//                    accum.append(input.substring(begin, at-1));
//                maybeVar = false;
//                break;
//            } else
                if (!maybeVar) {
                accum.append(ch);
            } else if (nonVar(ch)) {
                maybeVar = false;
                accum.append(input.substring(begin, at));
            } 
            
            if (ch == Naming.LEFT_OXFORD_CHAR) {
                at = (disabled ? EMPTY: this).mVIO(input, input.substring(begin, at-1), at, accum);
            }
            
            if (at >= input.length()) {
                eol = true;
                break;
            }
            ch = input.charAt(at++);
        }
        
        at = eol ? at : at - 1;
        
        if (maybeVar) {
            String s = input.substring(begin, at);
            if (ch != '=') {
                String t = p.get(s); // (inOxfords ? q : p).get(s);
                if (t != null) {
                    if (xlate_specials && t.equals(Naming.SNOWMAN)) {
                        t = Naming.specialFortressTypes.get(t);
                    }
                    accum.append(t);
                    //accum.append(inOxfords ? t : t.substring(1));
                } else {
                    accum.append(s);
                }
            } else {
                accum.append(s);
            }
        }
        return at;
    }
    
     int maybeVarInMethodSig(String input, int begin, StringBuilder accum) {
         int at = begin;
         char ch = input.charAt(at++);
         // Begin with "("
         // Expect signature letters, followed by ")"
         // Expect one more signature letter
         if (ch != '(') {
             throw new Error("Signature does not begin with '(', sig="+input);
         }
         accum.append(ch);
         ch = input.charAt(at++);
         while (ch != ')') {
             accum.append(ch);
             if (ch == 'L') {
                 at = maybeVarInLSemi(input, at, accum);
             }
             ch = input.charAt(at++);
         }
         accum.append(ch);
         
         return maybeVarInTypeDesc(input, at, accum);
     }


    /**
     * @param input
     * @param accum
     * @param at
     * @return
     */
    private int maybeVarInTypeDesc(String input, int at, StringBuilder accum) {
        char ch;
        ch = input.charAt(at++);
         accum.append(ch);
         if (ch == 'L') {
             at = maybeVarInLSemi(input, at, accum);
         }
         return at;
    }

    /**
     * @param s
     * @return
     */
    public static int templateClosingRightOxford(String s) {
        int heavy_x = s.indexOf(Naming.HEAVY_X);
        int rightBracket = (heavy_x == -1 ? s : s.substring(0, heavy_x)).lastIndexOf(Naming.RIGHT_OXFORD);
        return rightBracket;
    }


    /**
     * @param s
     * @param leftBracket
     * @param rightBracket
     * @return
     */
    public static List<String> extractStringParameters(String s,
                                                             int leftBracket, int rightBracket, List<String> parameters) {
        
        int depth = 1;
        int pbegin = leftBracket+1;
        for (int i = leftBracket+1; i <= rightBracket; i++) {
            char ch = s.charAt(i);
    
            if ((ch == ';' || ch == Naming.RIGHT_OXFORD_CHAR) && depth == 1) {
                String parameter = s.substring(pbegin,i);
                if (parameters != null)
                    parameters.add(parameter);
                pbegin = i+1;
            } else {
                if (ch == Naming.LEFT_OXFORD_CHAR) {
                    depth++;
                } else if (ch == Naming.RIGHT_OXFORD_CHAR) {
                    depth--;
                } else {
    
                }
            }
        }
        return parameters;
    }


    /**
     * 
     * @param name
     * @param left_oxford
     * @param right_oxford
     * @return
     * @throws Error
     */
    public static String canonicalizeStaticParameters(String name, int left_oxford,
            int right_oxford, ArrayList<String> sargs) throws Error {
        
        
        
        String template_start = name.substring(0,left_oxford+1);
        String template_end = name.substring(right_oxford);
        // Note include trailing oxford to simplify loop termination.
        
        extractStringParameters(name, left_oxford, right_oxford, sargs);
        
        String s = template_start +
                   // template_middle +
                   template_end;
        return s;
    }
    
    
}
