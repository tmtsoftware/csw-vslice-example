package csw.examples.vsliceJava.hcd;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.Patterns;
import akka.util.Timeout;
import csw.services.loc.ComponentType;
import csw.services.pkg.Component;
import csw.services.pkg.Supervisor;
import csw.util.param.*;
import javacsw.services.ccs.JHcdController;
import javacsw.services.cs.akka.JConfigServiceClient;
import javacsw.services.loc.JComponentType;
import javacsw.services.pkg.*;
import scala.PartialFunction;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import static javacsw.util.param.JParameters.*;
import static javacsw.util.param.JParameterSetDsl.*;
import static javacsw.util.param.JUnitsOfMeasure.encoder;
import static javacsw.services.pkg.JSupervisor.*;

import static csw.util.param.Parameters.Setup;
import static csw.util.param.Parameters.Prefix;
import static csw.util.param.StateVariable.CurrentState;

import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.AxisState;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.AxisStarted;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.AxisStatistics;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.AxisUpdate;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.InitialState;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.GetStatistics;
import static csw.examples.vsliceJava.hcd.SingleAxisSimulator.PublishAxisUpdate;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * TromboneHCD -- This is the Top Level Actor for the TromboneHCD
 * It's main responsibilties are to interact with the trombone HCD axis (a simulator)
 * It also:
 * - initializes itself from Configiuration Service
 * - Works with the Supervisor to implement the lifecycle
 * - Handles incoming commands
 * - Generates CurrentState for the Assembly
 */
@SuppressWarnings({"unused", "CodeBlock2Expr", "OptionalUsedAsFieldOrParameterType", "WeakerAccess", "ConstantConditions"})
public class TromboneHCD extends JHcdController {
  private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  // Initialize axis from ConfigService
  AxisConfig axisConfig;

  // Create an axis for simulating trombone motion
  ActorRef tromboneAxis;

  // Initialize values -- This causes an update to the listener
  private final Timeout timeout = new Timeout(Duration.create(2, "seconds"));


  // The current axis position from the hardware axis, initialize to default value
  AxisUpdate current;
  AxisStatistics stats;

  private final ActorRef supervisor;


  // Actor constructor: use the props() method to create the actor.
  private TromboneHCD(final Component.HcdInfo info, ActorRef supervisor) throws Exception {

    this.supervisor = supervisor;

    // Note: The scala version initializes the following variables in a non-blocking way using a for comprehension.
    // It should be possible to do something similar in Java, but it would be more complicated.

    try {
      // Initialize axis from ConfigService
      axisConfig = getAxisConfig().get();

      // Create an axis for simulating trombone motion
      tromboneAxis = setupAxis(axisConfig);

      // The current axis position from the hardware axis, initialize to default value
      current = (AxisUpdate) Await.result(Patterns.ask(tromboneAxis, InitialState.instance, timeout), timeout.duration());
      stats = (AxisStatistics) Await.result(Patterns.ask(tromboneAxis, GetStatistics.instance, timeout), timeout.duration());

      // Required setup for Lifecycle in order to get messages
      supervisor.tell(Initialized, self());
    } catch (Exception ex) {
      supervisor.tell(new Supervisor.InitializeFailure(ex.getMessage()), self());
    }
  }

  @Override
  public Receive createReceive() {
    return initializingReceive();
  }

  private Receive initializingReceive() {
    Receive r = receiveBuilder().
        matchEquals(Running, e -> {
          // When Running is received, transition to running Receive
          log.debug("received Running");
          getContext().become(runningReceive());
        }).
        matchAny(x -> log.warning("Unexpected message in TromboneHCD (Not running yet): " + x)).
        build();
    return jPublisherReceive().orElse(r);
  }


