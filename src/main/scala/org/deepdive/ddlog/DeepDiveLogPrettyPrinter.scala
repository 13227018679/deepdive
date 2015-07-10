package org.deepdive.ddlog

import org.apache.commons.lang3.StringEscapeUtils
import org.deepdive.ddlog.DeepDiveLog.Mode._

// Pretty printer that simply prints the parsed input
object DeepDiveLogPrettyPrinter extends DeepDiveLogHandler {

  // Dispatch to the corresponding function
  def print(stmt: Statement): String = stmt match {
    case s: SchemaDeclaration   => print(s)
    case s: FunctionDeclaration => print(s)
    case s: ExtractionRule      => print(s)
    case s: FunctionCallRule    => print(s)
    case s: InferenceRule       => print(s)
  }

  def print(stmt: SchemaDeclaration): String = {
    val columnDecls = stmt.a.terms.zipWithIndex map {
      case (VarExpr(name),i) => s"${name} ${stmt.a.types(i)}"
    }
    val prefix = s"${stmt.a.name}${if (stmt.isQuery) "?" else ""}("
    val indentation = " " * prefix.length
    s"""${prefix}${columnDecls.mkString(",\n" + indentation)}).
       |""".stripMargin
  }

  def print(relationType: RelationType): String = relationType match {
    case ty: RelationTypeAlias => s"like ${ty.likeRelationName}"
    case ty: RelationTypeDeclaration =>
      val namesWithTypes = (ty.names, ty.types).zipped map {
        (colName,colType) => s"${colName} ${colType}"}
      s"(${namesWithTypes.mkString(", ")})"
  }
  def print(stmt: FunctionDeclaration): String = {
    val inputType = print(stmt.inputType)
    val outputType = print(stmt.outputType)
    val impls = stmt.implementations map {
      case impl: RowWiseLineHandler =>
        "\"" + StringEscapeUtils.escapeJava(impl.command) + "\"" +
        s"\n        handles ${impl.format} lines"
    }
    val modeStr = if (stmt.mode == null) "" else s" mode = ${stmt.mode}"
    s"""function ${stmt.functionName}
       |    over ${inputType}
       | returns ${outputType}
       | ${(impls map {"implementation " + _}).mkString("\n ")}${modeStr}.
       |""".stripMargin
  }

  // print an expression
  def printExpr(e: Expr) : String = {
    e match {
      case VarExpr(name) => name
      case ConstExpr(value) => {
        if (value.startsWith("'")) s""" "${value.stripPrefix("'").stripSuffix("'")}" """
        else value
      }
      case FuncExpr(function, args, agg) => {
        val resolvedArgs = args map (x => printExpr(x))
        s"${function}(${resolvedArgs.mkString(", ")})"
      }
      case BinaryOpExpr(lhs, op, rhs) => s"(${printExpr(lhs)} ${op} ${printExpr(rhs)})"
    }
  }

  // print a condition
  def printCond(cond: Cond) : String = {
    cond match {
      case ComparisonCond(lhs, op, rhs) => s"${printExpr(lhs)} ${op} ${printExpr(rhs)}"
      case NegationCond(c) => s"![${printCond(c)}]"
      case CompoundCond(lhs, op, rhs) => {
        op match {
          case LogicOperator.AND => s"[${printCond(lhs)}, ${printCond(rhs)}]" 
          case LogicOperator.OR  => s"[${printCond(lhs)}; ${printCond(rhs)}]"
        }
      }
    }
  }

  def print(cq: ConjunctiveQuery): String = {
    val printAtom = {a:Atom =>
      val vars = a.terms map printExpr
      s"${a.name}(${vars.mkString(", ")})"
    }
    val printListAtom = {a:List[Atom] =>
      s"${(a map printAtom).mkString(",\n    ")}"
    }

    val conditionList = cq.conditions map {
      case Some(x) => Some(printCond(x))
      case None    => None
    }
    val bodyList = cq.bodies map printListAtom
    val bodyWithCondition = (bodyList zip conditionList map { case(a,b) => 
      b match {
        case Some(c) => s"${a}, ${c}" 
        case None    => a
      }
    }).mkString(";\n    ")

    s"""${printAtom(cq.head)} ${if (cq.isDistinct) "*" else ""} :-
       |    ${bodyWithCondition}""".stripMargin
  }

  def print(stmt: ExtractionRule): String = {
    print(stmt.q) +
    ( if (stmt.supervision == null) ""
      else  "\n  label = " + stmt.supervision
    ) + ".\n"
  }

  def print(stmt: FunctionCallRule): String = {
    s"""${stmt.output} :- !${stmt.function}(${stmt.input}).
       |""".stripMargin
  }

  def print(stmt: InferenceRule): String = {
    print(stmt.q) +
    ( if (stmt.weights == null) ""
      else "\n  weight = " + (stmt.weights match {
        case KnownFactorWeight(w) => w.toString
        case UnknownFactorWeight(vs) => vs.mkString(", ")
      })
    ) +
    ( if (stmt.semantics == null) ""
      else "\n  semantics = " + stmt.semantics
    ) + ".\n"
  }

  override def run(parsedProgram: DeepDiveLog.Program, config: DeepDiveLog.Config) = {
    val programToPrint =
      // derive the program based on mode information
      config.mode match {
        case ORIGINAL => parsedProgram
        case INCREMENTAL => DeepDiveLogDeltaDeriver.derive(parsedProgram)
        case MATERIALIZATION => parsedProgram
        case MERGE => DeepDiveLogMergeDeriver.derive(parsedProgram)
      }
    // pretty print in original syntax
    programToPrint foreach {stmt => println(print(stmt))}
  }
}
