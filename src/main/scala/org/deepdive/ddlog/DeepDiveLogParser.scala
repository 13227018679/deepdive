package org.deepdive.ddlog

// DeepDiveLog syntax
// See: https://docs.google.com/document/d/1SBIvvki3mnR28Mf0Pkin9w9mWNam5AA0SpIGj1ZN2c4

import scala.util.parsing.combinator._
import org.apache.commons.lang3.StringEscapeUtils
import scala.util.Try

// ***************************************
// * The union types for for the parser. *
// ***************************************
// case class Variable(varName : String, relName : String, index : Int )
// TODO make Atom a trait, and have multiple case classes, e.g., RelationAtom and CondExprAtom
// ddlog column variable type: constant or variable
case class Variable(varName : String, relName : String, index : Int )

sealed trait Expr
case class VarExpr(name: String) extends Expr
case class ConstExpr(value: String) extends Expr
case class FuncExpr(function: String, args: List[Expr], isAggregation: Boolean) extends Expr
case class BinaryOpExpr(lhs: Expr, op: String, rhs: Expr) extends Expr

case class Atom(name : String, terms : List[Expr])
case class Attribute(name : String, terms : List[VarExpr], types : List[String])
case class ConjunctiveQuery(head: Atom, bodies: List[List[Atom]], conditions: List[Option[Cond]], isDistinct: Boolean)
case class Column(name : String, t : String)
case class BodyWithCondition(body: List[Atom], condition: Option[Cond])

// condition
sealed trait Cond
case class ComparisonCond(lhs: Expr, op: String, rhs: Expr) extends Cond
case class NegationCond(cond: Cond) extends Cond
case class CompoundCond(lhs: Cond, op: LogicOperator.LogicOperator, rhs: Cond) extends Cond

// logic operators
object LogicOperator extends Enumeration {
  type LogicOperator = Value
  val  AND, OR = Value
}

// variable type
sealed trait VariableType {
  def cardinality: Long
}
case object BooleanType extends VariableType {
  def cardinality = 2
}
case class MultinomialType(numCategories: Int) extends VariableType {
  def cardinality = numCategories
}

sealed trait FactorWeight {
  def variables : List[String]
}

case class KnownFactorWeight(value: Double) extends FactorWeight {
  def variables = Nil
}
case class UnknownFactorWeight(variables: List[String]) extends FactorWeight

trait RelationType
case class RelationTypeDeclaration(names: List[String], types: List[String]) extends RelationType
case class RelationTypeAlias(likeRelationName: String) extends RelationType

trait FunctionImplementationDeclaration
case class RowWiseLineHandler(format: String, command: String) extends FunctionImplementationDeclaration

// Statements that will be parsed and compiled
trait Statement
case class SchemaDeclaration( a : Attribute , isQuery : Boolean, variableType : Option[VariableType]) extends Statement // atom and whether this is a query relation.
case class FunctionDeclaration( functionName: String, inputType: RelationType, outputType: RelationType, implementations: List[FunctionImplementationDeclaration], mode: String = null) extends Statement
case class ExtractionRule(q : ConjunctiveQuery, supervision: String = null) extends Statement // Extraction rule
case class FunctionCallRule(input : String, output : String, function : String) extends Statement // Extraction rule
case class InferenceRule(q : ConjunctiveQuery, weights : FactorWeight, semantics : String = "Imply", mode: String = null) extends Statement // Weighted rule

// Parser
class DeepDiveLogParser extends JavaTokenParsers {

  // JavaTokenParsers provides several useful number parsers:
  //   wholeNumber, decimalNumber, floatingPointNumber 
  def floatingPointNumberAsDouble = floatingPointNumber ^^ { _.toDouble }
  def stringLiteralAsString = stringLiteral ^^ {
    s => StringEscapeUtils.unescapeJava(
      s.stripPrefix("\"").stripSuffix("\""))
  }
  def stringLiteralAsSqlString = stringLiteral ^^ { s =>
    s"""'${s.stripPrefix("\"").stripSuffix("\"")}'"""
  }
  def constant = stringLiteralAsSqlString | wholeNumber | "TRUE" | "FALSE" | "NULL" | "TEXT" | "INT" | "BOOLEAN"

