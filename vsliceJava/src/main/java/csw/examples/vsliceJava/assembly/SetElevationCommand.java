package csw.examples.vsliceJava.assembly;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.util.Timeout;
import csw.services.ccs.CommandStatus;
import csw.services.ccs.DemandMatcher;
import csw.services.ccs.HcdController;
import csw.util.param.DoubleParameter;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static csw.examples.vsliceJava.assembly.TromboneStateActor.*;
import static csw.examples.vsliceJava.hcd.TromboneHCD.*;
import static csw.services.ccs.Validation.WrongInternalStateIssue;
import static csw.util.param.Parameters.Setup;
import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.param.JParameterSetDsl.setup;
import static javacsw.util.param.JParameters.*;
import static javacsw.util.param.JUnitsOfMeasure.encoder;
import static akka.pattern.PatternsCS.ask;

/**
 * This actor implements the setElevation command.
 *
 * The setElevation command receives an elevation value. In this implementation, the elevation is used as a range distance
 * that is then converted to a state position and sent to the HCD.
 *
 * This command is similar to the move command and would use the move command except for the fact that at the end of the
 * command, the sodium layer state must be set to true, which is not the case with the mvoe command.  There is probably
 * a way to refactor this to reuse the move command.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class SetElevationCommand extends AbstractActor {

  private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
  private final AssemblyContext ac;
  private final Setup s;
  private final ActorRef tromboneHCD;
  private final TromboneState startState;
  private final Optional<ActorRef> stateActor;

  private SetElevationCommand(AssemblyContext ac, Setup s, ActorRef tromboneHCD,
                              TromboneState startState, Optional<ActorRef> stateActor) {
    this.ac = ac;
    this.s = s;
    this.tromboneHCD = tromboneHCD;
    this.startState = startState;
    this.stateActor = stateActor;
  }

  // Not using stateReceive since no state updates are needed here only writes
  @Override
  public Receive createReceive() {
    return receiveBuilder().
        matchEquals(TromboneAssembly.CommandStart.instance, t -> {
          if (cmd(startState).equals(cmdUninitialized) || (!move(startState).equals(moveIndexed) && !move(startState).equals(moveMoving))) {
            sender().tell(new CommandStatus.NoLongerValid(new WrongInternalStateIssue(
                "Assembly state of " + cmd(startState) + "/" + move(startState) + " does not allow setElevation")), self());
          } else {
            ActorRef mySender = sender();
            // Note that units have already been verified here
            DoubleParameter elevationItem = jparameter(s, AssemblyContext.naElevationKey);

            // Let the elevation be the range distance
            // Convert range distance to encoder units from mm
            double stagePosition = Algorithms.rangeDistanceToStagePosition(jvalue(elevationItem));
            int encoderPosition = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition);

            log.info("Using elevation as rangeDistance: " + jvalue(elevationItem) + " to get stagePosition: " + stagePosition + " to encoder: " + encoderPosition);

            DemandMatcher stateMatcher = TromboneCommandHandler.posMatcher(encoderPosition);
            // Position key is encoder units
            Setup scOut = jadd(setup(s.info(), axisMoveCK.prefix(), jset(positionKey, encoderPosition).withUnits(encoder)));
            sendState(new SetState(cmdItem(cmdBusy), moveItem(moveMoving), startState.sodiumLayer, startState.nss));
            tromboneHCD.tell(new HcdController.Submit(scOut), self());

            Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
            TromboneCommandHandler.executeMatch(getContext(), stateMatcher, tromboneHCD,  Optional.of(mySender), timeout, status -> {
              if (status == Completed)
                // NOTE ---> This is the place where sodium layer state gets set to TRUE
                sendState(new SetState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(true), startState.nss));
              else if (status instanceof CommandStatus.Error)
                log.error("setElevation command match failed with message: " + ((CommandStatus.Error)status).message());
            });

          }
        }).
        matchEquals(TromboneAssembly.StopCurrentCommand.instance, t -> {
          log.info("SetElevation command -- STOP");
          tromboneHCD.tell(new HcdController.Submit(cancelSC(s.info())), self());
        }).
        matchAny(t -> log.warning("Unknown message received: " + t)).
        build();
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

  public static Props props(AssemblyContext ac, Setup sc, ActorRef tromboneHCD, TromboneState startState, Optional<ActorRef> stateActor) {
    return Props.create(new Creator<SetElevationCommand>() {
      private static final long serialVersionUID = 1L;

      @Override
      public SetElevationCommand create() throws Exception {
        return new SetElevationCommand(ac, sc, tromboneHCD, startState, stateActor);
      }
    });
  }

}
