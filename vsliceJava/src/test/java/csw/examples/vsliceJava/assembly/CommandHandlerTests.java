package csw.examples.vsliceJava.assembly;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.testkit.javadsl.TestKit;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.examples.vsliceJava.TestEnv;
import csw.examples.vsliceJava.hcd.TromboneHCD;
import csw.services.ccs.CommandResponse.CommandResponse;
import csw.services.ccs.SequentialExecutor;
import csw.services.ccs.Validation;
import csw.services.loc.Connection.AkkaConnection;
import csw.services.loc.LocationService;
import csw.services.loc.LocationService.Location;
import csw.services.loc.LocationService.ResolvedAkkaLocation;
import csw.services.loc.LocationService.Unresolved;
import csw.services.pkg.Component;
import csw.services.pkg.Supervisor;
import csw.util.param.Parameters;
import javacsw.services.events.IEventService;
import javacsw.services.pkg.JComponent;
import org.junit.*;
import scala.concurrent.duration.FiniteDuration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static csw.examples.vsliceJava.assembly.TromboneStateActor.*;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.AxisUpdate;
import static csw.examples.vsliceJava.hcd.TromboneHCD.TromboneEngineering.GetAxisUpdateNow;
import static csw.services.ccs.CommandResponse.CommandResult;
import static csw.services.ccs.CommandResponse.NoLongerValid;
import static csw.services.pkg.SupervisorExternal.LifecycleStateChanged;
import static csw.services.pkg.SupervisorExternal.SubscribeLifecycleCallback;
import static csw.util.param.Parameters.Setup;
import static csw.util.param.Parameters.SetupArg;
import static javacsw.services.ccs.JCommandStatus.*;
import static javacsw.services.ccs.JSequentialExecutor.ExecuteOne;
import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.DoNotRegister;
import static javacsw.services.pkg.JSupervisor.HaltComponent;
import static javacsw.services.pkg.JSupervisor.LifecycleRunning;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;

@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType", "MismatchedReadAndWriteOfArray", "Duplicates"})
public class CommandHandlerTests extends TestKit {
  private static ActorSystem system;
  private static LoggingAdapter logger;
  private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

  // This def helps to make the test code look more like normal production code, where self() is defined in an actor class
  ActorRef self() {
    return getTestActor();
  }

  public CommandHandlerTests() {
    super(system);
  }

  @Before
  public void beforeEach() throws Exception {
    TestEnv.resetRedisServices(system);
  }

  @BeforeClass
  public static void setup() throws Exception {
    LocationService.initInterface();
    system = ActorSystem.create("CommandHandlerTests");
    logger = Logging.getLogger(system, system);
    TestEnv.createTromboneAssemblyConfig(system);
  }

  @AfterClass
  public static void teardown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  // Stop any actors created for a test to avoid conflict with other tests
  private void cleanup(ActorRef tromboneHCD, ActorRef... a) {
    TestProbe monitor = new TestProbe(system);
    for(ActorRef actorRef : a) {
      monitor.watch(actorRef);
      system.stop(actorRef);
      monitor.expectTerminated(actorRef, timeout.duration());
    }

    monitor.watch(tromboneHCD);
    tromboneHCD.tell(HaltComponent, self());
    monitor.expectTerminated(tromboneHCD, timeout.duration());
  }

  static final AssemblyContext ac = AssemblyTestData.TestAssemblyContext;

  void setupState(TromboneState ts) {
    // These times are important to allow time for test actors to get and process the state updates when running tests
    expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
    system.eventStream().publish(ts);
    // This is here to allow the destination to run and set its state
    expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
  }

  ActorRef startHCD() {
    Component.HcdInfo testInfo = JComponent.hcdInfo(
      TromboneHCD.componentName,
      TromboneHCD.trombonePrefix,
      TromboneHCD.componentClassName,
      DoNotRegister, Collections.singleton(AkkaType), FiniteDuration.create(1, TimeUnit.SECONDS)
    );

    return Supervisor.apply(testInfo);
  }

  ActorRef newCommandHandler(ActorRef tromboneHCD, Optional<ActorRef> allEventPublisher) {
    return system.actorOf(TromboneCommandHandler.props(ac, Optional.of(tromboneHCD), allEventPublisher));
  }

