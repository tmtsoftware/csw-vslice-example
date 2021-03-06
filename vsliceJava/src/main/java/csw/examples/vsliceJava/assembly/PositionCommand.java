package csw.examples.vsliceJava.assembly;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import csw.services.ccs.CommandStatus.Error;
import csw.services.ccs.DemandMatcher;
import csw.services.ccs.HcdController;
import csw.util.config.DoubleItem;
import javacsw.services.ccs.JSequentialExecutor;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static csw.examples.vsliceJava.assembly.TromboneStateActor.*;
import static csw.examples.vsliceJava.hcd.TromboneHCD.*;
import static csw.services.ccs.CommandStatus.NoLongerValid;
import static csw.services.ccs.Validation.WrongInternalStateIssue;
import static csw.util.config.Configurations.SetupConfig;
import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.config.JConfigDSL.sc;
import static javacsw.util.config.JItems.*;
import static javacsw.util.config.JUnitsOfMeasure.encoder;
import static akka.pattern.PatternsCS.ask;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class PositionCommand extends AbstractActor {

  private LoggingAdapter log = Logging.getLogger(getContext().system(), this);
  private final Optional<ActorRef> stateActor;

  private PositionCommand(AssemblyContext ac, SetupConfig sc, ActorRef tromboneHCD, TromboneState startState, Optional<ActorRef> stateActor) {
    this.stateActor = stateActor;

    // Not using stateReceive since no state updates are needed here only writes
    receive(ReceiveBuilder.
      matchEquals(JSequentialExecutor.CommandStart(), t -> {
        if (cmd(startState).equals(cmdUninitialized) || (!move(startState).equals(moveIndexed) && !move(startState).equals(moveMoving))) {
          sender().tell(new NoLongerValid(new WrongInternalStateIssue(
            "Assembly state of " + cmd(startState) + "/" + move(startState) + " does not allow motion")), self());
        } else {
          ActorRef mySender = sender();

          // Note that units have already been verified here
          DoubleItem rangeDistance = jitem(sc, AssemblyContext.naRangeDistanceKey);

          // Convert range distance to encoder units from mm
          double stagePosition = Algorithms.rangeDistanceToStagePosition(jvalue(rangeDistance));
          int encoderPosition = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition);

          log.info("Using rangeDistance: " + jvalue(rangeDistance) + " to get stagePosition: " + stagePosition + " to encoder: " + encoderPosition);

          DemandMatcher stateMatcher = TromboneCommandHandler.posMatcher(encoderPosition);
          // Position key is encoder units
          SetupConfig scOut = jadd(sc(axisMoveCK.prefix(), jset(positionKey, encoderPosition).withUnits(encoder)));
          sendState(new SetState(cmdItem(cmdBusy), moveItem(moveMoving), startState.sodiumLayer, startState.nss));
          tromboneHCD.tell(new HcdController.Submit(scOut), self());

          Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
          TromboneCommandHandler.executeMatch(context(), stateMatcher, tromboneHCD,  Optional.of(mySender), timeout, status -> {
            if (status == Completed)
              sendState(new SetState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(false), startState.nss));
            else if (status instanceof Error)
              log.error("Position command match failed with message: " + ((Error)status).message());
          });

        }
      }).
      matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
        log.info("Position command -- STOP");
        tromboneHCD.tell(new HcdController.Submit(cancelSC), self());
      }).
      matchAny(t -> log.warning("Unknown message received: " + t)).
      build());
  }

  private void sendState(SetState setState) {
    stateActor.ifPresent(actorRef -> {
      try {
        ask(actorRef, setState, 5000).toCompletableFuture().get();
      } catch (Exception e) {
        log.error(e, "Error setting state");
      }
    });
  }

  public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef tromboneHCD, TromboneState startState, Optional<ActorRef> stateActor) {
    return Props.create(new Creator<PositionCommand>() {
      private static final long serialVersionUID = 1L;

      @Override
      public PositionCommand create() throws Exception {
        return new PositionCommand(ac, sc, tromboneHCD, startState, stateActor);
      }
    });
  }

}