  private Receive runningReceive() {
    return jControllerReceive().orElse(receiveBuilder()
      .matchEquals(Running, e -> {
        log.info("Received Running");
      })
      .matchEquals(RunningOffline, e -> {
        log.info("Received RunningOffline");
      })
      .matchEquals(DoRestart, e -> {
        log.info("Received DoRestart");
      })
      .matchEquals(DoShutdown, e -> {
        log.info("Received DoShutdown");
        // Just say complete for now
        supervisor.tell(ShutdownComplete, self());
      })
      .match(Supervisor.LifecycleFailureInfo.class, e -> {
        log.error("Received failed state: " + e.state() + " for reason: " + e.reason());
      })
      .matchEquals(TromboneEngineering.GetAxisStats, e -> {
        tromboneAxis.tell(GetStatistics.instance, self());
      })
      .matchEquals(TromboneEngineering.GetAxisUpdate, e -> {
        tromboneAxis.tell(PublishAxisUpdate.instance, self());
      })
      .matchEquals(TromboneEngineering.GetAxisUpdateNow, e -> {
        sender().tell(current, self());
      })
      .match(AxisStarted.class, e -> {
        // println("Axis Started")
      })
      .matchEquals(TromboneEngineering.GetAxisConfig, e -> {
        CurrentState axisConfigState = jadd(defaultConfigState,
          jset(lowLimitKey, axisConfig.lowLimit),
          jset(lowUserKey, axisConfig.lowUser),
          jset(highUserKey, axisConfig.highUser),
          jset(highLimitKey, axisConfig.highLimit),
          jset(homeValueKey, axisConfig.home),
          jset(startValueKey, axisConfig.startPosition),
          jset(stepDelayMSKey, axisConfig.stepDelayMS)
        );
        notifySubscribers(axisConfigState);
      })
      .match(AxisUpdate.class, e -> {
        //log.info(s"Axis Update: $au")
        // Update actor state
        current = e;
        CurrentState tromboneAxisState = jadd(defaultAxisState,
          jset(positionKey, e.current).withUnits(encoder),
          jset(stateKey, Choice(e.state.toString())),
          jset(inLowLimitKey, e.inLowLimit),
          jset(inHighLimitKey, e.inHighLimit),
          jset(inHomeKey, e.inHomed)
        );
        notifySubscribers(tromboneAxisState);
      })
      .match(AxisStatistics.class, e -> {
        log.debug("AxisStatus: " + e);
        // Update actor statistics
        stats = e;
        CurrentState tromboneStats = jadd(defaultStatsState,
          jset(datumCountKey, e.initCount),
          jset(moveCountKey, e.moveCount),
          jset(limitCountKey, e.limitCount),
          jset(homeCountKey, e.homeCount),
          jset(successCountKey, e.successCount),
          jset(failureCountKey, e.failureCount),
          jset(cancelCountKey, e.cancelCount)
        );
        notifySubscribers(tromboneStats);
      })
      .matchAny(x -> log.warning("Unexpected message in TromboneHCD:unhandledPF: " + x))
      .build());
  }

  /**
   * @param sc the config received
   */
  @Override
  public void process(Setup sc) {
//    import TromboneHCD._

    log.debug("Trombone process received sc: " + sc);

    Prefix prefix = sc.prefix();
    if (prefix.equals(axisMoveCK)) {
      tromboneAxis.tell(new SingleAxisSimulator.Move(jvalue(jitem(sc, positionKey)), true), self());
    } else if (prefix.equals(axisDatumCK)) {
      log.info("Received Datum");
      tromboneAxis.tell(SingleAxisSimulator.Datum.instance, self());
    } else if (prefix.equals(axisHomeCK)) {
      tromboneAxis.tell(SingleAxisSimulator.Home.instance, self());
    } else if (prefix.equals(axisCancelCK)) {
      tromboneAxis.tell(SingleAxisSimulator.CancelMove.instance, self());
    }
  }

  private ActorRef setupAxis(AxisConfig ac) {
    return getContext().actorOf(SingleAxisSimulator.props(ac, Optional.of(self())), "Test1");
  }

  // -- Utility functions

  // Gets the trombone config file from the config service, or uses the trombone.conf resource file if that doesn't work
  private CompletableFuture<AxisConfig> getAxisConfig() {
    //noinspection OptionalGetWithoutIsPresent
    return JConfigServiceClient.getConfigFromConfigService(tromboneConfigFile, Optional.empty(),
      Optional.of(resource), getContext().system(), timeout).thenApply(config -> new AxisConfig(config.get()));
  }

  // --- Static defs ---

  public static File tromboneConfigFile = new File("trombone/hcd/trombone.conf");
  public static File resource = new File("tromboneHCD.conf");



  /**
   * Used to create the TromboneHCD actor
   *
   * @param info       the HCD's prefix, used in configurations
   * @param supervisor the supervisor for the HCD
   * @return the Props needed to create the actor
   */
  public static Props props(final Component.HcdInfo info, ActorRef supervisor) {
    return Props.create(new Creator<TromboneHCD>() {
      private static final long serialVersionUID = 1L;

      @Override
      public TromboneHCD create() throws Exception {
        return new TromboneHCD(info, supervisor);
      }
    });
  }

  // HCD Info
  public static final String componentName = "lgsTromboneHCD";
  public static final ComponentType componentType = JComponentType.HCD;
  public static final String componentClassName = "csw.examples.vsliceJava.hcd.TromboneHCD";
  public static final String trombonePrefix = "nfiraos.ncc.tromboneHCD";

  public static final String tromboneAxisName = "tromboneAxis";