  @Test
  public void shouldAllowRunningDatumDirectlyToCommandHandler() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

//    ActorRef tsa = system.actorOf(TromboneStateActor.props());

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveUnindexed), sodiumItem(false), nssItem(false)));

    Parameters.Setup sc = new Setup(ac.datumCK.prefix());

    ch.tell(ExecuteOne(sc, Optional.of(fakeAssembly.ref())), self());

    CommandResponse msg = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
      CommandResponse.class);
    assertEquals(msg, Completed);
    //info("Final: " + msg)

    // Demonstrate error
    ch.tell(new TromboneState(cmdItem(cmdUninitialized), moveItem(moveUnindexed), sodiumItem(false), nssItem(false)), self());
    ch.tell(ExecuteOne(sc, Optional.of(fakeAssembly.ref())), self());

    CommandResponse errMsg = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
      CommandResponse.class);

    assertTrue(errMsg instanceof NoLongerValid);

    cleanup(tromboneHCD, ch);
  }

  @Test
  public void datumShouldHandleChangeInHCD() throws URISyntaxException {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

    // Start with good HCD
    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveUnindexed), sodiumItem(false), nssItem(false)));

    Parameters.Setup sc = new Setup(ac.datumCK.prefix());

    ch.tell(ExecuteOne(sc, Optional.of(fakeAssembly.ref())), self());

    CommandResponse msg = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
      CommandResponse.class);
    assertEquals(msg, Completed);
    //info("Final: " + msg)

    Location unresolvedHCD = new Unresolved(new AkkaConnection(ac.hcdComponentId));
    ch.tell(unresolvedHCD, self());

    ch.tell(ExecuteOne(sc, Optional.of(fakeAssembly.ref())), self());

    // XXX TODO: Check problem here
    CommandResponse errMsg = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
      CommandResponse.class);

    assertTrue(errMsg instanceof NoLongerValid);
    assertTrue(((NoLongerValid)errMsg).issue() instanceof Validation.RequiredHCDUnavailableIssue);

    Location resolvedHCD = new ResolvedAkkaLocation(new AkkaConnection(ac.hcdComponentId), new URI("http://help"), "", Optional.of(tromboneHCD));
    ch.tell(resolvedHCD, self());

    ch.tell(ExecuteOne(sc, Optional.of(fakeAssembly.ref())), self());
    CommandResponse msg2 = fakeAssembly.expectMsgClass(duration("10 seconds"), CommandResponse.class);
    assertEquals(msg2, Completed);

    cleanup(tromboneHCD, ch);
  }


  @Test
  public void shouldAllowRunningDatumThroughSequentialExecutor() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveUnindexed), sodiumItem(false), nssItem(false)));

    SetupArg sca = Parameters.createSetupArg("testobsId", new Setup(ac.datumCK.prefix()));

    ActorRef se = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult msg = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    //info("Final: " + msg)
    assertEquals(msg.overall(), AllCompleted);
    assertEquals(msg.details().results().size(), 1);

    // Demonstrate error
    ch.tell(new TromboneState(cmdItem(cmdUninitialized), moveItem(moveUnindexed), sodiumItem(false), nssItem(false)), self());

    ActorRef se2 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult errMsg = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    assertEquals(errMsg.overall(), Incomplete);
    CommandResponse e1 = errMsg.details().getResults().get(0).first();
    assertTrue(e1 instanceof NoLongerValid);
    assertTrue(((NoLongerValid) e1).issue() instanceof Validation.WrongInternalStateIssue);

    //info("Final: " + errMsg)

    cleanup(tromboneHCD, ch);
  }

  @Test
  public void shouldAllowRunningMove() {
    ActorRef tromboneHCD = startHCD();

    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    //expectNoMsg(100.milli)

    double testPosition = 90.0;
    SetupArg sca = Parameters.createSetupArg("testobsId", ac.moveSC(testPosition));

    ActorRef se2 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    fakeAssembly.expectMsgClass(FiniteDuration.create(35, TimeUnit.SECONDS), CommandResult.class);
    int finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testPosition);

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    AxisUpdate upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    assertEquals(upd.current, finalPos);

    cleanup(tromboneHCD, ch, se2);
  }

  @Test
  public void shouldAllowRunningAMoveWithoutSequence() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    //expectNoMsg(100.milli)

    double testPosition = 90.0;
    ch.tell(ExecuteOne(ac.moveSC(testPosition), Optional.of(fakeAssembly.ref())), self());

    fakeAssembly.expectMsgClass(FiniteDuration.create(35, TimeUnit.SECONDS), CommandResponse.class);
    int finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testPosition);

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    AxisUpdate upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    assertEquals(upd.current, finalPos);

    cleanup(tromboneHCD, ch);
  }

  @Test
  public void shouldAllowTwoMoves() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    double pos1 = 86.0;
    double pos2 = 150.1;

    SetupArg sca = Parameters.createSetupArg("testobsId", ac.moveSC(pos1), ac.moveSC(pos2));
    System.out.println("SCA: " + sca);

    ActorRef se2 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    int finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, pos2);

    CommandResult msg = fakeAssembly.expectMsgClass(FiniteDuration.create(35, TimeUnit.SECONDS), CommandResult.class);
    logger.info("result: " + msg);

    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    AxisUpdate upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    assertEquals(upd.current, finalPos);

    cleanup(tromboneHCD, se2, ch);
  }

  @Test
  public void shouldAllowAMoveWithAStop() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    double pos1 = 150.1;

    SetupArg sca = Parameters.createSetupArg("testobsId", ac.moveSC(pos1));

    ActorRef se = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

