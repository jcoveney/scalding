/*
Copyright 2014 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.scalding.typed

import org.scalatest.{ Matchers, WordSpec }

import com.twitter.scalding._
import com.twitter.algebird.monad.Reader

// Need this to flatMap Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Promise }
import scala.util.Try

// this is the scalding ExecutionContext
import ExecutionContext._

import com.twitter.scalding.serialization.OrderedSerialization
import com.twitter.scalding.serialization.macros.impl.ordered_serialization.runtime_helpers.MacroEqualityOrderedSerialization

object ExecutionTestJobs {
  def wordCount(in: String, out: String) =
    TypedPipe.from(TextLine(in))
      .flatMap(_.split("\\s+"))
      .map((_, 1L))
      .sumByKey
      .writeExecution(TypedTsv(out))

  def wordCount2(in: TypedPipe[String]) =
    in
      .flatMap(_.split("\\s+"))
      .map((_, 1L))
      .sumByKey
      .toIterableExecution

  def zipped(in1: TypedPipe[Int], in2: TypedPipe[Int]) =
    in1.groupAll.sum.values.toIterableExecution
      .zip(in2.groupAll.sum.values.toIterableExecution)

  def mergeFanout(in: List[Int]): Execution[Iterable[(Int, Int)]] = {
    // Force a reduce, so no fancy optimizations kick in
    val source = TypedPipe.from(in).groupBy(_ % 3).head

    (source.mapValues(_ * 2) ++ (source.mapValues(_ * 3))).toIterableExecution
  }
}

class WordCountEc(args: Args) extends ExecutionJob[Unit](args) {
  def execution = ExecutionTestJobs.wordCount(args("input"), args("output"))
  // In tests, classloader issues with sbt mean we should not
  // really use threads, so we run immediately
  override def concurrentExecutionContext = new scala.concurrent.ExecutionContext {
    def execute(r: Runnable) = r.run
    def reportFailure(t: Throwable) = ()
  }
}

case class MyCustomType(s: String)

class ExecutionTest extends WordSpec with Matchers {
  "An Execution" should {
    "run" in {
      ExecutionTestJobs.wordCount2(TypedPipe.from(List("a b b c c c", "d d d d")))
        .waitFor(Config.default, Local(false)).get.toMap shouldBe Map("a" -> 1L, "b" -> 2L, "c" -> 3L, "d" -> 4L)
    }
    "run with zip" in {
      (ExecutionTestJobs.zipped(TypedPipe.from(0 until 100), TypedPipe.from(100 until 200))
        .waitFor(Config.default, Local(false)).get match {
          case (it1, it2) => (it1.head, it2.head)
        }) shouldBe ((0 until 100).sum, (100 until 200).sum)
    }
    "lift to try" in {
      val res = ExecutionTestJobs
        .wordCount2(TypedPipe.from(List("a", "b")))
        .liftToTry
        .waitFor(Config.default, Local(false)).get

      assert(res.isSuccess)
    }
    "lift to try on exception" in {
      val res = ExecutionTestJobs
        .wordCount2(TypedPipe.from(List("a", "b")))
        .map(_ => throw new RuntimeException("Something went wrong"))
        .liftToTry
        .waitFor(Config.default, Local(false)).get

      assert(res.isFailure)
    }
    "merge fanouts without error" in {
      def unorderedEq[T](l: Iterable[T], r: Iterable[T]): Boolean =
        (l.size == r.size) && (l.toSet == r.toSet)

      def correct(l: List[Int]): List[(Int, Int)] = {
        val in = l.groupBy(_ % 3).mapValues(_.head)
        in.mapValues(_ * 2).toList ++ in.mapValues(_ * 3)
      }
      val input = (0 to 100).toList
      val result = ExecutionTestJobs.mergeFanout(input).waitFor(Config.default, Local(false)).get
      val cres = correct(input)
      unorderedEq(cres, result.toList) shouldBe true
    }
    "If either fails, zip fails, else we get success" in {
      val neverHappens = Promise[Int]().future
      Execution.fromFuture { _ => neverHappens }
        .zip(Execution.failed(new Exception("oh no")))
        .waitFor(Config.default, Local(false)).isFailure shouldBe true

      Execution.failed(new Exception("oh no"))
        .zip(Execution.fromFuture { _ => neverHappens })
        .waitFor(Config.default, Local(false)).isFailure shouldBe true
      // If both are good, we succeed:
      Execution.from(1)
        .zip(Execution.from("1"))
        .waitFor(Config.default, Local(true)).get shouldBe (1, "1")
    }

    "Config transformer will isolate Configs" in {
      def doesNotHaveVariable(message: String) = Execution.getConfig.flatMap { cfg =>
        if (cfg.get("test.cfg.variable").isDefined)
          Execution.failed(new Exception(s"${message}\n: var: ${cfg.get("test.cfg.variable")}"))
        else
          Execution.from(())
      }

      val hasVariable = Execution.getConfig.flatMap { cfg =>
        if (cfg.get("test.cfg.variable").isEmpty)
          Execution.failed(new Exception("Should see variable inside of transform"))
        else
          Execution.from(())
      }

      def addOption(cfg: Config) = cfg.+ ("test.cfg.variable", "dummyValue")

      doesNotHaveVariable("Should not see variable before we've started transforming")
        .flatMap{ _ => Execution.withConfig(hasVariable)(addOption) }
        .flatMap(_ => doesNotHaveVariable("Should not see variable in flatMap's after the isolation"))
        .map(_ => true)
        .waitFor(Config.default, Local(false)) shouldBe scala.util.Success(true)
    }

    "Config transformer will interact correctly with the cache" in {
      var incrementIfDefined = 0
      var totalEvals = 0

      val incrementor = Execution.getConfig.flatMap { cfg =>
        totalEvals += 1
        if (cfg.get("test.cfg.variable").isDefined)
          incrementIfDefined += 1
        Execution.from(())
      }

      def addOption(cfg: Config) = cfg.+ ("test.cfg.variable", "dummyValue")

      // Here we run without the option, with the option, and finally without again.
      incrementor
        .flatMap{ _ => Execution.withConfig(incrementor)(addOption) }
        .flatMap(_ => incrementor)
        .map(_ => true)
        .waitFor(Config.default, Local(false)) shouldBe scala.util.Success(true)

      assert(incrementIfDefined === 1)
      // We should evaluate once for the default config, and once for the modified config.
      assert(totalEvals === 2)
    }
  }

  "ExecutionApp" should {
    val parser = new ExecutionApp { def job = Execution.from(()) }
    "parse hadoop args correctly" in {
      val conf = parser.config(Array("-Dmapred.reduce.tasks=100", "--local"))._1
      conf.get("mapred.reduce.tasks") should contain ("100")
      conf.getArgs.boolean("local") shouldBe true

      val (conf1, Hdfs(_, hconf)) = parser.config(Array("--test", "-Dmapred.reduce.tasks=110", "--hdfs"))
      conf1.get("mapred.reduce.tasks") should contain ("110")
      conf1.getArgs.boolean("test") shouldBe true
      hconf.get("mapred.reduce.tasks") shouldBe "110"
    }
  }
  "An ExecutionJob" should {
    "run correctly" in {
      JobTest(new WordCountEc(_))
        .arg("input", "in")
        .arg("output", "out")
        .source(TextLine("in"), List((0, "hello world"), (1, "goodbye world")))
        .typedSink(TypedTsv[(String, Long)]("out")) { outBuf =>
          outBuf.toMap shouldBe Map("hello" -> 1L, "world" -> 2L, "goodbye" -> 1L)
        }
        .run
        .runHadoop
        .finish
    }
  }
  "Executions" should {
    "evaluate once per run" in {
      var first = 0
      var second = 0
      var third = 0
      val e1 = Execution.from({ first += 1; 42 })
      val e2 = e1.flatMap { x =>
        second += 1
        Execution.from(2 * x)
      }
      val e3 = e1.map { x => third += 1; x * 3 }
      /**
       * Notice both e3 and e2 need to evaluate e1.
       */
      val res = e3.zip(e2)
      res.waitFor(Config.default, Local(true))
      assert((first, second, third) == (1, 1, 1))
    }

    "Running a large loop won't exhaust boxed instances" in {
      var timesEvaluated = 0
      import com.twitter.scalding.serialization.macros.impl.BinaryOrdering._
      // Attempt to use up 4 boxed classes for every execution
      def baseExecution(idx: Int): Execution[Unit] = TypedPipe.from(0 until 1000).map(_.toShort).flatMap { i =>
        timesEvaluated += 1
        List((i, i), (i, i))
      }.sumByKey.map {
        case (k, v) =>
          (k.toInt, v)
      }.sumByKey.map {
        case (k, v) =>
          (k.toLong, v)
      }.sumByKey.map {
        case (k, v) =>
          (k.toString, v)
      }.sumByKey.map {
        case (k, v) =>
          (MyCustomType(k), v)
      }.sumByKey.writeExecution(TypedTsv(s"/tmp/asdf_${idx}"))

      implicitly[OrderedSerialization[MyCustomType]] match {
        case mos: MacroEqualityOrderedSerialization[_] => assert(mos.uniqueId == "com.twitter.scalding.typed.MyCustomType")
        case _ => sys.error("Ordered serialization should have been the MacroEqualityOrderedSerialization for this test")
      }
      def executionLoop(idx: Int): Execution[Unit] = {
        if (idx > 0)
          baseExecution(idx).flatMap(_ => executionLoop(idx - 1))
        else
          Execution.unit
      }

      executionLoop(55).waitFor(Config.default, Local(true))
      assert(timesEvaluated == 55 * 1000, "Should run the 55 execution loops for 1000 elements")
    }

    "evaluate shared portions just once, writeExecution" in {

      var timesEvaluated = 0
      val baseTp = TypedPipe.from(0 until 1000).flatMap { i =>
        timesEvaluated += 1
        List(i, i)
      }.fork

      val fde1 = baseTp.map{ _ * 3 }.writeExecution(TypedTsv("/tmp/asdf"))
      val fde2 = baseTp.map{ _ * 5 }.writeExecution(TypedTsv("/tmp/asdf2"))

      val res = fde1.zip(fde2)

      res.waitFor(Config.default, Local(true))
      assert(timesEvaluated == 1000, "Should share the common sub section of the graph when we zip two write Executions")
    }

    "evaluate shared portions just once, forceToDiskExecution" in {

      var timesEvaluated = 0
      val baseTp = TypedPipe.from(0 until 1000).flatMap { i =>
        timesEvaluated += 1
        List(i, i)
      }.fork

      val fde1 = baseTp.map{ _ * 3 }.forceToDiskExecution
      val fde2 = baseTp.map{ _ * 5 }.forceToDiskExecution

      val res = fde1.zip(fde2)

      res.waitFor(Config.default, Local(true))
      assert(timesEvaluated == 1000, "Should share the common sub section of the graph when we zip two write Executions")
    }

    "evaluate shared portions just once, forceToDiskExecution with execution cache" in {

      var timesEvaluated = 0
      val baseTp = TypedPipe.from(0 until 1000).flatMap { i =>
        timesEvaluated += 1
        List(i, i)
      }.fork

      val fde1 = baseTp.map{ _ * 3 }.forceToDiskExecution
      val fde2 = baseTp.map{ _ * 5 }.forceToDiskExecution

      val res = fde1.zip(fde2).flatMap{ _ => fde1 }.flatMap(_.toIterableExecution)

      res.waitFor(Config.default, Local(true))
      assert(timesEvaluated == 1000, "Should share the common sub section of the graph when we zip two write Executions and then flatmap")
    }

    "handle failure" in {
      val result = Execution.withParallelism(Seq(Execution.failed(new Exception("failed"))), 1)

      assert(result.waitFor(Config.default, Local(true)).isFailure)
    }

    "handle an error running in parallel" in {
      val executions = Execution.failed(new Exception("failed")) :: 0.to(10).map(i => Execution.from[Int](i)).toList

      val result = Execution.withParallelism(executions, 3)

      assert(result.waitFor(Config.default, Local(true)).isFailure)
    }

    "run in parallel" in {
      val executions = 0.to(10).map(i => Execution.from[Int](i)).toList

      val result = Execution.withParallelism(executions, 3)

      assert(result.waitFor(Config.default, Local(true)).get == 0.to(10).toSeq)
    }

    "block correctly" in {
      var seen = 0
      def updateSeen(idx: Int) {
        assert(seen === idx)
        seen += 1
      }

      val executions = 0.to(10).map{ i =>
        Execution
          .from[Int](i)
          .map{ i => Thread.sleep(10 - i); i }
          .onComplete(t => updateSeen(t.get))
      }.toList.reverse

      val result = Execution.withParallelism(executions, 1)

      assert(result.waitFor(Config.default, Local(true)).get == 0.to(10).reverse)
    }
  }
}
