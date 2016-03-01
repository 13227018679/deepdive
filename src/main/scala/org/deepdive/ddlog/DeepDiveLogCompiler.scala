package org.deepdive.ddlog

// DeepDiveLog compiler
// See: https://docs.google.com/document/d/1SBIvvki3mnR28Mf0Pkin9w9mWNam5AA0SpIGj1ZN2c4

import scala.collection.immutable.HashMap
import scala.language.postfixOps

// Compiler that takes parsed program as input and turns into blocks of deepdive.conf
class DeepDiveLogCompiler( program : DeepDiveLog.Program, config : DeepDiveLog.Config ) {
  type CompiledBlocks = List[(String, Any)]  // TODO be more specific

  case class QuotedString(value: String)

  val statements = program

  val attrNameForRelationAndPosition: Map[ Tuple2[String,Int], String ] =
    (statements collect {
      case decl: SchemaDeclaration =>
        decl.a.terms.zipWithIndex.map { case (n, i) => (decl.a.name, i) -> n }
    } flatten) toMap

  val functionDeclarationByName: Map[String, FunctionDeclaration] =
    statements collect {
      case fdecl: FunctionDeclaration => fdecl.functionName -> fdecl
    } toMap

  val schemaDeclarationByRelationName: Map[String, SchemaDeclaration] =
    statements collect {
      case decl: SchemaDeclaration => decl.a.name -> decl
    } toMap

  def isCategoricalRelation(name: String): Boolean =
    schemaDeclarationByRelationName get(name) map (_.categoricalColumns.size > 0) getOrElse(false)

  val inferenceRules = statements collect { case s: InferenceRule => s }

  val statementsByHeadName: Map[String, List[Statement]] =
    statements collect {
      case s: ExtractionRule       => s.headName -> s
      case s: FunctionCallRule     => s.output -> s
    } groupBy(_._1) mapValues(_ map {_._2})

  val statementsByRepresentative: Map[Statement, List[Statement]] =
    statementsByHeadName map { case (name, stmts) => stmts(0) -> stmts }

  val representativeStatements : Set[Statement] =
    statementsByHeadName.values map { _(0) } toSet

  def optionalIndex(idx: Int) =
    if (idx < 1) "" else s"${idx}"

  // Given a statement, resolve its name for the compiled extractor block.
  def resolveExtractorBlockName(s: Statement): String = s match {
    case d: SchemaDeclaration => s"init_${d.a.name}"
    case e: ExtractionRule => s"ext_${e.headName}"
    case f: FunctionCallRule => s"ext${
      // Extra numbering is necessary because there can be many function calls to the same head relation
      // XXX This comes right after the prefix to prevent potential collision with user's name
      val functionCallsForSameOutputAndFunction =
        statementsByHeadName getOrElse(f.output, List.empty) filter {
          case s:FunctionCallRule => s.function == f.function
        }
      val idx = functionCallsForSameOutputAndFunction indexOf f
      optionalIndex(idx)
    }_${f.output}_by_${f.function}"
    case _ => sys.error(s"${s}: Cannot determine extractor block name for rule")
  }

  // Given an inference rule, resolve its name for the compiled inference block.
  def resolveInferenceBlockName(s: InferenceRule): String = {
    val factorFuncName: String = s.head.function.getClass.getSimpleName.toLowerCase
    val idxInInferenceRulesSharingHead = inferenceRules filter(_.head equals s.head) indexOf(s)
    s"inf${
        // XXX This comes right after the prefix to prevent potential collision with user's name
        optionalIndex(idxInInferenceRulesSharingHead)
      }_${
        // function name
        factorFuncName
      }_${
        // followed by variable names
        s.head.terms map {_.name} mkString("_")
      }"
  }

  // Given a variable, resolve it.  TODO: This should give a warning,
  // if we encouter a variable that is not in this map, then something
  // odd has happened.
  def resolveName( v : Variable ) : String = {
    v match { case Variable(v,relName,i) =>
      if(attrNameForRelationAndPosition contains (relName,i)) {
        attrNameForRelationAndPosition(relName,i)
      } else {
        // for views, columns names are in the form of "column_index"
        return s"column_${i}"
      }
    }
  }

  // has schema declared
  def hasSchemaDeclared(relName: String) : Boolean = {
    schemaDeclarationByRelationName contains relName
  }

  def collectUsedRelations1(body: Body): List[String] = body match {
    case a: BodyAtom => List(a.name)
    case q: QuantifiedBody => collectUsedRelations(q.bodies)
    case _ => List.empty
  }
  def collectUsedRelations(bodies: List[Body]): List[String] = bodies flatMap collectUsedRelations1
  val relationNamesUsedByStatement = statements collect {
      case f : FunctionCallRule => f -> (f.q.bodies flatMap collectUsedRelations distinct)
      case e : ExtractionRule   => e -> (e.q.bodies flatMap collectUsedRelations distinct)
      case e : InferenceRule    => e -> (e.q.bodies flatMap collectUsedRelations distinct)
    } toMap


  sealed trait AliasStyle
  // ViewStyle: column_{columnIndex}
  case object ViewAlias extends AliasStyle
  // TableStyle: tableName.R{tableIndex}.columnName
  case object TableAlias extends AliasStyle
  case object NoAlias extends AliasStyle
  case object UseVariableAsAlias extends AliasStyle


  // This is responsible for compiling a single conjunctive query
