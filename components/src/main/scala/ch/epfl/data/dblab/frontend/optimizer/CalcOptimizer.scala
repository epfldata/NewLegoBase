package ch.epfl.data.dblab.frontend.optimizer

import ch.epfl.data.dblab.frontend.parser.CalcAST._
import ch.epfl.data.dblab.frontend.parser.SQLAST.{ DoubleLiteral, IntLiteral }
import ch.epfl.data.sc.pardis.types._

/**
 * @author Parand Alizadeh
 */

//TODO make sum,prod, aggsum,

object CalcOptimizer {

  //TODO

  // def typeOfExpression(expr: CalcExpr): Tpe = {

  //}
  /**
   * Determine whether two expressions safely commute (in a product).
   *
   * param scope  (optional) The scope in which the expression is evaluated
   * (having a subset of the scope may produce false negatives)
   * param e1     The left hand side expression
   * param e2     The right hand side expression
   * return       true if and only if e1 * e2 = e2 * e1
   */

  def commutes(expr1: CalcExpr, expr2: CalcExpr): Boolean = {
    val (_, ovars1) = SchemaOfExpression(expr1)
    val (ivars2, _) = SchemaOfExpression(expr2)

    return ivars2.toSet.intersect(ovars1.toSet).isEmpty
    //some of ocaml code is removed

  }
  // evaluating constants in product list and remove unnecessary calc expression (0 or 1 in product list)
  def Prod(exprs: List[CalcExpr]): CalcExpr = {

    val (cs, ncs) = exprs.foldLeft[(List[Double], List[CalcExpr])]((Nil, Nil))({
      case ((acc1, acc2), cur) => cur match {
        case CalcValue(ArithConst(IntLiteral(t)))    => (t :: acc1, acc2)
        case CalcValue(ArithConst(DoubleLiteral(t))) => (t :: acc1, acc2)
        case _                                       => (acc1, cur :: acc2)
      }
    })

    var res = 1.0
    if (cs.length > 0)
      res = cs.foldLeft(1.0)((acc, cur) => (acc * cur))

    if (res == 0)
      return CalcValue(ArithConst(DoubleLiteral(0.0)))
    else if (res == 1.0)
      return CalcProd(ncs)

    if (ncs.length > 0)
      return CalcProd(CalcValue(ArithConst(DoubleLiteral(res))) :: ncs)
    else
      return CalcValue(ArithConst(DoubleLiteral(res)))

  }

  def Sum(exprs: List[CalcExpr]): CalcExpr = {
    val elems = exprs.foldLeft[(List[CalcExpr])]((Nil))({
      case (acc1, cur) => cur match {
        case CalcValue(ArithConst(IntLiteral(0)))      => (acc1)
        case CalcValue(ArithConst(DoubleLiteral(0.0))) => (acc1)
        case _                                         => (cur :: acc1)
      }
    })

    if (elems.length > 0)
      return CalcSum(elems)
    else
      return CalcSum(List(CalcValue(ArithConst(IntLiteral(0)))))

  }

  def Neg(expr: CalcExpr): CalcExpr = {
    expr match {
      case CalcProd(list) => Prod(CalcValue(ArithConst(IntLiteral(-1))) :: list)
      case _              => CalcProd(CalcValue(ArithConst(IntLiteral(-1))) :: List(expr))
    }
  }

  def Value(expr: CalcExpr): CalcExpr = {
    return expr
  }

  /* Normalize a given expression by replacing all Negs with {-1} and
    evaluating all constants in the product list. */
  def Normalize(expr: CalcExpr): CalcExpr = {
    return rewrite(expr, Sum, Prod, Neg, Value)
  }

  def Fold[A](sumFun: List[A] => A, prodFun: List[A] => A, negFun: A => A, leafFun: CalcExpr => A, expr: CalcExpr): A = {

    def rcr(expr: CalcExpr): A = {
      return Fold(sumFun, prodFun, negFun, leafFun, expr)
    }
    expr match {
      case CalcSum(list)  => sumFun(list.map(x => rcr(x)))
      case CalcProd(list) => prodFun(list.map(x => rcr(x)))
      case CalcNeg(e)     => Fold(sumFun, prodFun, negFun, leafFun, e)
      case _              => leafFun(expr)
    }
  }

