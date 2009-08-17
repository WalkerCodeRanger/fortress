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

package com.sun.fortress.scala_src.typechecker.impls

import com.sun.fortress.compiler.Types
import com.sun.fortress.compiler.index._
import com.sun.fortress.exceptions._
import com.sun.fortress.exceptions.StaticError.errorMsg
import com.sun.fortress.nodes._
import com.sun.fortress.nodes_util.{ExprFactory => EF}
import com.sun.fortress.nodes_util.{NodeFactory => NF}
import com.sun.fortress.nodes_util.{NodeUtil => NU}
import com.sun.fortress.nodes_util.Span
import com.sun.fortress.scala_src.nodes._
import com.sun.fortress.scala_src.useful.Lists._
import com.sun.fortress.scala_src.useful.Options._
import com.sun.fortress.scala_src.useful.SExprUtil._
import com.sun.fortress.scala_src.useful.STypesUtil._
import exceptions.StaticError
import fortress.useful.{HasAt, NI}
/**
 * Provides the implementation of cases relating to functionals and functional
 * application.
 *
 * This trait must be mixed in with an `STypeChecker with Common` instance
 * in order to provide the full type checker implementation.
 *
 * (The self-type annotation at the beginning declares that this trait must be
 * mixed into STypeChecker along with the Common helpers. This is what
 * allows this trait to implement abstract members of STypeChecker and to
 * access its protected members.)
 */
trait Functionals { self: STypeChecker with Common =>

  type AppCandidate = (ArrowType, List[StaticArg], List[Expr])

  // ---------------------------------------------------------------------------
  // HELPER METHODS ------------------------------------------------------------

  /**
   * Determines if the given overloading is dynamically applicable.
   */
  protected def isDynamicallyApplicable(overloading: Overloading,
                                        smaArrow: ArrowType,
                                        inferredStaticArgs: List[StaticArg])
                                        : Option[Overloading] = {
    // Is this arrow type applicable.
    def arrowTypeIsApplicable(overloadingType: ArrowType): Option[Type] = {
      val typ =
        // If static args given, then instantiate the overloading first.
        if (inferredStaticArgs.isEmpty)
          overloadingType
        else
          staticInstantiation(inferredStaticArgs, overloadingType).
            getOrElse(return None).asInstanceOf[ArrowType]

      if (isSubtype(typ.getDomain, smaArrow.getDomain))
        Some(typ)
      else
        None
    }

    // If overloading type is an intersection, check that any of its
    // constituents is applicable.
    val applicableArrows = conjuncts(toOption(overloading.getType).get).
      map(_.asInstanceOf[ArrowType]).
      flatMap(arrowTypeIsApplicable).
      toList

    val overloadingType = applicableArrows match {
      case Nil => return None
      case t::Nil => t
      case _ => NF.makeIntersectionType(toJavaList(applicableArrows))
    }
    Some(SOverloading(overloading.getInfo,
                      overloading.getUnambiguousName,
                      Some(overloadingType)))
  }

  /**
   * Given an applicand, the statically most applicable arrow type for it,
   * and the static args from the application, return the applicand updated
   * with the dynamically applicable overloadings, arrow type, and static args.
   */
  protected def rewriteApplicand(fn: Expr,
                                 arrow: ArrowType,
                                 sargs: List[StaticArg]): Expr = fn match {
    case fn: FunctionalRef =>
      // Use original static args if any were given. Otherwise use those inferred.
      val newSargs = if (fn.getStaticArgs.isEmpty) sargs else toList(fn.getStaticArgs)

      // Get the dynamically applicable overloadings.
      val overloadings =
        toList(fn.getNewOverloadings).
        flatMap(o => isDynamicallyApplicable(o, arrow, newSargs))

      // Add in the filtered overloadings, the inferred static args,
      // and the statically most applicable arrow to the fn.
      addType(
        addStaticArgs(
          addOverloadings(fn, overloadings), newSargs), arrow)

    case _ if !sargs.isEmpty =>
      NI.nyi("No place to put inferred static args in application.")

    // Just add the arrow type if the applicand is not a FunctionalRef.
    case _ => addType(fn, arrow)
  }

