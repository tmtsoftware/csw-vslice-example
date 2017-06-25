package csw.examples.vslice.assembly

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import csw.examples.vslice.assembly.TromboneStateActor.TromboneState
import csw.examples.vslice.hcd.TromboneHCD._
import csw.services.ccs.HcdController
import csw.services.ccs.Validation.WrongInternalStateIssue
import csw.util.param.Parameters.Setup
import csw.util.param.UnitsOfMeasure.encoder

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.pattern.ask
import csw.examples.vslice.assembly.TromboneAssembly.{CommandStart, StopCurrentCommand}
import csw.services.ccs.CommandStatus.{Completed, Error, NoLongerValid}

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
class SetElevationCommand(ac: AssemblyContext, s: Setup, tromboneHCD: ActorRef, startState: TromboneState, stateActor: Option[ActorRef]) extends Actor with ActorLogging {

  import TromboneCommandHandler._
  import TromboneStateActor._

  def receive: Receive = {
    case CommandStart =>
      if (cmd(startState) == cmdUninitialized || (move(startState) != moveIndexed && move(startState) != moveMoving)) {
        sender() ! NoLongerValid(WrongInternalStateIssue(s"Assembly state of ${cmd(startState)}/${move(startState)} does not allow setElevation"))
      } else {
        val mySender = sender()

        // Note that units have already been verified here
        val elevationItem = s(ac.naElevationKey)

        // Let the elevation be the range distance
        // Convert range distance to encoder units from mm
        val stagePosition = Algorithms.rangeDistanceToStagePosition(elevationItem.head)
        val encoderPosition = Algorithms.stagePositionToEncoder(ac.controlConfig, stagePosition)

        log.info(s"Using elevation as rangeDistance: ${elevationItem.head} to get stagePosition: $stagePosition to encoder: $encoderPosition")

        val stateMatcher = posMatcher(encoderPosition)
        // Position key is encoder units
        val scOut = Setup(ac.commandInfo, axisMoveCK).add(positionKey -> encoderPosition withUnits encoder)
        sendState(SetState(cmdItem(cmdBusy), moveItem(moveMoving), startState.sodiumLayer, startState.nss))
        tromboneHCD ! HcdController.Submit(scOut)

        executeMatch(context, stateMatcher, tromboneHCD, Some(mySender)) {
          case Completed =>
            // NOTE ---> This is the place where sodium layer state gets set to TRUE
            sendState(SetState(cmdItem(cmdReady), moveItem(moveIndexed), sodiumItem(true), startState.nss))
          case Error(message) =>
            log.error(s"setElevation command match failed with message: $message")
        }
      }
    case StopCurrentCommand =>
      log.debug("SetElevation command -- STOP")
      tromboneHCD ! HcdController.Submit(cancelSC(s.info))
  }

  private def sendState(setState: SetState): Unit = {
    implicit val timeout = Timeout(5.seconds)
    stateActor.foreach(actorRef => Await.ready(actorRef ? setState, timeout.duration))
  }
}

object SetElevationCommand {

  def props(ac: AssemblyContext, s: Setup, tromboneHCD: ActorRef, startState: TromboneState, stateActor: Option[ActorRef]): Props =
    Props(new SetElevationCommand(ac, s, tromboneHCD, startState, stateActor))
}
