package csw.examples.vslice.assembly

/**
 * TMT Source Code: 10/10/16.
 */

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.scalalogging.LazyLogging
import csw.examples.vslice.TestEnv
import csw.services.apps.containerCmd.ContainerCmd
import csw.services.ccs.AssemblyController.Submit
import csw.services.ccs.CommandStatus.{Accepted, CommandResponse, Completed}
import csw.services.loc.LocationService
import csw.services.pkg.Component.AssemblyInfo
import csw.services.pkg.Supervisor
import csw.services.pkg.Supervisor._
import csw.services.pkg.SupervisorExternal.{LifecycleStateChanged, SubscribeLifecycleCallback}
import csw.util.param.Parameters.Setup
import org.scalatest.{BeforeAndAfterAll, _}
import csw.services.sequencer.SequencerEnv._

import scala.concurrent.duration._

object TromboneAssemblyCompTests {
  LocationService.initInterface()

  private val system = ActorSystem("TromboneAssemblyCompTests")
//  private val taName = "lgsTrombone"
  private val thName = "lgsTromboneHCD"
}

class TromboneAssemblyCompTests extends TestKit(TromboneAssemblyCompTests.system) with ImplicitSender
    with FunSpecLike with Matchers with BeforeAndAfterAll with LazyLogging {

  val assemblyContext = AssemblyTestData.TestAssemblyContext

  import assemblyContext._

  def newTrombone(assemblyInfo: AssemblyInfo = assemblyContext.info): ActorRef = {
    Supervisor(assemblyInfo)
  }

  // List of top level actors that were created for the HCD (for clean up)
  var hcdActors: List[ActorRef] = Nil

  override def beforeAll: Unit = {
    TestEnv.createTromboneAssemblyConfig()

    expectNoMsg(1.seconds)

    // Starts the HCD used in the test
    val cmd = ContainerCmd("vslice", Array("--standalone"), Map("" -> "tromboneHCD.conf"))
    hcdActors = cmd.actors
    resolveHcd(TromboneAssemblyCompTests.thName)
  }

  override def afterAll: Unit = {
    hcdActors.foreach(cleanup)
    TestKit.shutdownActorSystem(system)
  }

  // Stop any actors created for a test to avoid conflict with other tests
  private def cleanup(component: ActorRef): Unit = {
    val monitor = TestProbe()
    monitor.watch(component)
    component ! HaltComponent
    monitor.expectTerminated(component, timeout.duration)
    monitor.expectNoMsg(1.seconds)
  }

  describe("comp tests") {

    it("should just startup") {
      val tla = newTrombone()
      val fakeSequencer = TestProbe()

      tla ! SubscribeLifecycleCallback(fakeSequencer.ref)
      fakeSequencer.expectMsg(LifecycleStateChanged(LifecycleRunning))
      fakeSequencer.expectNoMsg(3.seconds) // wait for connections
      cleanup(tla)
    }

    it("should allow a datum") {
      val tla = newTrombone()
      val fakeSequencer = TestProbe()

      tla ! SubscribeLifecycleCallback(fakeSequencer.ref)
      fakeSequencer.expectMsg(LifecycleStateChanged(LifecycleRunning))

      fakeSequencer.expectNoMsg(3.seconds) // wait for connections

      fakeSequencer.send(tla, Submit(Setup(commandInfo, initCK)))
      // This first one is the accept/verification
      val acceptedMsg = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
      acceptedMsg shouldBe Accepted
      val completeMsg = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
      completeMsg shouldBe Completed
      // Wait a bit to see if there is any spurious messages
      fakeSequencer.expectNoMsg(250.milli)

      fakeSequencer.send(tla, Submit(Setup(commandInfo, datumCK)))
      // This first one is the accept/verification
      val acceptedMsg2 = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
      acceptedMsg2 shouldBe Accepted
      val completeMsg2 = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
      completeMsg2 shouldBe Completed
      // Wait a bit to see if there is any spurious messages
      fakeSequencer.expectNoMsg(250.milli)


      cleanup(tla)
    }

    it("should allow a datum then a set of positions as separate sca") {
      val tla = newTrombone()
      val fakeSequencer = TestProbe()

      tla ! SubscribeLifecycleCallback(fakeSequencer.ref)
      fakeSequencer.expectMsg(20.seconds, LifecycleStateChanged(LifecycleRunning))

      //fakeSequencer.expectNoMsg(12.seconds)  // wait for connections

      fakeSequencer.send(tla, Submit(Setup(commandInfo, initCK)))
      // This first one is the accept/verification
      val acceptedMsg = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
      acceptedMsg shouldBe Accepted
      val completeMsg = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
      completeMsg shouldBe Completed

      fakeSequencer.send(tla, Submit(Setup(commandInfo, datumCK)))
      // This first one is the accept/verification
      val acceptedMsg2 = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
      acceptedMsg2 shouldBe Accepted
      val completeMsg2 = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
      completeMsg2 shouldBe Completed

      // This will send a config arg with 10 position commands
      val testRangeDistance = 90 to 180 by 10
      val positionConfigs = testRangeDistance.map(f => positionSC(f))

//      val sca = Parameters.createSetupArg("testobsId", positionConfigs: _*)
      positionConfigs.foreach { pc =>
        fakeSequencer.send(tla, Submit(pc))

        // This first one is the accept/verification
        val acceptedMsg = fakeSequencer.expectMsgClass(3.seconds, classOf[CommandResponse])
        acceptedMsg shouldBe Accepted

        // Second one is completion of the executed ones - give this some extra time to complete
        val completeMsg = fakeSequencer.expectMsgClass(10.seconds, classOf[CommandResponse])
        completeMsg shouldBe Completed
      }

      cleanup(tla)
    }
  }

}