  /**
   * Signal a static error for an application for which there were no applicable
   * functions.
   */
  protected def noApplicableFunctions(application: Expr,
                                      fn: Expr,
                                      fnType: Type,
                                      argType: Type) = {
    val kind = fn match {
      case _:FnRef => "function"
      case _:OpRef => "operator"
      case _ => ""
    }
    val argTypeStr = normalize(argType) match {
      case tt:TupleType => tt.getElements.toString
      case ty => if (fn.isInstanceOf[OpRef]) "[" + ty + "]" else ty
    }
    val message = fn match {
      case fn:FunctionalRef =>
        val name = fn.getOriginalName
        val sargs = fn.getStaticArgs
        if (sargs.isEmpty)
          "Call to %s %s has invalid arguments, %s".format(kind, name, argTypeStr)
        else
          "Call to %s %s with static arguments %s has invalid arguments, %s".format(kind, name, sargs, argTypeStr)
      case _ =>
        "Expression of type %s is not applicable to argument type %s.".format(normalize(fnType), argTypeStr)
      }
      signal(application, message)
    }

  /** Given a single argument expr, break it into a list of args. */
  def getArgList(arg: Expr): List[Expr] = arg match {
    case STupleExpr(_, exprs, _, _, _) => exprs
    case _:VoidLiteralExpr => Nil
    case _ => List(arg)
  }


  /**
   * Given a list of arguments, partition it into a list of eithers, where Left is checked and Right
   * is unchecked.
   */
  def partitionArgs(args: List[Expr]): Option[List[Either[Expr, FnExpr]]] = {
    val partitioned = args.map(checkExprIfCheckable)
    if (partitioned.exists(_.fold(getType(_).isNone, x => false)))
      None
    else
      Some(partitioned)
  }

  /**
   * Get the full argument type from the partitioned list of args, filling in with the bottom
   * arrow.
   */
  def getArgType(args: List[Either[Expr, FnExpr]]): Type = getArgType(args, NF.typeSpan)

  /** Same as other but provides a location for the span on the new type. */
  def getArgType(args: List[Either[Expr, FnExpr]], span: Span): Type = {
    val argTypes = args.map(_.fold(e => getType(e).get, _ => Types.BOTTOM_ARROW))
    NF.makeMaybeTupleType(span, toJavaList(argTypes))
  }

  /**
   * Given an arrow type, an expected type context, and a list of partitioned args (where Left is
   * checked and Right is an unchecked arg), determine if the arrow is applicable to these args.
   * This method will infer static arguments on the arrow and parameter types on any FnExpr args.
   * The result is an AppCandidate, which contains the inferred arrow type, the list of static
   * args that were inferred, and the checked arguments.
   */
  def checkApplicable(arrow: ArrowType,
                      context: Option[Type],
                      args: List[Either[Expr, FnExpr]])
                     (implicit errorFactory: ApplicationErrorFactory)
                      : Either[AppCandidate, OverloadingError] = {

    // Make sure all uncheckable args correspond to arrow type params.
    zipWithDomain(args, arrow.getDomain).foreach {
      case (Right(_), pt) if !possiblyArrows(pt, getStaticParams(arrow)) =>
        return Right(errorFactory.makeNotApplicableError(arrow, args))
      case _ =>
    }

    // Try to check the unchecked args, constructing a new list of
    // (checked, unchecked) arg pairs.
    def updateArgs(argsAndParam: (Either[Expr, FnExpr], Type))
                     : (Either[Expr, FnExpr], Option[BodyError]) = argsAndParam match {

      // This arg is checkable if there are no more inference vars
      // in the param type (which must be an arrow).
      case (Right(unchecked), paramType:ArrowType) =>
        if (hasInferenceVars(paramType.getDomain))
          (Right(unchecked), None)
        else {

          // Try to check the arg given this new expected type.
          val domain = paramType.getDomain
          val expectedArrow = NF.makeArrowType(NU.getSpan(paramType),
                                               domain,
                                               Types.ANY)
          val tryChecker = STypeCheckerFactory.makeTryChecker(this)
          // (Called with Some(arrow) so that no coercion is performed.)
          tryChecker.tryCheckExpr(unchecked, Some(expectedArrow)) match {
            // Move this arg out of unchecked and into checked.
            case Some(checked) => (Left(checked), None)

            // This arg might be checkable later, so keep going.
            case None =>
              val bodyError =
                errorFactory.makeBodyError(unchecked,
                                           domain,
                                           tryChecker.getError.get)
              (Right(unchecked), Some(bodyError))
          }
        }

      // If the parameter type is not an arrow, skip it.
      case (Right(unchecked), _) => (Right(unchecked), None)

      // Skip args that are already checked.
      case (Left(checked), _) => (Left(checked), None)
    }

    // Update all args until we have checked as much as possible.
    def recurOnArgs(args: List[Either[Expr, FnExpr]])
                    : Either[AppCandidate, OverloadingError] = {

      // Build the single type for all the args, inserting the least arrow
      // type for any that aren't checkable.
      val argType = getArgType(args)

      // Do type inference to get the inferred static args.
      val (resultArrow, sargs) =
        typeInference(arrow, argType, context).getOrElse {
          return Right(errorFactory.makeNotApplicableError(arrow, args))
        }

      // Match up checked/unchecked args and param types and try to check the unchecked args,
      // constructing a new list of (checked, unchecked) arg pairs.
      val newArgsAndErrors = zipWithDomain(args, resultArrow.getDomain).
                               map(updateArgs)
      val (newArgs, maybeErrors) = List.unzip(newArgsAndErrors)

      // If progress was made, keep going. Otherwise return.
      if (newArgs.count(_.isRight) < args.count(_.isRight))
        recurOnArgs(newArgs)
      else {

        // If not all args were checked, gather the errors.
        if (newArgs.count(_.isRight) != 0) {

          // For each unchecked arg, get its body error if it had one. If it did
          // not but the parameter type was an arrow, it is a parameter
          // inference error. Otherwise it is a not applicable error.
          val argsErrorsParams = zipWithDomain(newArgsAndErrors,
                                               resultArrow.getDomain)
          val fnErrors = argsErrorsParams flatMap {
            case ((Right(_), Some(bodyError)), _) => Some(bodyError)
            case ((Right(unchecked), None), _:ArrowType) =>
              Some(errorFactory.makeParameterError(unchecked))
            case ((Right(_), None), _) =>
              return Right(errorFactory.makeNotApplicableError(arrow, newArgs))
            case ((Left(_), _), _) => None
          }
          return Right(errorFactory.makeFnInferenceError(arrow, fnErrors))
        }

        // If there are inference variables left, inform the user that there
        // wasn't enough context.
        if (hasInferenceVars(resultArrow)) {
          return Right(errorFactory.makeNoContextError(arrow, sargs))
        }

        // We've reached a fixed point and all args are checked!
        Left((resultArrow, sargs, newArgs.map(_.left.get)))
      }
    }



    // Do the recursion to check the args.
    recurOnArgs(args)
  }

