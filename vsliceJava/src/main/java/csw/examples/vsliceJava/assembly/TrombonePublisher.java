package csw.examples.vsliceJava.assembly;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import csw.services.loc.LocationService;
import csw.util.param.*;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import javacsw.services.pkg.ILocationSubscriberClient;

import java.util.Optional;

import static csw.examples.vsliceJava.assembly.TromboneStateActor.TromboneState;
import static csw.services.loc.LocationService.ResolvedTcpLocation;
import static csw.util.param.Events.StatusEvent;
import static csw.util.param.Events.SystemEvent;
import static javacsw.util.param.JParameters.jadd;

/**
 * An actor that provides the publishing interface to the TMT Event Service and Telemetry Service.
 *
 * The TrombonePublisher receives messages from other actors that need to publish an event of some kind. The messages are
 * repackaged as SystemEvents or StatusEvents as needed.
 *
 * Currently, this actor publishes the sodiumLayer System Event for RTC, and the engr StatusEvent
 * and the state StatusEvent. The sodiumLayer event is triggered by the arrival of an AOESWUpdate message. The engr
 * StatusEvent is triggered by the arrival of an EngrUpdate message, and the state StatusEvent is triggered by the
 * TromboneState message.
 *
 * The pubisher also publishes diagnostic data from the DiagPublisher as an axis state and statistics StatusEvent.
 *
 * Values in received messages are assumed to be correct and ready for publishing.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class TrombonePublisher extends AbstractActor implements TromboneStateClient, ILocationSubscriberClient {
  private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  private final AssemblyContext assemblyContext;

  @SuppressWarnings("FieldCanBeLocal")
  private TromboneStateActor.TromboneState internalState = TromboneStateActor.defaultTromboneState;
  private final Optional<IEventService> eventServiceIn;
  private final Optional<ITelemetryService> telemetryServiceIn;

  @Override
  public void setCurrentState(TromboneStateActor.TromboneState ts) {
    internalState = ts;
  }

  private TromboneStateActor.TromboneState currentState() {
    return internalState;
  }

  /**
   * An actor that provides the publishing interface to the TMT Event Service and Telemetry Service.
   *
   * The TrombonePublisher receives messages from other actors that need to publish an event of some kind. The messages are
   * repackaged as SystemEvents or StatusEvents as needed.
   *
   * Currently, this actor publishes the sodiumLayer System Event for RTC, and the engr StatusEvent
   * and the state StatusEvent. The sodiumLayer event is triggered by the arrival of an AOESWUpdate message. The engr
   * StatusEvent is triggered by the arrival of an EngrUpdate message, and the state StatusEvent is triggered by the
   * TromboneState message.
   *
   * Values in messages are assumed to be correct and ready for publishing.
   *
   * @param assemblyContext the trombone AssemblyContext contains important shared values and useful functions
   * @param eventServiceIn optional EventService for testing event service
   * @param telemetryServiceIn optional Telemetryservice for testing with telemetry service
   */
  public TrombonePublisher(AssemblyContext assemblyContext, Optional<IEventService> eventServiceIn, Optional<ITelemetryService> telemetryServiceIn) {
    this.eventServiceIn = eventServiceIn;
    this.telemetryServiceIn = telemetryServiceIn;
    getContext().system().eventStream().subscribe(self(), TromboneState.class);
    subscribeToLocationUpdates();
    this.assemblyContext = assemblyContext;

    // This actor subscribes to TromboneState using the EventBus
    getContext().system().eventStream().subscribe(self(), TromboneState.class);

    log.info("Event Service in: " + eventServiceIn);
    log.info("Telemetry Service in: " + telemetryServiceIn);
  }

  @Override
  public Receive createReceive() {
    return publishingEnabled(eventServiceIn, telemetryServiceIn);
  }

    private Receive publishingEnabled(Optional<IEventService> eventService, Optional<ITelemetryService> telemetryService) {
    return receiveBuilder().
      match(AOESWUpdate.class, t ->
          publishAOESW(eventService, t.naElevation, t.naRange)).

      match(EngrUpdate.class, t ->
          publishEngr(telemetryService, t.focusError, t.stagePosition, t.zenithAngle)).

      match(TromboneState.class, t ->
          publishState(telemetryService, t)).

      match(AxisStateUpdate.class, t ->
        publishAxisState(telemetryService, t.axisName, t.position, t.state, t.inLowLimit, t.inHighLimit, t.inHome)).

      match(AxisStatsUpdate.class, t ->
        publishAxisStats(telemetryService, t.axisName, t.initCount, t.moveCount, t.homeCount, t.limitCount, t.successCount, t.failCount, t.cancelCount)).

      match(LocationService.Location.class, location -> handleLocations(location, eventService, telemetryService)).

      matchAny(t -> log.warning("Unexpected message in TrombonePublisher:publishingEnabled: " + t)).

      build();
  }

  private void handleLocations(LocationService.Location location, Optional<IEventService> currentEventService, Optional<ITelemetryService> currentTelemetryService) {
    if (location instanceof ResolvedTcpLocation) {
      ResolvedTcpLocation t = (ResolvedTcpLocation)location;
      log.debug("Received TCP Location: " + t.connection());
      // Verify that it is the event service
      if (location.connection().equals(IEventService.eventServiceConnection())) {
        log.debug("TrombonePublisher received connection: " + t);
        Optional<IEventService> newEventService = Optional.of(IEventService.getEventService(t.host(), t.port(), getContext().system()));
        log.debug("Event Service at: " + newEventService);
        getContext().become(publishingEnabled(newEventService, currentTelemetryService));
      }

      if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
        log.debug("TrombonePublisher received connection: " + t);
        Optional<ITelemetryService> newTelemetryService = Optional.of(ITelemetryService.getTelemetryService(t.host(), t.port(), getContext().system()));
        log.debug("Telemetry Service at: " + newTelemetryService);
        getContext().become(publishingEnabled(currentEventService, newTelemetryService));
      }

    } else if (location instanceof LocationService.Unresolved) {
      log.debug("Unresolved: " + location.connection());
      if (location.connection().equals(IEventService.eventServiceConnection()))
        getContext().become(publishingEnabled(Optional.empty(), currentTelemetryService));
      else if (location.connection().equals(ITelemetryService.telemetryServiceConnection()))
        getContext().become(publishingEnabled(currentEventService, Optional.empty()));

    } else  {
      log.info("TrombonePublisher received some other location: " + location);
    }
  }


  private void publishAOESW(Optional<IEventService> eventService, DoubleParameter elevationItem, DoubleParameter rangeItem) {
    SystemEvent se = jadd(new SystemEvent(assemblyContext.aoSystemEventPrefix), elevationItem, rangeItem);
    log.info("System publish of " + assemblyContext.aoSystemEventPrefix + ": " + se);
    eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
      log.error("TrombonePublisher failed to publish AO system event: " + se, ex);
      return null;
    }));
  }

  private void publishEngr(Optional<ITelemetryService> telemetryService, DoubleParameter rtcFocusError, DoubleParameter stagePosition, DoubleParameter zenithAngle) {
    StatusEvent ste = jadd(new StatusEvent(assemblyContext.engStatusEventPrefix), rtcFocusError, stagePosition, zenithAngle);
    log.info("Status publish of " + assemblyContext.engStatusEventPrefix + ": " + ste);

    telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
      log.error("TrombonePublisher failed to publish engr: " + ste, ex);
      return null;
    }));
  }

  private void publishState(Optional<ITelemetryService> telemetryService, TromboneState ts) {
    // We can do this for convenience rather than using TromboneStateHandler's stateReceive
    StatusEvent ste = jadd(new StatusEvent(assemblyContext.tromboneStateStatusEventPrefix), ts.cmd, ts.move, ts.sodiumLayer, ts.nss);
    log.debug("Status state publish of " + assemblyContext.tromboneStateStatusEventPrefix + ": " + ste);
    telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
      log.error("TrombonePublisher failed to publish state: " + ste, ex);
      return null;
    }));
  }

  private void publishAxisState(Optional<ITelemetryService> telemetryService, StringParameter axisName, IntParameter position, ChoiceParameter state, BooleanParameter inLowLimit,
                                BooleanParameter inHighLimit, BooleanParameter inHome) {
    StatusEvent ste = jadd(new StatusEvent(assemblyContext.axisStateEventPrefix), axisName, position, state, inLowLimit, inHighLimit, inHome);
    log.debug("Axis state publish of " + assemblyContext.axisStateEventPrefix + ": " + ste);
    telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
      log.error("TrombonePublisher failed to publish axis state: " + ste, ex);
      return null;
    }));
  }

  private void publishAxisStats(Optional<ITelemetryService> telemetryService, StringParameter axisName, IntParameter datumCount, IntParameter moveCount, IntParameter homeCount, IntParameter limitCount,
                                IntParameter successCount, IntParameter failureCount, IntParameter cancelCount) {
    StatusEvent ste = jadd(new StatusEvent(assemblyContext.axisStatsEventPrefix), axisName, datumCount, moveCount, homeCount, limitCount,
        successCount, failureCount, cancelCount);
    log.debug("Axis stats publish of " + assemblyContext.axisStatsEventPrefix + ": " + ste);
    telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
      log.error("TrombonePublisher failed to publish trombone axis stats: " + ste, ex);
      return null;
    }));
  }

  // --- static defs ---

  public static Props props(AssemblyContext assemblyContext, Optional<IEventService> eventServiceIn, Optional<ITelemetryService> telemetryServiceIn) {
    return Props.create(new Creator<TrombonePublisher>() {
      private static final long serialVersionUID = 1L;

      @Override
      public TrombonePublisher create() throws Exception {
        return new TrombonePublisher(assemblyContext, eventServiceIn, telemetryServiceIn);
      }
    });
  }

  /**
   * Used by actors wishing to cause an event for AO ESW
   */
  @SuppressWarnings("WeakerAccess")
  public static class AOESWUpdate {
    public final DoubleParameter naElevation;
    public final DoubleParameter naRange;

    /**
     * Constructor
     * @param naElevation elevation update
     * @param naRange range update
     */
    public AOESWUpdate(DoubleParameter naElevation, DoubleParameter naRange) {
      this.naElevation = naElevation;
      this.naRange = naRange;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      AOESWUpdate that = (AOESWUpdate) o;

      return naElevation.equals(that.naElevation) && naRange.equals(that.naRange);
    }

    @Override
    public int hashCode() {
      int result = naElevation.hashCode();
      result = 31 * result + naRange.hashCode();
      return result;
    }

    @Override
    public String toString() {
      return "AOESWUpdate{" +
        "naElevation=" + naElevation +
        ", naRange=" + naRange +
        '}';
    }
  }

  /**
   * Used by actors wishing to cause an engineering event update
   */
  @SuppressWarnings("WeakerAccess")
  public static class EngrUpdate {
    public final DoubleParameter focusError;
    public final DoubleParameter stagePosition;
    public final DoubleParameter zenithAngle;

    /**
     * Constructor
     * @param focusError focus error value as DoubleParameter
     * @param stagePosition stage position as a DoubleParameter
     * @param zenithAngle zenith angle update as a DoubleParameter
     */
    public EngrUpdate(DoubleParameter focusError, DoubleParameter stagePosition, DoubleParameter zenithAngle) {
      this.focusError = focusError;
      this.stagePosition = stagePosition;
      this.zenithAngle = zenithAngle;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      EngrUpdate that = (EngrUpdate) o;

      return focusError.equals(that.focusError) && stagePosition.equals(that.stagePosition) && zenithAngle.equals(that.zenithAngle);
    }

    @Override
    public int hashCode() {
      int result = focusError.hashCode();
      result = 31 * result + stagePosition.hashCode();
      result = 31 * result + zenithAngle.hashCode();
      return result;
    }
  }

  @SuppressWarnings("WeakerAccess")
  public static class AxisStateUpdate {
    public final StringParameter axisName;
    public final IntParameter position;
    public final ChoiceParameter state;
    public final BooleanParameter inLowLimit;
    public final BooleanParameter inHighLimit;
    public final BooleanParameter inHome;

    public AxisStateUpdate(StringParameter axisName, IntParameter position, ChoiceParameter state, BooleanParameter inLowLimit,
                           BooleanParameter inHighLimit, BooleanParameter inHome) {
      this.axisName = axisName;
      this.position = position;
      this.state = state;
      this.inLowLimit = inLowLimit;
      this.inHighLimit = inHighLimit;
      this.inHome = inHome;
    }
  }

  @SuppressWarnings("WeakerAccess")
  public static class AxisStatsUpdate {
    public final StringParameter axisName;
    public final IntParameter initCount;
    public final IntParameter moveCount;
    public final IntParameter homeCount;
    public final IntParameter limitCount;
    public final IntParameter successCount;
    public final IntParameter failCount;
    public final IntParameter cancelCount;

    public AxisStatsUpdate(StringParameter axisName, IntParameter initCount, IntParameter moveCount, IntParameter homeCount,
                           IntParameter limitCount, IntParameter successCount, IntParameter failCount, IntParameter cancelCount) {
      this.axisName = axisName;
      this.initCount = initCount;
      this.moveCount = moveCount;
      this.homeCount = homeCount;
      this.limitCount = limitCount;
      this.successCount = successCount;
      this.failCount = failCount;
      this.cancelCount = cancelCount;
    }
  }
}

