package mimir.parser;

import mimir.algebra._
import mimir.ctables._

class OperatorParser(modelLookup: (String => Model), schemaLookup: (String => List[(String,Type.T)]))
	extends ExpressionParser(modelLookup) 
{

	def operator(s: String): Operator = 
		parseAll(operatorBase, s) match {
			case Success(ret, _) => ret
			case x => throw new Exception(x.toString)
		}

	def operatorBase: Parser[Operator] = 
		project | select | cross | rel_union | table

	def project =
		"PROJECT[" ~> (projectArgs <~ "](") ~ operatorBase <~ ")" ^^ { 
			case args ~ src => Project(args, src)
		}
	def projectArgs: Parser[List[ProjectArg]] = (
		  (projectArg <~ ",") ~ projectArgs ^^ { case hd ~ tl => hd :: tl }
		| projectArg ^^ { List(_) }
	)

	def projectArg: Parser[ProjectArg] =
		id ~ "<=" ~ exprBase ^^ { case name ~ _ ~ e => ProjectArg(name, e) }

	def select =
		"SELECT[" ~> (exprBase <~ "](") ~ operatorBase <~ ")" ^^ {
			case cond ~ src => Select(cond, src)
		}

	def cross =
	    "JOIN(" ~> (operatorBase <~ ",") ~ operatorBase <~ ")" ^^ {
	    	case lhs ~ rhs => Join(lhs, rhs)
	    }

	def rel_union =
		"UNION(" ~> ((rel_union_all | rel_union_distinct) <~ ",") ~ (operatorBase <~ ",") ~ operatorBase <~ ")" ^^ { case isAll ~ lhs ~ rhs => { Union(true, lhs, rhs) } }

	def rel_union_all       = "ALL"      ^^ { _ => true }
	def rel_union_distinct  = "DISTINCT" ^^ { _ => false }

	def table =
		(id ~ opt("(" ~> (colList ~ opt("//" ~> colList) ) <~ ")")) ^^ {
			case name ~ cols_and_metadata => {
				cols_and_metadata match {
					case None => 
						Table(name, schemaLookup(name), List[(String,Type.T)]())
					case Some((cols ~ metadata)) => 
						Table(name, 
							schemaLookup(name).zip(cols).map {
								case ((_,t),(v,Type.TAny)) => (v,t)
								case ((_,_),(v,t)) => (v,t)
							},
							metadata.getOrElse(List[(String,Type.T)]())
						)
				}
			}		
		}

	def colList:Parser[List[(String,Type.T)]] =
		( (col <~ ",") ~ colList ^^ { case hd ~ tl => hd :: tl }
		| col ^^ { List(_) }
		)

	def col =
		( (id <~ ":") ~ exprType ^^ { case name ~ t => (name, t) } 
		| id ^^ { (_, Type.TAny) }
		)

}