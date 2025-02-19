package ca.uhn.fhir.jpa.dao.r4;

import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.ResourceSearch;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import ca.uhn.fhir.jpa.util.SqlQuery;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.MessageHeader;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.SearchParameter;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.countMatches;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;


public class ChainingR4SearchTest extends BaseJpaR4Test {

	@Autowired
	MatchUrlService myMatchUrlService;

	@AfterEach
	public void after() throws Exception {

		myStorageSettings.setAllowMultipleDelete(new JpaStorageSettings().isAllowMultipleDelete());
		myStorageSettings.setAllowExternalReferences(new JpaStorageSettings().isAllowExternalReferences());
		myStorageSettings.setReuseCachedSearchResultsForMillis(new JpaStorageSettings().getReuseCachedSearchResultsForMillis());
		myStorageSettings.setCountSearchResultsUpTo(new JpaStorageSettings().getCountSearchResultsUpTo());
		myStorageSettings.setSearchPreFetchThresholds(new JpaStorageSettings().getSearchPreFetchThresholds());
		myStorageSettings.setAllowContainsSearches(new JpaStorageSettings().isAllowContainsSearches());
		myStorageSettings.setIndexMissingFields(new JpaStorageSettings().getIndexMissingFields());
		myStorageSettings.setIndexOnContainedResources(new JpaStorageSettings().isIndexOnContainedResources());
		myStorageSettings.setIndexOnContainedResourcesRecursively(new JpaStorageSettings().isIndexOnContainedResourcesRecursively());
	}

	@Override
	@BeforeEach
	public void before() throws Exception {
		super.before();
		myFhirContext.setParserErrorHandler(new StrictErrorHandler());

		myStorageSettings.setAllowMultipleDelete(true);
		myStorageSettings.setSearchPreFetchThresholds(new JpaStorageSettings().getSearchPreFetchThresholds());
		myStorageSettings.setReuseCachedSearchResultsForMillis(null);
		myStorageSettings.setIndexMissingFields(JpaStorageSettings.IndexEnabledEnum.DISABLED);
	}

	@Test
	public void testIndexSearchParamPointingToResource() {
		// Setup

		myStorageSettings.setIndexOnContainedResources(true);

		Bundle inputBundle = new Bundle();
		inputBundle.setType(Bundle.BundleType.MESSAGE);

		MessageHeader msgHeader = new MessageHeader();
		msgHeader.setEvent(new Coding("http://foo", "bar", "blah"));
		inputBundle.addEntry().setResource(msgHeader);

		RuntimeSearchParam sp = mySearchParamRegistry.getActiveSearchParam("Bundle", "message");
		assertEquals("Bundle.entry[0].resource", sp.getPath());
		assertThat(sp.getBase(), contains("Bundle"));
		assertEquals(RuntimeSearchParam.RuntimeSearchParamStatusEnum.ACTIVE, sp.getStatus());

		// Test
		myBundleDao.create(inputBundle, mySrd);

		// Verify - We'll check that the right indexes got written, but the main test is that
		// the create step didn't crash
		runInTransaction(()->{
			assertEquals(0, myResourceIndexedSearchParamStringDao.count());
			assertEquals(1, myResourceIndexedSearchParamTokenDao.count());
			assertEquals(0, myResourceLinkDao.count());
		});
	}


