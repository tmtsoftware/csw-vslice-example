package csw.examples.vslice.assembly

import csw.services.ccs.CommandResponse
import csw.services.ccs.Validation._
import csw.util.param.Parameters.{CommandInfo, Setup}
import csw.util.param.UnitsOfMeasure.kilometers
import csw.util.param.{DoubleKey, Parameters}
import org.scalatest.{BeforeAndAfterAll, FunSpec, Inspectors, Matchers}

/**
 * TMT Source Code: 8/25/16.
 */
class ValidationTests extends FunSpec with Matchers with Inspectors with BeforeAndAfterAll {
  import ParamValidation._

  implicit val ac = AssemblyTestData.TestAssemblyContext
  import ac._

  // Normally this would contain the obsId, runId and other info about the current observation
  private val commandInfo = new CommandInfo("obs001")

  def checkInvalid(result: Validation): Invalid = {
    result shouldBe a[Invalid]
    result.asInstanceOf[Invalid]
  }

  def checkForWrongPrefix(result: Validation): Unit = {
    checkInvalid(result).issue shouldBe a[WrongPrefixIssue]
  }

  def checkForMissingKeys(result: Validation): Unit = {
    checkInvalid(result).issue shouldBe a[MissingKeyIssue]
  }

  def checkForWrongItemType(result: Validation): Unit = {
    checkInvalid(result).issue shouldBe a[WrongParameterTypeIssue]
  }

  def checkForWrongUnits(result: Validation): Unit = {
    checkInvalid(result).issue shouldBe a[WrongUnitsIssue]
  }

  def checkForWrongNumberOfParameters(result: Validation): Unit = {
    checkInvalid(result).issue shouldBe a[WrongNumberOfParametersIssue]
  }

  def checkForOutOfRange(result: Validation): Unit = {
    checkInvalid(result).issue shouldBe a[ParameterValueOutOfRangeIssue]
  }

  def checkForOtherIssue(result: Validation): Unit = {
    checkInvalid(result).issue shouldBe a[OtherIssue]
  }

  /**
   * Test Description: This tests the validation of the init SC
   */
  describe("testing validation for init command") {

    it("should fail if not an init") {
      val sc = Setup(commandInfo, positionCK)
      checkForWrongPrefix(initValidation(sc))
    }

    it("should validate init setupconfig with 0 args") {
      val sc = Setup(initCK)

      // Should validate with no arguments
      initValidation(sc) should be(Valid)
    }
    it("should validate 2 arg init setupconfig") {
      var sc = Setup(initCK).madd(configurationNameKey -> "config1", configurationVersionKey -> "1.0")

      // Should validate with 2 good arguments
      initValidation(sc) should be(Valid)

      // Should be invalid with an extra argument
      sc = sc.add(zenithAngleKey -> 0.0)
      checkForWrongNumberOfParameters(initValidation(sc))
      //initValidation(sc).isInstanceOf[Invalid] should be(true)
    }
    it("should check for init item types") {
      // Make a key with the correct name that isn't the right type
      val cvKey = DoubleKey(configurationVersionKey.keyName)
      val sc = Setup(initCK).madd(configurationNameKey -> "config1", cvKey -> 1.0)
      // Should be invalid
      checkForWrongItemType(initValidation(sc))
    }
  }

  /**
   * Test Description: This tests the validation of the move SC
   */
  describe("testing validation of move setupconfig") {
    it("should fail if not a move") {
      val sc = Setup(positionCK)
      checkForWrongPrefix(moveValidation(sc))
    }

    it("should validate the move setupconfig with 0 args") {
      val sc = Setup(moveCK)
      // Should validate with no arguments
      moveValidation(sc) should be(Valid)
    }

    it("should validate 1 arg move setupconfig") {
      // Create but don't set units
      var sc = Setup(moveCK).add(stagePositionKey -> 22.0)

      // Should fail for units
      checkForWrongUnits(moveValidation(sc))

      // Now add good units
      sc = sc.add(stagePositionKey -> 22.0 withUnits stagePositionUnits)

      // Validates with 1 good argument
      moveValidation(sc) should be(Valid)

      // Should be valid with an extra argument in this case
      sc = sc.add(zenithAngleKey -> 0.0)
      moveValidation(sc) shouldBe Valid
    }
  }

