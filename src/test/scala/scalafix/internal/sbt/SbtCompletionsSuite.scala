package scalafix.internal.sbt

import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.scalatest.FunSuite
import org.scalatest.Tag
import sbt.complete.Parser
import scala.collection.JavaConverters._

class SbtCompletionsSuite extends FunSuite {
  val fs = new Fs()
  val git = new Git(fs.workingDirectory)
  fs.mkdir("foo")
  fs.add("foo/f.scala", "object F")
  fs.mkdir("bar")
  fs.add("bar/b.scala", "object B")
  fs.mkdir("buzz")
  fs.add("my.conf", "conf.file = []")
  git.commit()
  // create at least 20 commits for diff-base log
  (1 to 20).foreach { i =>
    fs.add(s"f$i", s"f$i")
    git.commit()
  }
  git.tag("v0.1.0")

  val exampleDependency = {
    import sbt._
    "com.geirsson" %% "example-scalafix-rule" % "1.3.0"
  }
  val mainArgs =
    ScalafixInterface
      .fromToolClasspath(
        Seq(exampleDependency),
        ScalafixCoursier.defaultResolvers,
        Seq.empty
      )()
      .args
  val loadedRules = mainArgs.availableRules.asScala.toList

  val parser = new ScalafixCompletions(
    workingDirectory = () => fs.workingDirectory.toAbsolutePath,
    loadedRules = () => loadedRules,
    terminalWidth = None
  ).parser

  def checkCompletion(name: String, testTags: Tag*)(
      assertCompletions: (List[String], List[String]) => Unit
  ): Unit = {
    val option =
      if (name == "all") ""
      else name

    test(name, testTags: _*) {
      val input = " " + option

      val completions =
        Parser.completions(parser, input, 0).get.toList.sortBy(_.display)

      val appends = completions.map(_.append)
      val displays = completions.map(_.display)

      assertCompletions(appends, displays)
    }
  }

  def checkArgs(name: String)(assertArgs: Seq[String] => Unit): Unit = {
    test(name) {
      val input = name
      val args = Parser.parse(" " + input, parser).right.get
      val asStrings =
        args.rules.flatMap(r => "-r" :: r :: Nil) ++ args.extra
      assertArgs(asStrings)
    }
  }

  def isSha1(in: String): Boolean =
    AbbreviatedObjectId.isId(in)

  checkCompletion("all", SkipWindows) { (_, displays) =>
    val obtained = displays.mkString("\n").trim
    val expected =
      """|
         |--auto-suppress-linter-errors
         |--diff
         |--diff-base
         |--files
         |--help
         |--verbose
         |DisableSyntax
         |  Reports an error for disabled features such as var or XML literals.
         |LeakingImplicitClassVal
         |  Adds 'private' to val parameters of implicit value classes
         |NoAutoTupling
         |  Inserts explicit tuples for adapted argument lists for compatibility with -Yno-adapted-args
         |NoValInForComprehension
         |  Removes deprecated val inside for-comprehension binders
         |ProcedureSyntax
         |  Replaces deprecated procedure syntax with explicit ': Unit ='
         |RemoveUnused
         |  Removes unused imports and terms that reported by the compiler under -Ywarn-unused
         |SemanticRule
         |SyntacticRule
         |class:
         |dependency:
         |file:
         |github:
         |replace:
      """.stripMargin.trim.replaceAll("\r\n", "\n")
    if (obtained != expected) {
      println("\"\"\"|")
      obtained.lines.foreach { line =>
        print("   |")
        println(line)
      }
      println("   |\"\"\"")
      fail("obtained != expected")
    }
  }

  checkCompletion("--diff-base=", SkipWindows) { (appends, displays) =>
    // branches
    assert(displays.contains("master"))

    // tag
    assert(displays.contains("v0.1.0"))

    // last 20 commits
    appends.zip(displays).reverse.take(20).foreach {
      case (append, display) =>
        assert(isSha1(append))
        assert(display.endsWith("ago)"))
    }
  }

  checkCompletion("--rules file:bar/../", SkipWindows) { (appends, displays) =>
    // resolve parent directories
    assert(appends.contains("foo"))
    assert(displays.contains("bar/../foo"))
  }

  // shortcut for --rules
  checkArgs("ProcedureSyntax") { args =>
    assert(args == Seq("-r", "ProcedureSyntax"))
  }

  // consume extra
  checkArgs("--bash") { args =>
    assert(args == Seq("--bash"))
  }
}
