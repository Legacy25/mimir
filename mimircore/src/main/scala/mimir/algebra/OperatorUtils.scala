package mimir.algebra;

object OperatorUtils {
    
  /** 
   * Strip the expression required to compute a single column out
   * of the provided operator tree.  
   * Essentially a shortcut for an optimized form of 
   *
   * Project( [Var(col)], oper )
   * 
   * is equivalent to (and will often be slower than): 
   *
   * Project(ret(0)._1, ret(0)._2) UNION 
   *     Project(ret(1)._1, ret(1)._2) UNION
   *     ...
   *     Project(ret(N)._1, ret(N)._2) UNION
   */
  def columnExprForOperator(col: String, oper: Operator): 
    List[(Expression, Operator)] =
  {
    oper match {
      case p @ Project(_, src) => 
        List[(Expression,Operator)]((p.bindings.get(col).get, src))
      case Union(true, lhs, rhs) =>
        columnExprForOperator(col, lhs) ++ 
        	columnExprForOperator(col, rhs)
      case _ => 
        List[(Expression,Operator)]((Var(col), oper))
    }
  }

  /**
   * Normalize an operator tree by distributing operators
   * over union terms.
   */
  def extractUnions(o: Operator): List[Operator] =
  {
    // println("Extract: " + o)
    o match {
      case Union(true, lhs, rhs) => 
        extractUnions(lhs) ++ extractUnions(rhs)
      case Project(args, c) =>
        extractUnions(c).map ( Project(args, _) )
      case Select(expr, c) =>
        extractUnions(c).map ( Select(expr, _) )
      case t : Table => List[Operator](t)
      case Join(lhs, rhs) =>
        extractUnions(lhs).flatMap (
          (lhsTerm: Operator) =>
            extractUnions(rhs).map( 
              Join(lhsTerm, _)
            )
        )
      case Union(false, _, _) => List[Operator](o)
    }
  }

}