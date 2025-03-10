package org.hl7.fhir.r4.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.fhirpath.BaseValidationTestWithInlineMocks;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CustomResourceGenerationTest extends BaseValidationTestWithInlineMocks {

	private static final Logger ourLog = LoggerFactory.getLogger(CustomResourceGenerationTest.class);
	private FhirContext myCtx = FhirContext.forR4();

	@Test
	public void testValidateCustomResource() throws IOException {

		StructureDefinition customProfile = loadResource(myCtx, StructureDefinition.class, "/r4/custom-resource-profile.json");
		String customResource = loadResource("/r4/custom-resource.json");

		PrePopulatedValidationSupport prePopulatedValidationSupport = new PrePopulatedValidationSupport(myCtx);
		prePopulatedValidationSupport.addStructureDefinition(customProfile);

		DefaultProfileValidationSupport defaultProfileValidationSupport = new DefaultProfileValidationSupport(myCtx);
		ValidationSupportChain validationSupport = new ValidationSupportChain(defaultProfileValidationSupport, prePopulatedValidationSupport);

		FhirValidator validator = myCtx.newValidator();
		validator.registerValidatorModule(new FhirInstanceValidator(validationSupport));

		ValidationResult result = validator.validateWithResult(customResource);

		String outcome = myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(result.toOperationOutcome());
		ourLog.info(outcome);

		assertEquals(3, result.getMessages().size());
		assertEquals("Error parsing JSON: the primitive value must be a boolean", result.getMessages().get(0).getMessage());
		assertEquals("The property name  must be a JSON Array, not a Primitive property (at CustomResource)", result.getMessages().get(1).getMessage());
		assertEquals("Unrecognized property 'id1'", result.getMessages().get(2).getMessage());

	}

}
