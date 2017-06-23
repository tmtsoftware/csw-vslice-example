package csw.examples.vslice.assembly

import com.typesafe.config.Config
import csw.examples.vslice.assembly.AssemblyContext.{TromboneCalculationConfig, TromboneControlConfig}
import csw.services.loc.ComponentId
import csw.services.pkg.Component.AssemblyInfo
import csw.util.param.Parameters.{CommandInfo, Prefix, Setup}
import csw.util.param.UnitsOfMeasure.{degrees, kilometers, micrometers, millimeters}
import csw.util.param.{BooleanKey, DoubleKey, DoubleParameter, StringKey}

/**
 * TMT Source Code: 10/4/16.
 */
case class AssemblyContext(info: AssemblyInfo, calculationConfig: TromboneCalculationConfig, controlConfig: TromboneControlConfig) {
  // Assembly Info
  // These first three are set from the config file
  val componentName: String = info.componentName
  val componentClassName: String = info.componentClassName
  val componentPrefix: String = info.prefix
  val componentType = info.componentType
  val fullName = s"$componentPrefix.$componentName"

  val assemblyComponentId = ComponentId(componentName, componentType)
  val hcdComponentId = info.connections.head.componentId // There is only one

  // Public command configurations
  // Init submit command
  val initPrefix = s"$componentPrefix.init"
  val initCK: Prefix = initPrefix

  // Dataum submit command
  val datumPrefix = s"$componentPrefix.datum"
  val datumCK: Prefix = datumPrefix

  // Stop submit command
  val stopPrefix = s"$componentPrefix.stop"
  val stopCK: Prefix = stopPrefix

  // Move submit command
  val movePrefix = s"$componentPrefix.move"
  val moveCK: Prefix = movePrefix

  def moveSC(position: Double): Setup = Setup(commandInfo, moveCK).add(stagePositionKey -> position withUnits stagePositionUnits)

  // Position submit command
  val positionPrefix = s"$componentPrefix.position"
  val positionCK: Prefix = positionPrefix

  def positionSC(rangeDistance: Double): Setup = Setup(commandInfo, positionCK).add(naRangeDistanceKey -> rangeDistance withUnits naRangeDistanceUnits)

  // setElevation submit command
  val setElevationPrefix = s"$componentPrefix.setElevation"
  val setElevationCK: Prefix = setElevationPrefix
  def setElevationSC(elevation: Double): Setup = Setup(commandInfo, setElevationCK).add(naElevation(elevation))

  // setAngle submit command
  val setAnglePrefx = s"$componentPrefix.setAngle"
  val setAngleCK: Prefix = setAnglePrefx
  def setAngleSC(zenithAngle: Double): Setup = Setup(commandInfo, setAngleCK).add(za(zenithAngle))

  // Follow submit command
  val followPrefix = s"$componentPrefix.follow"
  val followCK: Prefix = followPrefix
  val nssInUseKey = BooleanKey("nssInUse")

  def setNssInUse(value: Boolean) = nssInUseKey -> value

  def followSC(nssInUse: Boolean): Setup = Setup(commandInfo, followCK).add(nssInUseKey -> nssInUse)

  // A list of all commands
  val allCommandKeys: List[Prefix] = List(initCK, datumCK, stopCK, moveCK, positionCK, setElevationCK, setAngleCK, followCK)

  // Shared key values --
  // Used by setElevation, setAngle
  val configurationNameKey = StringKey("initConfigurationName")
  val configurationVersionKey = StringKey("initConfigurationVersion")

  val focusErrorKey = DoubleKey("focus")
  val focusErrorUnits = micrometers

  def fe(error: Double): DoubleParameter = focusErrorKey -> error withUnits focusErrorUnits

  val zenithAngleKey = DoubleKey("zenithAngle")
  val zenithAngleUnits = degrees

  def za(angle: Double): DoubleParameter = zenithAngleKey -> angle withUnits zenithAngleUnits

  val naRangeDistanceKey = DoubleKey("rangeDistance")
  val naRangeDistanceUnits = kilometers

  def rd(rangedistance: Double): DoubleParameter = naRangeDistanceKey -> rangedistance withUnits naRangeDistanceUnits

  val naElevationKey = DoubleKey("elevation")
  val naElevationUnits = kilometers
  def naElevation(elevation: Double): DoubleParameter = naElevationKey -> elevation withUnits naElevationUnits

  val initialElevationKey = DoubleKey("initialElevation")
  val initialElevationUnits = kilometers
  def iElevation(elevation: Double): DoubleParameter = initialElevationKey -> elevation withUnits initialElevationUnits

  val stagePositionKey = DoubleKey("stagePosition")
  val stagePositionUnits = millimeters

  def spos(pos: Double): DoubleParameter = stagePositionKey -> pos withUnits stagePositionUnits

  // ---------- Keys used by TromboneEventSubscriber and Others
  // This is the zenith angle from TCS
  val zenithAnglePrefix = "TCS.tcsPk.zenithAngle"
  val zaPrefix: Prefix = zenithAnglePrefix

  // This is the focus error from RTC
  val focusErrorPrefix = "RTC.focusError"
  val fePrefix: Prefix = focusErrorPrefix

  // ----------- Keys, etc. used by trombonePublisher, calculator, comamnds
  val aoSystemEventPrefix = s"$componentPrefix.sodiumLayer"
  val engStatusEventPrefix = s"$componentPrefix.engr"
  val tromboneStateStatusEventPrefix = s"$componentPrefix.state"
  val axisStateEventPrefix = s"$componentPrefix.axis1State"
  val axisStatsEventPrefix = s"$componentPrefix.axis1Stats"

  // Normally this would contain the obsId, runId and other info about the current observation
  val commandInfo = new CommandInfo("obs001")

}