class QueryCompiler(cq : ConjunctiveQuery) {
  // TODO remove var and make the generate* stuff below side-effect free
  // This is responsible for schema elements within a given query, e.g.,
  // what is the canonical version of x? (i.e., the first time it is
  // mentioned in the body. This is useful to translate to SQL (join
  // conditions, select, etc.)
  var query_schema = new HashMap[ String, Tuple2[String,Variable] ]()

  // maps each variable name to a canonical version of itself (first occurrence in body in left-to-right order)
  // index is the index of the subgoal/atom this variable is found in the body.
  // variable is the complete Variable type for the found variable.
  def generateCanonicalVar()  = {
    def generateCanonicalVarFromAtom(a: BodyAtom, index: String) {
      a.terms.zipWithIndex.foreach { case (expr, i) =>
        expr match {
          case VarPattern(v) =>
            if (! (query_schema contains v) )
              query_schema += { v -> (index, Variable(v,a.name,i) ) }
          case _ =>
        }
      }
    }
    // For quantified body, we need to recursively add variables from the atoms inside the quantified body
    def generateCanonicalVarFromBodies(bodies: List[Body], indexPrefix: String) {
      bodies.zipWithIndex.foreach {
        case (a: BodyAtom, index) => generateCanonicalVarFromAtom(a, s"${indexPrefix}${index}")
        // we need to recursively handle the atoms inside the modifier
        case (a: QuantifiedBody, index) => generateCanonicalVarFromBodies(a.bodies, s"${indexPrefix}${index}_")
        case _ =>
      }
    }

    cq.bodies foreach { generateCanonicalVarFromBodies(_, "") }
  }
  generateCanonicalVar() // initialize

  // accessors
  def getBodyIndex( varName : String )       = { query_schema(varName)._1 }
  def getVar(varName : String ) : Variable   = { query_schema(varName)._2 }

  // compile a variable name to its canonical relation and column in R{index}.{name} style
  def compileVariable(v: String) = {
    val index = getBodyIndex(v)
    val name  = resolveName(getVar(v))
    s"R${index}.${name}"
  }

  // compile alias
  def compileAlias(e: Expr, aliasStyle: AliasStyle) = aliasStyle match {
    case NoAlias => ""
    case TableAlias => e match {
      case VarExpr(v) => {
        val index = getBodyIndex(v)
        val variable = getVar(v)
        val name  = resolveName(variable)
        val relation = variable.relName
        s""" AS "${relation}.R${index}.${name}"""" //"
      }
      case _ => s" AS column_${cq.headTerms indexOf e}"
    }
    case ViewAlias => s" AS column_${cq.headTerms indexOf e}"
    case UseVariableAsAlias => s""" AS "${DeepDiveLogPrettyPrinter.print(e)}"""" //" fix sublime highlighting
  }