  def FoldOfVars(sum: List[List[VarT]] => List[VarT], prod: List[List[VarT]] => List[VarT], neg: List[VarT] => List[VarT], leaf: ArithExpr => List[VarT], expr: ArithExpr): List[VarT] = {

    def rcr(expr: ArithExpr): List[VarT] = {
      return FoldOfVars(sum, prod, neg, leaf, expr)
    }

    expr match {
      case ArithSum(list)  => sum(list.map(x => rcr(x)))
      case ArithProd(list) => prod(list.map(x => rcr(x)))
      case ArithNeg(e)     => neg(rcr(e))
      case _               => leaf(expr)
    }
  }

  def varsOfValue(expr: ArithExpr): List[VarT] = {
    def multiunion(list: List[List[VarT]]): List[VarT] = {
      return list.foldLeft(List.empty[VarT])((acc, cur) => acc.toSet.union(cur.toSet).toList)
    }
    def leaf(arithExpr: ArithExpr): List[VarT] = {
      arithExpr match {
        case ArithConst(_)       => List()
        case ArithVar(v)         => List(v)
        case ArithFunc(_, tr, _) => multiunion(tr.map(x => varsOfValue(x)))
      }
    }
    return FoldOfVars(multiunion, multiunion, (x => x), leaf, expr)

  }
  def SchemaOfExpression(expr: CalcExpr): (List[VarT], List[VarT]) = {

    def sum(sumlist: List[(List[VarT], List[VarT])]): (List[VarT], List[VarT]) = {
      val (ivars, ovars) = sumlist.unzip
      val oldivars = ivars.foldLeft(List.empty[VarT])((acc, cur) => acc.toSet.union(cur.toSet).toList)
      val oldovars = ovars.foldLeft(List.empty[VarT])((acc, cur) => acc.toSet.union(cur.toSet).toList)
      val newivars = oldovars.toSet.diff(ovars.foldLeft(Set.empty[VarT])((acc, cur) => acc.intersect(cur.toSet))).toList

      return (oldivars.toSet.union(newivars.toSet).toList, oldovars.toSet.diff(newivars.toSet).toList)
    }

    def prod(prodList: List[(List[VarT], List[VarT])]): (List[VarT], List[VarT]) = {
      return prodList.foldLeft((List.empty[VarT], List.empty[VarT]))((oldvars, newvars) => (oldvars._1.toSet.union(newvars._1.toSet.diff(oldvars._2.toSet)).toList, oldvars._2.toSet.union(newvars._2.toSet).diff(oldvars._1.toSet).toList))

    }

    def negSch(varTs: (List[VarT], List[VarT])): (List[VarT], List[VarT]) = { return varTs }

    def leafSch(calcExpr: CalcExpr): (List[VarT], List[VarT]) = {

      def lift(target: VarT, expr: CalcExpr): (List[VarT], List[VarT]) = {
        val (ivars, ovars) = SchemaOfExpression(expr)
        return (ivars.toSet.union(ovars.toSet).toList, List(target))
      }

      def aggsum(gbvars: List[VarT], subexp: CalcExpr): (List[VarT], List[VarT]) = {
        val (ivars, ovars) = SchemaOfExpression(subexp)
        val trimmedGbVars = ovars.toSet.intersect(gbvars.toSet).toList
        if (!(trimmedGbVars.equals(gbvars)))
          throw new Exception
        else
          return (ivars, gbvars)
      }

      calcExpr match {
        case CalcValue(v)                   => (varsOfValue(v), List())
        case External(_, eins, eouts, _, _) => (eins, eouts)
        case AggSum(gbvars, subexp)         => { aggsum(gbvars, subexp) }
        case Rel("Rel", _, rvars, _)        => (List.empty[VarT], rvars)
        case Cmp(_, v1, v2)                 => (varsOfValue(v1).toSet.union(varsOfValue(v2).toSet).toList, List())
        case CmpOrList(v, _)                => (varsOfValue(v), List())
        case Lift(target, subexpr)          => lift(target, subexpr)
        case Exists(expr)                   => SchemaOfExpression(expr)

      }
    }
    Fold(sum, prod, negSch, leafSch, expr)

  }
  def rewrite(expression: CalcExpr, sumFunc: List[CalcExpr] => CalcExpr, prodFunc: List[CalcExpr] => CalcExpr, negFunc: CalcExpr => CalcExpr, leafFunc: CalcExpr => CalcExpr): CalcExpr = {
    expression match {
      case CalcProd(list)  => prodFunc(list.foldLeft(List.empty[CalcExpr])((acc, cur) => rewrite(cur, sumFunc, prodFunc, negFunc, leafFunc) :: acc))
      case CalcSum(list)   => sumFunc(list.foldLeft(List.empty[CalcExpr])((acc, cur) => rewrite(cur, sumFunc, prodFunc, negFunc, leafFunc) :: acc))
      case CalcNeg(expr)   => rewrite(negFunc(expr), sumFunc, prodFunc, negFunc, leafFunc)
      case AggSum(t, expr) => AggSum(t, rewrite(expr, sumFunc, prodFunc, negFunc, leafFunc))
      case External(name, inps, outs, tp, meta) => meta match {
        case Some(expr) => External(name, inps, outs, tp, Some(rewrite(expr, sumFunc, prodFunc, negFunc, leafFunc)))
        case None       => expression
      }
      case Lift(t, expr) => Lift(t, rewrite(expr, sumFunc, prodFunc, negFunc, leafFunc))
      case Exists(expr)  => Exists(rewrite(expr, sumFunc, prodFunc, negFunc, leafFunc))
      case _             => leafFunc(expression)
    }
  }

