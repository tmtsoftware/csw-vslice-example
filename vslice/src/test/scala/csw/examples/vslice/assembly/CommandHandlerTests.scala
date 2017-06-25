package csw.examples.vslice.assembly

import java.net.URI

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import csw.examples.vslice.TestEnv
import csw.examples.vslice.hcd.SingleAxisSimulator.AxisUpdate
import csw.examples.vslice.hcd.TromboneHCD
import csw.examples.vslice.hcd.TromboneHCD.GetAxisUpdateNow
import csw.services.ccs.CommandResponse._
import csw.services.ccs.SequentialExecutor
import csw.services.ccs.SequentialExecutor.ExecuteOne
import csw.services.ccs.Validation.{RequiredHCDUnavailableIssue, WrongInternalStateIssue}
import csw.services.events.EventService
import csw.services.events.EventService.eventServiceConnection
import csw.services.loc.Connection.AkkaConnection
import csw.services.loc.ConnectionType.AkkaType
import csw.services.loc.LocationService
import csw.services.loc.LocationService.{ResolvedAkkaLocation, ResolvedTcpLocation, Unresolved}
import csw.services.pkg.Component.{DoNotRegister, HcdInfo}
import csw.services.pkg.Supervisor
import csw.services.pkg.Supervisor.{HaltComponent, LifecycleRunning}
import csw.services.pkg.SupervisorExternal.{ExComponentShutdown, LifecycleStateChanged, SubscribeLifecycleCallback}
import csw.util.param.Parameters
import csw.util.param.Parameters.Setup
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, _}

import scala.concurrent.Await
import scala.concurrent.duration._

object CommandHandlerTests {
  LocationService.initInterface()
  val system = ActorSystem("TromboneAssemblyCommandHandlerTests")
}

/**
 * TMT Source Code: 9/21/16.
 */