  // Define an ordering relation on arrows with their instantiations.
  def moreSpecific(candidate1: AppCandidate,
                   candidate2: AppCandidate): Boolean = {

    val SArrowType(_, domain1, range1, _, _, _) = candidate1._1
    val SArrowType(_, domain2, range2, _, _, _) = candidate2._1

    if (analyzer.equivalent(domain1, domain2).isTrue) false
    else isSubtype(domain1, domain2)
  }

  /**
   * Type check the application of the given arrows to the given arg. This returns the statically
   * most specific arrow, inferred static args, and the new, checked argument expression.
   */
  def checkApplication(arrows: List[ArrowType],
                       arg: Expr,
                       context: Option[Type])
                      (implicit errorFactory: ApplicationErrorFactory)
                       : Option[(ArrowType, List[StaticArg], Expr)] = {

    // Check the application using the args extrapolated from arg.
    val args = getArgList(arg)
    val (smaArrow, infSargs, newArgs) = checkApplication(arrows, args, context).
                                          getOrElse(return None)

    // Combine the separate args back into a single one.
    val checkedArg = EF.makeArgumentExpr(NU.getSpan(arg), toJavaList(newArgs))
    Some((smaArrow, infSargs, checkedArg))
  }

  /**
   * Type check the application of the given arrows to the given args. This returns the statically
   * most specific arrow, inferred static args, and the new, checked argument expressions.
   */
  def checkApplication(arrows: List[ArrowType],
                       iargs: List[Expr],
                       context: Option[Type])
                      (implicit errorFactory: ApplicationErrorFactory)
                       : Option[AppCandidate] = {

    // Check all the checkable args and make sure they all have types.
    val args = partitionArgs(iargs).getOrElse(return None)

    // Filter the overloadings that are applicable.
    val (candidates, overloadingErrors) =
      List.separate(arrows.map(arrow => checkApplicable(arrow, context, args)))

    // If there were no candidates, report errors.
    if (candidates.isEmpty) {
      errors.signal(errorFactory.makeApplicationError(overloadingErrors))
      return None
    }

    // Sort the arrows and instantiations to find the statically most applicable.
    Some(candidates.sort(moreSpecific).first)
  }

