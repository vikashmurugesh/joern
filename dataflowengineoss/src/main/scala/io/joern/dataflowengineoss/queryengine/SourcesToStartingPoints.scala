package io.joern.dataflowengineoss.queryengine

import io.joern.dataflowengineoss.globalFromLiteral
import io.joern.x2cpg.Defines
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.v2.Operators
import io.shiftleft.codepropertygraph.generated.v2.nodes.*
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.operatorextension.allAssignmentTypes
import io.shiftleft.semanticcpg.utils.MemberAccess.isFieldAccess
import org.slf4j.LoggerFactory

import java.util.concurrent.{ForkJoinPool, ForkJoinTask, RecursiveTask, RejectedExecutionException}
import scala.util.{Failure, Success, Try}

case class StartingPointWithSource(startingPoint: CfgNode, source: StoredNode)

object SourcesToStartingPoints {

  private val log = LoggerFactory.getLogger(SourcesToStartingPoints.getClass)

  def sourceTravsToStartingPoints[NodeType](sourceTravs: IterableOnce[NodeType]*): List[StartingPointWithSource] = {
    val fjp = ForkJoinPool.commonPool()
    try {
      fjp.invoke(new SourceTravsToStartingPointsTask(sourceTravs: _*)).distinct
    } catch {
      case e: RejectedExecutionException =>
        log.error("Unable to execute 'SourceTravsToStartingPoints` task", e); List()
    } finally {
      fjp.shutdown()
    }
  }

}

class SourceTravsToStartingPointsTask[NodeType](sourceTravs: IterableOnce[NodeType]*)
    extends RecursiveTask[List[StartingPointWithSource]] {

  private val log = LoggerFactory.getLogger(this.getClass)

  override def compute(): List[StartingPointWithSource] = {
    val sources: List[StoredNode] = sourceTravs
      .flatMap(_.iterator.toList)
      .collect { case n: StoredNode => n }
      .dedup
      .toList
      .sortBy(_.id)
    val tasks = sources.map(src => (src, new SourceToStartingPoints(src).fork()))
    tasks.flatMap { case (src, t: ForkJoinTask[List[CfgNode]]) =>
      Try(t.get()) match {
        case Failure(e)       => log.error("Unable to complete 'SourceToStartingPoints' task", e); List()
        case Success(sources) => sources.map(s => StartingPointWithSource(s, src))
      }
    }
  }
}

/** The code below deals with member variables, and specifically with the situation where literals that initialize
  * static members are passed to `reachableBy` as sources. In this case, we determine the first usages of this member in
  * each method, traversing the AST from left to right. This isn't fool-proof, e.g., goto-statements would be
  * problematic, but it works quite well in practice.
  */
class SourceToStartingPoints(src: StoredNode) extends RecursiveTask[List[CfgNode]] {

  private val cpg = Cpg(src.graph)

  override def compute(): List[CfgNode] = sourceToStartingPoints(src)

  private def sourceToStartingPoints(src: StoredNode): List[CfgNode] = {
    src match {
      case methodReturn: MethodReturn =>
        methodReturn.method.callIn.l
      case lit: Literal =>
        List(lit) ++ usages(targetsToClassIdentifierPair(literalToInitializedMembers(lit))) ++ globalFromLiteral(lit)
      case member: Member =>
        usages(targetsToClassIdentifierPair(List(member)))
      case x: Declaration =>
        List(x).collectAll[CfgNode].toList
      case x: Identifier =>
        (withFieldAndIndexAccesses(
          List(x).collectAll[CfgNode].toList ++ x.refsTo.collectAll[Local].flatMap(sourceToStartingPoints)
        ) ++ x.refsTo.capturedByMethodRef.referencedMethod.flatMap(m => usagesForName(x.name, m))).flatMap {
          case x: Call => sourceToStartingPoints(x)
          case x       => List(x)
        }
      case x: Call =>
        (x._receiverIn.l :+ x).collect { case y: CfgNode => y }
      case x => List(x).collect { case y: CfgNode => y }
    }
  }

  private def withFieldAndIndexAccesses(nodes: List[CfgNode]): List[CfgNode] =
    nodes.flatMap {
      case identifier: Identifier =>
        List(identifier) ++ fieldAndIndexAccesses(identifier)
      case x => List(x)
    }

  private def fieldAndIndexAccesses(identifier: Identifier): List[CfgNode] =
    identifier.method._identifierViaContainsOut
      .nameExact(identifier.name)
      .inCall
      .collect { case c if isFieldAccess(c.name) => c }
      .l