  // Single-line comments beginning with # or // are supported by treating them as whiteSpace
  // C/Java/Scala style multi-line comments cannot be easily supported with RegexParsers unless we introduce a dedicated lexer.
  protected override val whiteSpace = """(?:(?:^|\s+)#.*|//.*|\s)+""".r

  // We just use Java identifiers to parse various names
  def relationName = ident
  def columnName   = ident
  def columnType   = ident ~ ("[]"?) ^^ {
      case ty ~ isArrayType => ty + isArrayType.getOrElse("")
    }
  def variableName = ident
  def functionName = ident
  def semanticType = ident
  def functionModeType = ident
  def inferenceModeType = ident

  def columnDeclaration: Parser[Column] =
    columnName ~ columnType ^^ {
      case(name ~ ty) => Column(name, ty)
    }

  def CategoricalParser = "Categorical" ~> "(" ~> """\d+""".r <~ ")" ^^ { n => MultinomialType(n.toInt) }
  def BooleanParser = "Boolean" ^^ { s => BooleanType }
  def dataType = CategoricalParser | BooleanParser

  def schemaDeclaration: Parser[SchemaDeclaration] =
    relationName ~ opt("?") ~ "(" ~ rep1sep(columnDeclaration, ",") ~ ")" ~ opt(dataType) ^^ {
      case (r ~ isQuery ~ "(" ~ attrs ~ ")" ~ vType) => {
        val vars = attrs map { case(x) => VarExpr(x.name) }
        var types = attrs map { case(x) => x.t }
        val variableType = vType match {
          case None => if (isQuery != None) Some(BooleanType) else None
          case Some(s) => Some(s)
        }
        SchemaDeclaration(Attribute(r, vars, types), (isQuery != None), variableType)
      }
    }

  def operator = "||" | "+" | "-" | "*" | "/" | "&"
  def typeOperator = "::"
  val aggregationFunctions = Set("MAX", "SUM", "MIN", "ARRAY_ACCUM", "ARRAY_AGG")

  // expressions
  def expr : Parser[Expr] =
    ( lexpr ~ operator ~ expr ^^ { case (lhs ~ op ~ rhs) => BinaryOpExpr(lhs, op, rhs) }
    | lexpr ~ typeOperator ~ constant ^^ { case (lhs ~ op ~ rhs) => BinaryOpExpr(lhs, op, ConstExpr(rhs)) }
    | lexpr
    )

  def lexpr : Parser[Expr] =
    ( functionName ~ "(" ~ rep1sep(expr, ",") ~ ")" ^^ { 
        case (name ~ _ ~ args ~ _) => FuncExpr(name, args, (aggregationFunctions contains name))
      }
    | constant ^^ { ConstExpr(_) }
    | variableName ^^ { VarExpr(_) }
    | "(" ~> expr <~ ")" 
    )

  // TODO support aggregate function syntax somehow
  def cqHead = relationName ~ "(" ~ rep1sep(expr, ",") ~ ")" ^^ {
    case (r ~ "(" ~ expressions ~ ")") => Atom(r, expressions)
  }

  // conditional expressions
  def compareOperator = "LIKE" | ">" | "<" | ">=" | "<=" | "!=" | "=" | "IS" | "IS NOT"
  def cond : Parser[Cond] = 
    ( acond ~ (";") ~ cond ^^ { case (lhs ~ op ~ rhs) =>
        CompoundCond(lhs, LogicOperator.OR, rhs)
      }
    | acond
    )
  def acond : Parser[Cond] = 
    ( lcond ~ (",") ~ acond ^^ { case (lhs ~ op ~ rhs) => CompoundCond(lhs, LogicOperator.AND, rhs) }
    | lcond
    )
  // ! has higher priority...
  def lcond : Parser[Cond] = 
    ( "!" ~> bcond ^^ { NegationCond(_) }
    | bcond
    )
  def bcond : Parser[Cond] = 
    ( expr ~ compareOperator ~ expr ^^ { case (lhs ~ op ~ rhs) =>
        ComparisonCond(lhs, op, rhs)
      }
    | "[" ~> cond <~ "]"
    )

