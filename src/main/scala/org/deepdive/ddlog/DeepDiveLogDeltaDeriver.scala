package org.deepdive.ddlog

import scala.collection.mutable.ListBuffer

object DeepDiveLogDeltaDeriver{

  // Default prefix for incremental tables
  val deltaPrefix = "dd_delta_"
  val newPrefix = "dd_new_"

  def transform(stmt: Statement): List[Statement] = stmt match {
    case s: SchemaDeclaration   => transform(s)
    case s: FunctionDeclaration => transform(s)
    case s: ExtractionRule      => transform(s)
    case s: FunctionCallRule    => transform(s)
    case s: InferenceRule       => transform(s)
  }

  def transform(cq: ConjunctiveQuery): ConjunctiveQuery = {
    // New head
    val incCqHead = cq.head.copy(
      name = deltaPrefix + cq.head.name,
      terms = cq.head.terms map {term => term.copy(relName = deltaPrefix + term.relName)}
    )
    var incCqBodies = new ListBuffer[List[Atom]]()
    // New incremental bodies
    for (body <- cq.bodies) {
      // Delta body
      val incDeltaBody = body map {
        a => a.copy(
          name = deltaPrefix + a.name,
          terms = a.terms map {term => term.copy(relName = deltaPrefix + term.relName)}
        )
      }
      // New body
      val incNewBody = body map {
        a => a.copy(
          name = newPrefix + a.name,
          terms = a.terms map {term => term.copy(relName = newPrefix + term.relName)}
        )
      }
      var i = 0
      var j = 0
      for (i <- 0 to (body.length - 1)) {
        var newBody = new ListBuffer[Atom]()
        for (j <- 0 to (body.length - 1)) {
          if (j > i)
            newBody += body(j)
          else if (j < i)
            newBody += incNewBody(j)
          else if (j == i)
            newBody += incDeltaBody(j)
        }
        incCqBodies += newBody.toList
      }
    }
    ConjunctiveQuery(incCqHead, incCqBodies.toList)
  }

  // Incremental scheme declaration,
  // keep the original scheme and create one delta scheme
  def transform(stmt: SchemaDeclaration): List[Statement] = {
    var incrementalStatement = new ListBuffer[Statement]()
    // Incremental table
    val incStmt = if (stmt.isQuery) stmt else stmt.copy(
      a = stmt.a.copy(
          terms = stmt.a.terms :+ Variable("dd_count", stmt.a.name, stmt.a.terms.length),
          types = stmt.a.types :+ "int"
          )
    )
    incrementalStatement += incStmt

    // Delta table
    var incDeltaStmt = stmt.copy(
      a = stmt.a.copy(
        name = deltaPrefix + stmt.a.name,
        terms = stmt.a.terms map {term => term.copy(relName = deltaPrefix + term.relName)},
        types = stmt.a.types
      )
    )
    if (!stmt.isQuery) incDeltaStmt = incDeltaStmt.copy(
      a = incDeltaStmt.a.copy(
        terms = incDeltaStmt.a.terms :+ Variable("dd_count", deltaPrefix + stmt.a.name, stmt.a.terms.length),
        types = incDeltaStmt.a.types :+ "int"
      )
    )    
    incrementalStatement += incDeltaStmt

    // New table
    var incNewStmt = stmt.copy(
      a = stmt.a.copy(
        name = newPrefix + stmt.a.name,
        terms = stmt.a.terms map {term => term.copy(relName = newPrefix + term.relName)},
        types = stmt.a.types
      )
    )
    if (!stmt.isQuery) incNewStmt = incNewStmt.copy(
      a = incNewStmt.a.copy(
        terms = stmt.a.terms :+ Variable("dd_count", newPrefix + stmt.a.name, stmt.a.terms.length),
        types = stmt.a.types :+ "int"
      )
    )
    incrementalStatement += incNewStmt

    if (!stmt.isQuery) {
      incrementalStatement += ExtractionRule(ConjunctiveQuery(Atom(incNewStmt.a.name, incNewStmt.a.terms),
        List(List(Atom(incStmt.a.name, incStmt.a.terms)), List(Atom(incDeltaStmt.a.name, incDeltaStmt.a.terms)))))
    }
    incrementalStatement.toList
  }

  // Incremental function declaration,
  // create one delta function scheme based on original function scheme
  def transform(stmt: FunctionDeclaration): List[Statement] = {
    List(stmt.copy(
      inputType = stmt.inputType match {
        case inTy: RelationTypeDeclaration => 
          inTy.copy(names = inTy.names map {name => deltaPrefix + name})
        case inTy: RelationTypeAlias => 
          inTy.copy(likeRelationName = deltaPrefix + inTy.likeRelationName)
      },
      outputType = stmt.outputType match {
        case outTy: RelationTypeDeclaration =>
          outTy.copy(names = outTy.names map {name => deltaPrefix + name})
        case outTy: RelationTypeAlias =>
          outTy.copy(likeRelationName = deltaPrefix + outTy.likeRelationName)
      }
    ))
  }

  // Incremental extraction rule,
  // create delta rules based on original extraction rule
  def transform(stmt: ExtractionRule): List[Statement] = {
    List(ExtractionRule(transform(stmt.q)))
  }

  // Incremental function call rule,
  // modify function input and output
  def transform(stmt: FunctionCallRule): List[Statement] = {
    List(FunctionCallRule(deltaPrefix + stmt.input, deltaPrefix + stmt.output, stmt.function))
  }

  // Incremental inference rule,
  // create delta rules based on original extraction rule
  def transform(stmt: InferenceRule): List[Statement] = {
    List(InferenceRule(transform(stmt.q), stmt.weights, stmt.supervision, stmt.semantics))
  }

  def derive(program: DeepDiveLog.Program): DeepDiveLog.Program = {
    var incrementalProgram = new ListBuffer[Statement]()
    for (x <- program) {
      incrementalProgram = incrementalProgram ++ transform(x)
    }
    incrementalProgram.toList
  }
}
