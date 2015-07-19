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
      case (name,i) => s"${name} ${stmt.a.types(i)}"
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
      case impl: RowWiseLineHandler => {
        val styleStr = if (impl.style == "plpy") s"\n        runs as plpy"
        else s"\n        handles ${impl.style} lines"
        "\"" + StringEscapeUtils.escapeJava(impl.command) + "\"" + styleStr
      }
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
      case TypecastExpr(lhs, rhs) => s"(${printExpr(lhs)} :: ${rhs})"
    }
  }

  // print a condition
  def printCond(cond: Cond) : String = {
    cond match {
      case ComparisonCond(lhs, op, rhs) => s"${printExpr(lhs)} ${op} ${printExpr(rhs)}"
      case NegationCond(c) => s"[!${printCond(c)}]"
      case CompoundCond(lhs, op, rhs) => {
        op match {
          case LogicOperator.AND => s"[${printCond(lhs)}, ${printCond(rhs)}]"
          case LogicOperator.OR  => s"[${printCond(lhs)}; ${printCond(rhs)}]"
        }
      }
      case InCond(lhs, rhs) => s"${printExpr(lhs)} IN ${rhs}"
      case ExistCond(rhs) => s"EXISTS ${rhs}"
    }
  }

  def printAtom(a: Atom) = {
    val vars = a.terms map printExpr
    s"${a.name}(${vars.mkString(", ")})"
  }

  def printModifierAtom(a: ModifierAtom) = {
    val modifier = a.modifier match {
      case ExistModifier() => "EXIST"
      case OuterModifier() => "OUTER"
    }
    val conditionStr = (a.condition map { c => s": ${printCond(c)}" }).getOrElse("")
    s"${modifier}[${printAtom(a.atom)}${conditionStr}]"
  }

  def print(cq: ConjunctiveQuery): String = {

    def printBody(b: Body) = {
      b match {
        case b: Atom => printAtom(b)
        case b: Cond => printCond(b)
        case b: ModifierAtom => printModifierAtom(b)
      }
    }

    def printBodyList(b: List[Body]) = {
      s"${(b map printBody).mkString(",\n    ")}"
    }

    val bodyStr = (cq.bodies map printBodyList).mkString(";\n    ")

    val distinctStr = if (cq.isDistinct) "*" else ""
    val limitStr = cq.limit match {
      case Some(s) => s" | ${s}"
      case None => ""
    }

    s"""${printAtom(cq.head)} ${distinctStr}${limitStr} :-
       |    ${bodyStr}""".stripMargin
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
        case UnknownFactorWeightBindingToConst(vs) => "\"" + vs + "\""
      })
    ) +
    ( stmt.function match {
        case Some(f) => s"\n  function = ${f}"
        case None => ""
      }
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