  def cqBodyAtom: Parser[Atom] =
    relationName ~ "(" ~ repsep(expr, ",") ~ ")" ^^ {
      case (r ~ "(" ~ patterns ~ ")") => Atom(r, patterns)
    }
  def cqBody: Parser[List[Atom]] = rep1sep(cqBodyAtom, ",")

  def cqBodyWithCondition = cqBody ~ ("," ~> cond).? ^^ {
    case (b ~ c) => BodyWithCondition(b, c)
  }

  def conjunctiveQuery : Parser[ConjunctiveQuery] =
    cqHead ~ opt("*") ~ ":-" ~ rep1sep(cqBodyWithCondition, ";") ^^ {
      case (headatom ~ isDistinct ~ ":-" ~ disjunctiveBodies) =>
        ConjunctiveQuery(headatom, disjunctiveBodies.map(_.body), disjunctiveBodies.map(_.condition), isDistinct != None)
  }

  def relationType: Parser[RelationType] =
    ( "like" ~> relationName ^^ { RelationTypeAlias(_) }
    | rep1sep(columnDeclaration, ",") ^^ {
        attrs => RelationTypeDeclaration(attrs map { _.name }, attrs map { _.t })
      }
    )

  def functionMode = "mode" ~> "=" ~> functionModeType
  def inferenceMode = "mode" ~> "=" ~> inferenceModeType

  def functionImplementation : Parser[FunctionImplementationDeclaration] =
    "implementation" ~ stringLiteralAsString ~ "handles" ~ ("tsv" | "json") ~ "lines" ^^ {
      case (_ ~ command ~ _ ~ format ~ _) => RowWiseLineHandler(command=command, format=format)
    }

  def functionDeclaration : Parser[FunctionDeclaration] =
    ( "function" ~ functionName ~ "over" ~ relationType
                             ~ "returns" ~ relationType
                 ~ (functionImplementation+) ~ opt(functionMode)
    ) ^^ {
      case ("function" ~ a ~ "over" ~ inTy
                           ~ "returns" ~ outTy
                       ~ implementationDecls ~ mode) =>
             FunctionDeclaration(a, inTy, outTy, implementationDecls, mode.getOrElse(null))
    }

  def extractionRule : Parser[ExtractionRule] =
    conjunctiveQuery ~ opt(supervision) ^^ {
      case (q ~ supervision) =>
        ExtractionRule(q, supervision.getOrElse(null))
    }

  def functionCallRule : Parser[FunctionCallRule] =
    ( relationName ~ ":-" ~ "!"
    ~ functionName ~ "(" ~ relationName ~ ")"
    ) ^^ {
      case (out ~ ":-" ~ "!" ~ fn ~ "(" ~ in ~ ")") =>
        FunctionCallRule(in, out, fn)
    }

  def constantWeight = floatingPointNumberAsDouble ^^ {   KnownFactorWeight(_) }
  def unknownWeight  = repsep(variableName, ",")   ^^ { UnknownFactorWeight(_) }
  def factorWeight = "weight" ~> "=" ~> (constantWeight | unknownWeight)

  def supervision = "label" ~> "=" ~> variableName

  def semantics = "semantics" ~> "=" ~> semanticType

  def inferenceRule : Parser[InferenceRule] =
    ( conjunctiveQuery ~ factorWeight ~ opt(semantics) ~ opt(inferenceMode)
    ) ^^ {
      case (q ~ weight ~ semantics ~ mode) =>
        InferenceRule(q, weight, semantics.getOrElse("Imply"), mode.getOrElse(null))
    }

  // rules or schema elements in arbitrary order
  def statement : Parser[Statement] = ( schemaDeclaration
                                      | inferenceRule
                                      | extractionRule
                                      | functionDeclaration
                                      | functionCallRule
                                      )
  def program : Parser[DeepDiveLog.Program] = phrase(rep1(statement <~ "."))

  def parseProgram(inputProgram: CharSequence, fileName: Option[String] = None): List[Statement] = {
    parse(program, inputProgram) match {
      case result: Success[_] => result.get
      case error:  NoSuccess  => throw new RuntimeException(fileName.getOrElse("") + error.toString())
    }
  }

  def parseProgramFile(fileName: String): List[Statement] = {
    val source = scala.io.Source.fromFile(fileName)
    try parseProgram(source.getLines mkString "\n", Some(fileName))
    finally source.close()
  }
}
