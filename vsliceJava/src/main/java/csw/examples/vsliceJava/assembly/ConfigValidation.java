package csw.examples.vsliceJava.assembly;

import csw.util.param.Parameters.Prefix;
import csw.util.param.Parameters.Setup;
import csw.util.param.Parameters.SetupArg;
import csw.util.param.DoubleParameter;
import csw.util.param.StringParameter;
import javacsw.services.ccs.JValidation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static csw.examples.vsliceJava.assembly.AssemblyContext.*;
import static csw.examples.vsliceJava.assembly.AssemblyContext.configurationVersionKey;
import static csw.services.ccs.Validation.Invalid;
import static csw.services.ccs.Validation.Validation;
import static javacsw.services.ccs.JValidation.*;
import static javacsw.util.param.JParameters.jitem;
import static javacsw.util.param.JParameters.jvalue;

/**
 * TMT Source Code: 8/24/16.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class ConfigValidation {

  /**
   * Looks for any Setups in a SetupArg that fail validation and returns as a list of only Invalid
   *
   * @param sca input SetupArg for checking
   * @param ac  AssemblyContext provides command names
   * @return scala [[List]] that includes only the Invalid configurations in the SetupArg
   */
  public static List<Invalid> invalidsInTromboneSetupArg(SetupArg sca, AssemblyContext ac) {
    // Returns a list of all failed validations in config arg
    List<Validation> list = validateTromboneSetupArg(sca, ac);
    List<Invalid> badList = new ArrayList<>();
    for (Validation v : list) {
      if (v instanceof Invalid) badList.add((Invalid) v);
    }
    return badList;
  }

  /**
   * Runs Trombone-specific validation on a single Setup.
   */
  public static Validation validateOneSetup(Setup sc, AssemblyContext ac) {
    Prefix prefix = sc.prefix();
    if (prefix.equals(ac.initCK)) return initValidation(sc, ac);
    if (prefix.equals(ac.datumCK)) return datumValidation(sc);
    if (prefix.equals(ac.stopCK)) return stopValidation(sc);
    if (prefix.equals(ac.moveCK)) return moveValidation(sc, ac);
    if (prefix.equals(ac.positionCK)) return positionValidation(sc, ac);
    if (prefix.equals(ac.setElevationCK)) return setElevationValidation(sc, ac);
    if (prefix.equals(ac.setAngleCK)) return setAngleValidation(sc, ac);
    if (prefix.equals(ac.followCK)) return followValidation(sc, ac);
    return Invalid(OtherIssue("Setup with prefix $x is not support for $componentName"));
  }

  // Validates a SetupArg for Trombone Assembly
  public static List<Validation> validateTromboneSetupArg(SetupArg sca, AssemblyContext ac) {
    List<Validation> result = new ArrayList<>();
    for (Setup config : sca.getConfigs()) {
      result.add(validateOneSetup(config, ac));
    }
    return result;
  }

  /**
   * Validation for the init Setup
   *
   * @param sc the received Setup
   * @return Valid or Invalid
   */
  public static Validation initValidation(Setup sc, AssemblyContext ac) {
    int size = sc.size();
    if (!sc.prefix().equals(ac.initCK))
      return Invalid(WrongPrefixIssue("The Setup is not an init configuration"));

    // If no arguments, then this is okay
    if (sc.size() == 0)
      return JValidation.Valid;

    if (size == 2) {
      // Check for correct keys and types
      // This example assumes that we want only these two keys
      Set<String> missing = sc.jMissingKeys(configurationNameKey, configurationVersionKey);

      if (!missing.isEmpty())
        return Invalid(MissingKeyIssue("The 2 parameter init Setup requires keys: "
          + configurationNameKey + " and " + configurationVersionKey));

      try {
        StringParameter i1 = jitem(sc, configurationNameKey);
        StringParameter i2 = jitem(sc, configurationVersionKey);
      } catch (Exception ex) {
        return Invalid(JValidation.WrongItemTypeIssue("The init Setup requires StringItems named: "
          + configurationVersionKey + " and " + configurationVersionKey));
      }
      return JValidation.Valid;
    }
    return Invalid(WrongNumberOfItemsIssue("The init configuration requires 0 or 2 items, but " + size + " were received"));
  }

  /**
   * Validation for the datum Setup -- currently nothing to validate
   *
   * @param sc the received Setup
   * @return Valid or Invalid
   */
  public static Validation datumValidation(Setup sc) {
    return JValidation.Valid;
  }

  /**
   * Validation for the stop Setup -- currently nothing to validate
   *
   * @param sc the received Setup
   * @return Valid or Invalid
   */
  public static Validation stopValidation(Setup sc) {
    return JValidation.Valid;
  }

  /**
   * Validation for the move Setup
   * Note: position is optional, if not present, it moves to home
   *
   * @param sc the received Setup
   * @return Valid or Invalid
   */
  public static Validation moveValidation(Setup sc, AssemblyContext ac) {
    if (!sc.prefix().equals(ac.moveCK)) {
      return Invalid(WrongPrefixIssue("The Setup is not a move configuration."));
    }
    if (sc.size() == 0) return JValidation.Valid;

    // Check for correct key and type -- only checks that essential key is present, not strict
    if (!sc.exists(stagePositionKey)) {
      return Invalid(MissingKeyIssue("The move Setup must have a DoubleParameter named: " + stagePositionKey));
    }

    try {
      jitem(sc, stagePositionKey);
    } catch(Exception ex) {
      return Invalid(WrongItemTypeIssue("The move Setup must have a DoubleParameter named: " + stagePositionKey));
    }
    if (jitem(sc, stagePositionKey).units() != stagePositionUnits) {
      return Invalid(WrongUnitsIssue("The move Setup parameter: "
        + stagePositionKey
        + " must have units of: "
        + stagePositionUnits));
    }
    return JValidation.Valid;
  }

  /**
   * Validation for the position Setup -- must have a single parameter named rangeDistance
   *
   * @param sc the received Setup
   * @return Valid or Invalid
   */
  public static Validation positionValidation(Setup sc, AssemblyContext ac) {
    if (!sc.prefix().equals(ac.positionCK)) {
      return Invalid(WrongPrefixIssue("The Setup is not a position configuration."));
    }

    // The spec says parameter is not required, but doesn't explain so requiring parameter
    // Check for correct key and type -- only checks that essential key is present, not strict
    if (!sc.exists(naRangeDistanceKey)) {
      return Invalid(MissingKeyIssue("The position Setup must have a DoubleParameter named: " + naRangeDistanceKey));
    }

    try {
      jitem(sc, naRangeDistanceKey);
    } catch(Exception ex) {
      return Invalid(WrongItemTypeIssue("The position Setup must have a DoubleParameter named: " + naRangeDistanceKey));
    }

    if (jitem(sc, naRangeDistanceKey).units() != naRangeDistanceUnits) {
      return Invalid(WrongUnitsIssue("The position Setup parameter: "
        + naRangeDistanceKey
        + " must have units of: "
        + naRangeDistanceUnits));
    }

    double el = jvalue(jitem(sc, naRangeDistanceKey));
    if (el < 0) {
      return Invalid(ItemValueOutOfRangeIssue("Range distance value of "
        + el
        + " for position must be greater than or equal 0 km."));
    }

    return JValidation.Valid;
  }

  /**
   * Validation for the setElevation Setup
   *
   * @param sc the received Setup
   * @return Valid or Invalid
   */
  public static Validation setElevationValidation(Setup sc, AssemblyContext ac) {
    if (!sc.prefix().equals(ac.setElevationCK)) {
      return Invalid(WrongPrefixIssue("The Setup is not a setElevation configuration"));
    }

    // Check for correct key and type -- only checks that essential key is present, not strict
    if (!sc.jMissingKeys(naElevationKey).isEmpty()) {
      return Invalid(MissingKeyIssue("The setElevation Setup must have a parameter named: " + naElevationKey));
    }

    try {
      jitem(sc, naElevationKey);
    } catch(Exception ex) {
      return Invalid(WrongItemTypeIssue("The setElevation Setup must have a parameter: " + naElevationKey + " as a DoubleParameter"));
    }

    if (jitem(sc, naElevationKey).units() != naRangeDistanceUnits) {
      return Invalid(WrongUnitsIssue("The move Setup parameter: "
        + naElevationKey
        + " must have units: "
        + naElevationUnits));
    }

    return Valid;
  }

  /**
   * Validation for the setAngle Setup
   *
   * @param sc the received Setup
   * @return Valid or Invalid
   */
  public static Validation setAngleValidation(Setup sc, AssemblyContext ac) {
    if (!sc.prefix().equals(ac.setAngleCK)) {
      return Invalid(WrongPrefixIssue("The Setup is not a setAngle configuration"));
    }

    // Check for correct key and type -- only checks that essential key is present, not strict
    if (!sc.exists(zenithAngleKey)) {
      return Invalid(MissingKeyIssue("The setAngle Setup must have a DoubleParameter named: " + zenithAngleKey));
    }

    try {
      jitem(sc, zenithAngleKey);
    } catch(Exception ex) {
      return Invalid(WrongItemTypeIssue("The setAngle Setup must have a DoubleParameter named: " + zenithAngleKey));
    }

    DoubleParameter di = jitem(sc, zenithAngleKey);
    if (di.units() != zenithAngleUnits) {
      return Invalid(WrongUnitsIssue("The setAngle Setup parameter: "
        + zenithAngleKey
        + " must have units: "
        + zenithAngleUnits));
    }

    return Valid;
  }

  /**
   * Validation for the follow Setup
   *
   * @param sc the received Setup
   * @return Valid or Invalid
   */
  public static Validation followValidation(Setup sc, AssemblyContext ac) {
    if (!sc.prefix().equals(ac.followCK)) {
      return Invalid(WrongPrefixIssue("The Setup is not a follow configuration"));
    }
    // Check for correct key and type -- only checks that essential key is present, not strict
    if (!sc.exists(nssInUseKey)) {
      return Invalid(MissingKeyIssue("The follow Setup must have a BooleanParameter named: " + nssInUseKey));
    }

    try {
      jitem(sc, nssInUseKey);
    } catch(Exception ex) {
      return Invalid(WrongItemTypeIssue("The follow Setup must have a BooleanParameter named " + nssInUseKey));
    }

    return Valid;
  }
}
