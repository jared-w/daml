// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.http.perf

import java.io.File

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.daml.gatling.stats.SimulationLog.ScenarioStats
import com.daml.gatling.stats.{SimulationLog, SimulationLogSyntax}
import com.daml.grpc.adapter.{AkkaExecutionSequencerPool, ExecutionSequencerFactory}
import com.daml.http.HttpServiceTestFixture.withHttpService
import com.daml.http.domain.LedgerId
import com.daml.http.perf.scenario.SimulationConfig
import com.daml.http.util.FutureUtil._
import com.daml.http.{EndpointsCompanion, HttpService}
import com.daml.jwt.domain.Jwt
import com.daml.scalautil.Statement.discard
import com.typesafe.scalalogging.StrictLogging
import io.gatling.core.scenario.Simulation
import scalaz.std.scalaFuture._
import scalaz.syntax.tag._
import scalaz.{-\/, EitherT, \/, \/-}
import scalaz.std.string._

import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success}

object Main extends StrictLogging {

  private type ET[A] = EitherT[Future, Throwable, A]

  sealed abstract class ExitCode(val code: Int) extends Product with Serializable
  object ExitCode {
    case object Ok extends ExitCode(0)
    case object InvalidUsage extends ExitCode(100)
    case object StartupError extends ExitCode(101)
    case object InvalidScenario extends ExitCode(102)
    case object TimedOutScenario extends ExitCode(103)
    case object GatlingError extends ExitCode(104)
  }

  def main(args: Array[String]): Unit = {
    val name = "http-json-perf"
    val terminationTimeout: FiniteDuration = 30.seconds

    implicit val asys: ActorSystem = ActorSystem(name)
    implicit val mat: Materializer = Materializer(asys)
    implicit val aesf: ExecutionSequencerFactory =
      new AkkaExecutionSequencerPool(poolName = name, terminationTimeout = terminationTimeout)
    implicit val ec: ExecutionContext = asys.dispatcher

    def terminate(): Unit = discard { Await.result(asys.terminate(), terminationTimeout) }

    val exitCode: ExitCode = Config.parseConfig(args) match {
      case None =>
        // error is printed out by scopt
        ExitCode.InvalidUsage
      case Some(config) =>
        waitForResult(logCompletion(main1(config)), config.maxDuration.getOrElse(Duration.Inf))
    }

    terminate()
    sys.exit(exitCode.code)
  }

  private def logCompletion(fa: Future[Throwable \/ _])(implicit ec: ExecutionContext): fa.type = {
    fa.onComplete {
      case Success(\/-(_)) => logger.info(s"Scenario completed")
      case Success(-\/(e)) => logger.error(s"Scenario failed", e)
      case Failure(e) => logger.error(s"Scenario failed", e)
    }
    fa
  }

  private def waitForResult[A](fa: Future[Throwable \/ ExitCode], timeout: Duration): ExitCode =
    try {
      Await
        .result(fa, timeout)
        .valueOr(_ => ExitCode.GatlingError)
    } catch {
      case e: TimeoutException =>
        logger.error(s"Scenario failed", e)
        ExitCode.TimedOutScenario
    }

  private def main1(config: Config[String])(
      implicit asys: ActorSystem,
      mat: Materializer,
      aesf: ExecutionSequencerFactory,
      ec: ExecutionContext
  ): Future[Throwable \/ ExitCode] = {
    import scalaz.syntax.traverse._

    logger.info(s"$config")

    val et: ET[ExitCode] = for {
      ledgerId <- either(
        getLedgerId(config.jwt).leftMap(_ =>
          new IllegalArgumentException("Cannot infer Ledger ID from JWT"))
      ): ET[LedgerId]

      _ <- either(
        config.traverse(s => resolveSimulationClass(s))
      ): ET[Config[Class[_ <: Simulation]]]

      exitCode <- rightT(
        main2(ledgerId, config)
      ): ET[ExitCode]

    } yield exitCode

    et.run
  }

  private def main2(ledgerId: LedgerId, config: Config[String])(
      implicit asys: ActorSystem,
      mat: Materializer,
      aesf: ExecutionSequencerFactory,
      ec: ExecutionContext
  ): Future[ExitCode] =
    withHttpService(ledgerId.unwrap, config.dars, None, None) { (uri, _, _, _) =>
      runGatlingScenario(config, uri.authority.host.address, uri.authority.port)
        .flatMap {
          case (exitCode, dir) =>
            toFuture(generateReport(dir))
              .map { _ =>
                logger.info(s"Report directory: ${dir.getAbsolutePath}")
                exitCode
              }
        }: Future[ExitCode]
    }

  private def resolveSimulationClass(str: String): Throwable \/ Class[_ <: Simulation] = {
    try {
      val klass: Class[_] = Class.forName(str)
      val simClass = klass.asSubclass(classOf[Simulation])
      \/-(simClass)
    } catch {
      case e: Throwable =>
        logger.error(s"Cannot resolve scenario: '$str'", e)
        -\/(e)
    }
  }

  private def getLedgerId(jwt: Jwt): EndpointsCompanion.Unauthorized \/ LedgerId =
    EndpointsCompanion
      .decodeAndParsePayload(jwt, HttpService.decodeJwt)
      .map { case (_, payload) => payload.ledgerId }

  private def runGatlingScenario(config: Config[String], jsonApiHost: String, jsonApiPort: Int)(
      implicit sys: ActorSystem,
      ec: ExecutionContext): Future[(ExitCode, File)] = {

    import io.gatling.app
    import io.gatling.core.config.GatlingPropertiesBuilder

    val hostAndPort = s"${jsonApiHost: String}:${jsonApiPort: Int}"
    discard { System.setProperty(SimulationConfig.HostAndPortKey, hostAndPort) }
    discard { System.setProperty(SimulationConfig.JwtKey, config.jwt.value) }

    val configBuilder = new GatlingPropertiesBuilder()
      .simulationClass(config.scenario)
      .resultsDirectory(config.reportsDir.getAbsolutePath)
      .noReports()

    Future
      .fromTry {
        app.CustomRunner.runWith(sys, configBuilder.build, None)
      }
      .map {
        case (a, f) =>
          if (a == app.cli.StatusCode.Success.code) (ExitCode.Ok, f) else (ExitCode.GatlingError, f)
      }
  }

  private def generateReport(dir: File): String \/ Unit = {
    import SimulationLogSyntax._

    require(dir.isDirectory)

    val logPath = new File(dir, "simulation.log")
    val simulationLog = SimulationLog.fromFile(logPath)
    simulationLog.foreach { x =>
      x.writeSummary(dir)
      logger.info(s"Report\n${formatReport(x.scenarios)}")
    }
    simulationLog.map(_ => ())
  }

  private def formatReport(scenarios: List[ScenarioStats]): String = {
    val buf = new StringBuffer()
    scenarios.foreach { x =>
      x.requestsByType.foreach {
        case (name, stats) =>
          buf.append(stats.formatted(name))
      }
    }
    buf.toString
  }
}
