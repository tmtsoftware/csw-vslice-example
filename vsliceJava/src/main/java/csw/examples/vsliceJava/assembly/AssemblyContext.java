package csw.examples.vsliceJava.assembly;

import com.typesafe.config.Config;
import csw.services.loc.ComponentId;
import csw.services.loc.ComponentType;
import csw.util.param.*;
import csw.util.param.BooleanKey;
import csw.util.param.DoubleKey;
import csw.util.param.StringKey;

import static csw.services.pkg.Component.AssemblyInfo;
import static csw.util.param.Parameters.Prefix;
import static csw.util.param.Parameters.Setup;
import static javacsw.util.param.JParameterSetDsl.setup;
import static javacsw.util.param.JParameters.*;
import static javacsw.util.param.JUnitsOfMeasure.*;

/**
 * TMT Source Code: 10/4/16.
 */
@SuppressWarnings({"unused", "WeakerAccess", "SameParameterValue"})
public class AssemblyContext {

  public final AssemblyInfo info;
  public final TromboneCalculationConfig calculationConfig;
  public final TromboneControlConfig controlConfig;

  // Assembly Info
  // These first three are set from the config file
  public final String componentName;
  public final String componentClassName;
  public final String componentPrefix;
  public final ComponentType componentType;
  public final String fullName;

  public final ComponentId assemblyComponentId ;
  public final ComponentId hcdComponentId; // There is only one

  // Public command configurations
  // Init submit command
  public final String initPrefix;
  public final Prefix initCK;

  // Dataum submit command
  public final String datumPrefix;
  public final Prefix datumCK;

  // Stop submit command
  public final String stopPrefix;
  public final Prefix stopCK;

  // Move submit command
  public final String movePrefix;
  public final Prefix moveCK;

  public Setup moveSC(double position) {
    return sc(moveCK.prefix(), jset(stagePositionKey, position).withUnits(stagePositionUnits));
  }

  // Position submit command
  public final String positionPrefix;
  public final Prefix positionCK;

  public Setup positionSC(double rangeDistance) {
    return sc(positionCK.prefix(), jset(naRangeDistanceKey, rangeDistance).withUnits(naRangeDistanceUnits));
  }

  // setElevation submit command
  public final String setElevationPrefix;
  public final Prefix setElevationCK;

  public Setup setElevationSC(double elevation) {
    return sc(setElevationCK.prefix(), naElevation(elevation));
  }

  // setAngle submit command
  public final String setAnglePrefx;
  public final Prefix setAngleCK;

  public Setup setAngleSC(double zenithAngle) {
    return jadd(sc(setAngleCK.prefix()), za(zenithAngle));
  }

  // Follow submit command
  public final String followPrefix;
  public final Prefix followCK;
  public static final BooleanKey nssInUseKey = BooleanKey("nssInUse");

  public static BooleanParameter setNssInUse(boolean value) {
    return jset(nssInUseKey, value);
  }

  public Setup followSC(boolean nssInUse) {
    return sc(followCK.prefix(), jset(nssInUseKey, nssInUse));
  }

  // A list of all commands
  public final Prefix[] allCommandKeys;

  // Shared key values --
  // Used by setElevation, setAngle
  public static final StringKey configurationNameKey = StringKey("initConfigurationName");
  public static final StringKey configurationVersionKey = StringKey("initConfigurationVersion");


  public static final DoubleKey focusErrorKey = DoubleKey("focus");
  public static final UnitsOfMeasure.Units focusErrorUnits = micrometers;

  public static DoubleParameter fe(double error) {
    return jset(focusErrorKey, error).withUnits(focusErrorUnits);
  }

  public static final DoubleKey zenithAngleKey = DoubleKey("zenithAngle");
  public static final UnitsOfMeasure.Units zenithAngleUnits = degrees;

  public static DoubleParameter za(double angle) {
    return jset(zenithAngleKey, angle).withUnits(zenithAngleUnits);
  }

  public static final DoubleKey naRangeDistanceKey = DoubleKey("rangeDistance");
  public static final UnitsOfMeasure.Units naRangeDistanceUnits = kilometers;

  public static DoubleParameter rd(double rangedistance) {
    return jset(naRangeDistanceKey, rangedistance).withUnits(naRangeDistanceUnits);
  }

  public static final DoubleKey naElevationKey = DoubleKey("elevation");
  public static final UnitsOfMeasure.Units naElevationUnits = kilometers;
  public static DoubleParameter naElevation(Double elevation) {
    return jset(naElevationKey, elevation).withUnits(naElevationUnits);
  }

  public static final DoubleKey initialElevationKey = DoubleKey("initialElevation");
  public static final UnitsOfMeasure.Units initialElevationUnits = kilometers;
  public static DoubleParameter iElevation(double elevation) {
    return jset(initialElevationKey, elevation).withUnits(initialElevationUnits);
  }

  public static final DoubleKey stagePositionKey = DoubleKey("stagePosition");
  public static final UnitsOfMeasure.Units stagePositionUnits = millimeters;

  public static DoubleParameter spos(double pos) {
    return jset(stagePositionKey, pos).withUnits(stagePositionUnits);
  }

  // ---------- Keys used by TromboneEventSubscriber and Others
  // This is the zenith angle from TCS
  public static final String zenithAnglePrefix = "TCS.tcsPk.zenithAngle";
  public static final Prefix zaPrefix = new Prefix(zenithAnglePrefix);

  // This is the focus error from RTC
  public static final String focusErrorPrefix = "RTC.focusError";
  public static final Prefix fePrefix = new Prefix(focusErrorPrefix);