  // resolve an expression
  def compileExpr(e: Expr) : String = compileExpr(e, 0)
  def compileExpr(e: Expr, level: Int) : String = {
    e match {
      case VarExpr(name) => compileVariable(name)
      case NullConst() => "NULL"
      case IntConst(value) => value.toString
      case DoubleConst(value) => value.toString
      case BooleanConst(value) => value.toString
      case StringConst(value) => s"'${value.replaceAll("'", "''")}'"
      case FuncExpr(function, args, agg) => {
        val resolvedArgs = args map (x => compileExpr(x))
        val resolved = s"${function}(${resolvedArgs.mkString(", ")})"
        resolved
      }
      case BinaryOpExpr(lhs, op, rhs) => {
        val resovledLhs = compileExpr(lhs, level + 1)
        val resovledRhs = compileExpr(rhs, level + 1)
        val sql = s"${resovledLhs} ${op} ${resovledRhs}"
        if (level == 0) sql else s"(${sql})"
      }
      case TypecastExpr(lhs, rhs) => {
        val resovledLhs = compileExpr(lhs)
        s"(${resovledLhs} :: ${rhs})"
      }
      case IfThenElseExpr(ifCondThenExprPairs, optElseExpr) => {
        (ifCondThenExprPairs map {
          case (ifCond, thenExpr) => s"WHEN ${compileCond(ifCond)} THEN ${compileExpr(thenExpr)}"
        }) ++ List(optElseExpr map compileExpr mkString("ELSE ", "", ""))
      } mkString("\nCASE ", "\n     ", "\nEND")
      case ArrayElementExpr(name, index) => s"${compileExpr(name, level + 1)}[${compileExpr(index, level + 1)}]"
    }
  }

  // resolve a condition
  def compileCond(cond: Cond) : String = compileCond(cond, 0)
  def compileCond(cond: Cond, level: Int) : String = {
    cond match {
      case ExprCond(e) => compileExpr(e)
      case NegationCond(c) => s"NOT ${compileCond(c, level + 1)}"
      case CompoundCond(lhs, op, rhs) => {
        val resolvedLhs = s"${compileCond(lhs, level + 1)}"
        val resolvedRhs = s"${compileCond(rhs, level + 1)}"
        val sql = op match {
          case LogicOperator.AND() => s"${resolvedLhs} AND ${resolvedRhs}"
          case LogicOperator.OR()  => s"${resolvedLhs} OR ${resolvedRhs}"
        }
        if (level == 0) sql else s"(${sql})"
      }
      case _ => ""
    }
  }

  // generate SQL SELECT cluase
  def generateSQLHead(aliasStyle: AliasStyle) = {
    val head = if (cq.headTerms isEmpty) "*" else cq.headTerms map { expr =>
      compileExpr(expr) + compileAlias(expr, aliasStyle)
    } mkString(", ")
    val distinctStr = if (cq.isDistinct) "DISTINCT " else ""
    s"${distinctStr}${head}"
  }