  /**
   * Given a receiver type and a method name, return the list of all the arrow types for each
   * method overloading. Ignores any arrows that were for getters or setters.
   */
  def getArrowsForMethod(recvrType: Type,
                         name: IdOrOp,
                         sargs: List[StaticArg],
                         loc: HasAt): Option[List[ArrowType]] = {
    def noGetterSetter(m: Method): Option[Method] = m match {
      case g:FieldGetterMethod => None
      case s:FieldSetterMethod => None
      case m => Some(m)
    }
    val methods = findMethodsInTraitHierarchy(name, recvrType).toList.flatMap(noGetterSetter)
    var arrows = methods.flatMap(makeArrowFromFunctional)
    // Make sure all of the functions had return types declared or inferred
    // TODO: This could be handled more gracefully
    if (arrows.size != methods.size) {
      signal(loc, "The return type for %s could not be inferred".format(name))
      return None
    }

    // Instantiate the arrows if you were given static args
    if (!sargs.isEmpty) {
      arrows = arrows.flatMap(a => staticInstantiation(sargs, a).map(_.asInstanceOf[ArrowType]))
    }
    Some(arrows)
  }

  /**
   * Given the type of a functional ref, return the list of all arrow types for each overloading.
   */
  def getArrowsForFunction(fnType: Type, loc: HasAt): Option[List[ArrowType]] = {
    if (!isArrows(fnType)) {
      signal(loc, "Applicand has a type that is not an arrow: %s".format(normalize(fnType)))
      return None
    }
    Some(conjuncts(fnType).toList.map(_.asInstanceOf[ArrowType]))
  }

  // ---------------------------------------------------------------------------
  // CHECK IMPLEMENTATION ------------------------------------------------------

  def checkFunctionals(node: Node): Node = node match {

    case SOverloading(info, name, _) => {
      val checkedName = check(name).asInstanceOf[IdOrOp]
      getTypeFromName(checkedName) match {
        case Some(checkedType) =>
          SOverloading(info, checkedName, Some(normalize(checkedType)))
        case None => node
      }
    }

    case _ => throw new Error(errorMsg("not yet implemented: ", node.getClass))
  }

  // ---------------------------------------------------------------------------
  // CHECKEXPR IMPLEMENTATION --------------------------------------------------

