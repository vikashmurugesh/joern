package io.joern.joerncli.console

import io.joern.console.{BridgeBase, JoernProduct}

import java.io.PrintStream

object ReplBridge extends BridgeBase {

  override val slProduct = JoernProduct

  def main(args: Array[String]): Unit = {
    run(parseConfig(args))
  }

  /** Code that is executed when starting the shell
    */
  override def predefPlus(lines: Seq[String]): String = {
    s"""${Predefined.forInteractiveShell}
       |${lines.mkString("\n")}
       |""".stripMargin
  }

  override def greeting = JoernConsole.banner()

  override def promptStr: String = "joern"

  override def onExitCode: String = "workspace.projects.foreach(_.close)"

}