  // This is generic code that generates the FROM with positional aliasing R0, R1, etc.
  // and the corresponding WHERE clause
  def generateSQLBody(body: List[Body]) : String = {
    // generate where clause from unification and conditions
    def generateWhereClause(bodies: List[Body], indexPrefix: String) : String = {
      val conditions = bodies.zipWithIndex.flatMap {
        case (BodyAtom(relName, terms), index) => {
          val bodyIndex = s"${indexPrefix}${index}"
          terms.zipWithIndex flatMap { case (pattern, termIndex) =>
            pattern match {
              // a simple variable indicates a join condition with other columns having the same variable name
              case VarPattern(varName) => {
                val canonical_body_index = getBodyIndex(varName)
                if (canonical_body_index != bodyIndex) {
                  val real_attr_name1 = resolveName( Variable(varName, relName, termIndex) )
                  val real_attr_name2 = resolveName( getVar(varName))
                  Some(s"R${ bodyIndex }.${ real_attr_name1 } = R${ canonical_body_index }.${ real_attr_name2 } ")
                } else { None }
              }
              case PlaceholderPattern() => None
              // expression patterns indicate a filter condition on the column
              case ExprPattern(expr) => {
                val resolved = compileExpr(expr)
                val attr = attrNameForRelationAndPosition(relName, termIndex)
                Some(s"R${bodyIndex}.${attr} = ${resolved}")
              }
            }
          }
        }
        case (x: Cond, _) => Some(compileCond(x, 1))
        case (x: QuantifiedBody, index) => {
          val newIndexPrefix = s"${indexPrefix}${index}_"
          val subqueryWhereStr = generateWhereClause(x.bodies, newIndexPrefix)
          val subqueryFromStr  = generateFromClause(x.bodies, newIndexPrefix)
          x.modifier match {
            case ExistModifier(negated) => Some(s"${if (negated) "NOT " else ""}EXISTS (SELECT 1 FROM ${subqueryFromStr} WHERE ${subqueryWhereStr})")
            case AllModifier() => Some(s"NOT EXISTS (SELECT 1 FROM ${subqueryFromStr} WHERE NOT (${subqueryWhereStr}))")
            case _ => None
          }
        }
      }
      conditions.mkString(" AND ")
    }

    def generateFromClause(bodies: List[Body], indexPrefix: String) : String = {
      val atoms = bodies.zipWithIndex flatMap {
        case (x:BodyAtom,i) => Some(s"${x.name} R${indexPrefix}${i}")
        case _ => None
      }
      val outers = bodies.zipWithIndex flatMap {
        case (x:QuantifiedBody,i) => {
          val newIndexPrefix = s"${indexPrefix}${i}_"
          x.modifier match {
            case OuterModifier() => {
              val from = generateFromClause(x.bodies, newIndexPrefix)
              val joinCond = generateWhereClause(x.bodies, newIndexPrefix)
              Some(s"${from} ON ${joinCond}")
            }
            case _ => None
          }
        }
        case _ => None
      }
      // full outer join
      if (atoms isEmpty) {
        outers.mkString(" FULL OUTER JOIN ")
      } else {
        (atoms.mkString(", ") +: outers).mkString(" LEFT OUTER JOIN ")
      }
    }

    def optionalClause(prefix: String, clause: String): String =
      if (clause.isEmpty) "" else prefix + " " + clause

    // check if an expression contains an aggregation function
    def containsAggregation(expr: Expr) : Boolean = {
      expr match {
        case FuncExpr(function, args, agg) => if (agg) agg else {
          args.map(containsAggregation).foldLeft(false)(_ || _)
        }
        case BinaryOpExpr(lhs, op, rhs) => containsAggregation(lhs) || containsAggregation(rhs)
        case TypecastExpr(lhs, rhs) => containsAggregation(lhs)
        case _ => false
      }
    }

    // handle group by
    // map head terms, leaving out aggregation functions
    val groupbyTerms = cq.headTerms.filterNot(containsAggregation) map (compileExpr(_))

    val groupbyStr = if (groupbyTerms.size == cq.headTerms.size || groupbyTerms.isEmpty) {
      ""
    } else {
      s"\n        GROUP BY ${groupbyTerms.mkString(", ")}"
    }

    // limit clause
    val limitStr = optionalClause("\nLIMIT", cq.limit.getOrElse("").toString)

    // order by clause by handling any @order_by annotations
    val orderbyStr =
      if (cq.headTermAnnotations.size != cq.headTerms.size) "" // invalid annotations
      else {
        val ORDER_BY_ANNOTATION_NAMES = Set("order_by")
        val orderByAnnos = cq.headTermAnnotations map {
          case annos => annos collectFirst {
            case anno if ORDER_BY_ANNOTATION_NAMES contains anno.name => anno
          }
        }
        val orderByExprs = cq.headTerms.zip(orderByAnnos).zipWithIndex collect {
          case ((e, Some(orderByAnno)), i) =>
            // TODO factor out annotation argument handling
            val isAscending = // the "dir" or first argument of the annotation decides the sort order, defaulting to ASCending
              orderByAnno.args collect {
                case Left(argsMap) => (argsMap getOrElse("dir", "ASC")).toString == "ASC"
                case Right(dir :: _) => dir.toString == "ASC"
              } getOrElse(true)
            val priority = // the priority or second argument decides the key index
              orderByAnno.args collect {
                case Left(argsMap) => (argsMap getOrElse("priority", i+1)).toString.toInt
                case Right(_ :: priority :: _) => priority.toString.toInt
              } getOrElse(i+1)
            (e, isAscending, priority)
        } sortBy { _._3 }
        optionalClause("\nORDER BY", orderByExprs map {
            case (e, isAscending, _) => s"${compileExpr(e)} ${if (isAscending) "ASC" else "DESC"}"
          } mkString(", ")
        )
      }

    s"""FROM ${ generateFromClause(body, "") }
        ${ optionalClause("WHERE", generateWhereClause(body, "")) }${groupbyStr}${orderbyStr}${limitStr}"""
  }

