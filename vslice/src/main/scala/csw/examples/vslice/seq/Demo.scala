package csw.examples.vslice.seq

import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import csw.services.ccs.AssemblyMessages.{DiagnosticMode, OperationsMode}
import csw.util.param.{BooleanKey, Parameters, DoubleParameter, DoubleKey}
import csw.util.param.Parameters.{Prefix, Setup, SetupArg}
import csw.services.ccs.BlockingAssemblyClient
import csw.services.ccs.CommandStatus.CommandResult
import csw.services.events.EventService.EventMonitor
import csw.services.events.TelemetryService.TelemetryMonitor
import csw.services.events.{Event, EventService, TelemetryService}
import csw.util.param.UnitsOfMeasure.{degrees, kilometers, millimeters}

import scala.concurrent.duration._

/**
 * TMT Source Code: 12/4/16.
 */
object Demo extends LazyLogging {
  import csw.services.sequencer.SequencerEnv._

  implicit val timeout = Timeout(10.seconds)

  val taName = "lgsTrombone"
  val thName = "lgsTromboneHCD"

  val componentPrefix: String = "nfiraos.ncc.trombone"

  val obsId: String = "testObsId"

  // Public command configurations
  // Init submit command
  val initPrefix = s"$componentPrefix.init"
  val initCK: Prefix = initPrefix

  // Datum submit command
  val datumPrefix = s"$componentPrefix.datum"
  val datumCK: Prefix = datumPrefix

  val naRangeDistanceKey = DoubleKey("rangeDistance")
  val naRangeDistanceUnits = kilometers

  val naElevationKey = DoubleKey("elevation")
  val naElevationUnits = kilometers
  def naElevation(elevation: Double): DoubleParameter = naElevationKey -> elevation withUnits naElevationUnits

  val stagePositionKey = DoubleKey("stagePosition")
  val stagePositionUnits = millimeters

  val zenithAngleKey = DoubleKey("zenithAngle")
  val zenithAngleUnits = degrees
  def za(angle: Double): DoubleParameter = zenithAngleKey -> angle withUnits zenithAngleUnits

  // Move submit command
  val movePrefix = s"$componentPrefix.move"
  val moveCK: Prefix = movePrefix
  def moveSC(position: Double): Setup = Setup(moveCK).add(stagePositionKey -> position withUnits stagePositionUnits)

  // Position submit command
  val positionPrefix = s"$componentPrefix.position"
  val positionCK: Prefix = positionPrefix
  def positionSC(rangeDistance: Double): Setup = Setup(positionCK).add(naRangeDistanceKey -> rangeDistance withUnits naRangeDistanceUnits)

  // setElevation submit command
  val setElevationPrefix = s"$componentPrefix.setElevation"
  val setElevationCK: Prefix = setElevationPrefix
  def setElevationSC(elevation: Double): Setup = Setup(setElevationCK).add(naElevation(elevation))

  // Follow submit command
  val followPrefix = s"$componentPrefix.follow"
  val followCK: Prefix = followPrefix
  val nssInUseKey = BooleanKey("nssInUse")

  // Follow command
  def followSC(nssInUse: Boolean): Setup = Setup(followCK).add(nssInUseKey -> nssInUse)

  // setAngle submit command
  val setAnglePrefx = s"$componentPrefix.setAngle"
  val setAngleCK: Prefix = setAnglePrefx
  def setAngleSC(zenithAngle: Double): Setup = Setup(setAngleCK).add(za(zenithAngle))

  // Stop Command
  val stopPrefix = s"$componentPrefix.stop"
  val stopCK: Prefix = stopPrefix

  /**
   * Generic function to print any event
   */
  val evPrinter = (ev: Event) => { println(s"EventReceived: $ev") }

  // Test SetupArgs
  // Init and Datum axis
  val sca1 = Parameters.createSetupArg(obsId, Setup(initCK), Setup(datumCK))

  // Sends One Move
  val sca2 = Parameters.createSetupArg(obsId, positionSC(100.0))

  // This will send a config arg with 10 position commands
  val testRangeDistance = 40 to 130 by 10
  val positionConfigs = testRangeDistance.map(f => positionSC(f))
  val sca3 = Parameters.createSetupArg(obsId, positionConfigs: _*)

  /**
   * Returns the TromboneAssembly after LocationService lookup
   * @return BlockingAssemblyClient
   */
  def getTrombone: BlockingAssemblyClient = resolveAssembly(taName)