  // ----------- Keys, etc. used by trombonePublisher, calculator, comamnds
  public final String aoSystemEventPrefix;
  public final String engStatusEventPrefix;
  public final String tromboneStateStatusEventPrefix;
  public final String axisStateEventPrefix;
  public final String axisStatsEventPrefix;

  // ---

  public AssemblyContext(AssemblyInfo info, TromboneCalculationConfig calculationConfig, TromboneControlConfig controlConfig) {
    this.info = info;
    this.calculationConfig = calculationConfig;
    this.controlConfig = controlConfig;

    componentName = info.componentName();
    componentClassName = info.componentClassName();
    componentPrefix = info.prefix();
    componentType = info.componentType();
    fullName = componentPrefix + "." + componentName;

    assemblyComponentId = new ComponentId(componentName, componentType);
    hcdComponentId = info.connections().head().componentId(); // There is only one

    // Public command configurations
    // Init submit command
    initPrefix = componentPrefix + ".init";
    initCK = new Prefix(initPrefix);

    // Dataum submit command
    datumPrefix = componentPrefix + ".datum";
    datumCK = new Prefix(datumPrefix);

    // Stop submit command
    stopPrefix = componentPrefix + ".stop";
    stopCK = new Prefix(stopPrefix);

    // Move submit command
    movePrefix = componentPrefix + ".move";
    moveCK = new Prefix(movePrefix);

    // Position submit command
    positionPrefix = componentPrefix + ".position";
    positionCK = new Prefix(positionPrefix);

    // setElevation submit command
    setElevationPrefix = componentPrefix + ".setElevation";
    setElevationCK = new Prefix(setElevationPrefix);

    // setAngle submit command
    setAnglePrefx = componentPrefix + ".setAngle";
    setAngleCK = new Prefix(setAnglePrefx);

    // Follow submit command
    followPrefix = componentPrefix + ".follow";
    followCK = new Prefix(followPrefix);

    // A list of all commands
    allCommandKeys = new Prefix[]{initCK, datumCK, stopCK, moveCK, positionCK, setElevationCK, setAngleCK, followCK};

    // ----------- Keys, etc. used by trombonePublisher, calculator, comamnds
    aoSystemEventPrefix = componentPrefix + ".sodiumLayer";
    engStatusEventPrefix = componentPrefix + ".engr";
    tromboneStateStatusEventPrefix = componentPrefix + ".state";
    axisStateEventPrefix = componentPrefix + ".axis1State";
    axisStatsEventPrefix = componentPrefix + ".axis1Stats";
  }


  // --- static defs ---

  /**
   * Configuration class
   */
  public static class TromboneControlConfig {
    public final double positionScale;
    public final int minStageEncoder;
    public final double stageZero;
    public final int minEncoderLimit;
    public final int maxEncoderLimit;

    /**
     * Configuration class
     *
     * @param positionScale   value used to scale
     * @param stageZero       zero point in stage conversion
     * @param minStageEncoder minimum
     * @param minEncoderLimit minimum
     */
    public TromboneControlConfig(double positionScale, int minStageEncoder, double stageZero, int minEncoderLimit, int maxEncoderLimit) {
      this.positionScale = positionScale;
      this.minStageEncoder = minStageEncoder;
      this.stageZero = stageZero;
      this.minEncoderLimit = minEncoderLimit;
      this.maxEncoderLimit = maxEncoderLimit;
    }

    /**
     * Init from the given config
     */
    public TromboneControlConfig(Config config) {
      // Main prefix for keys used below
      String prefix = "csw.examples.trombone.assembly";

      this.positionScale = config.getDouble(prefix + ".control-config.positionScale");
      this.stageZero = config.getDouble(prefix + ".control-config.stageZero");
      this.minStageEncoder = config.getInt(prefix + ".control-config.minStageEncoder");
      this.minEncoderLimit = config.getInt(prefix + ".control-config.minEncoderLimit");
      this.maxEncoderLimit = config.getInt(prefix + ".control-config.maxEncoderLimit");
    }

  }

  /**
   * Configuration class
   */
  @SuppressWarnings("unused")
  public static class TromboneCalculationConfig {
    public final double defaultInitialElevation;
    public final double focusErrorGain;
    public final double upperFocusLimit;
    public final double lowerFocusLimit;
    public final double zenithFactor;

    /**
     * Configuration class
     *
     * @param defaultInitialElevation a default initial eleveation (possibly remove once workign)
     * @param focusErrorGain          gain value for focus error
     * @param upperFocusLimit         check for maximum focus error
     * @param lowerFocusLimit         check for minimum focus error
     * @param zenithFactor            an algorithm value for scaling zenith angle term
     */
    public TromboneCalculationConfig(double defaultInitialElevation, double focusErrorGain, double upperFocusLimit, double lowerFocusLimit, double zenithFactor) {
      this.defaultInitialElevation = defaultInitialElevation;
      this.focusErrorGain = focusErrorGain;
      this.upperFocusLimit = upperFocusLimit;
      this.lowerFocusLimit = lowerFocusLimit;
      this.zenithFactor = zenithFactor;
    }

    /**
     * Init from the given config
     */
    public TromboneCalculationConfig(Config config) {
      // Main prefix for keys used below
      String prefix = "csw.examples.trombone.assembly";

      this.defaultInitialElevation = config.getDouble(prefix + ".calculation-config.defaultInitialElevation");
      this.focusErrorGain = config.getDouble(prefix + ".calculation-config.focusErrorGain");
      this.upperFocusLimit = config.getDouble(prefix + ".calculation-config.upperFocusLimit");
      this.lowerFocusLimit = config.getDouble(prefix + ".calculation-config.lowerFocusLimit");
      this.zenithFactor = config.getDouble(prefix + ".calculation-config.zenithFactor");
    }
  }
}
