package csw.examples.vsliceJava.hcd;


import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.actor.ActorSystem;
import akka.japi.JavaPartialFunction;
import akka.testkit.javadsl.TestKit;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.examples.vsliceJava.TestEnv;
import csw.services.loc.LocationService;
import csw.services.pkg.Component.HcdInfo;
import csw.services.pkg.Supervisor;
import csw.services.pkg.SupervisorExternal.*;
import csw.util.param.Parameters.Setup;
import csw.util.param.StateVariable.CurrentState;
import javacsw.services.ccs.JHcdController;
import javacsw.services.loc.JConnectionType;
import javacsw.services.pkg.JComponent;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import static csw.examples.vsliceJava.hcd.TromboneHCD.TromboneEngineering.GetAxisConfig;
import static csw.examples.vsliceJava.hcd.TromboneHCD.*;
import static csw.examples.vsliceJava.hcd.TromboneHCD.TromboneEngineering.GetAxisStats;
import static javacsw.util.param.JParameters.*;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static javacsw.services.pkg.JSupervisor.*;
import static org.junit.Assert.*;

@SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "unused", "WeakerAccess"})
public class TromboneHCDCompTests extends TestKit {
  private static LoggingAdapter log;
  private static ActorSystem system;
  Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(60, TimeUnit.SECONDS));

  // This def helps to make the test code look more like normal production code, where self() is defined in an actor class
  ActorRef self() {
    return getTestActor();
  }

  // For compatibility with Scala tests
  void it(String s) {
    System.out.println(s);
  }

  public TromboneHCDCompTests() {
    super(system);
    log = Logging.getLogger(system, this);
  }

  @BeforeClass
  public static void setup() throws ExecutionException, InterruptedException {
    LocationService.initInterface();
    system = ActorSystem.create();
    TestEnv.createTromboneHcdConfig(system);
  }

  @AfterClass
  public static void teardown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  HcdInfo testInfo = JComponent.hcdInfo(
    TromboneHCD.componentName,
    TromboneHCD.trombonePrefix,
    TromboneHCD.componentClassName,
    JComponent.DoNotRegister,
    Collections.singleton(JConnectionType.AkkaType),
    FiniteDuration.create(1, "second"));

  String troboneAssemblyPrefix = "nfiraos.ncc.trombone";


  ActorRef startHCD() {
    return Supervisor.apply(testInfo);
  }

  // Stop any actors created for a test to avoid conflict with other tests
  private void cleanup(ActorRef component) {
    TestProbe monitor = new TestProbe(system);
    monitor.watch(component);
    component.tell(HaltComponent, ActorRef.noSender());
    monitor.expectTerminated(component, timeout.duration());
  }


  @SuppressWarnings("Duplicates")
  Vector<CurrentState> waitForMoveMsgs() {
    final List<CurrentState> msgs =
      receiveWhile(duration("5 seconds"), in -> {
          if (in instanceof CurrentState) {
            CurrentState cs = (CurrentState) in;
            if (cs.prefix().contains(TromboneHCD.axisStatePrefix) && jvalue(jitem(cs, stateKey)).name().equals(AXIS_MOVING.name())) {
              return cs;
            }
            // This is present to pick up the first status message
            if (cs.prefix().equals(TromboneHCD.axisStatsPrefix)) {
              return cs;
            }
          }
          throw JavaPartialFunction.noMatch();
      });

    CurrentState fmsg = expectMsgClass(CurrentState.class); // last one

    Vector<CurrentState> allmsgs = new Vector<>(msgs);
    allmsgs.add(fmsg);
    return allmsgs;
  }

  @Test
  public void shouldAllowFetchingStats() throws Exception {
    it("should allow fetching stats");
    ActorRef hcd = startHCD();

    TestProbe fakeAssembly = new TestProbe(system);

    hcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    log.info("Running");

    fakeAssembly.send(hcd, JHcdController.Subscribe);
    fakeAssembly.send(hcd, GetAxisStats);

    CurrentState stats = fakeAssembly.expectMsgClass(CurrentState.class);
    System.out.println("AxisStats: " + stats);
    assertEquals(jvalue(jitem(stats, datumCountKey)).intValue(), 0);
    assertEquals(jvalue(jitem(stats, moveCountKey)).intValue(), 0);
    assertEquals(jvalue(jitem(stats, homeCountKey)).intValue(), 0);
    assertEquals(jvalue(jitem(stats, limitCountKey)).intValue(), 0);
    assertEquals(jvalue(jitem(stats, successCountKey)).intValue(), 0);
    assertEquals(jvalue(jitem(stats, failureCountKey)).intValue(), 0);
    assertEquals(jvalue(jitem(stats, cancelCountKey)).intValue(), 0);

    hcd.tell(JHcdController.Unsubscribe, self());

    cleanup(hcd);
    log.info("Done");
  }


  @Test
  public void shouldAllowFetchingConfig() throws Exception {
    it("should allow fetching config");
    ActorRef hcd = startHCD();

    TestProbe fakeAssembly = new TestProbe(system);

    hcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
    fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));
    log.info("Running");

    fakeAssembly.send(hcd, JHcdController.Subscribe);
    fakeAssembly.send(hcd, GetAxisConfig);

    // The values are hard-coded because we can't look at the config inside the actor, will fail if config changes
    CurrentState config = fakeAssembly.expectMsgClass(CurrentState.class);
    System.out.println("AxisConfig: " + config);
    assertEquals(jvalue(jitem(config, axisNameKey)), TromboneHCD.tromboneAxisName);
    assertEquals(jvalue(jitem(config, lowLimitKey)).intValue(), 100);
    assertEquals(jvalue(jitem(config, lowUserKey)).intValue(), 200);
    assertEquals(jvalue(jitem(config, highUserKey)).intValue(), 1200);
    assertEquals(jvalue(jitem(config, highLimitKey)).intValue(), 1300);
    assertEquals(jvalue(jitem(config, homeValueKey)).intValue(), 300);
    assertEquals(jvalue(jitem(config, startValueKey)).intValue(), 350);

    fakeAssembly.send(hcd, JHcdController.Unsubscribe);
    cleanup(hcd);
    log.info("Done");
  }


  @Test
  public void shouldAcceptAnInit() throws Exception {
    it("should accept an init");
    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    hcd.tell(new SubscribeLifecycleCallback(getRef()), self());
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));
    log.info("Running");

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);
    send(hcd, new Submit(datumSC));

    Vector<CurrentState> msgs = waitForMoveMsgs();
    //msgs.last(positionKey).head should equal(tla.underlyingActor.axisConfig.startPosition + 1) // Init position is one off the start position
    log.info("Msgs: " + msgs);

    send(hcd, GetAxisStats);
    CurrentState stats = expectMsgClass(CurrentState.class);
    System.out.println("Stats: " + stats);
    assertEquals(stats.prefix(), TromboneHCD.axisStatsCK);
    assertEquals(jvalue(jitem(stats, datumCountKey)).intValue(), 1);
    assertEquals(jvalue(jitem(stats, moveCountKey)).intValue(), 1);

    send(hcd, JHcdController.Unsubscribe);
    cleanup(hcd);
    log.info("Done");
  }

  @Test
  public void shouldAllowHoming() throws Exception {
    it("should allow homing");

    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    hcd.tell(new SubscribeLifecycleCallback(getRef()), self());
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));
    log.info("Running");

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);

    // Being done this way to ensure Prefix equality works
    Setup sc = Setup(axisHomePrefix);
    send(hcd, new Submit(sc));

    Vector<CurrentState> msgs = waitForMoveMsgs();
    log.info("Msgs: " + msgs);

    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), 300);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHomeKey)), true);
    assertEquals(jvalue(jitem(msgs.lastElement(), inLowLimitKey)), false);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHighLimitKey)), false);

    send(hcd, GetAxisStats);
    CurrentState stats = expectMsgClass(CurrentState.class);
    log.info("Stats: " + stats);
    assertEquals(stats.prefix(), TromboneHCD.axisStatsCK);
    assertEquals(stats.parameter(homeCountKey).head(), 1);
    assertEquals(stats.parameter(moveCountKey).head(), 1);
    send(hcd, JHcdController.Unsubscribe);

    cleanup(hcd);
    log.info("Done");
  }

  @Test
  public void shouldAllowAShortMove() throws Exception {
    it("should allow a short move");

    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    hcd.tell(new SubscribeLifecycleCallback(getRef()), self());
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));
    log.info("Running");

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);

    // Being done this way to ensure Prefix equality works
    int testPos = 500;

    send(hcd, new Submit(positionSC(testPos)));

    Vector<CurrentState> msgs = waitForMoveMsgs();
    // Check the last message
    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), testPos);
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);

    send(hcd, JHcdController.Unsubscribe);

    cleanup(hcd);
    log.info("Done");
  }

  @Test
  public void shouldShowEnteringALowLimit() throws Exception {
    it("should show entering a low limit");
    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    hcd.tell(new SubscribeLifecycleCallback(getRef()), self());
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));
    log.info("Running");

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);

    int testPos = 0;

    send(hcd, new Submit(positionSC(testPos)));

    int lowLimit = 100; // Note this will fail if axisConfig is changed

    Vector<CurrentState> msgs = waitForMoveMsgs();
    // Check the last message
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);
    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), lowLimit);
    assertEquals(jvalue(jitem(msgs.lastElement(), inLowLimitKey)), true);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHighLimitKey)), false);

    send(hcd, JHcdController.Unsubscribe);

    cleanup(hcd);
    log.info("Done");
  }

  @Test
  public void shouldShowEnteringAHighLimit() throws Exception {
    it("should show entering a high limit");
    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    hcd.tell(new SubscribeLifecycleCallback(getRef()), self());
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));
    log.info("Running");

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);

    int testPos = 3000;
    int highLimit = 1300; // Note this will fail if axisConfig is changed

    send(hcd, new Submit(positionSC(testPos)));

    Vector<CurrentState> msgs = waitForMoveMsgs();
    // Check the last message
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);
    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), highLimit);
    assertEquals(jvalue(jitem(msgs.lastElement(), inLowLimitKey)), false);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHighLimitKey)), true);

    send(hcd, JHcdController.Unsubscribe);

    cleanup(hcd);
    log.info("Done");
  }

  @Test
  public void shouldAllowComplexSeriesOfMoves() throws Exception {
    it("should allow complex series of moves");
    // Starts at 350, init (351), go home, go to 423, 800, 560, highlmit at 1240, then home
    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    send(hcd, new SubscribeLifecycleCallback(getRef()));
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));
    log.info("Running");

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);

    // Move 1
    send(hcd, new Submit(Setup(axisDatumPrefix))); // Could use ones in TromboneHCD
    Vector<CurrentState> msgs = waitForMoveMsgs();
    assertEquals(jvalue(jitem(msgs.lastElement(), inHomeKey)), false);

    // Move 2
    send(hcd, new Submit(homeSC));
    msgs = waitForMoveMsgs();
    assertEquals(jvalue(jitem(msgs.lastElement(), inHomeKey)), true);

    // Move 3
    int testPos = 423;
    send(hcd, new Submit(positionSC(testPos)));
    msgs = waitForMoveMsgs();
    // Check the last message

    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), testPos);
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHomeKey)), false);
    assertEquals(jvalue(jitem(msgs.lastElement(), inLowLimitKey)), false);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHighLimitKey)), false);

    // Move 4
    testPos = 800;
    send(hcd, new Submit(positionSC(testPos)));
    msgs = waitForMoveMsgs();
    // Check the last message
    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), testPos);
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);

    // Move 5
    testPos = 1240;
    send(hcd, new Submit(positionSC(testPos)));
    msgs = waitForMoveMsgs();
    // Check the last message
    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), testPos);
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);
    assertEquals(jvalue(jitem(msgs.lastElement(), inLowLimitKey)), false);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHighLimitKey)), true);

    // Move 6
    send(hcd, new Submit(homeSC));
    msgs = waitForMoveMsgs();
    assertEquals(jvalue(jitem(msgs.lastElement(), inHomeKey)), true);
    assertEquals(jvalue(jitem(msgs.lastElement(), inLowLimitKey)), false);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHighLimitKey)), false);

    // Get summary stats
    send(hcd, GetAxisStats);
    CurrentState stats = expectMsgClass(CurrentState.class);
    //println("Stats: " + stats)
    assertEquals(stats.prefix(), TromboneHCD.axisStatsCK);
    assertEquals(jvalue(jitem(stats, datumCountKey)).intValue(), 1);
    assertEquals(jvalue(jitem(stats, moveCountKey)).intValue(), 6);
    assertEquals(jvalue(jitem(stats, homeCountKey)).intValue(), 2);
    assertEquals(jvalue(jitem(stats, limitCountKey)).intValue(), 1);
    assertEquals(jvalue(jitem(stats, successCountKey)).intValue(), 6);
    assertEquals(jvalue(jitem(stats, failureCountKey)).intValue(), 0);
    assertEquals(jvalue(jitem(stats, cancelCountKey)).intValue(), 0);

    send(hcd, JHcdController.Unsubscribe);

    cleanup(hcd);
    log.info("Done");
  }

  @Test
  public void startUpAMoveAndCancelIt() throws Exception {
    it("start up a move and cancel it");
    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    send(hcd, new SubscribeLifecycleCallback(getRef()));
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);

    int testPos = 1000;

    send(hcd, new Submit(positionSC(testPos)));

    // wait for 2 updates
    receiveN(2);
    send(hcd, new Submit(cancelSC));
    Vector<CurrentState> msgs = waitForMoveMsgs();
    // Check the last message
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);

    // Get summary stats
    send(hcd, GetAxisStats);
    CurrentState stats = expectMsgClass(CurrentState.class);
    //println("Stats: " + stats)
    assertEquals(stats.prefix(), TromboneHCD.axisStatsCK);
    assertEquals(jvalue(jitem(stats, moveCountKey)).intValue(), 1);
    assertEquals(jvalue(jitem(stats, successCountKey)).intValue(), 1);
    assertEquals(jvalue(jitem(stats, cancelCountKey)).intValue(), 1);

    send(hcd, JHcdController.Unsubscribe);

    cleanup(hcd);
    log.info("Done");
  }

  @Test
  public void shouldAllowRepetitiveMoves() throws Exception {
    it("should allow repetitive moves");
    // Starts at 350, init (351), go home, small moves repeating */
    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    send(hcd, new SubscribeLifecycleCallback(getRef()));
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);

    // Init 1
    send(hcd, new Submit(Setup(axisDatumCK))); // Could use ones in TromboneHCD
    Vector<CurrentState> msgs = waitForMoveMsgs();
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);

    // Move 2
    send(hcd, new Submit(homeSC));
    msgs = waitForMoveMsgs();
    assertEquals(jvalue(jitem(msgs.lastElement(), inHomeKey)), true);

    int start = 300;
    int finish = 500;
    int stepSize = 10;
    for (int loops = 1; loops <= 2; loops++) {
      log.info("Loop: " + loops);
      for (int testPos = start; testPos <= finish; testPos += stepSize) {
        send(hcd, new Submit(positionSC(testPos)));
        waitForMoveMsgs();
      }
    }
    cleanup(hcd);
  }

  @Test
  public void shouldDriveIntoLimits() throws Exception {
    it("should drive into limits");
    // Starts at 350, goes to zero */
    ActorRef hcd = startHCD();

//      val fakeAssembly = TestProbe()
    // Note: Can't use receiveWhile with a TestProbe in Java, so using inherited TestProbe here!

    send(hcd, new SubscribeLifecycleCallback(getRef()));
    expectMsgEquals(new LifecycleStateChanged(LifecycleRunning));

    // Currently can't subscribe unless in Running state because controllerReceive has process
    send(hcd, JHcdController.Subscribe);

    // Get the axis config  for testing limits
    send(hcd, GetAxisConfig);

    // The values are hard-coded because we can't look at the config inside the actor, will fail if config changes
    CurrentState config = expectMsgClass(CurrentState.class);
    int lowLimit = jvalue(jitem(config, lowLimitKey));
    int highLimit = jvalue(jitem(config, highLimitKey));

    // Move to 0
    int testPos = 0;
    send(hcd, new Submit(positionSC(testPos)));
    Vector<CurrentState> msgs = waitForMoveMsgs();
    // Check the last message
    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), lowLimit);
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);
    assertEquals(jvalue(jitem(msgs.lastElement(), inLowLimitKey)), true);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHighLimitKey)), false);

    // Move to 2000
    testPos = 2000;
    send(hcd, new Submit(positionSC(testPos)));
    msgs = waitForMoveMsgs();
    // Check the last message
    assertEquals(jvalue(jitem(msgs.lastElement(), positionKey)).intValue(), highLimit);
    assertEquals(jvalue(jitem(msgs.lastElement(), stateKey)), AXIS_IDLE);
    assertEquals(jvalue(jitem(msgs.lastElement(), inLowLimitKey)), false);
    assertEquals(jvalue(jitem(msgs.lastElement(), inHighLimitKey)), true);
    cleanup(hcd);
  }
}