class CommandHandlerTests extends TestKit(CommandHandlerTests.system)
    with FunSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with LazyLogging {

  import TromboneStateActor._

  override protected def beforeEach(): Unit = {
    TestEnv.resetRedisServices()
  }

  override def beforeAll(): Unit = {
    TestEnv.createTromboneAssemblyConfig()
  }

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val ac = AssemblyTestData.TestAssemblyContext

  def getEventServiceLocation: ResolvedTcpLocation = {
    implicit val timeout = Timeout(5.seconds)
    val connection = eventServiceConnection(EventService.defaultName)
    val locationsReady = Await.result(LocationService.resolve(Set(connection)), timeout.duration)
    locationsReady.locations.head.asInstanceOf[ResolvedTcpLocation]
  }

  def setupState(ts: TromboneState): Unit = {
    // These times are important to allow time for test actors to get and process the state updates when running tests
    expectNoMsg(20.milli)
    system.eventStream.publish(ts)
    // This is here to allow the destination to run and set its state
    expectNoMsg(20.milli)
  }

  def startHCD: ActorRef = {
    val testInfo = HcdInfo(
      TromboneHCD.componentName,
      TromboneHCD.trombonePrefix,
      TromboneHCD.componentClassName,
      DoNotRegister, Set(AkkaType), 1.second
    )

    Supervisor(testInfo)
  }

  def newCommandHandler(tromboneHCD: ActorRef, allEventPublisher: Option[ActorRef] = None) = {
    //val thandler = TestActorRef(TromboneCommandHandler.props(configs, tromboneHCD, allEventPublisher), "X")
    //thandler
    system.actorOf(TromboneCommandHandler.props(ac, Some(tromboneHCD), allEventPublisher))
  }

  // Stop any actors created for a test to avoid conflict with other tests
  private def cleanup(tromboneHCD: ActorRef, a: ActorRef*): Unit = {
    val monitor = TestProbe()
    a.foreach { actorRef =>
      monitor.watch(actorRef)
      system.stop(actorRef)
      monitor.expectTerminated(actorRef)
    }

    monitor.watch(tromboneHCD)
    tromboneHCD ! HaltComponent
    monitor.expectTerminated(tromboneHCD)
  }

  it("should allow running datum directly to CommandHandler") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    //val tsa = system.actorOf(TromboneStateActor.props)

    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveUnindexed), sodiumItem(false), nssItem(false)))

    val sc = Setup(ac.datumCK)

    ch ! ExecuteOne(sc, Some(fakeAssembly.ref))

    val msg: CommandResponse = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResponse])
    msg shouldBe Completed
    //info("Final: " + msg)

    // Demonstrate error
    ch ! TromboneState(cmdItem(cmdUninitialized), moveItem(moveUnindexed), sodiumItem(false), nssItem(false))
    ch ! ExecuteOne(sc, Some(fakeAssembly.ref))

    val errMsg = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResponse])
    errMsg shouldBe a[NoLongerValid]

    cleanup(tromboneHCD, ch)
  }

  it("datum should handle change in HCD") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    //val tsa = system.actorOf(TromboneStateActor.props)

    // Start with good HCD
    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveUnindexed), sodiumItem(false), nssItem(false)))

    val sc = Setup(ac.datumCK)

    ch ! ExecuteOne(sc, Some(fakeAssembly.ref))

    val msg: CommandResponse = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResponse])
    msg shouldBe Completed
    //info("Final: " + msg

    val unresolvedHCD = Unresolved(AkkaConnection(ac.hcdComponentId))
    ch ! unresolvedHCD

    ch ! ExecuteOne(sc, Some(fakeAssembly.ref))

    val errMsg: CommandResponse = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResponse])
    errMsg shouldBe a[NoLongerValid]
    errMsg.asInstanceOf[NoLongerValid].issue shouldBe a[RequiredHCDUnavailableIssue]

    val resolvedHCD = ResolvedAkkaLocation(AkkaConnection(ac.hcdComponentId), new URI("http://help"), "", Some(tromboneHCD))
    ch ! resolvedHCD

    ch ! ExecuteOne(sc, Some(fakeAssembly.ref))
    val msg2: CommandResponse = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResponse])
    msg2 shouldBe Completed

    cleanup(tromboneHCD, ch)
  }

  it("should allow running datum through SequentialExecutor") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveUnindexed), sodiumItem(false), nssItem(false)))

    val sca = Parameters.createSetupArg("testobsId", Setup(ac.datumCK))

    val se = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val msg = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    //info("Final: " + msg)
    msg.overall shouldBe AllCompleted
    msg.details.results.size shouldBe 1

    // Demonstrate error
    ch ! TromboneState(cmdItem(cmdUninitialized), moveItem(moveUnindexed), sodiumItem(false), nssItem(false))

    val se2 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val errMsg = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    errMsg.overall should equal(Incomplete)
    val e1 = errMsg.details.results.head.status
    e1 shouldBe a[NoLongerValid]
    e1.asInstanceOf[NoLongerValid].issue shouldBe a[WrongInternalStateIssue]

    //info("Final: " + errMsg)

    cleanup(tromboneHCD, ch)
  }

  it("should allow running move") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    //expectNoMsg(100.milli)

    val testPosition = 90.0
    val sca = Parameters.createSetupArg("testobsId", ac.moveSC(testPosition))

    val se2 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    fakeAssembly.expectMsgClass(35.seconds, classOf[CommandResult])
    val finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testPosition)

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    val upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    upd.current should equal(finalPos)

    cleanup(tromboneHCD, ch, se2)
  }

  it("should allow running a move without sequence") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    //expectNoMsg(100.milli)

    val testPosition = 90.0
    ch ! ExecuteOne(ac.moveSC(testPosition), Some(fakeAssembly.ref))

    fakeAssembly.expectMsgClass(35.seconds, classOf[CommandResponse])
    val finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testPosition)

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    val upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    upd.current should equal(finalPos)

    cleanup(tromboneHCD, ch)
  }

  it("should allow two moves") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)
    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    val pos1 = 86.0
    val pos2 = 150.1

    val sca = Parameters.createSetupArg("testobsId", ac.moveSC(pos1), ac.moveSC(pos2))

    val se2 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, pos2)

    val msg = fakeAssembly.expectMsgClass(35.seconds, classOf[CommandResult])
    info(s"result: $msg")

    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    val upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    upd.current should equal(finalPos)

    cleanup(tromboneHCD, se2, ch)
  }

  it("should allow a move with a stop") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)
    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    val pos1 = 150.1

    val sca = Parameters.createSetupArg("testobsId", ac.moveSC(pos1))

    val se = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    //    Parameters.createSetupArg("testobsId", Setup(ac.stopCK))
    Thread.sleep(100) // XXX FIXME: This is an arbitrary time to get things going before sending stop

    // This won't work
    //val se2 = system.actorOf(SequentialExecutor.props(sca2, Some(fakeAssembly.ref)))
    //se2 ! StartTheSequence(ch)

    // This will also work
    //  se ! StopCurrentCommand
    ch ! Setup(ac.stopCK)

    val msg = fakeAssembly.expectMsgClass(35.seconds, classOf[CommandResult])
    msg.overall shouldBe Incomplete
    msg.details.status(0) shouldBe Cancelled
    info(s"result: $msg")

    cleanup(tromboneHCD, se, ch)
  }

  it("should allow a single position command") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    val testRangeDistance = 94.0
    val positionConfig = ac.positionSC(testRangeDistance)
    logger.info("Position: " + positionConfig)
    val sca = Parameters.createSetupArg("testobsId", positionConfig)

    val se2 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    fakeAssembly.expectMsgClass(5.seconds, classOf[CommandResult])
    val finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testRangeDistance)

    // Use the engineering GetAxisUpdate to get the current encoder
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    val upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    upd.current should equal(finalPos)

    cleanup(tromboneHCD, se2, ch)
  }

  it("should allow a set of positions for the fun of it") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    // This will send a config arg with 10 position commands
    val testRangeDistance = 90 to 180 by 10
    val positionConfigs = testRangeDistance.map(f => ac.positionSC(f))

    val sca = Parameters.createSetupArg("testobsId", positionConfigs: _*)

    val se2 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val msg = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    //info("Final: " + msg)

    // Test
    val finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testRangeDistance.last)
    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    val upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    upd.current should equal(finalPos)

    cleanup(tromboneHCD, se2, ch)
  }

  it("should allow running a setElevation without sequence") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    val testEl = 150.0
    ch ! ExecuteOne(ac.setElevationSC(testEl), Some(fakeAssembly.ref))

    fakeAssembly.expectMsgClass(5.seconds, classOf[CommandResponse])
    val finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testEl)

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    val upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    upd.current should equal(finalPos)
    info("Upd: " + upd)

    cleanup(tromboneHCD, ch)
  }

  it("should get error for setAngle when not following") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)

    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    val sca = Parameters.createSetupArg("testobsId", ac.setAngleSC(22.0))

    val se2 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val errMsg = fakeAssembly.expectMsgClass(35.seconds, classOf[CommandResult])
    errMsg.overall should equal(Incomplete)
    errMsg.details.results.head.status shouldBe a[NoLongerValid]

    cleanup(tromboneHCD, se2, ch)
  }

  it("should allow follow and a stop") {

    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)
    //    val evLocation = ResolvedTcpLocation(EventService.eventServiceConnection(), "localhost", 7777)
    val evLocation = getEventServiceLocation
    ch ! evLocation

    // set the state so the command succeeds
    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(true), nssItem(false)))

    //fakeAssembly.expectNoMsg(30.milli)
    val sca = Parameters.createSetupArg("testobsId", ac.followSC(false), Setup(ac.stopCK))
    val se = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val msg2 = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    info("Msg: " + msg2)

    cleanup(tromboneHCD, se, ch)
  }

  it("should allow follow, with two SetAngles and stop") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)
    //    val evLocation = ResolvedTcpLocation(EventService.eventServiceConnection(), "localhost", 7777)
    val evLocation = getEventServiceLocation
    ch ! evLocation

    // I'm sending this event to the follower so I know its state so I can check the final result
    // to see that it moves the stage to the right place when sending a new elevation
    val testFocusError = 0.0
    val testElevation = 100.0
    val initialZenithAngle = 0.0

    // set the state so the command succeeds - NOTE: Setting sodiumItem true here
    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)))

    //fakeAssembly.expectNoMsg(30.milli)
    var totalRangeDistance = Algorithms.focusZenithAngleToRangeDistance(ac.calculationConfig, testElevation, testFocusError, initialZenithAngle)
    var stagePosition = Algorithms.rangeDistanceToStagePosition(totalRangeDistance)
    var expectedEncoderValue = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition)
    logger.info(s"Expected for setElevation: $expectedEncoderValue")

    var sca = Parameters.createSetupArg("testobsId", ac.setElevationSC(testElevation))
    val se2 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val msg1 = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    logger.info("Msg: " + msg1)

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    var upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    upd.current should equal(expectedEncoderValue)

    //    fakeAssembly.expectNoMsg(2.seconds)

    // This sets up the follow command to put assembly into follow mode
    sca = Parameters.createSetupArg("testobsId", ac.followSC(nssInUse = false))
    val se = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))
    val msg2 = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    logger.info("Msg2: " + msg2)

    val testZenithAngle = 30.0
    sca = Parameters.createSetupArg("testobsId", ac.setAngleSC(testZenithAngle))
    val se3 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val msg3 = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    logger.info("Msg3: " + msg3)

    totalRangeDistance = Algorithms.focusZenithAngleToRangeDistance(ac.calculationConfig, testElevation, testFocusError, testZenithAngle)
    stagePosition = Algorithms.rangeDistanceToStagePosition(totalRangeDistance)
    expectedEncoderValue = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition)
    logger.info(s"Expected for setAngle: $expectedEncoderValue")

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    logger.info(s"Upd2: $upd")
    upd.current should equal(expectedEncoderValue)

    sca = Parameters.createSetupArg("testobsId", Setup(ac.stopCK))
    val se4 = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val msg5 = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    logger.info("Msg: " + msg5)
    fakeAssembly.expectNoMsg(1.seconds)

    cleanup(tromboneHCD, ch, se, se2, se3, se4)
  }

  it("should allow one Arg with setElevation, follow, SetAngle and stop as a single sequence") {
    val tromboneHCD = startHCD
    val fakeAssembly = TestProbe()

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD ! SubscribeLifecycleCallback(fakeAssembly.ref)
    fakeAssembly.expectMsg(LifecycleStateChanged(LifecycleRunning))
    //info("Running")

    val ch = newCommandHandler(tromboneHCD)
    //    val evLocation = ResolvedTcpLocation(EventService.eventServiceConnection(), "localhost", 7777)
    val evLocation = getEventServiceLocation
    ch ! evLocation

    // I'm sending this event to the follower so I know its state so I can check the final result
    // to see that it moves the stage to the right place when sending a new elevation
    val testFocusError = 0.0
    val testElevation = 100.0
    //val initialZenithAngle = 0.0
    val testZenithAngle = 30.0

    // set the state so the command succeeds
    setupState(TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(true), nssItem(false)))

    //fakeAssembly.expectNoMsg(30.milli)
    val sca = Parameters.createSetupArg("testobsId", ac.setElevationSC(testElevation), ac.followSC(false), ac.setAngleSC(testZenithAngle), Setup(ac.stopCK))
    val se = system.actorOf(SequentialExecutor.props(ch, sca, Some(fakeAssembly.ref)))

    val msg = fakeAssembly.expectMsgClass(10.seconds, classOf[CommandResult])
    logger.info(">>>>>>>Msg: " + msg)

    fakeAssembly.expectNoMsg(2.seconds)

    val totalRangeDistance = Algorithms.focusZenithAngleToRangeDistance(ac.calculationConfig, testElevation, testFocusError, testZenithAngle)
    val stagePosition = Algorithms.rangeDistanceToStagePosition(totalRangeDistance)
    val expectedEncoderValue = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition)
    logger.info(s"Expected for setAngle: $expectedEncoderValue")

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow)
    val upd = fakeAssembly.expectMsgClass(classOf[AxisUpdate])
    logger.info(s"Upd2: $upd")

    cleanup(tromboneHCD, se, ch)
  }

}
