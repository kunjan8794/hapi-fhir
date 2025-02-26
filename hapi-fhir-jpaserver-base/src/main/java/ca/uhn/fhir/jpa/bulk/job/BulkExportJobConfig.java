package ca.uhn.fhir.jpa.bulk.job;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2021 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.jpa.batch.BatchJobsConfig;
import ca.uhn.fhir.jpa.batch.processors.PidToIBaseResourceProcessor;
import ca.uhn.fhir.jpa.bulk.svc.BulkExportDaoSvc;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * Spring batch Job configuration file. Contains all necessary plumbing to run a
 * Bulk Export job.
 */
@Configuration
public class BulkExportJobConfig {

	public static final String JOB_UUID_PARAMETER = "jobUUID";
	public static final String READ_CHUNK_PARAMETER = "readChunkSize";
	public static final String EXPAND_MDM_PARAMETER = "expandMdm";
	public static final String GROUP_ID_PARAMETER = "groupId";
	public static final String RESOURCE_TYPES_PARAMETER = "resourceTypes";
	public static final int CHUNK_SIZE = 100;

	@Autowired
	private StepBuilderFactory myStepBuilderFactory;

	@Autowired
	private JobBuilderFactory myJobBuilderFactory;

	@Autowired
	private PidToIBaseResourceProcessor myPidToIBaseResourceProcessor;

	@Bean
	public BulkExportDaoSvc bulkExportDaoSvc() {
		return new BulkExportDaoSvc();
	}

	@Bean
	@Lazy
	public Job bulkExportJob() {
		return myJobBuilderFactory.get(BatchJobsConfig.BULK_EXPORT_JOB_NAME)
			.validator(bulkJobParameterValidator())
			.start(createBulkExportEntityStep())
			.next(partitionStep())
			.next(closeJobStep())
			.build();
	}

	@Bean
	@Lazy
	public Job groupBulkExportJob() {
		return myJobBuilderFactory.get(BatchJobsConfig.GROUP_BULK_EXPORT_JOB_NAME)
			.validator(groupBulkJobParameterValidator())
			.validator(bulkJobParameterValidator())
			.start(createBulkExportEntityStep())
			.next(groupPartitionStep())
			.next(closeJobStep())
			.build();
	}

	@Bean
	@Lazy
	public Job patientBulkExportJob() {
		return myJobBuilderFactory.get(BatchJobsConfig.PATIENT_BULK_EXPORT_JOB_NAME)
			.validator(bulkJobParameterValidator())
			.start(createBulkExportEntityStep())
			.next(patientPartitionStep())
			.next(closeJobStep())
			.build();
	}

	@Bean
	public GroupIdPresentValidator groupBulkJobParameterValidator() {
		return new GroupIdPresentValidator();
	}

	@Bean
	public Step createBulkExportEntityStep() {
		return myStepBuilderFactory.get("createBulkExportEntityStep")
			.tasklet(createBulkExportEntityTasklet())
			.listener(bulkExportCreateEntityStepListener())
			.build();
	}

	@Bean
	public CreateBulkExportEntityTasklet createBulkExportEntityTasklet() {
		return new CreateBulkExportEntityTasklet();
	}

	@Bean
	public JobParametersValidator bulkJobParameterValidator() {
		return new BulkExportJobParameterValidator();
	}

	//Writers
	@Bean
	public Step groupBulkExportGenerateResourceFilesStep() {
		return myStepBuilderFactory.get("groupBulkExportGenerateResourceFilesStep")
			.<List<ResourcePersistentId>, List<IBaseResource>> chunk(CHUNK_SIZE) //1000 resources per generated file, as the reader returns 10 resources at a time.
			.reader(groupBulkItemReader())
			.processor(myPidToIBaseResourceProcessor)
			.writer(resourceToFileWriter())
			.listener(bulkExportGenerateResourceFilesStepListener())
			.build();
	}

	@Bean
	public Step bulkExportGenerateResourceFilesStep() {
		return myStepBuilderFactory.get("bulkExportGenerateResourceFilesStep")
			.<List<ResourcePersistentId>, List<IBaseResource>> chunk(CHUNK_SIZE) //1000 resources per generated file, as the reader returns 10 resources at a time.
			.reader(bulkItemReader())
			.processor(myPidToIBaseResourceProcessor)
			.writer(resourceToFileWriter())
			.listener(bulkExportGenerateResourceFilesStepListener())
			.build();
	}
	@Bean
	public Step patientBulkExportGenerateResourceFilesStep() {
		return myStepBuilderFactory.get("patientBulkExportGenerateResourceFilesStep")
			.<List<ResourcePersistentId>, List<IBaseResource>> chunk(CHUNK_SIZE) //1000 resources per generated file, as the reader returns 10 resources at a time.
			.reader(patientBulkItemReader())
			.processor(myPidToIBaseResourceProcessor)
			.writer(resourceToFileWriter())
			.listener(bulkExportGenerateResourceFilesStepListener())
			.build();
	}

	@Bean
	@JobScope
	public BulkExportJobCloser bulkExportJobCloser() {
		return new BulkExportJobCloser();
	}

	@Bean
	public Step closeJobStep() {
		return myStepBuilderFactory.get("closeJobStep")
			.tasklet(bulkExportJobCloser())
			.build();
	}

	@Bean
	@JobScope
	public BulkExportCreateEntityStepListener bulkExportCreateEntityStepListener() {
		return new BulkExportCreateEntityStepListener();
	}

	@Bean
	@JobScope
	public BulkExportGenerateResourceFilesStepListener bulkExportGenerateResourceFilesStepListener() {
		return new BulkExportGenerateResourceFilesStepListener();
	}

	@Bean
	public Step partitionStep() {
		return myStepBuilderFactory.get("partitionStep")
			.partitioner("bulkExportGenerateResourceFilesStep", bulkExportResourceTypePartitioner())
			.step(bulkExportGenerateResourceFilesStep())
			.build();
	}

	@Bean
	public Step groupPartitionStep() {
		return myStepBuilderFactory.get("partitionStep")
			.partitioner("groupBulkExportGenerateResourceFilesStep", bulkExportResourceTypePartitioner())
			.step(groupBulkExportGenerateResourceFilesStep())
			.build();
	}

	@Bean
	public Step patientPartitionStep() {
		return myStepBuilderFactory.get("partitionStep")
			.partitioner("patientBulkExportGenerateResourceFilesStep", bulkExportResourceTypePartitioner())
			.step(patientBulkExportGenerateResourceFilesStep())
			.build();
	}


	@Bean
	@StepScope
	public GroupBulkItemReader groupBulkItemReader(){
		return new GroupBulkItemReader();
	}

	@Bean
	@StepScope
	public PatientBulkItemReader patientBulkItemReader() {
		return new PatientBulkItemReader();
	}

	@Bean
	@StepScope
	public BulkItemReader bulkItemReader(){
		return new BulkItemReader();
	}

	@Bean
	@JobScope
	public ResourceTypePartitioner bulkExportResourceTypePartitioner() {
		return new ResourceTypePartitioner();
	}

	@Bean
	@StepScope
	public ResourceToFileWriter resourceToFileWriter() {
		return new ResourceToFileWriter();
	}

}
