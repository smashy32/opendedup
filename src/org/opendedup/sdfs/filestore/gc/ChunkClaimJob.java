package org.opendedup.sdfs.filestore.gc;

import java.io.IOException;


import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

public class ChunkClaimJob implements Job{

	@Override
	public void execute(JobExecutionContext ctx) throws JobExecutionException {
		try {
			//MemoryHashStore.claimAllHashes();
		} catch (Exception e) {
			throw new JobExecutionException(e);
		}
	}

}