//    Parameters.createSetupArg("testobsId", new Setup(ac.stopCK.prefix()));
    try {
      Thread.sleep(100); // This is an arbitrary time to get things going before sending stop
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    // This won't work
    //val se2 = system.actorOf(SequentialExecutor.props(sca2, Optional.of(fakeAssembly.ref)))
    //se2 ! StartTheSequence(ch)

    // This will also work
    //  se ! StopCurrentCommand
    ch.tell(new Setup(ac.stopCK.prefix()), self());

    CommandResult msg = fakeAssembly.expectMsgClass(FiniteDuration.create(35, TimeUnit.SECONDS), CommandResult.class);
    assertEquals(msg.overall(), Incomplete);
    assertEquals(msg.details().status(0), Cancelled);
    logger.info("result: " + msg);

    cleanup(tromboneHCD, se, ch);
  }

  @Test
  public void shouldAllowASinglePositionCommand() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    double testRangeDistance = 94.0;
    Setup positionConfig = ac.positionSC(testRangeDistance);
    logger.info("Position: " + positionConfig);
    SetupArg sca = Parameters.createSetupArg("testobsId", positionConfig);

    ActorRef se2 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    fakeAssembly.expectMsgClass(FiniteDuration.create(5, TimeUnit.SECONDS), CommandResult.class);
    int finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testRangeDistance);

    // Use the engineering GetAxisUpdate to get the current encoder
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    AxisUpdate upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    assertEquals(upd.current, finalPos);

    cleanup(tromboneHCD, se2, ch);
  }

  @Test
  public void shouldAllowASetOfPositionsForTheFunOfIt() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    // This will send a config arg with 10 position commands
    int[] testRangeDistance = new int[]{90, 100, 110, 120, 130, 140, 150, 160, 170, 180}; // 90 to 180 by 10

    Setup[] positionConfigs = Arrays.stream(testRangeDistance).mapToObj(ac::positionSC)
      .toArray(Setup[]::new);

    SetupArg sca = Parameters.createSetupArg("testobsId", positionConfigs);

    ActorRef se2 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult msg = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    logger.info("Final: " + msg);

    // Test
    int finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testRangeDistance[testRangeDistance.length - 1]);
    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    AxisUpdate upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    assertEquals(upd.current, finalPos);

    cleanup(tromboneHCD, se2, ch);
  }

  @Test
  public void shouldAllowRunningASetElevationWithoutSequence() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    double testEl = 150.0;
    ch.tell(ExecuteOne(ac.setElevationSC(testEl), Optional.of(fakeAssembly.ref())), self());

    fakeAssembly.expectMsgClass(FiniteDuration.create(5, TimeUnit.SECONDS), CommandResponse.class);
    int finalPos = Algorithms.stagePositionToEncoder(ac.controlConfig, testEl);

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    AxisUpdate upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    assertEquals(upd.current, finalPos);
    logger.info("Upd: " + upd);

    cleanup(tromboneHCD, ch);
  }

  @Test
  public void shouldGetErrorForSetAngleWhenNotFollowing() {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());

    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    SetupArg sca = Parameters.createSetupArg("testobsId", ac.setAngleSC(22.0));

    ActorRef se2 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult errMsg = fakeAssembly.expectMsgClass(FiniteDuration.create(35, TimeUnit.SECONDS), CommandResult.class);
    assertEquals(errMsg.overall(), Incomplete);
    assertTrue(errMsg.details().getResults().get(0).first() instanceof NoLongerValid);

    cleanup(tromboneHCD, se2, ch);
  }

  @Test
  public void shouldAllowFollowAndAStop() throws ExecutionException, InterruptedException {

    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());
//    LocationService.ResolvedTcpLocation evLocation = new LocationService.ResolvedTcpLocation(IEventService.eventServiceConnection(), "localhost", 7777);
    LocationService.ResolvedTcpLocation evLocation = IEventService.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
    ch.tell(evLocation, self());

    // set the state so the command succeeds
    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(true), nssItem(false)));

    //fakeAssembly.expectNoMsg(30.milli)
    SetupArg sca = Parameters.createSetupArg("testobsId", ac.followSC(false), new Setup(ac.stopCK.prefix()));
    ActorRef se = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult msg2 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    logger.info("Msg: " + msg2);

    cleanup(tromboneHCD, se, ch);
  }

  @Test
  public void shouldAllowFollowWithTwoSetAnglesAndStop() throws ExecutionException, InterruptedException {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());
