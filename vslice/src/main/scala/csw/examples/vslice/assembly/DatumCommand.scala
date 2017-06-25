package csw.examples.vslice.assembly

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import csw.examples.vslice.assembly.TromboneStateActor.TromboneState
import csw.examples.vslice.hcd.TromboneHCD._
import csw.services.ccs.HcdController
import csw.services.ccs.Validation.WrongInternalStateIssue
import csw.util.param.Parameters.Setup
import akka.pattern.ask
import akka.util.Timeout
import csw.examples.vslice.assembly.TromboneAssembly.{CommandStart, StopCurrentCommand}
import csw.services.ccs.CommandStatus.{Completed, Error, NoLongerValid}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * TMT Source Code: 10/21/16.
 */
class DatumCommand(s: Setup, tromboneHCD: ActorRef, startState: TromboneState, stateActor: Option[ActorRef])
  extends Actor with ActorLogging {
  import TromboneCommandHandler._
  import TromboneStateActor._

  // Not using stateReceive since no state updates are needed here only writes
  def receive: Receive = {
    case CommandStart =>
      if (startState.cmd.head == cmdUninitialized) {
        sender() ! NoLongerValid(WrongInternalStateIssue(s"Assembly state of ${cmd(startState)}/${move(startState)} does not allow datum"))
      } else {
        val mySender = sender()
        sendState(SetState(cmdItem(cmdBusy), moveItem(moveIndexing), startState.sodiumLayer, startState.nss))
        tromboneHCD ! HcdController.Submit(Setup(s.info, axisDatumCK))
        TromboneCommandHandler.executeMatch(context, idleMatcher, tromboneHCD, Some(mySender)) {
          case Completed =>
            sendState(SetState(cmdReady, moveIndexed, sodiumLayer = false, nss = false))
          case Error(message) =>
            log.error(s"Data command match failed with error: $message")
        }
      }
    case StopCurrentCommand =>
      log.debug(">>  DATUM STOPPED")
      tromboneHCD ! HcdController.Submit(cancelSC(s.info))

    case StateWasSet(b) => // ignore confirmation
  }

  private def sendState(setState: SetState): Unit = {
    implicit val timeout = Timeout(5.seconds)
    stateActor.foreach(actorRef => Await.ready(actorRef ? setState, timeout.duration))
  }
}

object DatumCommand {
  def props(s: Setup, tromboneHCD: ActorRef, startState: TromboneState, stateActor: Option[ActorRef]): Props =
    Props(new DatumCommand(s, tromboneHCD, startState, stateActor))
}