  /**
   * Test Description: This tests the validation of the position SC
   */
  describe("testing validation of position setupconfig") {
    it("should fail if not a position") {
      val sc = Setup(moveCK)
      checkForWrongPrefix(positionValidation(sc))
    }

    it("should fail for missing unitsg") {
      val sc = Setup(positionCK).add(naRangeDistanceKey -> 22.0)

      // Should fail for units
      checkForWrongUnits(positionValidation(sc))
    }

    it("should validate when keys and units present") {
      // Now add good units
      var sc = Setup(positionCK).add(naRangeDistanceKey -> 22.0 withUnits naRangeDistanceUnits)

      // Should validate with 1 good argument
      positionValidation(sc) shouldBe Valid

      // Should be valid with an extra argument in this case
      sc = sc.add(zenithAngleKey -> 0.0)
      positionValidation(sc) shouldBe Valid
    }

    it("should fail for negative range distance value") {
      // Now  good units with neg value
      val sc = Setup(positionCK).add(naRangeDistanceKey -> -22.0 withUnits naRangeDistanceUnits)
      checkForOutOfRange(positionValidation(sc))
    }
  }

  /**
   * Test Description: This tests the validation of the setElevation SC
   */
  describe("testing validation for setElevation command") {

    it("should fail if not a setElevation") {
      val sc = Setup(initCK)
      checkForWrongPrefix(setElevationValidation(sc))
    }

    it("should vail to vailidate for missing units and keys") {
      // First check for missing args
      var sc = Setup(setElevationCK)
      checkForMissingKeys(setElevationValidation(sc))

      // Should validate with 2 good arguments
      sc = sc.madd(zenithAngleKey -> 0.0, naElevationKey -> 100.0)
      checkForWrongUnits(setElevationValidation(sc))
    }

    it("should validate 2 arg setElevation setupconfig") {
      var sc = Setup(setElevationCK).madd(zenithAngleKey -> 0.0 withUnits zenithAngleUnits, naElevationKey -> 100.0 withUnits naElevationUnits)
      setElevationValidation(sc) should be(Valid)

      // Should ignore an extra parameter
      sc = sc.add(naRangeDistanceKey -> 0.0)
      setElevationValidation(sc) shouldBe Valid
    }

    it("should check for init item types") {
      // Make a key with the correct name that isn't the right type
      val cvKey = DoubleKey(configurationVersionKey.keyName)
      val sc = Setup(initCK).madd(configurationNameKey -> "config1", cvKey -> 1.0)
      // Should be invalid
      //val result = initValidation(sc)
      //info("result: " + result)
      initValidation(sc).isInstanceOf[Invalid] should be(true)
    }
  }

  /**
   * Test Description: Test tests the validation of a setAngle SC
   */
  describe("testing validation of setAngle setupconfig") {
    it("should fail if not a setAngle") {
      val sc = Setup(moveCK)
      checkForWrongPrefix(setAngleValidation(sc))
    }

    it("should fail for missing key") {
      val sc = Setup(setAngleCK)

      // Should fail for units
      checkForMissingKeys(setAngleValidation(sc))
    }

    it("should fail for missing units") {
      val sc = Setup(setAngleCK).add(zenithAngleKey -> 22.0)

      // Should fail for units
      checkForWrongUnits(setAngleValidation(sc))
    }

    it("should validate when keys and units present") {
      // Now add good units
      var sc = Setup(setAngleCK).add(zenithAngleKey -> 22.0 withUnits zenithAngleUnits)

      // Should validate with 1 good argument
      setAngleValidation(sc) should be(Valid)

      // Should be valid with an extra argument in this case
      sc = sc.add(naElevationKey -> 0.0)
      setAngleValidation(sc) shouldBe Valid
    }
  }