  def checkExprFunctionals(expr: Expr,
                           expected: Option[Type]): Expr = expr match {

    case SSubscriptExpr(SExprInfo(span, paren, _), obj, subs, Some(op), sargs) => {
      val checkedObj = checkExpr(obj)
      val recvrType = getType(checkedObj).getOrElse(return expr)
      val arrows = getArrowsForMethod(recvrType, op, sargs, expr).getOrElse(return expr)
      if (arrows.isEmpty) {
        signal(new NoSuchMethod(expr, recvrType))
        return expr
      }
      implicit val errorFactory = new ApplicationErrorFactory(expr, Some(recvrType))

      // Type check the application.
      val (smaArrow, infSargs, checkedSubs) =
        checkApplication(arrows, subs, expected).getOrElse(return expr)
      val newSargs = if (sargs.isEmpty) infSargs else sargs

      // Rewrite the new expression with its type and checked args.
      SSubscriptExpr(SExprInfo(span, paren, Some(smaArrow.getRange)),
                     checkedObj,
                     checkedSubs,
                     Some(op),
                     newSargs)
    }

    case SMethodInvocation(SExprInfo(span, paren, _), obj, method, sargs, arg, _) =>{
      val checkedObj = checkExpr(obj)
      val recvrType = getType(checkedObj).getOrElse(return expr)
      val arrows = getArrowsForMethod(recvrType, method, sargs, expr).getOrElse(return expr)
      if (arrows.isEmpty) {
        signal(new NoSuchMethod(expr, recvrType))
        return expr
      }
      implicit val errorFactory = new ApplicationErrorFactory(expr, Some(recvrType))

      // Type check the application.
      val (smaArrow, infSargs, checkedArg) =
        checkApplication(arrows, arg, expected).getOrElse(return expr)
      val newSargs = if (sargs.isEmpty) infSargs else sargs

      // Rewrite the new expression with its type and checked args.
      SMethodInvocation(SExprInfo(span, paren, Some(smaArrow.getRange)),
                        checkedObj,
                        method,
                        newSargs,
                        checkedArg,
                        Some(smaArrow))
    }

    case fn@SFunctionalRef(_, sargs, _, name, _, _, overloadings, _) => {
      // Error if this is a getter
      val thisEnv = handleAlias(name, toList(current.ast.getImports)) match {
        case id@SIdOrOpOrAnonymousName(_, Some(api)) => getEnvFromApi(api)
        case _ => env
      }
      thisEnv.getMods(name) match {
        case Some(mods) =>
          if (mods.isGetter) {
            signal(expr,
                   errorMsg("Getter " + name + " must be called with the field reference syntax."))
            return expr
          }
        case _ =>
      }

      // Note that ExprDisambiguator inserts the static args from a
      // FunctionalRef into each of its Overloadings.

      // Check all the overloadings and filter out any that have the wrong
      // number or kind of static parameters.
      var hadNoType = false
      def rewriteOverloading(o: Overloading): Option[Overloading] = check(o) match {
        case ov@SOverloading(_, _, Some(ty)) if sargs.isEmpty => Some(ov)
        case SOverloading(info, name, Some(ty)) =>
          staticInstantiation(sargs, getStaticParams(ty), ty, true).
            map(t => SOverloading(info, name, Some(t)))
        case _ => hadNoType = true; None
      }
      val checkedOverloadings = overloadings.flatMap(rewriteOverloading)

      // If there are no overloadings, we cannot continue type checking. If any
      // of the overloadings failed to get a type
      if (checkedOverloadings.isEmpty) {
        if (!hadNoType) {
          signal(expr, errorMsg("Wrong number or kind of static arguments for function: ",
                                name))
        }
        return expr
      }

      // Make the intersection type of all the overloadings.
      val overloadingTypes = checkedOverloadings.map(_.getType.unwrap)
      val intersectionType = NF.makeMaybeIntersectionType(toJavaList(overloadingTypes))
      addType(addOverloadings(fn, checkedOverloadings), intersectionType)
    }

    case S_RewriteFnApp(SExprInfo(span, paren, _), fn, arg) => {
      val checkedFn = checkExpr(fn)
      val fnType = getType(checkedFn).getOrElse(return expr)
      val arrows = getArrowsForFunction(fnType, expr).getOrElse(return expr)
      implicit val errorFactory = new ApplicationErrorFactory(expr, None)

      // Type check the application.
      val (smaArrow, infSargs, checkedArg) =
        checkApplication(arrows, arg, expected).getOrElse(return expr)

      // Rewrite the applicand to include the arrow and static args
      // and update the application.
      val newFn = rewriteApplicand(checkedFn, smaArrow, infSargs)
      S_RewriteFnApp(SExprInfo(span, paren, Some(smaArrow.getRange)), newFn, checkedArg)
    }

    case SOpExpr(SExprInfo(span, paren, _), op, args) => {
      val checkedOp = checkExpr(op)
      val opType = getType(checkedOp).getOrElse(return expr)
      val arrows = getArrowsForFunction(opType, expr).getOrElse(return expr)
      implicit val errorFactory = new ApplicationErrorFactory(expr, None)

      // Type check the application.
      val (smaArrow, infSargs, checkedArgs) =
        checkApplication(arrows, args, expected).getOrElse(return expr)

      // Rewrite the applicand to include the arrow and static args
      // and update the application.
      val newOp = rewriteApplicand(checkedOp, smaArrow, infSargs).asInstanceOf[FunctionalRef]
      SOpExpr(SExprInfo(span, paren, Some(smaArrow.getRange)), newOp, checkedArgs)
    }

    case SFnExpr(SExprInfo(span, paren, _),
                 SFnHeader(a, b, c, d, e, f, tempParams, retType), body) => {
      // If expecting an arrow type, use its domain to infer param types.
      val params = expected match {
        case Some(arrow:ArrowType) =>
          addParamTypes(arrow.getDomain, tempParams).getOrElse(tempParams)
        case _ => tempParams
      }

      // Make sure all params have a type.
      val domain = makeDomainType(params).getOrElse {
        signal(expr, "Could not determine all parameter types of function expression.")
        return expr
      }

      val (checkedBody, range) = retType match {
        // If there is a declared return type, use it.
        case Some(typ) =>
          (this.extend(params).checkExpr(body,
                                         typ,
                                         errorString("Function body",
                                                     "declared return")), typ)

        case None =>
          val temp = this.extend(params).checkExpr(body)
          (temp, getType(temp).getOrElse(return expr))
      }

      val arrow = NF.makeArrowType(span, domain, range)
      val newRetType = retType.getOrElse(range)
      val newHeader = SFnHeader(a, b, c, d, e, f, params, Some(newRetType))
      SFnExpr(SExprInfo(span, paren, Some(arrow)), newHeader, checkedBody)
    }

    case _ => throw new Error(errorMsg("Not yet implemented: ", expr.getClass))
  }

}