  public static final String axisStatePrefix = trombonePrefix + ".axis1State";
  public static final Prefix axisStateCK = new Prefix(axisStatePrefix);
  public static final StringKey axisNameKey = new StringKey("axisName");
  public static final Choice AXIS_IDLE = new Choice(AxisState.AXIS_IDLE.toString());
  public static final Choice AXIS_MOVING = new Choice(AxisState.AXIS_MOVING.toString());
  public static final Choice AXIS_ERROR = new Choice(AxisState.AXIS_ERROR.toString());
  public static final ChoiceKey stateKey = new ChoiceKey("axisState",
    Choices.from(AXIS_IDLE.toString(), AXIS_MOVING.toString(), AXIS_ERROR.toString()));
  public static final IntKey positionKey = new IntKey("position");
  public static final UnitsOfMeasure.Units positionUnits = encoder;
  public static final BooleanKey inLowLimitKey = new BooleanKey("lowLimit");
  public static final BooleanKey inHighLimitKey = new BooleanKey("highLimit");
  public static final BooleanKey inHomeKey = new BooleanKey("homed");

  public static final CurrentState defaultAxisState = cs(axisStateCK.prefix(),
    jset(axisNameKey, tromboneAxisName),
    jset(stateKey, AXIS_IDLE),
    jset(positionKey, 0).withUnits(encoder),
    jset(inLowLimitKey, false),
    jset(inHighLimitKey, false),
    jset(inHomeKey, false));

  public static final String axisStatsPrefix = trombonePrefix + ".axisStats";
  public static final Prefix axisStatsCK = new Prefix(axisStatsPrefix);
  public static final IntKey datumCountKey = IntKey("initCount");
  public static final IntKey moveCountKey = IntKey("moveCount");
  public static final IntKey homeCountKey = IntKey("homeCount");
  public static final IntKey limitCountKey = IntKey("limitCount");
  public static final IntKey successCountKey = IntKey("successCount");
  public static final IntKey failureCountKey = IntKey("failureCount");
  public static final IntKey cancelCountKey = IntKey("cancelCount");
  public static final CurrentState defaultStatsState = cs(axisStatsCK.prefix(),
    jset(axisNameKey, tromboneAxisName),
    jset(datumCountKey, 0),
    jset(moveCountKey, 0),
    jset(homeCountKey, 0),
    jset(limitCountKey, 0),
    jset(successCountKey, 0),
    jset(failureCountKey, 0),
    jset(cancelCountKey, 0));

  public static final String axisConfigPrefix = trombonePrefix + ".axisConfig";
  public static final Prefix axisConfigCK = new Prefix(axisConfigPrefix);
  // axisNameKey
  public static final IntKey lowLimitKey = IntKey("lowLimit");
  public static final IntKey lowUserKey = IntKey("lowUser");
  public static final IntKey highUserKey = IntKey("highUser");
  public static final IntKey highLimitKey = IntKey("highLimit");
  public static final IntKey homeValueKey = IntKey("homeValue");
  public static final IntKey startValueKey = IntKey("startValue");
  public static final IntKey stepDelayMSKey = IntKey("stepDelayMS");
  // No full default current state because it is determined at runtime
  public static final CurrentState defaultConfigState = cs(axisConfigPrefix,
    jset(axisNameKey, tromboneAxisName)
  );

  public static final String axisMovePrefix = trombonePrefix + ".move";
  public static final Prefix axisMoveCK = new Prefix(axisMovePrefix);

  public static Setup positionSC(int value) {
    return sc(axisMovePrefix, jset(positionKey, value).withUnits(encoder));
  }

  public static final String axisDatumPrefix = trombonePrefix + ".datum";
  public static final Prefix axisDatumCK = new Prefix(axisDatumPrefix);
  public static final Setup datumSC = Setup(axisDatumCK);

  public static final String axisHomePrefix = trombonePrefix + ".home";
  public static final Prefix axisHomeCK = new Prefix(axisHomePrefix);
  public static final Setup homeSC = Setup(axisHomeCK);

  public static final String axisCancelPrefix = trombonePrefix + ".cancel";
  public static final Prefix axisCancelCK = new Prefix(axisCancelPrefix);
  public static final Setup cancelSC = Setup(axisCancelCK);

  // Testing messages for TromboneHCD
  public enum TromboneEngineering {
    /**
     * Returns an AxisUpdate through subscribers
     */
    GetAxisStats,

    /**
     * Directly returns an AxisUpdate to sender
     */
    GetAxisUpdate,

    /**
     * Directly returns an AxisUpdate to sender
     */
    GetAxisUpdateNow,

    GetAxisConfig
  }
}





