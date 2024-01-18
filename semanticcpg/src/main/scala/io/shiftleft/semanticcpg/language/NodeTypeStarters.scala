package io.shiftleft.semanticcpg.language

import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.CpgNodeStarters
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*

/**
 * Starting point for a new traversal, e.g.
 * - `cpg.method`, `cpg.call` etc. - these are generated by the flatgraph codegenerator and automatically inherited
 * - `cpg.method.name`
 */
 // TODO bring back: @help.TraversalSource
class NodeTypeStarters(cpg: Cpg) {

// TODO ensure scaladoc and @doc annotation are also generated
  /** Traverse to all arguments passed to methods */
  // @Doc(info = "All arguments (actual parameters)")
  def argument: Iterator[Expression] =
    cpg.call.argument

  /** Shorthand for `cpg.argument.code(code)` */
  def argument(code: String): Iterator[Expression] =
    cpg.argument.code(code)

  // @Doc(info = "All breaks (`ControlStructure` nodes)")
  def break: Iterator[ControlStructure] =
    cpg.controlStructure.isBreak

  // @Doc(info = "All continues (`ControlStructure` nodes)")
  def continue: Iterator[ControlStructure] =
    cpg.controlStructure.isContinue

  // @Doc(info = "All do blocks (`ControlStructure` nodes)")
  def doBlock: Iterator[ControlStructure] =
    cpg.controlStructure.isDo

  // @Doc(info = "All else blocks (`ControlStructure` nodes)")
  def elseBlock: Iterator[ControlStructure] =
    cpg.controlStructure.isElse

  // @Doc(info = "All throws (`ControlStructure` nodes)")
  def throws: Iterator[ControlStructure] =
    cpg.controlStructure.isThrow

  // @Doc(info = "All for blocks (`ControlStructure` nodes)")
  def forBlock: Iterator[ControlStructure] =
    cpg.controlStructure.isFor

  // @Doc(info = "All gotos (`ControlStructure` nodes)")
  def goto: Iterator[ControlStructure] =
    cpg.controlStructure.isGoto

  // @Doc(info = "All if blocks (`ControlStructure` nodes)")
  def ifBlock: Iterator[ControlStructure] =
    cpg.controlStructure.isIf

  /** Shorthand for `cpg.methodRef.filter(_.referencedMethod.name(name))` */
  def methodRef(name: String): Iterator[MethodRef] = {
    // This case is special since it's not using a property filter, but a traversal. Therefor we can't (like e.g. 
    // for `call.name`) just define `name` as the primary key for `MethodRef`, and since the implicit resolver doesn't
    // disambiguate between `.methodRef` and `.methodRef(String)`, we cannot use implicits here...
    new CpgNodeStarters(cpg).methodRef.where(_.referencedMethod.name(name))
  }

  /** Traverse to all input parameters */
  // @Doc(info = "All parameters")
  def parameter: Iterator[MethodParameterIn] =
    cpg.methodParameterIn

  // @Doc(info = "All switch blocks (`ControlStructure` nodes)")
  def switchBlock: Iterator[ControlStructure] =
    cpg.controlStructure.isSwitch

  // @Doc(info = "All try blocks (`ControlStructure` nodes)")
  def tryBlock: Iterator[ControlStructure] =
    cpg.controlStructure.isTry

  // @Doc(info = "All while blocks (`ControlStructure` nodes)")
  def whileBlock: Iterator[ControlStructure] =
    cpg.controlStructure.isWhile

}