	@Test
	public void testShouldResolveATwoLinkChainWithStandAloneResourcesWithoutContainedResourceIndexing() throws Exception {

		// setup
		IIdType oid1;

		{
			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.name=Smith";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveATwoLinkChainWithStandAloneResources() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.name=Smith";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveATwoLinkChainWithStandAloneResources_CommonReference() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			myPatientDao.create(p, mySrd);

			Encounter encounter = new Encounter();
			encounter.setId(IdType.newRandomUuid());
			encounter.addIdentifier().setSystem("foo").setValue("bar");
			myEncounterDao.create(encounter, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Body Weight");
			obs.getCode().addCoding().setCode("obs2").setSystem("Some System").setDisplay("Body weight as measured by me");
			obs.setStatus(Observation.ObservationStatus.FINAL);
			obs.setValue(new Quantity(81));
			obs.setSubject(new Reference(p.getId()));
			obs.setEncounter(new Reference(encounter.getId()));
			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?encounter.identifier=foo|bar";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveATwoLinkChainWithStandAloneResources_CompoundReference() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;
		IIdType oid2;

		// AuditEvent should match both AuditEvent.agent.who and AuditEvent.entity.what
		{
			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			myPatientDao.create(p, mySrd);

			AuditEvent auditEvent = new AuditEvent();
			auditEvent.addAgent().setWho(new Reference(p.getId()));
			oid1 = myAuditEventDao.create(auditEvent, mySrd).getId().toUnqualifiedVersionless();
		}
		{
			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			myPatientDao.create(p, mySrd);

			AuditEvent auditEvent = new AuditEvent();
			auditEvent.addEntity().setWhat(new Reference(p.getId()));
			oid2 = myAuditEventDao.create(auditEvent, mySrd).getId().toUnqualifiedVersionless();
		}
		{
			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning all the records
			myAuditEventDao.create(new AuditEvent(), mySrd);
		}

		String url = "/AuditEvent?patient.name=Smith";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url, myAuditEventDao);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(2L, oids.size());
		assertThat(oids, contains(oid1.getIdPart(), oid2.getIdPart()));
	}

	@Test
	public void testShouldResolveATwoLinkChainWithContainedResources_CompoundReference() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;
		IIdType oid2;

		// AuditEvent should match both AuditEvent.agent.who and AuditEvent.entity.what
		{
			Patient p = new Patient();
			p.setId("p1");
			p.addName().setFamily("Smith").addGiven("John");

			AuditEvent auditEvent = new AuditEvent();
			auditEvent.addContained(p);
			auditEvent.addAgent().setWho(new Reference("#p1"));
			oid1 = myAuditEventDao.create(auditEvent, mySrd).getId().toUnqualifiedVersionless();
		}
		{
			Patient p = new Patient();
			p.setId("p2");
			p.addName().setFamily("Smith").addGiven("John");

			AuditEvent auditEvent = new AuditEvent();
			auditEvent.addContained(p);
			auditEvent.addEntity().setWhat(new Reference("#p2"));
			oid2 = myAuditEventDao.create(auditEvent, mySrd).getId().toUnqualifiedVersionless();
		}
		{
			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning all the records
			myAuditEventDao.create(new AuditEvent(), mySrd);
		}

		String url = "/AuditEvent?patient.name=Smith";
		logAllStringIndexes();

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url, myAuditEventDao);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(2L, oids.size());
		assertThat(oids, contains(oid1.getIdPart(), oid2.getIdPart()));
	}

	@Test
	public void testShouldResolveATwoLinkChainWithAContainedResource() throws Exception {
		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");

			Observation obs = new Observation();
			obs.getContained().add(p);

			obs.getCode().addCoding().setCode("29463-7").setSystem("http://loinc.org").setDisplay("Body Weight");
			obs.setStatus(Observation.ObservationStatus.FINAL);
			obs.setSubject(new Reference(p.getId()));
			obs.setValue(new Quantity(null, 67.1, "http://unitsofmeasure.org", "kg", "kg"));
			obs.getSubject().setReference("#pat");

			ourLog.debug("Resource: {}", myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(obs));

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myCaptureQueriesListener.clear();
			myObservationDao.create(new Observation(), mySrd);
			myCaptureQueriesListener.logInsertQueries();
		}

		String url = "/Observation?subject.name=Smith";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldNotResolveATwoLinkChainWithAContainedResourceWhenContainedResourceIndexingIsTurnedOff() throws Exception {
		// setup
		IIdType oid1;

		{
			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.setValue(new StringType("Test"));
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.name=Smith";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(0L, oids.size());
	}

	@Test
	@Disabled
	public void testShouldResolveATwoLinkChainWithQualifiersWithAContainedResource() throws Exception {
		// TODO: This test fails because of a known limitation in qualified searches over contained resources.
		//       Type information for intermediate resources in the chain is not being retained in the indexes.
		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.setValue(new StringType("Test"));
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			Location loc = new Location();
			loc.setId("loc");
			loc.setName("Smith");

			Observation obs2 = new Observation();
			obs2.getContained().add(loc);
			obs2.getCode().setText("Observation 2");
			obs2.setValue(new StringType("Test"));
			obs2.getSubject().setReference("#loc");

			myObservationDao.create(obs2, mySrd);

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject:Patient.name=Smith";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveATwoLinkChainToAContainedReference() throws Exception {
		// Adding support for this case in SMILE-3151

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;
		IIdType orgId;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			orgId = myOrganizationDao.create(org, mySrd).getId();

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(org.getId());

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization=" + orgId.getValueAsString();

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveATwoLinkChainToAStandAloneReference() throws Exception {
		// Adding support for this case in SMILE-3151

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;
		IIdType orgId;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			orgId = myOrganizationDao.create(org, mySrd).getId();

			Patient p = new Patient();
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(org.getId());
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization=" + orgId.getValueAsString();

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveATwoLinkChainWithAContainedResource_CommonReference() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Encounter encounter = new Encounter();
			encounter.setId("enc");
			encounter.addIdentifier().setSystem("foo").setValue("bar");

			Observation obs = new Observation();
			obs.getCode().setText("Body Weight");
			obs.getCode().addCoding().setCode("obs2").setSystem("Some System").setDisplay("Body weight as measured by me");
			obs.setStatus(Observation.ObservationStatus.FINAL);
			obs.setValue(new Quantity(81));

			obs.addContained(encounter);
			obs.setEncounter(new Reference("#enc"));

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?encounter.identifier=foo|bar";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWhereAllResourcesStandAloneWithoutContainedResourceIndexing() throws Exception {

		// setup
		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(org.getId());
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			Organization dummyOrg = new Organization();
			dummyOrg.setId(IdType.newRandomUuid());
			dummyOrg.setName("Dummy");
			myOrganizationDao.create(dummyOrg, mySrd);

			Patient dummyPatient = new Patient();
			dummyPatient.setId(IdType.newRandomUuid());
			dummyPatient.addName().setFamily("Jones").addGiven("Jane");
			dummyPatient.getManagingOrganization().setReference(dummyOrg.getId());
			myPatientDao.create(dummyPatient, mySrd);

			Observation dummyObs = new Observation();
			dummyObs.getCode().setText("Observation 2");
			dummyObs.getSubject().setReference(dummyPatient.getId());
			myObservationDao.create(dummyObs, mySrd);
		}

		String url = "/Observation?subject.organization.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWhereAllResourcesStandAlone() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(org.getId());
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			Organization dummyOrg = new Organization();
			dummyOrg.setId(IdType.newRandomUuid());
			dummyOrg.setName("Dummy");
			myOrganizationDao.create(dummyOrg, mySrd);

			Patient dummyPatient = new Patient();
			dummyPatient.setId(IdType.newRandomUuid());
			dummyPatient.addName().setFamily("Jones").addGiven("Jane");
			dummyPatient.getManagingOrganization().setReference(dummyOrg.getId());
			myPatientDao.create(dummyPatient, mySrd);

			Observation dummyObs = new Observation();
			dummyObs.getCode().setText("Observation 2");
			dummyObs.getSubject().setReference(dummyPatient.getId());
			myObservationDao.create(dummyObs, mySrd);
		}

		String url = "/Observation?subject.organization.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWithAContainedResourceAtTheEndOfTheChain() throws Exception {
		// This is the case that is most relevant to SMILE-2899

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("org");
			org.setName("HealthCo");

			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.getContained().add(org);
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference("#org");
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWithAContainedResourceAtTheEndOfTheChain_CommonReference() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");

			Encounter encounter = new Encounter();
			encounter.addContained(p);
			encounter.setId(IdType.newRandomUuid());
			encounter.addIdentifier().setSystem("foo").setValue("bar");
			encounter.setSubject(new Reference("#pat"));
			myEncounterDao.create(encounter, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Body Weight");
			obs.getCode().addCoding().setCode("obs2").setSystem("Some System").setDisplay("Body weight as measured by me");
			obs.setStatus(Observation.ObservationStatus.FINAL);
			obs.setValue(new Quantity(81));
			obs.setSubject(new Reference(p.getId()));
			obs.setEncounter(new Reference(encounter.getId()));
			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?encounter.patient.name=Smith";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWithAContainedResourceAtTheBeginningOfTheChain() throws Exception {
		// Adding support for this case in SMILE-3151

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(org.getId());

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWithAContainedResourceAtTheBeginningOfTheChain_CommonReference() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			myPatientDao.create(p, mySrd);

			Encounter encounter = new Encounter();
			encounter.setId("enc");
			encounter.addIdentifier().setSystem("foo").setValue("bar");
			encounter.setSubject(new Reference(p.getId()));

			Observation obs = new Observation();
			obs.addContained(encounter);
			obs.getCode().setText("Body Weight");
			obs.getCode().addCoding().setCode("obs2").setSystem("Some System").setDisplay("Body weight as measured by me");
			obs.setStatus(Observation.ObservationStatus.FINAL);
			obs.setValue(new Quantity(81));
			obs.setEncounter(new Reference("#enc"));
			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?encounter.identifier=foo|bar";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldNotResolveAThreeLinkChainWithAllContainedResourcesWhenRecursiveContainedIndexesAreDisabled() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("org");
			org.setName("HealthCo");

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference("#org");

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getContained().add(org);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(0L, oids.size());
	}

	@Test
	public void testShouldResolveAThreeLinkChainWithAllContainedResources() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);
		myStorageSettings.setIndexOnContainedResourcesRecursively(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("org");
			org.setName("HealthCo");

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference("#org");

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getContained().add(org);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.name=HealthCo";
		logAllStringIndexes();

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWithQualifiersWhereAllResourcesStandAlone() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(org.getId());
			myPatientDao.create(p, mySrd);

			Device d = new Device();
			d.setId(IdType.newRandomUuid());
			d.getOwner().setReference(org.getId());
			myDeviceDao.create(d, mySrd);

			Observation obs1 = new Observation();
			obs1.getCode().setText("Observation 1");
			obs1.getSubject().setReference(p.getId());

			Observation obs2 = new Observation();
			obs2.getCode().setText("Observation 2");
			obs2.getSubject().setReference(d.getId());

			oid1 = myObservationDao.create(obs1, mySrd).getId().toUnqualifiedVersionless();
			myObservationDao.create(obs2, mySrd);

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject:Patient.organization:Organization.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWithQualifiersWithAContainedResourceAtTheEndOfTheChain() throws Exception {
		// This is the case that is most relevant to SMILE-2899

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("org");
			org.setName("HealthCo");

			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.getContained().add(org);
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference("#org");
			myPatientDao.create(p, mySrd);

			Organization org2 = new Organization();
			org2.setId("org");
			org2.setName("HealthCo");

			Device d = new Device();
			d.setId(IdType.newRandomUuid());
			d.getContained().add(org2);
			d.getOwner().setReference("#org");
			myDeviceDao.create(d, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			Observation obs2 = new Observation();
			obs2.getCode().setText("Observation 2");
			obs2.getSubject().setReference(d.getId());
			myObservationDao.create(obs2, mySrd);

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject:Patient.organization:Organization.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAThreeLinkChainWithQualifiersWithAContainedResourceAtTheBeginning() throws Exception {
		// Adding support for this case in SMILE-3151

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(org.getId());

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			Device d = new Device();
			d.setId("dev");
			d.getOwner().setReference(org.getId());

			Observation obs2 = new Observation();
			obs2.getContained().add(d);
			obs2.getCode().setText("Observation 2");
			obs2.getSubject().setReference("#dev");

			myObservationDao.create(obs2, mySrd);

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject:Patient.organization:Organization.name=HealthCo";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	@Disabled
	public void testShouldResolveAThreeLinkChainWithQualifiersWithAContainedResourceAtTheBeginning_NotDistinctSourcePaths() throws Exception {
		// TODO: This test fails because of a known limitation in qualified searches over contained resources.
		//       Type information for intermediate resources in the chain is not being retained in the indexes.

		// Adding support for this case in SMILE-3151

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(org.getId());

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			Location loc = new Location();
			loc.setId("loc");
			loc.getManagingOrganization().setReference(org.getId());

			Observation obs2 = new Observation();
			obs2.getContained().add(loc);
			obs2.getCode().setText("Observation 2");
			obs2.getSubject().setReference("#loc");

			myObservationDao.create(obs2, mySrd);

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject:Patient.organization:Organization.name=HealthCo";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	@Disabled
	public void testShouldResolveAThreeLinkChainWithQualifiersWithAllContainedResources() throws Exception {
		// TODO: This test fails because of a known limitation in qualified searches over contained resources.
		//       Type information for intermediate resources in the chain is not being retained in the indexes.

		// setup
		myStorageSettings.setIndexOnContainedResources(true);
		myStorageSettings.setIndexOnContainedResourcesRecursively(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("org");
			org.setName("HealthCo");

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference("#org");

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getContained().add(org);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			Organization org2 = new Organization();
			org2.setId("org");
			org2.setName("HealthCo");

			Device d = new Device();
			d.setId("dev");
			d.getOwner().setReference("#org");

			Observation obs2 = new Observation();
			obs2.getContained().add(d);
			obs2.getContained().add(org2);
			obs2.getCode().setText("Observation 2");
			obs2.getSubject().setReference("#dev");

			myObservationDao.create(obs2, mySrd);

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject:Patient.organization:Organization.name=HealthCo";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAFourLinkChainWhereAllResourcesStandAlone() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Organization partOfOrg = new Organization();
			partOfOrg.setId(IdType.newRandomUuid());
			partOfOrg.getPartOf().setReference(org.getId());
			myOrganizationDao.create(partOfOrg, mySrd);

			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(partOfOrg.getId());
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.partof.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAFourLinkChainWhereTheLastReferenceIsContained() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("parent");
			org.setName("HealthCo");

			Organization partOfOrg = new Organization();
			partOfOrg.setId(IdType.newRandomUuid());
			partOfOrg.getContained().add(org);
			partOfOrg.getPartOf().setReference("#parent");
			myOrganizationDao.create(partOfOrg, mySrd);

			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(partOfOrg.getId());
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.partof.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAFourLinkChainWhereTheLastTwoReferencesAreContained() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);
		myStorageSettings.setIndexOnContainedResourcesRecursively(true);
		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("parent");
			org.setName("HealthCo");

			Organization partOfOrg = new Organization();
			partOfOrg.setId("child");
			partOfOrg.getPartOf().setReference("#parent");

			Patient p = new Patient();
			p.getContained().add(org);
			p.getContained().add(partOfOrg);
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference("#child");
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.partof.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAFourLinkChainWithAContainedResourceInTheMiddle() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);

		IIdType oid1;

		{
			myCaptureQueriesListener.clear();

			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Organization partOfOrg = new Organization();
			partOfOrg.setId("org");
			partOfOrg.getPartOf().setReference(org.getId());

			Patient p = new Patient();
			p.setId(IdType.newRandomUuid());
			p.addName().setFamily("Smith").addGiven("John");
			p.getContained().add(partOfOrg);
			p.getManagingOrganization().setReference("#org");
			myPatientDao.create(p, mySrd);

			Observation obs = new Observation();
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference(p.getId());

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			myCaptureQueriesListener.logInsertQueries();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.partof.name=HealthCo";

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueries();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAFourLinkChainWhereTheFirstTwoReferencesAreContained() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);
		myStorageSettings.setIndexOnContainedResourcesRecursively(true);
		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId(IdType.newRandomUuid());
			org.setName("HealthCo");
			myOrganizationDao.create(org, mySrd);

			Organization partOfOrg = new Organization();
			partOfOrg.setId("child");
			partOfOrg.getPartOf().setReference(org.getId());

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference("#child");

			Observation obs = new Observation();
			obs.getContained().add(org);
			obs.getContained().add(partOfOrg);
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.partof.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAFourLinkChainWhereTheFirstReferenceAndTheLastReferenceAreContained() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);
		myStorageSettings.setIndexOnContainedResourcesRecursively(true);
		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("parent");
			org.setName("HealthCo");

			Organization partOfOrg = new Organization();
			partOfOrg.getContained().add(org);
			partOfOrg.setId(IdType.newRandomUuid());
			partOfOrg.getPartOf().setReference("#parent");
			myOrganizationDao.create(partOfOrg, mySrd);

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference(partOfOrg.getId());

			Observation obs = new Observation();
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.partof.name=HealthCo";

		// execute
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldResolveAFourLinkChainWhereAllReferencesAreContained() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);
		myStorageSettings.setIndexOnContainedResourcesRecursively(true);
		IIdType oid1;

		{
			Organization org = new Organization();
			org.setId("parent");
			org.setName("HealthCo");

			Organization partOfOrg = new Organization();
			partOfOrg.setId("child");
			partOfOrg.getPartOf().setReference("#parent");

			Patient p = new Patient();
			p.setId("pat");
			p.addName().setFamily("Smith").addGiven("John");
			p.getManagingOrganization().setReference("#child");

			Observation obs = new Observation();
			obs.getContained().add(org);
			obs.getContained().add(partOfOrg);
			obs.getContained().add(p);
			obs.getCode().setText("Observation 1");
			obs.getSubject().setReference("#pat");

			oid1 = myObservationDao.create(obs, mySrd).getId().toUnqualifiedVersionless();

			// Create a dummy record so that an unconstrained query doesn't pass the test due to returning the only record
			myObservationDao.create(new Observation(), mySrd);
		}

		String url = "/Observation?subject.organization.partof.name=HealthCo";

		logAllStringIndexes();

		// execute
		myCaptureQueriesListener.clear();
		List<String> oids = searchAndReturnUnqualifiedVersionlessIdValues(url);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();

		// validate
		assertEquals(1L, oids.size());
		assertThat(oids, contains(oid1.getIdPart()));
	}

	@Test
	public void testShouldThrowAnExceptionForAFiveLinkChain() throws Exception {

		// setup
		myStorageSettings.setIndexOnContainedResources(true);
		myStorageSettings.setIndexOnContainedResourcesRecursively(true);

		String url = "/Observation?subject.organization.partof.partof.name=HealthCo";

		try {
			// execute
			searchAndReturnUnqualifiedVersionlessIdValues(url);
			fail("Expected an exception to be thrown");
		} catch (InvalidRequestException e) {
			assertEquals(Msg.code(2007) + "The search chain subject.organization.partof.partof.name is too long. Only chains up to three references are supported.", e.getMessage());
		}
	}

	@Test
	public void testQueryStructure() throws Exception {

		// With indexing of contained resources turned off, we should not see UNION clauses in the query
		countUnionStatementsInGeneratedQuery("/Observation?patient.name=Smith", 0);
		countUnionStatementsInGeneratedQuery("/Observation?patient.organization.name=Smith", 0);
		countUnionStatementsInGeneratedQuery("/Observation?patient.organization.partof.name=Smith", 0);

		// With indexing of contained resources turned on, we take the UNION of several subselects that handle the different patterns of containment
		//  Keeping in mind that the number of clauses is one greater than the number of UNION keywords,
		//  this increases as the chain grows longer according to the Fibonacci sequence: (2, 3, 5, 8, 13)
		myStorageSettings.setIndexOnContainedResources(true);
		countUnionStatementsInGeneratedQuery("/Observation?patient.name=Smith", 1);
		countUnionStatementsInGeneratedQuery("/Observation?patient.organization.name=Smith", 2);
		countUnionStatementsInGeneratedQuery("/Observation?patient.organization.partof.name=Smith", 4);

		// With recursive indexing of contained resources turned on, even more containment patterns are considered
		//  This increases as the chain grows longer as powers of 2: (2, 4, 8, 16, 32)
		myStorageSettings.setIndexOnContainedResourcesRecursively(true);
		countUnionStatementsInGeneratedQuery("/Observation?patient.name=Smith", 1);
		countUnionStatementsInGeneratedQuery("/Observation?patient.organization.name=Smith", 3);
		countUnionStatementsInGeneratedQuery("/Observation?patient.organization.partof.name=Smith", 7);

		// If a reference in the chain has multiple potential target resource types, the number of subselects increases
		// Note: This previously had 3 unions but 2 of the selects within were duplicates of each other
		countUnionStatementsInGeneratedQuery("/Observation?subject.name=Smith", 2);

		// If such a reference if qualified to restrict the type, the number goes back down
		countUnionStatementsInGeneratedQuery("/Observation?subject:Location.name=Smith", 1);
	}

	@ParameterizedTest
	@CsvSource({
		// search url                                                                                       expected count
		"/Bundle?composition.patient.identifier=system|value-1&composition.patient.birthdate=1980-01-01,    1",     // correct identifier, correct birthdate
		"/Bundle?composition.patient.birthdate=1980-01-01&composition.patient.identifier=system|value-1,    1",     // correct birthdate,  correct identifier
		"/Bundle?composition.patient.identifier=system|value-1&composition.patient.birthdate=2000-01-01,    0",     // correct identifier, incorrect birthdate
		"/Bundle?composition.patient.birthdate=2000-01-01&composition.patient.identifier=system|value-1,    0",     // incorrect birthdate, correct identifier
		"/Bundle?composition.patient.identifier=system|value-2&composition.patient.birthdate=1980-01-01,    0",     // incorrect identifier, correct birthdate
		"/Bundle?composition.patient.birthdate=1980-01-01&composition.patient.identifier=system|value-2,    0",     // correct birthdate,  incorrect identifier
		"/Bundle?composition.patient.identifier=system|value-2&composition.patient.birthdate=2000-01-01,    0",     // incorrect identifier, incorrect birthdate
		"/Bundle?composition.patient.birthdate=2000-01-01&composition.patient.identifier=system|value-2,    0",     // incorrect birthdate,  incorrect identifier
	})
	public void testMultipleChainedBundleCompositionSearchParameters(String theSearchUrl, int theExpectedCount) {
		createSearchParameter("bundle-composition-patient-birthdate",
			"composition.patient.birthdate",
			"Bundle",
			"Bundle.entry.resource.ofType(Patient).birthDate",
			Enumerations.SearchParamType.DATE
		);

		createSearchParameter("bundle-composition-patient-identifier",
			"composition.patient.identifier",
			"Bundle",
			"Bundle.entry.resource.ofType(Patient).identifier",
			Enumerations.SearchParamType.TOKEN
		);

		createDocumentBundleWithPatientDetails("1980-01-01", "system", "value-1");

		SearchParameterMap params = myMatchUrlService.getResourceSearch(theSearchUrl).getSearchParameterMap().setLoadSynchronous(true);
		assertSearchReturns(myBundleDao, params, theExpectedCount);
	}

	private void createSearchParameter(String theId, String theCode, String theBase, String theExpression, Enumerations.SearchParamType theType) {
		SearchParameter searchParameter = new SearchParameter();
		searchParameter.setId(theId);
		searchParameter.setCode(theCode);
		searchParameter.setName(theCode);
		searchParameter.setUrl("http://example.org/SearchParameter/" + theId);
		searchParameter.setStatus(Enumerations.PublicationStatus.ACTIVE);
		searchParameter.addBase(theBase);
		searchParameter.setType(theType);
		searchParameter.setExpression(theExpression);
		searchParameter = (SearchParameter) mySearchParameterDao.update(searchParameter, mySrd).getResource();
		mySearchParamRegistry.forceRefresh();
		assertNotNull(mySearchParamRegistry.getActiveSearchParam(theBase, searchParameter.getName()));
	}

	private void createDocumentBundleWithPatientDetails(String theBirthDate, String theIdentifierSystem, String theIdentifierValue) {
		Patient patient = new Patient();
		patient.setBirthDate(Date.valueOf(theBirthDate));
		patient.addIdentifier().setSystem(theIdentifierSystem).setValue(theIdentifierValue);
		patient = (Patient) myPatientDao.create(patient, mySrd).getResource();
		assertSearchReturns(myPatientDao, SearchParameterMap.newSynchronous(), 1);

		Bundle bundle = new Bundle();
		bundle.setType(Bundle.BundleType.DOCUMENT);
		Composition composition = new Composition();
		composition.setType(new CodeableConcept().addCoding(new Coding().setCode("code").setSystem("http://example.org")));
		bundle.addEntry().setResource(composition);
		composition.getSubject().setReference(patient.getIdElement().getValue());
		bundle.addEntry().setResource(patient);
		myBundleDao.create(bundle, mySrd);
		assertSearchReturns(myBundleDao, SearchParameterMap.newSynchronous(), 1);
	}

	private void assertSearchReturns(IFhirResourceDao<?> theDao, SearchParameterMap theSearchParams, int theExpectedCount){
		assertEquals(theExpectedCount, theDao.search(theSearchParams, mySrd).size());
	}

	private void countUnionStatementsInGeneratedQuery(String theUrl, int theExpectedNumberOfUnions) throws IOException {
		myCaptureQueriesListener.clear();
		searchAndReturnUnqualifiedVersionlessIdValues(theUrl);
		List<SqlQuery> selectQueries = myCaptureQueriesListener.getSelectQueriesForCurrentThread();
		assertEquals(1, selectQueries.size());

		String sqlQuery = selectQueries.get(0).getSql(true, true).toLowerCase();
		assertEquals(theExpectedNumberOfUnions, countMatches(sqlQuery, "union"), sqlQuery);
	}

	private List<String> searchAndReturnUnqualifiedVersionlessIdValues(String theUrl) throws IOException {
		return searchAndReturnUnqualifiedVersionlessIdValues(theUrl, myObservationDao);
	}

	private List<String> searchAndReturnUnqualifiedVersionlessIdValues(String theUrl, IFhirResourceDao<? extends DomainResource> theObservationDao) {
		List<String> ids = new ArrayList<>();

		ResourceSearch search = myMatchUrlService.getResourceSearch(theUrl);
		SearchParameterMap map = search.getSearchParameterMap();
		map.setLoadSynchronous(true);
		IBundleProvider result = theObservationDao.search(map);
		return result.getAllResourceIds();
	}

}
