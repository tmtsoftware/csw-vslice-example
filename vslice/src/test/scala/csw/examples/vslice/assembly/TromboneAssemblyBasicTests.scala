package csw.examples.vslice.assembly

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import csw.examples.vslice.TestEnv
import csw.services.apps.containerCmd.ContainerCmd
import csw.services.ccs.AssemblyController.Submit
import csw.services.ccs.CommandStatus._
import csw.services.ccs.Validation.WrongInternalStateIssue
import csw.services.events.EventService
import csw.services.loc.LocationService
import csw.services.pkg.Component.AssemblyInfo
import csw.services.pkg.Supervisor._
import csw.util.param.Parameters.Setup
import csw.util.param.Events.SystemEvent
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, _}
import csw.services.sequencer.SequencerEnv._

import scala.concurrent.Await
import scala.concurrent.duration._

object TromboneAssemblyBasicTests {
  LocationService.initInterface()

  val system = ActorSystem("TromboneAssemblyBasicTests")
  val thName = "lgsTromboneHCD"
}

/**
 * This test assumes an HCD is running.
 * It creates an Assembly for direct interaction, not using the Supervisor
 */
class TromboneAssemblyBasicTests extends TestKit(TromboneAssemblyBasicTests.system) with ImplicitSender
    with FunSpecLike with Matchers with BeforeAndAfterAll with LazyLogging {

  import system.dispatcher

  // List of top level actors that were created for the HCD (for clean up)
  var hcdActors: List[ActorRef] = Nil

  override def beforeAll: Unit = {
    TestEnv.createTromboneAssemblyConfig()

    // Starts the HCD used in the test
    val cmd = ContainerCmd("vslice", Array("--standalone"), Map("" -> "tromboneHCD.conf"))
    hcdActors = cmd.actors
    expectNoMsg(2.seconds) // XXX FIXME Give time for location service update so we don't get previous value
    resolveHcd(TromboneAssemblyBasicTests.thName)
  }

  override def afterAll: Unit = {
    hcdActors.foreach { actorRef =>
      watch(actorRef)
      actorRef ! HaltComponent
      expectTerminated(actorRef)
    }
    TestKit.shutdownActorSystem(system)
    Thread.sleep(10000) // XXX FIXME Make sure components have time to unregister from location service
  }

  // Stop any actors created for a test to avoid conflict with other tests
  private def cleanup(a: ActorRef*): Unit = {
    val monitor = TestProbe()
    a.foreach { actorRef =>
      monitor.watch(actorRef)
      system.stop(actorRef)
      monitor.expectTerminated(actorRef)
    }
  }

  val assemblyContext = AssemblyTestData.TestAssemblyContext

  import assemblyContext._

  implicit val timeout = Timeout(10.seconds)
  // Get the event service by looking up the name with the location service.
  private val eventService = Await.result(EventService(), timeout.duration)

  def getTromboneProps(assemblyInfo: AssemblyInfo, supervisorIn: Option[ActorRef]): Props = {
    supervisorIn match {
      case None           => TromboneAssembly.props(assemblyInfo, TestProbe().ref)
      case Some(actorRef) => TromboneAssembly.props(assemblyInfo, actorRef)
    }
  }

  def newTrombone(supervisor: ActorRef, assemblyInfo: AssemblyInfo = assemblyContext.info): ActorRef = {
    val props = getTromboneProps(assemblyInfo, Some(supervisor))
    expectNoMsg(300.millis)
    system.actorOf(props)
  }

  describe("low-level instrumented trombone assembly tests") {

    it("should lifecycle properly with a fake supervisor") {
      // test2
      val fakeSupervisor = TestProbe()
      val tla = newTrombone(fakeSupervisor.ref)

      fakeSupervisor.expectMsg(Initialized)

      fakeSupervisor.send(tla, Running)

      fakeSupervisor.send(tla, DoShutdown)
      fakeSupervisor.expectMsg(ShutdownComplete)
      logger.info("Shutdown Complete")

      cleanup(tla)
    }

    it("datum without an init should fail") {
      // test3
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      //val fakeSupervisor = TestProbe()
      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.send(tromboneAssembly, Running)

      val sca = Setup(commandInfo, datumCK)

      fakeClient.send(tromboneAssembly, Submit(sca))

      // This first one is the accept/verification succeeds because verification does not look at state
      val acceptedMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      acceptedMsg shouldBe Accepted
      logger.info(s"Accepted: $acceptedMsg")

      // This should fail due to wrong internal state
      val completeMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      completeMsg shouldBe a[NoLongerValid]
      completeMsg.asInstanceOf[NoLongerValid].issue shouldBe a[WrongInternalStateIssue]

      cleanup(tromboneAssembly)
    }

    it("should allow a datum") {
      // test4
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      //val fakeSupervisor = TestProbe()
      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.send(tromboneAssembly, Running)

      val sca1 = Setup(commandInfo, initCK)
      val sca2 = Setup(commandInfo, datumCK)


      fakeClient.send(tromboneAssembly, Submit(sca1))

      // This first one is the accept/verification
      val acceptedMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      acceptedMsg shouldBe Accepted

      val completeMsg = fakeClient.expectMsgClass(5.seconds, classOf[CommandResponse])
      completeMsg shouldBe Completed


      fakeClient.send(tromboneAssembly, Submit(sca2))

      // This first one is the accept/verification
      val acceptedMsg2 = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      acceptedMsg2 shouldBe Accepted

      val completeMsg2 = fakeClient.expectMsgClass(5.seconds, classOf[CommandResponse])
      completeMsg2 shouldBe Completed


      // Wait a bit to see if there is any spurious messages
      fakeClient.expectNoMsg(250.milli)
      //logger.info("Completed: " + completeMsg)

      cleanup(tromboneAssembly)
    }

    it("should show a move without a datum as an error because trombone in wrong state") {
      // test5
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      //val fakeSupervisor = TestProbe()
      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.send(tromboneAssembly, Running)

      // Sending an Init first so we can see the dataum issue
      val testPosition = 90.0
      val sca1 = Setup(commandInfo, initCK)
      val sca2 = moveSC(testPosition)

      fakeClient.send(tromboneAssembly, Submit(sca1))

      // This first one is the accept/verification -- note that it is accepted because there is no static validation errors
      val acceptedMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      logger.info("msg1: " + acceptedMsg)
      acceptedMsg shouldBe Accepted
      // This should fail due to wrong internal state
      val completeMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      logger.info("Completed Msg: " + completeMsg)
      // First completes no issue
      completeMsg shouldBe Completed

      fakeClient.send(tromboneAssembly, Submit(sca2))
      val acceptedMsg2 = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      acceptedMsg shouldBe Accepted
      val completeMsg2 = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      // Second is for move and it should be invalid
      completeMsg2 shouldBe a[NoLongerValid]
      completeMsg2.asInstanceOf[NoLongerValid].issue shouldBe a[WrongInternalStateIssue]

      cleanup(tromboneAssembly)
    }

    it("should allow an init, datum then 2 moves") {
      // test6
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.send(tromboneAssembly, Running)

      val testMove = 90.0
      val testMove2 = 100.0
      val sca1 = Setup(commandInfo, initCK)
      val sca2 = Setup(commandInfo, datumCK)
      val sca3 = moveSC(testMove)
      val sca4 = moveSC(testMove2)
      val pList = List(sca1, sca2, sca3, sca4)

      pList.foreach {p =>
        fakeClient.send(tromboneAssembly, Submit(p))

        // This first one is the accept/verification
        val acceptedMsg = fakeClient.expectMsgClass(10.seconds, classOf[CommandResponse])
        acceptedMsg shouldBe Accepted

        // Second one is completion of the executed ones
        val completeMsg = fakeClient.expectMsgClass(10.seconds, classOf[CommandResponse])
        completeMsg shouldBe Completed
      }

      cleanup(tromboneAssembly)
    }

    it("should allow an init, datum then a position") {
      // test7
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      //val fakeSupervisor = TestProbe()
      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.expectNoMsg(200.milli)
      fakeSupervisor.send(tromboneAssembly, Running)

      val testRangeDistance = 125.0
      val sca1 = Setup(commandInfo, initCK)
      val sca2 = Setup(commandInfo, datumCK)
      val sca3 = positionSC(testRangeDistance)

      List(sca1, sca2, sca3).foreach { p=>
        fakeClient.send(tromboneAssembly, Submit(p))

        // This first one is the accept/verification
        val acceptedMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
        acceptedMsg shouldBe Accepted

        // Second one is completion of the executed ones
        val completeMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
        completeMsg shouldBe Completed
      }

      cleanup(tromboneAssembly)
    }

    it("should allow an init, datum then a set of positions as separate sca") {
      // test8
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      //val fakeSupervisor = TestProbe()
      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.expectNoMsg(200.milli)
      fakeSupervisor.send(tromboneAssembly, Running)
      expectNoMsg(200.millis)

      List(Setup(commandInfo, initCK), Setup(commandInfo, datumCK)).foreach { p =>
        fakeClient.send(tromboneAssembly, Submit(p))

        // This first one is the accept/verification
        val acceptedMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
        acceptedMsg shouldBe Accepted

        // Second one is completion of the executed ones
        val completeMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
        completeMsg shouldBe Completed
      }

      // This will send a config arg with 10 position commands
      val testRangeDistance = 90 to 180 by 10
      val positionConfigs = testRangeDistance.map(f => positionSC(f))

      positionConfigs.foreach { pc =>
        fakeClient.send(tromboneAssembly, Submit(pc))

        // This first one is the accept/verification
        val acceptedMsg2 = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
        //logger.info("acceptedMsg: " + acceptedMsg)
        acceptedMsg2 shouldBe Accepted

        // Second one is completion of the executed ones - give this some extra time to complete
        val completeMsg2 = fakeClient.expectMsgClass(10.seconds, classOf[CommandResponse])
        completeMsg2 shouldBe Completed
      }

      cleanup(tromboneAssembly)
    }

    it("should allow an init, datum then move and stop") {
      // test9
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.expectNoMsg(200.milli)
      fakeSupervisor.send(tromboneAssembly, Running)

      List(Setup(commandInfo, initCK), Setup(commandInfo, datumCK)).foreach { p =>
        fakeClient.send(tromboneAssembly, Submit(p))

        // This first one is the accept/verification
        var acceptedMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
        acceptedMsg shouldBe Accepted
        // Second one is completion of the executed datum
        var completeMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
        completeMsg shouldBe Completed
      }

      // Now start a long move
      val testMove = 150.1
      // Send the move
      fakeClient.send(tromboneAssembly, Submit(moveSC(testMove)))

      // This first one is the accept/verification
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted

      // Now send the stop after a bit of delay to let it get going
      // XXX FIXME: This is a timing thing that may not work on all machines
      fakeSupervisor.expectNoMsg(300.millis)
      // Send the stop
      fakeClient.send(tromboneAssembly, Submit(Setup(commandInfo, stopCK)))

      // Stop must be accepted too
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted

      // Second one is completion of the stop
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Cancelled
      // Checking that no talking
      fakeClient.expectNoMsg(100.milli)

      cleanup(tromboneAssembly)
    }

    it("should allow an init, setElevation") {
      // test10
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      //val fakeSupervisor = TestProbe()
      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.expectNoMsg(200.milli)
      fakeSupervisor.send(tromboneAssembly, Running)

      List(Setup(commandInfo, initCK), Setup(commandInfo, datumCK)).foreach { p=>
        fakeClient.send(tromboneAssembly, Submit(p))

        // This first one is the accept/verification
        fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted
        // Second one is completion of the executed datum
        fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Completed
      }

      val testEl = 150.0

      // Send the setElevation
      fakeClient.send(tromboneAssembly, Submit(setElevationSC(testEl)))

      // This first one is the accept/verification
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted

      // Second one is completion of the executed ones
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Completed

      cleanup(tromboneAssembly)
    }

    it("should get an error for SetAngle without fillowing after good setup") {
      // test11
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      //val fakeSupervisor = TestProbe()
      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.send(tromboneAssembly, Running)

      // Sending an Init first so we can see the datum issue
      List(Setup(commandInfo, initCK), Setup(commandInfo, datumCK)).foreach { p =>
        fakeClient.send(tromboneAssembly, Submit(p))

        // This first one is the accept/verification -- note that it is accepted because there is no static validation errors
        fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted

        // Second one is completion of the executed init/datum
        fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Completed
      }

      // Now try a setAngle
      val setAngleValue = 22.0
      // Send the command
      fakeClient.send(tromboneAssembly, Submit(setAngleSC(setAngleValue)))

      // This first one is the accept/verification -- note that it is accepted because there is no static validation errors
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted

      // This should fail due to wrong internal state
      val completeMsg = fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse])
      // First is not valid
      completeMsg shouldBe a[NoLongerValid]
      completeMsg.asInstanceOf[NoLongerValid].issue shouldBe a[WrongInternalStateIssue]

      cleanup(tromboneAssembly)
    }

    it("should allow an init, setElevation, follow, stop") {
      // test12
      val fakeSupervisor = TestProbe()
      val tromboneAssembly = newTrombone(fakeSupervisor.ref)
      val fakeClient = TestProbe()

      //val fakeSupervisor = TestProbe()
      fakeSupervisor.expectMsg(Initialized)
      fakeSupervisor.expectNoMsg(200.milli)
      fakeSupervisor.send(tromboneAssembly, Running)

      List(Setup(commandInfo, initCK), Setup(commandInfo, datumCK)).foreach { p =>
        fakeClient.send(tromboneAssembly, Submit(p))

        // This first one is the accept/verification
        fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted
        // Second one is completion of the executed datum
        fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Completed
      }

      val testEl = 150.0
      List(setElevationSC(testEl), followSC(false), Setup(commandInfo, stopCK)).foreach { p =>
        // Send the setElevation
        fakeClient.send(tromboneAssembly, Submit(p))

        // This first one is the accept/verification
        fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted

        // Second one is completion of the executed ones
        fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Completed
      }

      cleanup(tromboneAssembly)
    }
  }

  it("should allow an init, setElevation, follow, a bunch of events and a stop") {
    // test13

    val fakeSupervisor = TestProbe()
    val tromboneAssembly = newTrombone(fakeSupervisor.ref)
    val fakeClient = TestProbe()

    //val fakeSupervisor = TestProbe()
    fakeSupervisor.expectMsg(Initialized)
    fakeSupervisor.expectNoMsg(200.milli)
    fakeSupervisor.send(tromboneAssembly, Running)

    List(Setup(commandInfo, initCK), Setup(commandInfo, datumCK)).foreach { p =>
      fakeClient.send(tromboneAssembly, Submit(p))

      // This first one is the accept/verification
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted
      // Second one is completion of the executed datum
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Completed
    }

    val testEl = 150.0
    List(setElevationSC(testEl), followSC(false)).foreach { p =>
      // Send the setElevation
      fakeClient.send(tromboneAssembly, Submit(p))

      // This first one is the accept/verification
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Accepted

      // Second one is completion of the executed ones
      fakeClient.expectMsgClass(3.seconds, classOf[CommandResponse]) shouldBe Completed
    }

    // Now send some events
    // This eventService is used to simulate the TCS and RTC publishing zentith angle and focus error
    val tcsRtc = eventService

    val testFE = 10.0
    // Publish a single focus error. This will generate a published event
    tcsRtc.publish(SystemEvent(focusErrorPrefix).add(fe(testFE)))

    val testZenithAngles = 0.0 to 40.0 by 5.0
    // These are fake messages for the FollowActor that will be sent to simulate the TCS
    val tcsEvents = testZenithAngles.map(f => SystemEvent(zaPrefix.prefix).add(za(f)))

    // This should result in the length of tcsEvents being published
    tcsEvents.foreach { f =>
      logger.info(s"Publish: $f")
      tcsRtc.publish(f)
    }

    expectNoMsg(10.seconds)

    cleanup(tromboneAssembly)
  }

}
