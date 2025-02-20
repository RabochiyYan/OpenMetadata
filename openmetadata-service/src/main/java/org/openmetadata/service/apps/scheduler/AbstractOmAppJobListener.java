package org.openmetadata.service.apps.scheduler;

import static org.openmetadata.service.apps.scheduler.AppScheduler.APP_INFO_KEY;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.openmetadata.schema.entity.app.App;
import org.openmetadata.schema.entity.app.AppRunRecord;
import org.openmetadata.schema.entity.app.AppRunType;
import org.openmetadata.schema.entity.app.FailureContext;
import org.openmetadata.schema.entity.app.SuccessContext;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.util.JsonUtils;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;

public abstract class AbstractOmAppJobListener implements JobListener {
  private final CollectionDAO collectionDAO;
  private static final String SCHEDULED_APP_RUN_EXTENSION = "AppScheduleRun";
  public static final String APP_RUN_STATS = "AppRunStats";
  public static final String JOB_LISTENER_NAME = "OM_JOB_LISTENER";

  protected AbstractOmAppJobListener(CollectionDAO dao) {
    this.collectionDAO = dao;
  }

  @Override
  public String getName() {
    return JOB_LISTENER_NAME;
  }

  @Override
  public void jobToBeExecuted(JobExecutionContext jobExecutionContext) {
    AppRunType runType =
        AppRunType.fromValue(
            (String) jobExecutionContext.getJobDetail().getJobDataMap().get("triggerType"));
    App jobApp =
        JsonUtils.readOrConvertValue(
            jobExecutionContext.getJobDetail().getJobDataMap().get(APP_INFO_KEY), App.class);
    JobDataMap dataMap = jobExecutionContext.getJobDetail().getJobDataMap();
    long jobStartTime = System.currentTimeMillis();
    AppRunRecord runRecord;
    boolean update = false;
    if (jobExecutionContext.isRecovering()) {
      runRecord =
          JsonUtils.readValue(
              collectionDAO.appExtensionTimeSeriesDao().getLatestAppRun(jobApp.getId()),
              AppRunRecord.class);
      update = true;
    } else {
      runRecord =
          new AppRunRecord()
              .withAppId(jobApp.getId())
              .withStartTime(jobStartTime)
              .withTimestamp(jobStartTime)
              .withRunType(runType)
              .withStatus(AppRunRecord.Status.RUNNING)
              .withScheduleInfo(jobApp.getAppSchedule());
    }
    // Put the Context in the Job Data Map
    dataMap.put(SCHEDULED_APP_RUN_EXTENSION, JsonUtils.pojoToJson(runRecord));

    // Insert new Record Run
    pushApplicationStatusUpdates(jobExecutionContext, runRecord, update);

    this.doJobToBeExecuted(jobExecutionContext);
  }

  @Override
  public void jobExecutionVetoed(JobExecutionContext jobExecutionContext) {}

  @Override
  public void jobWasExecuted(
      JobExecutionContext jobExecutionContext, JobExecutionException jobException) {
    AppRunRecord runRecord =
        JsonUtils.readOrConvertValue(
            jobExecutionContext.getJobDetail().getJobDataMap().get(SCHEDULED_APP_RUN_EXTENSION),
            AppRunRecord.class);
    Object jobStats = jobExecutionContext.getJobDetail().getJobDataMap().get(APP_RUN_STATS);
    long endTime = System.currentTimeMillis();
    runRecord.withEndTime(endTime);

    if (jobException == null
        && !(runRecord.getStatus() == AppRunRecord.Status.FAILED
            || runRecord.getStatus() == AppRunRecord.Status.ACTIVE_ERROR)) {
      runRecord.withStatus(AppRunRecord.Status.SUCCESS);
      SuccessContext context = new SuccessContext();
      if (runRecord.getSuccessContext() != null) {
        context = runRecord.getSuccessContext();
      }
      context.getAdditionalProperties().put("stats", JsonUtils.getMap(jobStats));
      runRecord.setSuccessContext(context);
    } else {
      runRecord.withStatus(AppRunRecord.Status.FAILED);
      FailureContext context = new FailureContext();
      if (runRecord.getFailureContext() != null) {
        context = runRecord.getFailureContext();
      }
      if (jobException != null) {
        Map<String, Object> failure = new HashMap<>();
        failure.put("message", jobException.getMessage());
        failure.put("jobStackTrace", ExceptionUtils.getStackTrace(jobException));
        context.withAdditionalProperty("failure", failure);
      }
      runRecord.setFailureContext(context);
    }

    // Update App Run Record
    pushApplicationStatusUpdates(jobExecutionContext, runRecord, true);

    this.doJobWasExecuted(jobExecutionContext, jobException);
  }

  public AppRunRecord getAppRunRecordForJob(JobExecutionContext context) {
    JobDataMap dataMap = context.getJobDetail().getJobDataMap();
    return JsonUtils.readOrConvertValue(
        dataMap.get(SCHEDULED_APP_RUN_EXTENSION), AppRunRecord.class);
  }

  public void pushApplicationStatusUpdates(
      JobExecutionContext context, AppRunRecord runRecord, boolean update) {
    JobDataMap dataMap = context.getJobDetail().getJobDataMap();
    if (dataMap.containsKey(SCHEDULED_APP_RUN_EXTENSION)) {
      // Update the Run Record in Data Map
      dataMap.put(SCHEDULED_APP_RUN_EXTENSION, JsonUtils.pojoToJson(runRecord));

      // Push Updates to the Database
      App jobApp =
          JsonUtils.readOrConvertValue(
              context.getJobDetail().getJobDataMap().get(APP_INFO_KEY), App.class);
      updateStatus(jobApp.getId(), runRecord, update);
    }
  }

  private void updateStatus(UUID appId, AppRunRecord appRunRecord, boolean update) {
    if (update) {
      collectionDAO
          .appExtensionTimeSeriesDao()
          .update(
              appId.toString(), JsonUtils.pojoToJson(appRunRecord), appRunRecord.getTimestamp());
    } else {
      collectionDAO.appExtensionTimeSeriesDao().insert(JsonUtils.pojoToJson(appRunRecord));
    }
  }

  protected void doJobWasExecuted(
      JobExecutionContext jobExecutionContext, JobExecutionException jobException) {}

  protected void doJobToBeExecuted(JobExecutionContext jobExecutionContext) {}
}
