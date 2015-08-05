package mimir

import java.io.{File, StringReader}
import java.sql.SQLException

import mimir.algebra._
import mimir.ctables.{CTables, CTPercolator}
import mimir.exec.ResultSetIterator
import mimir.parser.MimirJSqlParser
import mimir.sql.{CreateLens, Explain, JDBCBackend}
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select

import scala.collection.mutable.ListBuffer
import scala.util.parsing.json.{JSONArray, JSONObject}

/**
 * Created by arindam on 6/29/15.
 */

class WebAPI {

  var conf: MimirConfig = null
  var db: Database = null
  var dbName: String = null
  var dbPath: String = null
  val dbDir = "databases"

  /* Initialize the configuration and database */
  def configure(args: Array[String]): Unit = {
    conf = new MimirConfig(args)

    // Set up the database connection(s)
    dbName = conf.dbname().toLowerCase
    if(dbName.length <= 0) {
      throw new Exception("DB name must be configured!")
    }

    dbPath = java.nio.file.Paths.get(dbDir, dbName).toString

    val backend = conf.backend() match {
      case "oracle" => new JDBCBackend(Mimir.connectOracle())
      case "sqlite" => new JDBCBackend(Mimir.connectSqlite(dbPath))
      case x => {
        println("Unsupported backend: "+x)
        sys.exit(-1)
      }
    }

    db = new Database(backend)

    if(conf.initDB()){
      db.initializeDBForMimir()
    } else if(conf.loadTable.get != None){
      db.handleLoadTable(conf.loadTable(), conf.loadTable()+".csv")
    }
  }

  def handleStatement(query: String): WebResult = {
    if(db == null) {
      new WebStringResult("Database is not configured properly")
    }

    val source = new StringReader(query)
    val parser = new MimirJSqlParser(source)

    try {
      val stmt: Statement = parser.Statement();
      if(stmt.isInstanceOf[Select]){
        handleSelect(stmt.asInstanceOf[Select])
      } else if(stmt.isInstanceOf[CreateLens]) {
        db.createLens(stmt.asInstanceOf[CreateLens])
        new WebStringResult("Lens created successfully.")
      } else if(stmt.isInstanceOf[Explain]) {
        handleExplain(stmt.asInstanceOf[Explain])
      } else {
        db.update(stmt.toString())
        new WebStringResult("Database updated.")
      }

    } catch {
      case e: Throwable => {
        e.printStackTrace()
        new WebErrorResult(e.getMessage)
      }
    }

  }

  private def handleSelect(sel: Select): WebQueryResult = {
    val raw = db.convert(sel)
    println("RAW QUERY: "+raw)
    val op = db.optimize(raw)
    val results = db.query(CTPercolator.propagateRowIDs(raw, true))

    results.open()
    val wIter = db.webDump(results)
    wIter.queryFlow = convertToTree(op)
    results.close()

    new WebQueryResult(wIter)
  }

  private def handleExplain(explain: Explain): WebStringResult = {
    val raw = db.convert(explain.getSelectBody())._1;
    val op = db.optimize(raw)
    val res = "------ Raw Query ------\n"+
      raw.toString()+"\n"+
      "--- Optimized Query ---\n"+
      op.toString

    new WebStringResult(res)
  }

  def getAllTables(): List[String] = {
    db.backend.getAllTables()
  }

  def getAllLenses(): List[String] = {
    val res = db.backend.execute(
      """
        SELECT *
        FROM MIMIR_LENSES
      """)

    val iter = new ResultSetIterator(res)
    val lensNames = new ListBuffer[String]()

    iter.open()
    while(iter.getNext()) {
      lensNames.append(iter(0).asString)
    }
    iter.close()

    lensNames.toList
  }

  def getAllDBs(): Array[String] = {
    val curDir = new File("./databases")
    curDir.listFiles().filter( f => f.isFile && f.getName.endsWith(".db")).map(x => x.getName)
  }

  def getVGTerms(query: String, row: String, ind: Int): List[String] = {
    val source = new StringReader(query)
    val parser = new MimirJSqlParser(source)

    val raw =
      try {
        val stmt: Statement = parser.Statement();
        if(stmt.isInstanceOf[Select]){
          db.convert(stmt.asInstanceOf[Select])
        } else {
          throw new Exception("getVGTerms got statement that is not SELECT")
        }

      } catch {
        case e: Throwable => {
          e.printStackTrace()
          return List()
        }
      }

    val iterator = db.query(
      CTPercolator.percolate(
        mimir.algebra.Select(
          Comparison(Cmp.Eq, Var("ROWID"), new RowIdPrimitive(row.substring(1, row.length - 1))),
          db.optimize(raw)
        )
      )
    )

    if(!iterator.getNext()){
      throw new SQLException("Invalid Source Data ROWID: '" +row+"'");
    }
    iterator.reason(ind)

    /* Really, this should be data-aware.  The query should
       get the VG terms specifically affecting the specific
       row in question.  See #32 */
//    val sch = optimized.schema
//    val col_defn = if(ind == -1) sch(sch.length - 1) else sch(ind)
//
//    val ret =
//    OperatorUtils.columnExprForOperator(col_defn._1, optimized).
//      // columnExprForOperator returns (expr,oper) pairs.  We care
//      // about the expression
//      map( _._1 ).
//      // Get the VG terms that might be affecting it.  THIS SHOULD
//      // BE DATA-DEPENDENT.
//      map( db.getVGTerms(_) ).
//      // List of lists -> Just a list
//      flatten.
//      // Pull the reasons
//      map( _.reason() ).
//      // Discard duplicate reasons
//      distinct
//    ret
  }

  def close(): Unit = {
    db.backend.close()
  }

  def convertToTree(op: Operator): OperatorNode = {
    op match {
      case Project(cols, source) => {
        val projArg = cols.find { case ProjectArg(col, exp) => CTables.isProbabilistic(exp) }.get
        var name = projArg.toString
        val i = name.indexOf("{{")
        val j = name.indexOf("}}")
        name = name.substring(i+3, j)
        new OperatorNode(name, List(convertToTree(source)))
      }
      case Join(lhs, rhs) => new OperatorNode("Join", List(convertToTree(lhs), convertToTree(rhs)))
      case Table(name, schema, metadata) => new OperatorNode(name, List[OperatorNode]())
      case o: Operator => convertToTree(o.children(0))
    }
  }
}

class OperatorNode(nodeName: String, c: List[OperatorNode]) {
  val name = nodeName
  val children = c

  def toJson(): JSONObject =
    new JSONObject(Map("name" -> name,
                       "children" -> JSONArray(children.map(a => a.toJson()))))
}

class WebIterator(h: List[String],
                  d: List[(List[String], Boolean)],
                  mR: Boolean) {

  val header = h
  val data = d
  val missingRows = mR
  var queryFlow: OperatorNode = null

}

abstract class WebResult {
  def toJson(): JSONObject
}

case class WebStringResult(string: String) extends WebResult {
  val result = string

  def toJson() = {
    new JSONObject(Map("result" -> result))
  }
}

case class WebQueryResult(webIterator: WebIterator) extends WebResult {
  val result = webIterator

  def toJson() = {
    new JSONObject(Map("header" -> result.header,
                        "data" -> result.data,
                        "missingRows" -> result.missingRows,
                        "queryFlow" -> result.queryFlow.toJson()))
  }
}

case class WebErrorResult(string: String) extends WebResult {
  val result = string

  def toJson() = {
    new JSONObject(Map("error" -> result))
  }
}