  private def usages(pairs: List[(TypeDecl, AstNode)]): List[CfgNode] = {
    pairs.flatMap { case (typeDecl, astNode) =>
      val nonConstructorMethods = methodsRecursively(typeDecl).iterator
        .whereNot(_.nameExact(Defines.StaticInitMethodName, Defines.ConstructorMethodName, "__init__"))
        .l

      val usagesInSameClass =
        nonConstructorMethods.flatMap { m => firstUsagesOf(astNode, m, typeDecl) }

      val usagesInOtherClasses = cpg.method.flatMap { m =>
        m.fieldAccess
          .where(_.argument(1).isIdentifier.typeFullNameExact(typeDecl.fullName))
          .where { x =>
            astNode match {
              case identifier: Identifier =>
                x.argument(2).isFieldIdentifier.canonicalNameExact(identifier.name)
              case fieldIdentifier: FieldIdentifier =>
                x.argument(2).isFieldIdentifier.canonicalNameExact(fieldIdentifier.canonicalName)
              case member: Member =>
                x.argument(2).isFieldIdentifier.canonicalNameExact(member.name)
              case _ => Iterator.empty
            }
          }
          .takeWhile(notLeftHandOfAssignment)
          .headOption
      }.l
      usagesInSameClass ++ usagesInOtherClasses
    }
  }

  /** For given method, determine the first usage of the given expression.
    */
  private def firstUsagesOf(astNode: AstNode, m: Method, typeDecl: TypeDecl): List[Expression] = {
    astNode match {
      case member: Member =>
        usagesForName(member.name, m)
      case identifier: Identifier =>
        usagesForName(identifier.name, m)
      case fieldIdentifier: FieldIdentifier =>
        val fieldIdentifiers = m.ast.isFieldIdentifier.sortBy(x => (x.lineNumber, x.columnNumber)).l
        fieldIdentifiers
          .canonicalNameExact(fieldIdentifier.canonicalName)
          .inFieldAccess
          // TODO `isIdentifier` seems to limit us here
          .where(_.argument(1).isIdentifier.or(_.nameExact("this", "self"), _.typeFullNameExact(typeDecl.fullName)))
          .takeWhile(notLeftHandOfAssignment)
          .l
      case _ => List()
    }
  }

  private def usagesForName(name: String, m: Method): List[Expression] = {
    val identifiers      = m.ast.isIdentifier.sortBy(x => (x.lineNumber, x.columnNumber)).l
    val identifierUsages = identifiers.nameExact(name).takeWhile(notLeftHandOfAssignment).l
    val fieldIdentifiers = m.ast.isFieldIdentifier.sortBy(x => (x.lineNumber, x.columnNumber)).l
    val thisRefs         = Seq("this", "self") ++ m.typeDecl.name.headOption.toList
    val fieldAccessUsages = fieldIdentifiers.isFieldIdentifier
      .canonicalNameExact(name)
      .inFieldAccess
      .where(_.argument(1).codeExact(thisRefs: _*))
      .takeWhile(notLeftHandOfAssignment)
      .l
    (identifierUsages ++ fieldAccessUsages).headOption.toList
  }

  /** For a literal, determine if it is used in the initialization of any member variables. Return list of initialized
    * members. An initialized member is either an identifier or a field-identifier.
    */
  private def literalToInitializedMembers(lit: Literal): List[Expression] =
    lit.inAssignment
      .or(
        _.method.nameExact(Defines.StaticInitMethodName, Defines.ConstructorMethodName, "__init__"),
        // in language such as Python, where assignments for members can be directly under a type decl
        _.method.typeDecl,
        // for Python, we have moved to replacing strong updates of module-level variables with their members
        _.target.isCall.nameExact(Operators.fieldAccess).argument(1).isIdentifier.name("<module>")
      )
      .target
      .flatMap {
        case identifier: Identifier
            // If these are the same, then the parent method is the module-level type
            if Option(identifier.method.fullName) == identifier.method.typeDecl.fullName.headOption ||
              // If a member shares the name of the identifier then we consider this as a member
              lit.method.typeDecl.member.name.toSet.contains(identifier.name) =>
          List(identifier)
        case call: Call if call.name == Operators.fieldAccess => call.ast.isFieldIdentifier.l
        case _                                                => List[Expression]()
      }
      .l

  private def methodsRecursively(typeDecl: TypeDecl): List[Method] = {
    def methods(x: AstNode): List[Method] = {
      x match {
        case m: Method => m :: m.astMinusRoot.isMethod.flatMap(methods).l
        case _         => List()
      }
    }
    typeDecl.method.flatMap(methods).l
  }

  private def isTargetInAssignment(identifier: Identifier): List[Identifier] = {
    identifier.start.argumentIndex(1).where(_.inAssignment).l
  }

  private def notLeftHandOfAssignment(x: Expression): Boolean = {
    !(x.argumentIndex == 1 && x.inCall.exists(y => allAssignmentTypes.contains(y.name)))
  }

  private def targetsToClassIdentifierPair(targets: List[AstNode]): List[(TypeDecl, AstNode)] = {
    targets.flatMap {
      case expr: FieldIdentifier =>
        expr.method.typeDecl.map { typeDecl => (typeDecl, expr) } ++
          expr.inCall.fieldAccess.referencedMember.flatMap { member =>
            member.typeDecl.map { typeDecl => (typeDecl, member) }
          }
      case expr: Expression =>
        expr.method.typeDecl.map { typeDecl => (typeDecl, expr) }
      case member: Member =>
        member.typeDecl.map { typeDecl => (typeDecl, member) }
    }
  }

}