  /**
   * Returns the TromboneHCD after Location Service lookup
   * @return HcdClient
   */
  def getTromboneHcd: HcdClient = resolveHcd(thName)

  /**
   * Sends an init and datum. Needs to be run before anything else
   * @param tla the BlockingAssemblyClient returned by getTrombone
   * @return CommandResult and the conclusion of execution
   */
  def init(tla: BlockingAssemblyClient): CommandResult = tla.submit(sca1)

  /**
   * Send one move command to the Trombone Assembly
   * @param tla the BlockingAssemblyClient returned by getTrombone
   * @param pos some move position as a double in millimeters.  Should be around 100-2000 or you will drive it to a limit
   * @return CommandResult and the conclusion of execution
   */
  def oneMove(tla: BlockingAssemblyClient, pos: Double): CommandResult = {
    tla.submit(Parameters.createSetupArg(obsId, moveSC(pos)))
  }

  /**
   * Send one position command to the Trombone Assembly
   * @param tla the BlockingAssemblyClient returned by getTrombone
   * @param pos some position as a double.  Should be around 90-200 or you will drive it to a limit
   * @return CommandResult and the conclusion of execution
   */
  def onePos(tla: BlockingAssemblyClient, pos: Double): CommandResult = {
    tla.submit(Parameters.createSetupArg(obsId, positionSC(pos)))
  }

  /**
   * setElevation before going to follow mode
   * @param tla the BlockingAssemblyClient returned by getTrombone
   * @param el some elevation should be in kilometers around 90-130 or so
   * @return CommandResult at the end of execution
   */
  def setElevation(tla: BlockingAssemblyClient, el: Double): CommandResult = {
    tla.submit(Parameters.createSetupArg(obsId, setElevationSC(el)))
  }

  /**
   * Set the TromboneAssembly into follow mode.  Note you must do init, datum, and setElevation prior to this command
   * @param tla the BlockingAssemblyClient returned by getTrombone
   * @param nssMode true if NFIRAOS source simulator in use
   * @return CommandResult at end of execution
   */
  def follow(tla: BlockingAssemblyClient, nssMode: Boolean): CommandResult = {
    tla.submit(Parameters.createSetupArg(obsId, followSC(nssMode)))
  }

  /**
   * Set the zenith angle while in Follow mode.  Should be between 0 and 60 or so.  Haven't tested beyond that.
   * @param tla the BlockingAssemblyClient returned by getTrombon
   * @param degrees some zenith angle value
   * @return CommandResult at the end of execution
   */
  def setAngle(tla: BlockingAssemblyClient, degrees: Double): CommandResult = {
    tla.submit(Parameters.createSetupArg(obsId, setAngleSC(degrees)))
  }

  /**
   * Send the stop command. This will take Trombone Assembly out of Follow mode or stop an ongoing postition command
   * @param tla the BlockingAssemblyClient returned by getTrombone
   * @return CommandResult at end of execution
   */
  def stop(tla: BlockingAssemblyClient): CommandResult = {
    tla.submit(Parameters.createSetupArg(obsId, Setup(stopCK)))
  }

  /**
   * Subscribe to all StatusEvents published by the TromboneAssembly and print them to screen
   * @param ts a Telemetry Service reference
   * @return an EventService referenc
   */
  def getStatusEvents(ts: TelemetryService): TelemetryMonitor = ts.subscribe(evPrinter, false, s"$componentPrefix.*")

  /**
   * Subscribe to all SystemEvents published by TromboneAssembly and print them to the screen
   * @param es an EventService reference
   * @return EventMonitor
   */
  def getSystemEvents(es: EventService): EventMonitor = es.subscribe(evPrinter, false, s"$componentPrefix.*")

  /**
   * Puts the Trombone Assembly into diagnostic mode
   * @param tla the BlockingAssemblyClient returned by getTrombone
   */
  def diagnosticMode(tla: BlockingAssemblyClient): Unit = {
    tla.client.assemblyController ! DiagnosticMode
  }

  /**
   * Puts the Trombone Assembly into operations mode
   * @param tla the BlockingAssemblyClient returned by getTrombone
   */
  def operationsMode(tla: BlockingAssemblyClient): Unit = {
    tla.client.assemblyController ! OperationsMode
  }

}
