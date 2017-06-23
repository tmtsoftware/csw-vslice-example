package csw.examples.vslice.assembly

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import csw.examples.vslice.assembly.FollowActor.{StopFollowing, UpdatedEventData}
import csw.examples.vslice.assembly.TromboneEventSubscriber.UpdateNssInUse
import csw.services.events.EventService
import csw.services.events.EventService.EventMonitor
import csw.services.loc.LocationService.ResolvedTcpLocation
import csw.services.loc.LocationSubscriberClient
import csw.util.param.Parameters.Prefix
import csw.util.param.Events.{EventTime, SystemEvent}
import csw.util.param._

/**
 * TMT Source Code: 6/20/16.
 */
class TromboneEventSubscriber(ac: AssemblyContext, nssInUseIn: BooleanParameter, followActor: Option[ActorRef], eventService: EventService) extends Actor with ActorLogging with LocationSubscriberClient {

  import ac._

  // If state of NSS is false, then subscriber provides 0 for zenith distance with updates to subscribers

  // This value is used when NSS is in Use
  final val nssZenithAngle: DoubleParameter = za(0.0)

  // Kim possibly set these initial values from config or get them from telemetry store
  // These vars are needed since updates from RTC and TCS will happen at different times and we need both values
  // Could have two events but that requries follow actor to keep values
  val initialZenithAngle: DoubleParameter = if (nssInUseIn.head) nssZenithAngle else za(0.0)
  val initialFocusError: DoubleParameter = fe(0.0)
  // This is used to keep track since it can be updated
  var nssInUseGlobal = nssInUseIn

  // This val is needed to capture the Monitor used for subscriptions
  val subscribeMonitor: EventMonitor = startupSubscriptions(eventService)

  private def startupSubscriptions(eventService: EventService): EventMonitor = {
    // Always subscribe to focus error
    // Create the subscribeMonitor here
    val subscribeMonitor = subscribeKeys(eventService, fePrefix)
    log.info(s"FeMonitor actor: ${subscribeMonitor.actorRef}")

    log.info("nssInuse: " + nssInUseIn)

    // But only subscribe to ZA if nss is not in use
    if (!nssInUseIn.head) {
      // NSS not inuse so subscribe to ZA
      subscribeKeys(subscribeMonitor, zaPrefix)
    }
    subscribeMonitor
  }

  def receive: Receive = subscribeReceive(nssInUseIn, initialZenithAngle, initialFocusError)

  def subscribeReceive(cNssInUse: BooleanParameter, cZenithAngle: DoubleParameter, cFocusError: DoubleParameter): Receive = {

    case event: SystemEvent =>
      event.info.source match {
        case `zaPrefix` =>
          val newZenithAngle = event(zenithAngleKey)
          log.debug(s"Received ZA: $event")
          updateFollowActor(newZenithAngle, cFocusError, event.info.eventTime)
          // Pass the new values to the next message
          context.become(subscribeReceive(cNssInUse, newZenithAngle, cFocusError))

        case `fePrefix` =>
          // Update focusError state and then update calculator
          log.debug(s"Received FE: $event")
          val newFocusError = event(focusErrorKey)
          updateFollowActor(cZenithAngle, newFocusError, event.info.eventTime)
          // Pass the new values to the next message
          context.become(subscribeReceive(cNssInUse, cZenithAngle, newFocusError))

        case x => log.error(s"subscribeReceive in TromboneEventSubscriber received an unknown SystemEvent: $x")
      }

    case StopFollowing =>
      // Kill this subscriber
      subscribeMonitor.stop()
      context.stop(self)

    // This is an engineering command to allow checking subscriber
    case UpdateNssInUse(nssInUseUpdate) =>
      if (nssInUseUpdate != cNssInUse) {
        if (nssInUseUpdate.head) {
          unsubscribeKeys(subscribeMonitor, zaPrefix)
          context.become(subscribeReceive(nssInUseUpdate, nssZenithAngle, cFocusError))
        } else {
          subscribeKeys(subscribeMonitor, zaPrefix)
          context.become(subscribeReceive(nssInUseUpdate, cZenithAngle, cFocusError))
        }
        // Need to update the global for shutting down event subscriptions (XXX not used anywhere but in the test!)
        nssInUseGlobal = nssInUseUpdate
      }

    case t: ResolvedTcpLocation =>
      log.info(s"Received TCP Location: ${t.connection}")
    //      // Verify that it is the event service
    //      if (t.connection == EventService.eventServiceConnection()) {
    //        log.info(s"received ES connection: $t")
    //        // Setting var here!
    //        eventService = Some(EventService.get(t.host, t.port))
    //        log.info(s"Event Service at: $eventService")
    //      }

    case x => log.error(s"Unexpected message received in TromboneEventSubscriber:subscribeReceive: $x")
  }

  def unsubscribeKeys(monitor: EventMonitor, configKeys: Prefix*): Unit = {
    log.debug(s"Unsubscribing to: $configKeys")
    monitor.unsubscribe(configKeys.map(_.prefix): _*)
  }

  def subscribeKeys(eventService: EventService, configKeys: Prefix*): EventMonitor = {
    log.debug(s"Subscribing to: $configKeys as $self")
    eventService.subscribe(self, postLastEvents = false, configKeys.map(_.prefix): _*)
  }

  def subscribeKeys(monitor: EventMonitor, configKeys: Prefix*): Unit = {
    log.debug(s"Subscribing to: $configKeys as $self")
    monitor.subscribe(configKeys.map(_.prefix): _*)
  }

  /**
   * This function is called whenever a new event arrives. The function takes the current information consisting of
   * the zenithAngle and focusError which is actor state and forwards it to the FoolowActor if present
   *
   * @param eventTime - the time of the last event update
   */
  def updateFollowActor(zenithAngle: DoubleParameter, focusError: DoubleParameter, eventTime: EventTime) = {
    followActor.foreach(_ ! UpdatedEventData(zenithAngle, focusError, eventTime))
  }

}

object TromboneEventSubscriber {

  /**
   * props for the TromboneEventSubscriber
   * @param followActor a FollowActor as an Option[ActorRef]
   * @param eventService for testing, an event Service can be provided
   * @return Props for TromboneEventSubscriber
   */
  def props(assemblyContext: AssemblyContext, nssInUse: BooleanParameter, followActor: Option[ActorRef] = None, eventService: EventService) =
    Props(classOf[TromboneEventSubscriber], assemblyContext, nssInUse, followActor, eventService)

  case class UpdateNssInUse(nssInUse: BooleanParameter)
}