  /**
   * Test Description: This tests the validation of a follow SC
   */
  describe("testing validation of follow setupconfig") {
    it("should fail if not a follow") {
      val sc = Setup(moveCK)
      checkForWrongPrefix(followValidation(sc))
    }

    it("should fail for missing key") {
      val sc = Setup(followCK)

      // Should fail for units
      checkForMissingKeys(followValidation(sc))
    }

    it("should validate when key present") {
      var sc = Setup(followCK).add(nssInUseKey -> true)

      // Should validate with 1 good argument
      followValidation(sc) should be(Valid)

      // Should be valid with an extra argument in this case
      sc = sc.add(naElevationKey -> 0.0)
      followValidation(sc) shouldBe Valid
    }
  }

  /**
   * Test Description: This is a test of the SetupARg validation routine in TromboneAssembly
   */
  describe("Test of TromboneAssembly validation") {
    //implicit val tc = AssemblyTestData.TestAssemblyContext

//    it("should work with okay sca") {
//      val sca = Parameters.createSetupArg("testobsId", Setup(initCK), Setup(stopCK))
//
//      val issues = invalidsInTromboneSetupArg(sca)
//      issues shouldBe empty
//    }

//    it("should show a single issue") {
//      // positionCK requires an argument
//      val sca = Parameters.createSetupArg("testobsId", Setup(initCK), Setup(positionCK))
//      val issues: Seq[Invalid] = invalidsInTromboneSetupArg(sca)
//      issues should not be empty
//      issues.size should be(1)
//      checkForMissingKeys(issues.head)
//    }

//    it("should show multiple issues") {
//      // positionCK needs an argument and moveCK has the wrong units
//      val sca = Parameters.createSetupArg(
//        "testobsId",
//        Setup(initCK),
//        Setup(positionCK),
//        Setup(moveCK).add(stagePositionKey -> 22 withUnits kilometers)
//      )
//      val issues = invalidsInTromboneSetupArg(sca)
//      issues should not be empty
//      issues.size should be(2)
//      checkForMissingKeys(issues.head)
//      checkForWrongUnits(issues(1))
//    }

    it("should convert validation invalid successfully to a CommandResponse invalid") {
      //import csw.services.ccs.CommandResponse.Invalid
      val testmessage = "test message"

      val t1 = Invalid(WrongPrefixIssue(testmessage))

      val c1 = CommandResponse.Invalid(t1)
      c1.issue shouldBe a[WrongPrefixIssue]
      c1.issue.reason should equal(testmessage)

    }

    it("should convert validation result to comand status result") {
      val sca = Parameters.createSetupArg("testobsId", Setup(initCK), Setup(positionCK), Setup(moveCK).add(stagePositionKey -> 22 withUnits kilometers))

      // Check if validated properly
      val validations = ParamValidation.validateTromboneSetupArg(sca)
      validations.size should equal(sca.configs.size)
      validations.head shouldBe Valid
      validations(1) shouldBe a[Invalid]
      validations(2) shouldBe a[Invalid]

      // Convert to pairs
      val cresult = CommandResponse.validationsToCommandResultPairs(sca.configs, validations)
      cresult.size should equal(sca.configs.size)
      cresult.head.status shouldBe CommandResponse.Valid
      cresult.head.config should equal(sca.configs.head)

      cresult(1).status shouldBe a[CommandResponse.Invalid]
      cresult(1).config should equal(sca.configs(1))

      cresult(2).status shouldBe a[CommandResponse.Invalid]
      cresult(2).config should equal(sca.configs(2))

      // Is correct overall returned
      CommandResponse.validationsToOverallCommandStatus(validations) shouldBe NotAccepted

      // Same with no errors
      val sca2 = Parameters.createSetupArg("testobsId", Setup(initCK), positionSC(22.0), moveSC(44.0))

      val validations2 = ParamValidation.validateTromboneSetupArg(sca2)
      isAllValid(validations2) shouldBe true

    }

  }

}

