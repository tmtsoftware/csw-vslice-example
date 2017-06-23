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
import csw.services.alarms.AlarmKey;
import csw.services.ccs.CommandStatus.CommandStatus;
import csw.services.loc.LocationService;
import csw.services.pkg.Component;
import csw.services.pkg.Supervisor;
import javacsw.services.alarms.IAlarmService;
import javacsw.services.alarms.IAlarmServiceAdmin;
import javacsw.services.alarms.JAlarmServiceAdmin;
import javacsw.services.ccs.JSequentialExecutor;
import javacsw.services.pkg.JComponent;
import org.junit.*;
import scala.concurrent.duration.FiniteDuration;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static akka.pattern.PatternsCS.ask;
import static csw.examples.vsliceJava.assembly.TromboneAlarmMonitor.highLimitAlarm;
import static csw.examples.vsliceJava.assembly.TromboneAlarmMonitor.lowLimitAlarm;
import static csw.examples.vsliceJava.assembly.TromboneStateActor.*;
import static csw.examples.vsliceJava.hcd.TromboneHCD.*;
import static csw.services.alarms.AlarmModel.CurrentSeverity;
import static csw.services.pkg.SupervisorExternal.LifecycleStateChanged;
import static csw.services.pkg.SupervisorExternal.SubscribeLifecycleCallback;
import static csw.util.param.StateVariable.CurrentState;
import static javacsw.services.alarms.JAlarmModel.JSeverityLevel.Okay;
import static javacsw.services.alarms.JAlarmModel.JSeverityLevel.Warning;
import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.DoNotRegister;
import static javacsw.services.pkg.JSupervisor.HaltComponent;
import static javacsw.services.pkg.JSupervisor.LifecycleRunning;
import static javacsw.util.param.JParameterSetDsl.cs;
import static javacsw.util.param.JParameters.jadd;
import static javacsw.util.param.JParameters.jset;
import static javacsw.util.param.JUnitsOfMeasure.encoder;
import static org.junit.Assert.assertEquals;

/**
 * These tests are for the Trombone AlarmMonitor.
 */
@SuppressWarnings({"unused", "OptionalUsedAsFieldOrParameterType", "WeakerAccess", "Duplicates"})
public class AlarmMonitorTests extends TestKit {
  private static ActorSystem system;
  private static LoggingAdapter logger;