  def nestingRewrites(bigexpr: CalcExpr): CalcExpr = {

    def leafNest(expr: CalcExpr): CalcExpr = {

      def aggsum(gbvars: List[VarT], unpsubterm: CalcExpr): CalcExpr = {
        val subterm = rcr(unpsubterm)
        if ((SchemaOfExpression(subterm)_2).length == 0)
          return subterm
        subterm match {
          case CalcSum(list) => {
            val (sumivars, _) = SchemaOfExpression(subterm)
            val rewritten = Sum(list.map(term => {
              val (_, termovars) = SchemaOfExpression(term)
              val termgbvars = gbvars.toSet.union(sumivars.toSet.intersect(termovars.toSet)).toList
              AggSum(termgbvars, term)
            }))
            rewritten
          }
          case CalcProd(list) => {
            val (unnested, nested) = list.foldLeft[(CalcExpr, CalcExpr)]((null, null))((acc, cur) => (if (commutes(acc._2, cur) && (SchemaOfExpression(cur)._2).toSet.subsetOf(gbvars.toSet)) (CalcProd(List(acc._1, cur)), acc._2) else (acc._1, CalcProd(List(acc._2, cur)))))
            val unnestedivars = SchemaOfExpression(unnested)._1
            val newgbvars = (SchemaOfExpression(nested)._2).toSet.intersect(gbvars.toSet.union(unnestedivars.toSet)).toList
            CalcProd(List(unnested, AggSum(newgbvars, nested)))

          }

          case AggSum(_, t) => AggSum(gbvars, t)
          case _ => {
            if ((SchemaOfExpression(subterm)_2).toSet.subsetOf(gbvars.toSet))
              subterm
            else
              AggSum(gbvars, subterm)
          }
        }
      }

      expr match {
        case AggSum(gbvars, CalcValue(ArithConst(IntLiteral(0))))      => CalcValue(ArithConst(IntLiteral(0)))
        case AggSum(gbvars, CalcValue(ArithConst(DoubleLiteral(0.0)))) => CalcValue(ArithConst(DoubleLiteral(0.0)))
        case AggSum(gbvars, subterm)                                   => aggsum(gbvars, subterm)
        case Exists(subterm) => rcr(subterm) match {
          case CalcValue(ArithConst(IntLiteral(0))) => CalcValue(ArithConst(IntLiteral(0)))
          case CalcValue(ArithConst(IntLiteral(_))) => CalcValue(ArithConst(IntLiteral(1)))
          case CalcValue(ArithConst(_))             => throw new Exception
          case _                                    => Exists(rcr(subterm))
        }

        case Lift(v, term) => {
          val nested = nestingRewrites(term)
          val (nestedivars, nestedovars) = SchemaOfExpression(nested)
          if (nestedovars.contains(v))
            throw new Exception
          else if (nestedivars.contains(v)) {
            nested match {
              case CalcValue(cmp) => Cmp(Eq, ArithVar(v), cmp)
              case _ => {
                expr

                //TODO
                // val tmpVar =
              }
            }
          } else
            Lift(v, nested)
        }

        case _ => expr

      }
    }

    def rcr(expr: CalcExpr): CalcExpr = {
      Fold(Sum, Prod, Neg, leafNest, expr)
    }

    val rewrittenExpr = rcr(bigexpr)
    rewrittenExpr

  }
}
