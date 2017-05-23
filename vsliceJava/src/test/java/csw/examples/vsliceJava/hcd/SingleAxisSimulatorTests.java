package csw.examples.vsliceJava.hcd;

import akka.actor.*;
import akka.japi.JavaPartialFunction;
import akka.testkit.javadsl.TestKit;
import akka.testkit.TestActorRef;
import akka.util.Timeout;
import csw.services.loc.LocationService;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;
import csw.examples.vsliceJava.hcd.SingleAxisSimulator.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.*;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.AxisState.AXIS_IDLE;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.AxisState.AXIS_MOVING;
import static org.junit.Assert.*;
import static csw.examples.vsliceJava.hcd.MotionWorker.*;


@SuppressWarnings({"unused", "SameParameterValue", "WeakerAccess"})
public class SingleAxisSimulatorTests extends TestKit {
  private static ActorSystem system;
  Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(60, TimeUnit.SECONDS));

  // This def helps to make the test code look more like normal production code, where self() is defined in an actor class
  private ActorRef self() {
    return getTestActor();
  }

  // For compatibility with Scala tests
  private void it(String s) {
    System.out.println(s);
  }

  public SingleAxisSimulatorTests() {
    super(system);
  }

  @BeforeClass
  public static void setup() {
    LocationService.initInterface();
    system = ActorSystem.create();
  }

  @AfterClass
  public static void teardown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  private Vector<MotionWorkerMsgs> expectLLMoveMsgs(final int start, final int destinationIn, final int delayInMS,
                                                    final boolean diagFlag) {
    Vector<MotionWorkerMsgs> allMsgs = new Vector<>();
    // Get AxisStarted
    allMsgs.add(expectMsgEquals(Start.instance));
    // Receive updates until axis idle then get the last one
    final List<MotionWorkerMsgs> moveMsgs =
      receiveWhile(duration("5 seconds"), in -> {
          if (in instanceof Tick) {
            return (Tick) in;
          } else {
            throw JavaPartialFunction.noMatch();
          }
      });

    MotionWorkerMsgs endMsg = expectMsgClass(End.class); // last one
    allMsgs.addAll(moveMsgs);
    allMsgs.add(endMsg);

    if (diagFlag) System.out.println("LLMoveMsgs: " + allMsgs);
    return allMsgs;
  }


  Vector<AxisUpdate> expectMoveMsgs(boolean diagFlag) {
    Vector<AxisUpdate> allMsgs = new Vector<>();
    // Get AxisStarted
    expectMsgEquals(AxisStarted.instance);
    // Receive updates until axis idle then get the last one
    final List<AxisUpdate> msgs =
      receiveWhile(duration("5 second"), in -> {
          if (in instanceof AxisUpdate && ((AxisUpdate) in).state == AXIS_MOVING) {
            return (AxisUpdate) in;
          } else {
            throw JavaPartialFunction.noMatch();
          }
      });

    AxisUpdate fmsg = expectMsgClass(AxisUpdate.class); // last one
    allMsgs.addAll(msgs);
    allMsgs.add(fmsg);

    if (diagFlag) System.out.println("MoveMsgs: " + allMsgs);
    return allMsgs;
  }


  private Vector<AxisResponse> expectMoveMsgsWithDest(int target, boolean diagFlag) {
    Vector<AxisResponse> allMsgs = new Vector<>();
    // Receive updates until axis idle then get the last one
    final List<AxisResponse> msgs =
      receiveWhile(duration("5 second"), in -> {
          if (in instanceof AxisStarted || in instanceof AxisUpdate && ((AxisUpdate) in).current != target) {
            return (AxisResponse) in;
          } else {
            throw JavaPartialFunction.noMatch();
          }
      });

    AxisUpdate fmsg1 = expectMsgClass(AxisUpdate.class); // last one when target == current
    AxisUpdate fmsg2 = expectMsgClass(AxisUpdate.class); // then the End event with the IDLE
    allMsgs.addAll(msgs);
    allMsgs.add(fmsg1);
    allMsgs.add(fmsg2);

    if (diagFlag) System.out.println("MoveMsgsWithDest: " + allMsgs);
    return allMsgs;
  }


  // Calculates the time to wait for messages with a little extra
  FiniteDuration calcDelay(int numberSteps, int delayInSseconds) {
    return FiniteDuration.create((numberSteps + 1) * delayInSseconds * 1000, TimeUnit.SECONDS);
  }

  @Test
  public void TestingStepsCalc() throws Exception {
    // should calculate different number of steps based on the size of the move
    assertEquals(calcNumSteps(100, 105), 1);
    assertEquals(calcNumSteps(100, 115), 2);
    assertEquals(calcNumSteps(100, 500), 5);
    assertEquals(calcNumSteps(100, 900), 10);


    // should also work with step size
    assertEquals(calcStepSize(100, 105, calcNumSteps(100, 105)), 5);
    assertEquals(calcStepSize(100, 115, calcNumSteps(100, 115)), 7);
    assertEquals(calcStepSize(100, 500, calcNumSteps(100, 500)), 80);
    assertEquals(calcStepSize(100, 900, calcNumSteps(100, 900)), 80);

    // should work with step size of 1 (bug found)
    int steps = calcNumSteps(870, 869);
    assertEquals(steps, 1);
    assertEquals(calcStepSize(870, 869, steps), -1);
  }

  @Test
  public void motionWorkerSetup() throws Exception {
    int testStart = 0;
    int testDestination = 1000;
    int testDelay = 100;

    // should be initialized properly
    Props props = props(testStart, testDestination, testDelay, self(), false);
    final TestActorRef<MotionWorker> ms = TestActorRef.create(system, props);
    final MotionWorker under = ms.underlyingActor();
    assertEquals(under.start, testStart);
    assertEquals(under.destination, testDestination);
    assertEquals(under.delayInNanoSeconds, testDelay * 1000000);
  }

  @Test
  public void motionWorkerForward() throws Exception {
    int testStart = 0;
    int testDestination = 1005;
    int testDelay = 10;

    // should allow simulation on increasing encoder steps
    Props props = props(testStart, testDestination, testDelay, self(), false);
    final TestActorRef<MotionWorker> ms = TestActorRef.create(system, props);
    ms.tell(Start.instance, self());
    Vector<MotionWorkerMsgs> msgs = expectLLMoveMsgs(testStart, testDestination, testDelay, false);
    assertEquals(msgs.lastElement(), new End(testDestination));
  }

  @Test
  public void motionWorkerReverse() throws Exception {
    int testStart = 1000;
    int testDestination = -110;
    int testDelay = 10;

    // should allow creation based on negative encoder steps
    Props props = props(testStart, testDestination, testDelay, self(), false);
    final TestActorRef<MotionWorker> ms = TestActorRef.create(system, props);
    ms.tell(Start.instance, self());
    Vector<MotionWorkerMsgs> msgs = expectLLMoveMsgs(testStart, testDestination, testDelay, false);
    assertEquals(msgs.lastElement(), new End(testDestination));
  }

  @Test
  public void simulateContinuousMotionWithMotionWorker() throws Exception {
    int testStart = 500;
    int testDestination = 600;
    int testDelay = 10;

    Props props = props(testStart, testDestination, testDelay, self(), false);
    final TestActorRef<MotionWorker> ms = TestActorRef.create(system, props);
    ms.tell(Start.instance, self());
    Vector<MotionWorkerMsgs> msgs = expectLLMoveMsgs(testStart, testDestination, testDelay, false);
    assertEquals(msgs.lastElement(), new End(testDestination));
  }

  @Test
  public void motionWorkerCancel() throws Exception {
    int testStart = 0;
    int testDestination = 1000;
    int testDelay = 200;

    it("should allow cancelling after a few steps");
    {
      Props props = props(testStart, testDestination, testDelay, self(), false);
      final TestActorRef<MotionWorker> ms = TestActorRef.create(system, props);
      ms.tell(Start.instance, self());
      expectMsgEquals(Start.instance);
      // Wait 3 messages
      receiveN(3, calcDelay(3, testDelay));
      ms.tell(Cancel.instance, self());
      // One more move
      receiveN(1);
      expectMsgClass(End.class);
    }
  }


  String defaultAxisName = "test";
  int defaultLowLimit = 100;
  int defaultLowUser = 200;
  int defaultHighUser = 1200;
  int defaultHighLimit = 1300;
  int defaultHome = 300;
  int defaultStartPosition = 350;
  int defaultStepDelayMS = 5;
  String defaultStatusPrefix = "test.axisStatus";


  AxisConfig defaultAxisConfig = new AxisConfig(defaultAxisName,
    defaultLowLimit,
    defaultLowUser,
    defaultHighUser,
    defaultHighLimit,
    defaultHome,
    defaultStartPosition,
    defaultStepDelayMS);

  TestActorRef<SingleAxisSimulator> defaultAxis(ActorRef replyTo) {
    Props props = SingleAxisSimulator.props(defaultAxisConfig, Optional.of(replyTo));
    return TestActorRef.create(system, props); // No name here since can't create actors with the same name
  }

  @Test
  public void testSingleAxis() throws Exception {
    it("should be creatable and initialize");
    {
      TestActorRef<SingleAxisSimulator> sa = defaultAxis(getTestActor());
      assertEquals(sa.underlyingActor().axisConfig, defaultAxisConfig);
      sa.tell(PoisonPill.getInstance(), self());
    }

    it("limitMove should clamp value");
    {
      AxisConfig ac = defaultAxisConfig;

      // Acceptable
      assertEquals(limitMove(ac, 200), 200);
      // Low limit
      assertEquals(limitMove(ac, 0), ac.lowLimit);

      // High limit
      assertEquals(limitMove(ac, 2000), ac.highLimit);

      // Check "limit" checks > or < user limits
      assertEquals(isHighLimit(ac, ac.home), false);
      assertEquals(isHighLimit(ac, ac.highUser - 1), false);
      assertEquals(isHighLimit(ac, ac.highUser), true);
      assertEquals(isHighLimit(ac, ac.highLimit), true);

      assertEquals(isLowLimit(ac, ac.home), false);
      assertEquals(isLowLimit(ac, ac.lowUser + 1), false);
      assertEquals(isLowLimit(ac, ac.lowUser), true);
      assertEquals(isLowLimit(ac, ac.lowLimit), true);

      isHomed(ac, ac.home);
    }

    it("Should init properly");
    {
      TestActorRef<SingleAxisSimulator> sa = defaultAxis(getTestActor());
      // Expect an initial axis status message
      // AxisUpdate one = expectMsgClass(AxisUpdate.class);

      sa.tell(Datum.instance, self());
      expectMsgEquals(AxisStarted.instance);
      AxisUpdate upd = expectMsgClass(AxisUpdate.class);
      assertEquals(upd.state, AXIS_IDLE);
      assertEquals(upd.current, defaultAxisConfig.startPosition + 1);

      sa.tell(GetStatistics.instance, self());
      AxisStatistics stats1 = expectMsgClass(AxisStatistics.class);
      assertEquals(stats1.initCount, 1);
      assertEquals(stats1.moveCount, 1);
      assertEquals(stats1.homeCount, 0);
      assertEquals(stats1.limitCount, 0);
      assertEquals(stats1.successCount, 1);
      assertEquals(stats1.failureCount, 0);
      assertEquals(stats1.cancelCount, 0);

      sa.tell(PoisonPill.getInstance(), self());
    }

    it("Should home properly");
    {
      TestActorRef<SingleAxisSimulator> sa = defaultAxis(getTestActor());

      sa.tell(Home.instance, self());


      Vector<AxisUpdate> allMsgs = new Vector<>();
      // Get AxisStarted
      expectMsgEquals(AxisStarted.instance);
      // Receive updates until axis idle then get the last one
      final List<AxisUpdate> msgs =
        receiveWhile(duration("5 second"), in -> {
            if (in instanceof AxisUpdate && ((AxisUpdate) in).state == AXIS_MOVING) {
              return (AxisUpdate) in;
            } else {
              throw JavaPartialFunction.noMatch();
            }
        });
      AxisUpdate fmsg = expectMsgClass(AxisUpdate.class); // last one
      allMsgs.addAll(msgs);
      allMsgs.add(fmsg);

      // System.out.println("MoveMsgs: " + allMsgs);

      assertEquals(allMsgs.lastElement().state, AXIS_IDLE);
      assertEquals(allMsgs.lastElement().inHomed, true);
      assertEquals(allMsgs.lastElement().current, defaultAxisConfig.home);

      assertEquals(sa.underlyingActor().current, defaultAxisConfig.home);

      sa.tell(GetStatistics.instance, self());
      AxisStatistics stats1 = expectMsgClass(AxisStatistics.class);
      assertEquals(stats1.initCount, 0);
      assertEquals(stats1.moveCount, 1);
      assertEquals(stats1.homeCount, 1);
      assertEquals(stats1.limitCount, 0);
      assertEquals(stats1.successCount, 1);
      assertEquals(stats1.failureCount, 0);
      assertEquals(stats1.cancelCount, 0);

      sa.tell(PoisonPill.getInstance(), self());
    }

    it("Should move properly");
    {
      TestActorRef<SingleAxisSimulator> sa = defaultAxis(getTestActor());
      sa.tell(new Move(500, false), self());
      Vector<AxisUpdate> msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().state, AXIS_IDLE);
      assertEquals(msgs.lastElement().current, 500);
      assertEquals(sa.underlyingActor().current, 500);
      sa.tell(PoisonPill.getInstance(), self());
    }


    it("Should move and update");
    {
      TestActorRef<SingleAxisSimulator> sa = defaultAxis(getTestActor());

      // Sleeps are to try and not do all the updates up front before movement starts
      sa.tell(new Move(360), self());
      Thread.sleep(30);
      sa.tell(new Move(365), self());
      Thread.sleep(20);
      sa.tell(new Move(390), self());
      Thread.sleep(30);
      sa.tell(new Move(420), self());
      Thread.sleep(20);
      sa.tell(new Move(425), self());

      Vector<AxisResponse> msgs = expectMoveMsgsWithDest(425, false);
      assertTrue(msgs.lastElement() instanceof AxisUpdate);
      AxisUpdate last = (AxisUpdate) msgs.lastElement();
      assertEquals(last.state, AXIS_IDLE);
      assertEquals(last.current, 425);

      sa.tell(PoisonPill.getInstance(), self());
    }

    it("Should allow a cancel");
    {
      TestActorRef<SingleAxisSimulator> sa = defaultAxis(getTestActor());

      sa.tell(new Move(850), self());
      expectMsgEquals(AxisStarted.instance);
      // Wait 2 updates
      receiveN(2);
      sa.tell(CancelMove.instance, self());
      // One more update due to algo
      Object lastmsg = receiveN(1);
      AxisUpdate end = expectMsgClass(AxisUpdate.class);
      assertEquals(end.state, AXIS_IDLE);
      assertEquals(end.current, 650);

      sa.tell(PoisonPill.getInstance(), self());
    }

    it("should limit out-of-range moves");
    {
      TestActorRef<SingleAxisSimulator> sa = defaultAxis(getTestActor());

      // Position starts out at 0
      sa.tell(new Move(0), self());
      Vector<AxisUpdate> msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().state, AXIS_IDLE);
      assertEquals(msgs.lastElement().current, 100);
      assertEquals(msgs.lastElement().inLowLimit, true);

      assertEquals(sa.underlyingActor().current, defaultAxisConfig.lowLimit);
      assertEquals(sa.underlyingActor().inLowLimit, true);
      assertEquals(sa.underlyingActor().inHighLimit, false);

      sa.tell(GetStatistics.instance, self());
      AxisStatistics stats1 = expectMsgClass(AxisStatistics.class);
      assertEquals(stats1.initCount, 0);
      assertEquals(stats1.moveCount, 1);
      assertEquals(stats1.homeCount, 0);
      assertEquals(stats1.limitCount, 1);
      assertEquals(stats1.successCount, 1);
      assertEquals(stats1.failureCount, 0);
      assertEquals(stats1.cancelCount, 0);

      sa.tell(new Move(2000), self());
      Vector<AxisUpdate> msgs2 = expectMoveMsgs(false);
      assertEquals(msgs2.lastElement().state, AXIS_IDLE);
      assertEquals(msgs2.lastElement().current, 1300);
      assertEquals(msgs2.lastElement().inLowLimit, false);
      assertEquals(msgs2.lastElement().inHighLimit, true);

      assertEquals(sa.underlyingActor().current, defaultAxisConfig.highLimit);
      assertEquals(sa.underlyingActor().inLowLimit, false);
      assertEquals(sa.underlyingActor().inHighLimit, true);

      sa.tell(GetStatistics.instance, self());
      AxisStatistics stats2 = expectMsgClass(AxisStatistics.class);
      assertEquals(stats2.initCount, 0);
      assertEquals(stats2.moveCount, 2);
      assertEquals(stats2.homeCount, 0);
      assertEquals(stats2.limitCount, 2);
      assertEquals(stats2.successCount, 2);
      assertEquals(stats2.failureCount, 0);
      assertEquals(stats2.cancelCount, 0);

      sa.tell(PoisonPill.getInstance(), self());
    }

    it("should support a complex example");
    {
      TestActorRef<SingleAxisSimulator> sa = defaultAxis(getTestActor());

      // Starts at 350, init (351), go home, go to 423, 800, 560, highlmit at 1240, then home
      sa.tell(Datum.instance, self());
      Vector<AxisUpdate> msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().current, defaultAxisConfig.startPosition + 1);

      sa.tell(Home.instance, self());
      msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().current, defaultAxisConfig.home);

      sa.tell(new Move(423), self());
      msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().current, 423);

      sa.tell(new Move(800), self());
      msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().current, 800);

      sa.tell(new Move(560), self());
      msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().current, 560);

      sa.tell(new Move(1240), self());
      msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().current, 1240);

      sa.tell(Home.instance, self());
      msgs = expectMoveMsgs(false);
      assertEquals(msgs.lastElement().current, defaultAxisConfig.home);

      sa.tell(GetStatistics.instance, self());
      AxisStatistics stats2 = expectMsgClass(AxisStatistics.class);
      assertEquals(stats2.initCount, 1);
      assertEquals(stats2.moveCount, 7);
      assertEquals(stats2.homeCount, 2);
      assertEquals(stats2.limitCount, 1);
      assertEquals(stats2.successCount, 7);
      assertEquals(stats2.failureCount, 0);
      assertEquals(stats2.cancelCount, 0);

      sa.tell(PoisonPill.getInstance(), self());
    }
  }
}