  def generateSQL(aliasStyle: AliasStyle = ViewAlias) = {
    val head = generateSQLHead(aliasStyle)
    val body = cq.bodies map generateSQLBody
    body map { b => s"SELECT ${head}\n${b}" } mkString("\nUNION ALL\n")
  }

}

  def escape4sh(arg: String): String = s"'${arg.replaceAll("'", "'\\\\''")}'"

  def compile(stmt: SchemaDeclaration): CompiledBlocks = {
    // Nothing to compile
    List.empty
  }

  def compile(stmt: FunctionDeclaration): CompiledBlocks = {
    // Nothing to compile
    List.empty
  }

  // Generate extraction rule part for deepdive
  def compile(stmt: ExtractionRule): CompiledBlocks = {
    val stmts = statementsByRepresentative getOrElse(stmt, List.empty) collect { case s: ExtractionRule => s }
    if (stmts isEmpty) return List.empty

    val inputQueries = stmts flatMap { case stmt =>
      stmt.q.bodies map { case cqBody =>
        val tmpCq = stmt.q.copy(bodies = List(cqBody))
        // Generate the body of the query.
        val qc              = new QueryCompiler(tmpCq)

        if (stmt.supervision != None) {
          if (stmt.q.bodies.length > 1) sys.error(s"Scoping rule does not allow disjunction.\n")
          val headStr = qc.generateSQLHead(NoAlias)
          val labelCol = qc.compileExpr(stmt.supervision.get)
          s"""SELECT DISTINCT ${ headStr }, 0 AS id, ${labelCol} AS label
          ${ qc.generateSQLBody(cqBody) }
          """
        } else {
          // variable columns
          // dd_new_ tale only need original column name to make sure the schema is the same with original table
          // views uses view alias
          val aliasStyle = if (!hasSchemaDeclared(stmt.headName)) ViewAlias
              else TableAlias
          qc.generateSQL(aliasStyle)
        }
      }
    }
    val blockName = resolveExtractorBlockName(stmts(0))
    val createTable = hasSchemaDeclared(stmts(0).headName)
    List(s"deepdive.extraction.extractors.${blockName}" -> Map(
      "cmd" -> QuotedString(if (createTable) {
	s"""
	# TODO use temporary table
	deepdive create table "${stmts(0).headName}"
	deepdive sql ${escape4sh(s"INSERT INTO ${stmts(0).headName} ${inputQueries.mkString("\nUNION ALL\n")}")}
	# TODO rename temporary table to replace output_relation
	"""
} else {
	s"""
	deepdive create view ${stmts(0).headName} as ${escape4sh(inputQueries.mkString("\nUNION ALL\n"))}
	"""
}),
      "output_relation" -> stmts(0).headName,
      "style" -> "cmd_extractor",
      "input_relations" -> (stmts flatMap relationNamesUsedByStatement distinct)
    ))
  }

  def compile(stmt: FunctionCallRule): CompiledBlocks = {
    val stmts = statementsByRepresentative getOrElse(stmt, List.empty) collect { case s: FunctionCallRule => s }
    if (stmts isEmpty) return List.empty

    stmts map { case stmt =>
      val function = functionDeclarationByName(stmt.function)
      val udfDetails = function.implementations collectFirst {
        case impl: RowWiseLineHandler => List(
            "udf" -> QuotedString(impl.command),
            "style" -> s"${impl.style}_extractor"
          )
      }

      if (udfDetails.isEmpty)
        sys.error(s"Cannot find compilable implementation for function ${stmt.function} among:\n  "
          + (function.implementations mkString "\n  "))

      val blockName = resolveExtractorBlockName(stmt)
      val parallelism = stmt.parallelism getOrElse("${PARALLELISM}")
      s"deepdive.extraction.extractors.${blockName}" -> (Map(
        "input" -> QuotedString(new QueryCompiler(stmt.q).generateSQL(TableAlias)),
        "output_relation" -> stmt.output,
        "input_relations" -> (List(stmt) flatMap relationNamesUsedByStatement distinct),
        "input_batch_size" -> "${INPUT_BATCH_SIZE}",
        "parallelism" -> parallelism
        ) ++ udfDetails.get)
    }
  }

  // generate inference rule part for deepdive
  def compile(stmt: InferenceRule): CompiledBlocks = {
    val headAsBody = stmt.head.terms map { x =>
      BodyAtom(x.name, x.terms map {
        case x: VarExpr => VarPattern(x.name)
        case x: Expr    => ExprPattern(x)
      })
    }

    val inputQueries =
      stmt.q.bodies map { case cqBody =>
        // edge query
        // Here we need to select from the bodies atoms as well as the head atoms,
        // which are the variable relations.
        // This is achieved by puting head atoms into the body.

        val fakeBody        = headAsBody ++ cqBody
        val fakeCQ          = stmt.q.copy(bodies = List(fakeBody))
        val fakeBodyAtoms   = fakeBody.collect { case x: BodyAtom => x }

        val index = cqBody.length + 1
        val qc = new QueryCompiler(fakeCQ)
        // TODO self-join?
        val varInBody = (fakeBody.zipWithIndex flatMap {
          case (x: BodyAtom, i) =>
            if (schemaDeclarationByRelationName get(x.name) map(_.isQuery) getOrElse(false))
              Some(s"""R${fakeBody indexOf x}.id AS "${x.name}.R${fakeBody indexOf x}.id" """)
            else
              None
          case _ => None
        }) mkString(", ")

        // weight string
        val uwStr = stmt.weights.variables.zipWithIndex.flatMap {
          case(s: ConstExpr, i) => None
          case(s: Expr, i) => Some(qc.compileExpr(s) + s""" AS "dd_weight_column_${i}" """)
        } mkString(", ")

        val selectStr = List(varInBody, uwStr).filterNot(_.isEmpty).mkString(", ")

        // factor input query
        s"""
          SELECT ${selectStr}
          ${ qc.generateSQLBody(fakeBody) }"""
      }

    // factor function
    val func = {
      val funcBody = (headAsBody zip stmt.head.terms map { case(x, y) =>
        s"""${if (y.isNegated) "!" else ""}${x.name}.R${headAsBody indexOf x}.label"""
      })

      val categoricalVars = stmt.head.terms filter { t => isCategoricalRelation(t.name) }
      val isMultinomialFactor =
        if (categoricalVars.size == 0 || stmt.head.terms.size == categoricalVars.size)
          categoricalVars.size > 0
        else
          sys.error("None or all variables must be categorical")

      val function =
        if (isMultinomialFactor) {
          stmt.head.function match {
            case FactorFunction.IsTrue() | FactorFunction.And() => "Multinomial"
            case f => sys.error(s"Unsupported factor over categorical variables: ${f}")
          }
        } else { // Boolean
          stmt.head.function match {
            case FactorFunction.Imply()  => "Imply"
            case FactorFunction.And()    => "And"
            case FactorFunction.Or()     => "Or"
            case FactorFunction.Equal()  => "Equal"
            case FactorFunction.Linear() => "Linear"
            case FactorFunction.Ratio()  => "Ratio"
            case FactorFunction.IsTrue() => "Imply" // TODO fix to IsTrue
          }
      }
      s"""${function}(${funcBody.mkString(", ")})"""
    }

    // weight
    val weight = {
      // note error cases should be handled in semantic checker
      // FIXME check if all weights.variables are ConstExpr to determine whether it's a fixed or unknown weight
      stmt.weights.variables(0) match {
        case IntConst(value) => value.toString
        case DoubleConst(value) => value.toString
        case StringConst(value) => "?"
        case _ => {
          val weightVars = stmt.weights.variables.zipWithIndex.flatMap {
            case(s: Expr, i) => Some(s"dd_weight_column_${i}")
          } mkString(", ")
          s"?(${weightVars})"
        }
      }
    }

    List(s"deepdive.inference.factors.${resolveInferenceBlockName(stmt)}" -> Map(
        "input_query" -> QuotedString(inputQueries.mkString(" UNION ALL ")),
        "function" -> QuotedString(func),
        "weight" -> QuotedString(weight),
        "input_relations" -> {
          val relationsInHead = stmt.head.terms map (_.name)
          val relationsInBody = relationNamesUsedByStatement getOrElse(stmt, List.empty)
          (relationsInHead ++ relationsInBody) distinct
        }
    ))
  }

  def compile(stmt: Statement): CompiledBlocks = stmt match {
    case s: SchemaDeclaration => compile(s)
    case s: FunctionDeclaration => compile(s)
    case s: ExtractionRule => compile(s)
    case s: FunctionCallRule => compile(s)
    case s: InferenceRule => compile(s)
    case _ => sys.error(s"Compiler does not recognize statement: ${stmt}")
  }

  // generate application.conf pipelines
  def compilePipelines(): CompiledBlocks = {
    val run = List("deepdive.pipeline.run" -> "${PIPELINE}")
    val extraction = statements filter(representativeStatements contains _) map resolveExtractorBlockName
    val extraction_pipeline = if (extraction nonEmpty) List("deepdive.pipeline.pipelines.extraction" -> extraction) else List.empty
    val inference = inferenceRules map resolveInferenceBlockName
    val inference_pipeline = if (inference nonEmpty) List("deepdive.pipeline.pipelines.inference" -> inference) else List.empty
    val endtoend = extraction ++ inference
    val endtoend_pipeline = if (endtoend nonEmpty) List("deepdive.pipeline.pipelines.endtoend" -> endtoend) else List.empty

    run ++ extraction_pipeline ++ inference_pipeline ++ endtoend_pipeline
  }

  // generate variable schema statements
  def compileVariableSchema(): CompiledBlocks = {
    // generate the statements.
    List("deepdive.schema.variables" -> (statements collect {
      case decl: SchemaDeclaration if decl.isQuery =>
        if (isCategoricalRelation(decl.a.name)) {
          decl.a.name -> (decl.categoricalColumns map (_ -> "Categorical") toMap)
        } else // Boolean variables
          s"${decl.a.name}.label" -> "Boolean"
      } toMap)
    )
  }

  def compile(): CompiledBlocks = (
    compileVariableSchema()
    ++
    (statements flatMap compile)
    ++
    compilePipelines()
  )

}