  private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));
  // Get the alarm service by looking up the name with the location service.
  private static IAlarmService alarmService;

  // Used to start and stop the alarm service Redis instance used for the test
  private static IAlarmServiceAdmin alarmAdmin;
  private static AssemblyContext ac = AssemblyTestData.TestAssemblyContext;


  // This def helps to make the test code look more like normal production code, where self() is defined in an actor class
  ActorRef self() {
    return getTestActor();
  }

  // For compatibility with Scala tests
  static void it(String s) {
    System.out.println(" ======> " + s);
  }

  public AlarmMonitorTests() {
    super(system);
  }

  @Before
  public void beforeEach() throws Exception {
    TestEnv.resetRedisServices(system);
  }

  @BeforeClass
  public static void setup() throws Exception {
    LocationService.initInterface();
    system = ActorSystem.create("AlarmMonitorTests");
    logger = Logging.getLogger(system, system);
    TestEnv.createTromboneAssemblyConfig(system);
    alarmService = IAlarmService.getAlarmService(system, timeout).get();
    alarmAdmin = new JAlarmServiceAdmin(alarmService, system);
    setupAlarms();
  }

  @AfterClass
  public static void teardown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  static void setupAlarms() throws Exception {
    alarmAdmin.acknowledgeAndResetAlarm(lowLimitAlarm).get(10, TimeUnit.SECONDS);
    alarmAdmin.acknowledgeAndResetAlarm(highLimitAlarm).get(10, TimeUnit.SECONDS);

    logger.info("Initializing alarm data");
  }

  // Initialize HCD for testing
  ActorRef startHCD() {
    Component.HcdInfo testInfo = JComponent.hcdInfo(
      TromboneHCD.componentName,
      TromboneHCD.trombonePrefix,
      TromboneHCD.componentClassName,
      DoNotRegister, Collections.singleton(AkkaType), FiniteDuration.apply(1, TimeUnit.SECONDS)
    );

    return Supervisor.apply(testInfo);
  }

  ActorRef newCommandHandler(ActorRef tromboneHCD, Optional<ActorRef> allEventPublisher) {
    ActorRef a = system.actorOf(TromboneCommandHandler.props(ac, Optional.of(tromboneHCD), allEventPublisher));
    expectNoMsg(duration("200 millis")); // give it time to start
    return a;
  }

  // Test Low Limit
  static final CurrentState testLowLimitEvent = jadd(cs(TromboneHCD.axisStateCK.prefix()),
    jset(positionKey, 0).withUnits(encoder),
    jset(stateKey, AXIS_IDLE),
    jset(inLowLimitKey, true), // Test case
    jset(inHighLimitKey, false),
    jset(inHomeKey, false));

  // Test High Limit
  static final CurrentState testHighLimitEvent = jadd(cs(TromboneHCD.axisStateCK.prefix()),
    jset(positionKey, 0).withUnits(encoder),
    jset(stateKey, AXIS_IDLE),
    jset(inLowLimitKey, false),
    jset(inHighLimitKey, true), // Test case
    jset(inHomeKey, false));

  // Test Clear
  static final CurrentState testClearLimitEvent = jadd(cs(TromboneHCD.axisStateCK.prefix()),
    jset(positionKey, 0).withUnits(encoder),
    jset(stateKey, AXIS_IDLE),
    jset(inLowLimitKey, false),
    jset(inHighLimitKey, false),
    jset(inHomeKey, false));

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


  /*
   * Test Description: this uses a fake trombone HCD to send  a CurrentState with low limit set.
   * This causes the monitor to send the warning severity to the Alarm Service
   * Then the alarm is cleared. In both cases, the admin interface of the Alarm Service is used to check that
   * the monitor actually did set the alarm severity.
   */
  @Test
  public void testLowLimitEvent() throws Exception {
    it("monitor should set a low alarm when receiving simulated encoder low limit");
    testLimitEvent(testLowLimitEvent, lowLimitAlarm);
  }

  /*
   * Test Description: this uses a fake trombone HCD to send  a CurrentState with high limit set.
   * This causes the monitor to send the warning severity to the Alarm Service
   * Then the alarm is cleared. In both cases, the admin interface of the Alarm Service is used to check that
   * the monitor actually did set the alarm severity.
   */
  @Test
  public void testHighLimitEvent() throws Exception {
    it("monitor should set a high alarm when receiving simulated encoder high limit");
    testLimitEvent(testHighLimitEvent, highLimitAlarm);
  }

  /*
   * Test Description: This test uses the actual HCD to drive the axis to the high limit and verify that the high
   * alarm is set and that the AlarmMonitor sets the alarm in the alarm service to warning
   */
  @Test
  public void testHighLimitAlarm() throws Exception {
    it("monitor should set a high alarm when receiving real encoder high limit using real HCD to generate data");
    testLimitAlarm(highLimitAlarm, 2000.0, AssemblyTestData.maxReasonableStage);
  }

  /*
   * Test Description: This test uses the actual HCD to drive the axis to the low limit and verify that the low
   * alarm is set and that the AlarmMonitor sets the alarm in the alarm service to warning
   */
  @Test
  public void testLowLimitAlarm() throws Exception {
    it("monitor should set a low alarm when receiving real encoder low limit using real HCD to generate data");
    testLimitAlarm(lowLimitAlarm, 0.0, 100.0);
  }

  void testLimitEvent(CurrentState limitEvent, AlarmKey alarmKey) throws Exception {
    TestProbe fakeTromboneHCD = new TestProbe(system);

    // Create an alarm monitor
    ActorRef am = system.actorOf(TromboneAlarmMonitor.props(fakeTromboneHCD.ref(), alarmService));
    expectNoMsg(FiniteDuration.create(100, TimeUnit.MILLISECONDS)); // A delay waiting for monitor to find AlarmService with LocationService

    // the fake trombone HCD sends a CurrentState event that has the low limit sent
    fakeTromboneHCD.send(am, limitEvent);

    expectNoMsg(FiniteDuration.create(50, TimeUnit.MILLISECONDS)); // A bit of time for processing and update of AlarmService

    // This is checking that the value in the alarm service has been set using admin interface
    CurrentSeverity alarmValue = alarmAdmin.getSeverity(alarmKey).get(10, TimeUnit.SECONDS);
    assertEquals(alarmValue.reported(), Warning);

    // This simulates that the alarm has been cleared
    fakeTromboneHCD.send(am, testClearLimitEvent);

    expectNoMsg(FiniteDuration.create(50, TimeUnit.MILLISECONDS)); // A bit of time

    // use the alarm service admin to see that it is cleared,
    CurrentSeverity alarmValue2 = alarmAdmin.getSeverity(alarmKey).get(10, TimeUnit.SECONDS);
    assertEquals(alarmValue2.reported(), Okay);

    watch(am);
    system.stop(am);
    expectTerminated(am);
  }

  void testLimitAlarm(AlarmKey alarmKey, double limitPosition, double clearPosition) throws Exception {
    // For setting state
    ActorRef tromboneHCD = startHCD();
    TestProbe fakeAssembly = new TestProbe(system);

    // This is checking that the value in the alarm service has been set using admin interface
    setupAlarms();
    alarmService.setSeverity(alarmKey, Okay).get(10, TimeUnit.SECONDS);
    CurrentSeverity alarmValue = alarmAdmin.getSeverity(alarmKey).get(10, TimeUnit.SECONDS);
    logger.info("Initial alarm value should be okay or disconnected");
    assertEquals(alarmValue.reported(), Okay);

    // This is boiler plate for setting up an HCD for testing
    tromboneHCD.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    //info("Running")

    // Create an alarm monitor
    ActorRef am = system.actorOf(TromboneAlarmMonitor.props(tromboneHCD, alarmService));
    expectNoMsg(FiniteDuration.create(1, TimeUnit.SECONDS)); // A delay waiting for alarms to be set?

    // The command handler sends commands to the trombone HCD
    ActorRef ch = newCommandHandler(tromboneHCD, Optional.empty());
    // Give command handler time to subscribe to command state!
    expectNoMsg(FiniteDuration.create(1, TimeUnit.SECONDS));

    ActorRef needToSetStateForMoveCommand = system.actorOf(TromboneStateActor.props());
    try {
      ask(needToSetStateForMoveCommand, new SetState(cmdReady, moveIndexed, false, false), 5000).toCompletableFuture().get();
    } catch (Exception e) {
      logger.error(e, "Error setting state");
    }
    expectNoMsg(FiniteDuration.create(1, TimeUnit.SECONDS));

    // Move to the 0 position
    ch.tell(JSequentialExecutor.ExecuteOne(ac.moveSC(limitPosition), Optional.of(fakeAssembly.ref())), self());
    // Watch for command completion
    CommandStatus result = fakeAssembly.expectMsgClass(FiniteDuration.create(5, TimeUnit.SECONDS), CommandStatus.class);
    logger.info("Result: " + result);
    assert(Completed.equals(result));

    expectNoMsg(FiniteDuration.create(1, TimeUnit.SECONDS)); // A bit of time for processing and update of AlarmService due to move

    // This is checking that the value in the alarm service has been set using admin interface
    CurrentSeverity alarmValue2 = alarmAdmin.getSeverity(alarmKey).get(10, TimeUnit.SECONDS);
    // use the alarm service admin to see that it is cleared,
    assertEquals(alarmValue2.reported(), Warning); // XXX TODO: Test failed here

    // Now move it out of the limit and see that the alarm is cleared
    ch.tell(JSequentialExecutor.ExecuteOne(ac.moveSC(clearPosition), Optional.of(fakeAssembly.ref())), self());
    fakeAssembly.expectMsgClass(FiniteDuration.create(5, TimeUnit.SECONDS), CommandStatus.class);

    expectNoMsg(FiniteDuration.create(1, TimeUnit.SECONDS)); // A bit of time for processing and update of AlarmService

    // This is checking that the value in the alarm service has been set using admin interface
    CurrentSeverity alarmValue3 = alarmAdmin.getSeverity(alarmKey).get(10, TimeUnit.SECONDS);
    assertEquals(alarmValue3.reported(), Okay);

    cleanup(tromboneHCD, ch, needToSetStateForMoveCommand, am);
  }
}

