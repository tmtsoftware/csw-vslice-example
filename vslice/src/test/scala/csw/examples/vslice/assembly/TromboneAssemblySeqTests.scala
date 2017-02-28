package csw.examples.vslice.assembly

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import csw.examples.vslice.TestEnv
import csw.services.apps.containerCmd.ContainerCmd
import csw.services.ccs.BlockingAssemblyClient
import csw.services.ccs.CommandStatus.AllCompleted
import csw.services.events.Event
import csw.services.loc.LocationService
import csw.services.pkg.Supervisor._
import csw.services.pkg.SupervisorExternal.{LifecycleStateChanged, SubscribeLifecycleCallback, UnsubscribeLifecycleCallback}
import csw.util.config.{Configurations, DoubleKey}
import csw.util.config.Configurations.{ConfigKey, SetupConfig}
import org.scalatest.{BeforeAndAfterAll, _}

import scala.concurrent.duration._
import csw.services.sequencer.SequencerEnv._
import csw.util.config.UnitsOfMeasure.kilometers

import scala.util.Try

/**
 * TMT Source Code: 12/9/16.
 */

object TromboneAssemblySeqTests {
  LocationService.initInterface()

  val system = ActorSystem("TromboneAssemblySeqTests")
}

class TromboneAssemblySeqTests extends TestKit(TromboneAssemblySeqTests.system) with ImplicitSender
    with FunSpecLike with Matchers with BeforeAndAfterAll with LazyLogging {

  implicit val timeout = Timeout(10.seconds)

  private val ac = AssemblyTestData.TestAssemblyContext

  import ac._

  private val taName = "lgsTrombone"
  private val thName = "lgsTromboneHCD"

  private val componentPrefix: String = "nfiraos.ncc.trombone"

  // Public command configurations
  // Init submit command
  private val initPrefix = s"$componentPrefix.init"
  private val initCK: ConfigKey = initPrefix

  // Datum submit command
  private val datumPrefix = s"$componentPrefix.datum"
  private val datumCK: ConfigKey = datumPrefix

  private val naRangeDistanceKey = DoubleKey("rangeDistance")
  private val naRangeDistanceUnits = kilometers

  // Position submit command
  private val positionPrefix = s"$componentPrefix.position"
  private val positionCK: ConfigKey = positionPrefix

  def positionSC(rangeDistance: Double): SetupConfig = SetupConfig(positionCK).add(naRangeDistanceKey -> rangeDistance withUnits naRangeDistanceUnits)

  def eventPrinter(ev: Event): Unit = {
    logger.info(s"EventReceived: $ev")
  }

  private val sca1 = Configurations.createSetupConfigArg("testobsId", SetupConfig(initCK), SetupConfig(datumCK))

  // This will send a config arg with 10 position commands
  private val testRangeDistance = 90 to 130 by 10
  private val positionConfigs = testRangeDistance.map(f => positionSC(f))

  private val sca2 = Configurations.createSetupConfigArg("testObsId", positionSC(100.0))

  // List of top level actors that were created for the HCD (for clean up)
  var containerActors: List[ActorRef] = Nil

  override def beforeAll: Unit = {
    TestEnv.createTromboneAssemblyConfig()

    val xxx = Try(resolveAssembly(taName))
    if (xxx.isSuccess) logger.error("XXX There is already an assembly registered from a previous test!")

    // Starts the assembly and HCD used in the test
    val cmd = ContainerCmd("vslice", Array("--standalone"), Map("" -> "tromboneContainer.conf"))
    containerActors = cmd.actors

    // Wait for components to be in running state
    val probe = TestProbe()
    containerActors.foreach { actorRef =>
      actorRef ! SubscribeLifecycleCallback(probe.ref)
    }
    probe.expectMsg(10.seconds, LifecycleStateChanged(LifecycleRunning))
    probe.expectMsg(10.seconds, LifecycleStateChanged(LifecycleRunning))
    containerActors.foreach { actorRef =>
      actorRef ! UnsubscribeLifecycleCallback(probe.ref)
    }
    try {
      resolveAssembly(taName)
    } catch {
      case e: Exception => logger.error(s"Can't resolve assembly $taName: maybe previous test failed to unregister it from location service?")
    }
  }

  // Stop any actors created for a test to avoid conflict with other tests
  private def cleanup(component: ActorRef): Unit = {
    val probe = TestProbe()
    component ! SubscribeLifecycleCallback(probe.ref)

    component ! HaltComponent

    probe.expectMsg(10.seconds, LifecycleStateChanged(LifecyclePreparingToShutdown))
    probe.expectMsg(10.seconds, LifecycleStateChanged(LifecycleShutdown))
  }

  override def afterAll: Unit = {
    containerActors.foreach(cleanup)
    TestKit.shutdownActorSystem(system)
    Thread.sleep(7000) // XXX FIXME Make sure components have time to unregister from location service
  }

  def getTrombone: BlockingAssemblyClient = resolveAssembly(taName)

  describe("Top Level Sequencer Tests") {

    it("should allow a datum then a set of positions as separate sca") {

      val tlaClient = getTrombone

      val datum = Configurations.createSetupConfigArg("testobsId", SetupConfig(initCK), SetupConfig(datumCK))
      var completeMsg = tlaClient.submit(datum)

      // This first one is the accept/verification

      // Second one is completion of the executed ones
      logger.info("msg2: " + completeMsg)
      completeMsg.overall shouldBe AllCompleted

      // This will send a config arg with 10 position commands
      val testRangeDistance = 140 to 180 by 10
      val positionConfigs = testRangeDistance.map(f => positionSC(f))

      val sca = Configurations.createSetupConfigArg("testobsId", positionConfigs: _*)
      completeMsg = tlaClient.submit(sca)

      logger.info("msg2: " + completeMsg)
      completeMsg.overall shouldBe AllCompleted

      expectNoMsg(5.seconds)
    }

    it("should allow a stop from follow mode") {
      val tlaClient = getTrombone

      val fakeSequencer = TestProbe()

      val datum = Configurations.createSetupConfigArg("testobsId", SetupConfig(initCK), SetupConfig(datumCK))
      var completeMsg = tlaClient.submit(datum)
      expectNoMsg(1.second)
      logger.info("Datum complete: " + completeMsg)

      val follow = Configurations.createSetupConfigArg("testobsId", setElevationSC(100.0), followSC(false))
      completeMsg = tlaClient.submit(follow)
      expectNoMsg(2.seconds)
      logger.info("Follow complete: " + completeMsg)

      val stop = Configurations.createSetupConfigArg("testObsId", SetupConfig(stopCK))
      completeMsg = tlaClient.submit(stop)
      logger.info("Stop complete: " + completeMsg)
    }
  }
}