object DeepDiveLogCompiler extends DeepDiveLogHandler {

  // entry point for compilation
  override def run(parsedProgram: DeepDiveLog.Program, config: DeepDiveLog.Config) = {
    // don't compile if it doesn't pass all semantic checks
    DeepDiveLogSemanticChecker.run(parsedProgram, config)
    val programToCompile = parsedProgram

    // take an initial pass to analyze the parsed program
    val compiler = new DeepDiveLogCompiler( programToCompile, config )

    // compile the program into blocks of deepdive.conf
    val blocks = compiler.compile()

    // codegen HOCON
    def codegenValue(value: Any): String = value match {
      case compiler.QuotedString(s) => // multi-line string
        s"""\"\"\"${s replaceAll("\"\"\"", "\\\"\\\"\\\"")}\"\"\""""
      case _ =>
        value.toString
    }
    def codegen(key: String, value: Any): String = value match {
      case m: Map[_, _] => // map
        s"""${key} {
           |${m map { case (k, v) => codegen(k.toString, v) } mkString("")}
           |}
           |""".stripMargin

      case l: List[_] => // lists
        s"""${key}: [${l map codegenValue mkString("\n  ", "\n  ", "")}
           |]
           |""".stripMargin
      case l: Set[_] => // sets
        s"""${key}: [${l map codegenValue mkString("\n  ", "\n  ", "")}
           |]
           |""".stripMargin

      case _ =>
        s"""${key}: ${codegenValue(value)}
           |""".stripMargin

    }

    blocks foreach { case (key, value) => println(codegen(key, value)) }
  }

}