object AssemblyContext {

  /**
   * Configuration class
   *
   * @param positionScale   value used to scale
   * @param stageZero       zero point in stage conversion
   * @param minStageEncoder minimum
   * @param minEncoderLimit minimum
   */
  case class TromboneControlConfig(
    positionScale: Double,
    stageZero:     Double, minStageEncoder: Int,
    minEncoderLimit: Int, maxEncoderLimit: Int
  )

  object TromboneControlConfig {
    def apply(config: Config): TromboneControlConfig = {
      // Main prefix for keys used below
      val prefix = "csw.examples.trombone.assembly"

      val positionScale = config.getDouble(s"$prefix.control-config.positionScale")
      val stageZero = config.getDouble(s"$prefix.control-config.stageZero")
      val minStageEncoder = config.getInt(s"$prefix.control-config.minStageEncoder")
      val minEncoderLimit = config.getInt(s"$prefix.control-config.minEncoderLimit")
      val maxEncoderLimit = config.getInt(s"$prefix.control-config.maxEncoderLimit")
      TromboneControlConfig(positionScale, stageZero, minStageEncoder, minEncoderLimit, maxEncoderLimit)
    }
  }

  /**
   * Configuration class
   *
   * @param defaultInitialElevation a default initial eleveation (possibly remove once workign)
   * @param focusErrorGain          gain value for focus error
   * @param upperFocusLimit         check for maximum focus error
   * @param lowerFocusLimit         check for minimum focus error
   * @param zenithFactor            an algorithm value for scaling zenith angle term
   */
  case class TromboneCalculationConfig(defaultInitialElevation: Double, focusErrorGain: Double,
                                       upperFocusLimit: Double, lowerFocusLimit: Double, zenithFactor: Double)

  object TromboneCalculationConfig {
    def apply(config: Config): TromboneCalculationConfig = {
      // Main prefix for keys used below
      val prefix = "csw.examples.trombone.assembly"

      val defaultInitialElevation = config.getDouble(s"$prefix.calculation-config.defaultInitialElevation")
      val focusGainError = config.getDouble(s"$prefix.calculation-config.focusErrorGain")
      val upperFocusLimit = config.getDouble(s"$prefix.calculation-config.upperFocusLimit")
      val lowerFocusLimit = config.getDouble(s"$prefix.calculation-config.lowerFocusLimit")
      val zenithFactor = config.getDouble(s"$prefix.calculation-config.zenithFactor")
      TromboneCalculationConfig(defaultInitialElevation, focusGainError, upperFocusLimit, lowerFocusLimit, zenithFactor)
    }
  }

}
