package csw.examples.vsliceJava.assembly;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.testkit.javadsl.TestKit;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.examples.vsliceJava.TestEnv;
import csw.services.apps.containerCmd.ContainerCmd;
import csw.services.ccs.AssemblyController.Submit;
import csw.services.ccs.CommandStatus.CommandResult;
import csw.services.loc.LocationService;
import csw.services.pkg.SupervisorExternal.SubscribeLifecycleCallback;
import csw.services.sequencer.SequencerEnv;
import csw.util.config.Configurations;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.pkg.JSupervisor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static csw.services.pkg.SupervisorExternal.LifecycleStateChanged;
import static javacsw.services.ccs.JCommandStatus.*;
import static javacsw.services.pkg.JSupervisor.HaltComponent;
import static javacsw.services.pkg.JSupervisor.LifecycleRunning;
import static junit.framework.TestCase.assertEquals;

@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType", "MismatchedReadAndWriteOfArray"})
public class TromboneAssemblyCompTests extends TestKit {
  private static ActorSystem system;
  private static LoggingAdapter logger;
  private static String taName = "lgsTrombone";
  private static String thName = "lgsTromboneHCD";

  private static AssemblyContext assemblyContext = AssemblyTestData.TestAssemblyContext;
  private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

  // List of top level actors that were created for the HCD (for clean up)
  private static List<ActorRef> hcdActors = Collections.emptyList();

  // This def helps to make the test code look more like normal production code, where self() is defined in an actor class
  ActorRef self() {
    return getTestActor();
  }

  public TromboneAssemblyCompTests() {
    super(system);
  }

  @BeforeClass
  public static void setup() throws Exception {
    LocationService.initInterface();
    system = ActorSystem.create("TromboneAssemblyCompTests");
    logger = Logging.getLogger(system, system);
    TestEnv.createTromboneAssemblyConfig(system);

    Thread.sleep(1000); // XXX Give time for location service update so we don't get previous value

    // Starts the HCD used in the test
    Map<String, String> configMap = Collections.singletonMap("", "tromboneHCD.conf");
    ContainerCmd cmd = new ContainerCmd("vsliceJava", new String[]{"--standalone"}, configMap);
    hcdActors = cmd.getActors();
    if (hcdActors.size() == 0) logger.error("Failed to create trombone HCD");
    else System.out.println("Created HCD actor: " + hcdActors);
    Thread.sleep(5000); // XXX FIXME Give time for location service update so we don't get previous value
    SequencerEnv.resolveHcd(thName);
  }

  @AfterClass
  public static void teardown() throws InterruptedException {
    hcdActors.forEach(TromboneAssemblyCompTests::cleanup);
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  // Stop any actors created for a test to avoid conflict with other tests
  private static void cleanup(ActorRef component) {
    TestProbe monitor = new TestProbe(system);
    monitor.watch(component);
    component.tell(HaltComponent, ActorRef.noSender());
    monitor.expectTerminated(component, timeout.duration());
    try {
      Thread.sleep(1000); // XXX FIXME Make sure components have time to unregister from location service
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  ActorRef newTrombone() {
    ActorRef a = JSupervisor.create(assemblyContext.info);
    return a;
  }

  // --- comp tests ---

  @Test
  public void test1() {
    // should just startup
    ActorRef tla = newTrombone();
    TestProbe fakeSequencer = new TestProbe(system);

    tla.tell(new SubscribeLifecycleCallback(fakeSequencer.ref()), self());
    fakeSequencer.expectMsg(duration("10 seconds"), new LifecycleStateChanged(LifecycleRunning));
    fakeSequencer.expectNoMsg(duration("3 seconds")); // wait for connections
    cleanup(tla);
  }

  @Test
  public void test2() {
    // should allow a datum
    ActorRef tla = newTrombone();
    TestProbe fakeSequencer = new TestProbe(system);

    tla.tell(new SubscribeLifecycleCallback(fakeSequencer.ref()), self());
    fakeSequencer.expectMsg(duration("5 seconds"), new LifecycleStateChanged(LifecycleRunning));

    fakeSequencer.expectNoMsg(duration("3 seconds")); // wait for connections

    SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId",
      new SetupConfig(assemblyContext.initCK.prefix()), new SetupConfig(assemblyContext.datumCK.prefix()));

    fakeSequencer.send(tla, new Submit(sca));

    // This first one is the accept/verification
    CommandResult acceptedMsg = fakeSequencer.expectMsgClass(duration("3 seconds"), CommandResult.class);
    assertEquals(acceptedMsg.overall(), Accepted);

    CommandResult completeMsg = fakeSequencer.expectMsgClass(duration("3 seconds"), CommandResult.class);
    assertEquals(completeMsg.overall(), AllCompleted);
    assertEquals(completeMsg.details().status(0), Completed);
    // Wait a bit to see if there is any spurious messages
    fakeSequencer.expectNoMsg(duration("250 milli"));
    logger.info("Msg: " + completeMsg);
    cleanup(tla);
  }

  @Test
  public void test3() {
    // should allow a datum then a set of positions as separate sca
    ActorRef tla = newTrombone();
    TestProbe fakeSequencer = new TestProbe(system);

    tla.tell(new SubscribeLifecycleCallback(fakeSequencer.ref()), self());
    fakeSequencer.expectMsg(duration("20 seconds"), new LifecycleStateChanged(LifecycleRunning));

    fakeSequencer.expectNoMsg(duration("3 seconds")); // wait for connections

    SetupConfigArg datum = Configurations.createSetupConfigArg("testobsId",
      new SetupConfig(assemblyContext.initCK.prefix()), new SetupConfig(assemblyContext.datumCK.prefix()));

    fakeSequencer.send(tla, new Submit(datum));

    // This first one is the accept/verification
    CommandResult acceptedMsg = fakeSequencer.expectMsgClass(duration("3 seconds"), CommandResult.class);
    logger.info("msg1: " + acceptedMsg);
    assertEquals(acceptedMsg.overall(), Accepted);

    CommandResult completeMsg = fakeSequencer.expectMsgClass(duration("3 seconds"), CommandResult.class);
    logger.info("msg2: " + completeMsg);
    assertEquals(completeMsg.overall(), AllCompleted);

    // This will send a config arg with 10 position commands
    int[] testRangeDistance = new int[]{90, 100, 110, 120, 130, 140, 150, 160, 170, 180};
    List<SetupConfig> positionConfigs = Arrays.stream(testRangeDistance).mapToObj(f -> assemblyContext.positionSC(f))
      .collect(Collectors.toList());

    SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId", positionConfigs);
    fakeSequencer.send(tla, new Submit(sca));

    // This first one is the accept/verification
    acceptedMsg = fakeSequencer.expectMsgClass(duration("3 seconds"), CommandResult.class);
    logger.info("msg1: " + acceptedMsg);
    assertEquals(acceptedMsg.overall(), Accepted);

    // Second one is completion of the executed ones - give this some extra time to complete
    completeMsg = fakeSequencer.expectMsgClass(duration("10 seconds"), CommandResult.class);
    logger.info("msg2: " + completeMsg);
    assertEquals(completeMsg.overall(), AllCompleted);
    assertEquals(completeMsg.details().results().size(), sca.configs().size());
    cleanup(tla);
  }
}