//    LocationService.ResolvedTcpLocation evLocation = new LocationService.ResolvedTcpLocation(IEventService.eventServiceConnection(), "localhost", 7777);
    LocationService.ResolvedTcpLocation evLocation = IEventService.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
    ch.tell(evLocation, self());

    // I'm sending this event to the follower so I know its state so I can check the final result
    // to see that it moves the stage to the right place when sending a new elevation
    double testFocusError = 0.0;
    double testElevation = 100.0;
    double initialZenithAngle = 0.0;

    // set the state so the command succeeds - NOTE: Setting sodiumItem true here
    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), nssItem(false)));

    //fakeAssembly.expectNoMsg(30.milli)
    double totalRangeDistance = Algorithms.focusZenithAngleToRangeDistance(ac.calculationConfig, testElevation, testFocusError, initialZenithAngle);
    double stagePosition = Algorithms.rangeDistanceToStagePosition(totalRangeDistance);
    int expectedEncoderValue = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition);
    logger.info("Expected for setElevation: " + expectedEncoderValue);

    SetupArg sca = Parameters.createSetupArg("testobsId", ac.setElevationSC(testElevation));
    ActorRef se2 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult msg1 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    logger.info("Msg: " + msg1);

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    AxisUpdate upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    assertEquals(upd.current, expectedEncoderValue);

//    fakeAssembly.expectNoMsg(2.seconds)

    // This sets up the follow command to put assembly into follow mode
    sca = Parameters.createSetupArg("testobsId", ac.followSC(false));
    ActorRef se = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));
    CommandResult msg2 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    logger.info("Msg2: " + msg2);

    double testZenithAngle = 30.0;
    sca = Parameters.createSetupArg("testobsId", ac.setAngleSC(testZenithAngle));
    ActorRef se3 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult msg3 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    logger.info("Msg3: " + msg3);

    totalRangeDistance = Algorithms.focusZenithAngleToRangeDistance(ac.calculationConfig, testElevation, testFocusError, testZenithAngle);
    stagePosition = Algorithms.rangeDistanceToStagePosition(totalRangeDistance);
    expectedEncoderValue = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition);
    logger.info("Expected for setAngle: " + expectedEncoderValue);

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    logger.info("Upd2: " + upd);
    assertEquals(upd.current, expectedEncoderValue);

    sca = Parameters.createSetupArg("testobsId", new Setup(ac.stopCK.prefix()));
    ActorRef se4 = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult msg5 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    logger.info("Msg: " + msg5);
    fakeAssembly.expectNoMsg(FiniteDuration.apply(1, TimeUnit.SECONDS));

    cleanup(tromboneHCD, ch, se, se2, se3, se4);
  }

  @Test
  public void shouldAllowOneArgWithSetElevationFollowSetAngleAndStopAsASingleSequence() throws ExecutionException, InterruptedException {
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // The following is to synchronize the test with the HCD entering Running state
    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());
//    LocationService.ResolvedTcpLocation evLocation = new LocationService.ResolvedTcpLocation(IEventService.eventServiceConnection(), "localhost", 7777);
    LocationService.ResolvedTcpLocation evLocation = IEventService.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
    ch.tell(evLocation, self());

    // I'm sending this event to the follower so I know its state so I can check the final result
    // to see that it moves the stage to the right place when sending a new elevation
    double testFocusError = 0.0;
    double testElevation = 100.0;
    double testZenithAngle = 30.0;

    // set the state so the command succeeds
    setupState(new TromboneState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(true), nssItem(false)));

    //fakeAssembly.expectNoMsg(30.milli)
    SetupArg sca = Parameters.createSetupArg("testobsId", ac.setElevationSC(testElevation), ac.followSC(false), ac.setAngleSC(testZenithAngle),
      new Setup(ac.stopCK.prefix()));
    ActorRef se = system.actorOf(SequentialExecutor.props(ch, sca, Optional.of(fakeAssembly.ref())));

    CommandResult msg = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS), CommandResult.class);
    logger.info(">>>>>>>Msg: " + msg);

    fakeAssembly.expectNoMsg(FiniteDuration.apply(2, TimeUnit.SECONDS));

    double totalRangeDistance = Algorithms.focusZenithAngleToRangeDistance(ac.calculationConfig, testElevation, testFocusError, testZenithAngle);
    double stagePosition = Algorithms.rangeDistanceToStagePosition(totalRangeDistance);
    int expectedEncoderValue = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition);
    logger.info("Expected for setAngle: " + expectedEncoderValue);

    // Use the engineering GetAxisUpdate to get the current encoder for checking
    fakeAssembly.send(tromboneHCD, GetAxisUpdateNow);
    AxisUpdate upd = fakeAssembly.expectMsgClass(AxisUpdate.class);
    logger.info("Upd2: " + upd);

    cleanup(tromboneHCD, se, ch);
  }

}
