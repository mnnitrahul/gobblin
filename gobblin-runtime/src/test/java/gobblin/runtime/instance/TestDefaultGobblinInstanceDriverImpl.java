/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.runtime.instance;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.util.concurrent.Service.State;
import com.typesafe.config.ConfigFactory;

import gobblin.metrics.MetricContext;
import gobblin.runtime.api.Configurable;
import gobblin.runtime.api.GobblinInstancePluginFactory;
import gobblin.runtime.api.JobExecutionLauncher;
import gobblin.runtime.api.JobSpec;
import gobblin.runtime.api.JobSpecScheduler;
import gobblin.runtime.job_catalog.InMemoryJobCatalog;
import gobblin.runtime.std.DefaultConfigurableImpl;
import gobblin.runtime.std.DefaultJobSpecScheduleImpl;
import gobblin.testing.AssertWithBackoff;

/**
 * Unit tests for {@link DefaultGobblinInstanceDriverImpl}
 */
public class TestDefaultGobblinInstanceDriverImpl {

  @Test
  public void testScheduling() throws Exception {
    final Logger log = LoggerFactory.getLogger(getClass().getName() + ".testScheduling");
    final Optional<Logger> loggerOpt = Optional.of(log);
    InMemoryJobCatalog jobCatalog = new InMemoryJobCatalog(loggerOpt);

    JobSpecScheduler scheduler = Mockito.mock(JobSpecScheduler.class);
    JobExecutionLauncher jobLauncher = Mockito.mock(JobExecutionLauncher.class);
    Configurable sysConfig = DefaultConfigurableImpl.createFromConfig(ConfigFactory.empty());

    final DefaultGobblinInstanceDriverImpl driver =
        new StandardGobblinInstanceDriver("testScheduling", sysConfig, jobCatalog, scheduler,
            jobLauncher,
            Optional.<MetricContext>absent(),
            loggerOpt,
            Collections.<GobblinInstancePluginFactory>emptyList());

    JobSpec js1_1 = JobSpec.builder("test.job1").withVersion("1").build();
    JobSpec js1_2 = JobSpec.builder("test.job1").withVersion("2").build();
    JobSpec js2 = JobSpec.builder("test.job2").withVersion("1").build();

    driver.startAsync().awaitRunning(100, TimeUnit.MILLISECONDS);
    long startTimeMs = System.currentTimeMillis();
    Assert.assertTrue(driver.isRunning());
    Assert.assertTrue(driver.isInstrumentationEnabled());
    Assert.assertNotNull(driver.getMetricContext());

    jobCatalog.put(js1_1);

    AssertWithBackoff awb = AssertWithBackoff.create().backoffFactor(1.5).maxSleepMs(100)
        .timeoutMs(1000).logger(log);
    awb.assertTrue(new Predicate<Void>() {
      @Override public boolean apply(Void input) {
        log.debug("upFlag=" + driver.getMetrics().getUpFlag().getValue().intValue());
        return driver.getMetrics().getUpFlag().getValue().intValue() == 1;
      }
    }, "upFlag==1");

    jobCatalog.put(js2);
    jobCatalog.put(js1_2);
    jobCatalog.remove(js2.getUri());

    Mockito.when(
        scheduler.scheduleJob(Mockito.eq(js1_1),
                              Mockito.any(DefaultGobblinInstanceDriverImpl.JobSpecRunnable.class)))
           .thenReturn(DefaultJobSpecScheduleImpl.createNoSchedule(js1_1, null));
    Mockito.when(
        scheduler.scheduleJob(Mockito.eq(js2),
                              Mockito.any(DefaultGobblinInstanceDriverImpl.JobSpecRunnable.class)))
           .thenReturn(DefaultJobSpecScheduleImpl.createNoSchedule(js2, null));
    Mockito.when(
        scheduler.scheduleJob(Mockito.eq(js1_2),
                              Mockito.any(DefaultGobblinInstanceDriverImpl.JobSpecRunnable.class)))
           .thenReturn(DefaultJobSpecScheduleImpl.createNoSchedule(js1_2, null));

    Mockito.verify(scheduler).scheduleJob(Mockito.eq(js1_1),
        Mockito.any(DefaultGobblinInstanceDriverImpl.JobSpecRunnable.class));
    Mockito.verify(scheduler).scheduleJob(Mockito.eq(js2),
        Mockito.any(DefaultGobblinInstanceDriverImpl.JobSpecRunnable.class));
    Mockito.verify(scheduler).scheduleJob(Mockito.eq(js1_2),
        Mockito.any(DefaultGobblinInstanceDriverImpl.JobSpecRunnable.class));
    Mockito.verify(scheduler).unscheduleJob(Mockito.eq(js2.getUri()));

    final long elapsedMs = System.currentTimeMillis()  - startTimeMs;


    awb.assertTrue(new Predicate<Void>() {
      @Override public boolean apply(Void input) {
        long uptimeMs = driver.getMetrics().getUptimeMs().getValue().longValue();
        return uptimeMs >= elapsedMs;
      }
    }, "uptime > elapsedMs");
    long uptimeMs = driver.getMetrics().getUptimeMs().getValue().longValue();
    Assert.assertTrue(uptimeMs <= 2 * elapsedMs, "uptime=" + uptimeMs + " elapsedMs=" + elapsedMs);

    driver.stopAsync();
    driver.awaitTerminated(100, TimeUnit.MILLISECONDS);
    Assert.assertEquals(driver.state(), State.TERMINATED);
    Assert.assertEquals(driver.getMetrics().getUpFlag().getValue().intValue(), 0);
    // Need an assert with retries because Guava service container notifications are async
    awb.assertTrue(new Predicate<Void>() {
      @Override public boolean apply(Void input) {
        log.debug("upTimeMs=" + driver.getMetrics().getUptimeMs().getValue().longValue());
        return driver.getMetrics().getUptimeMs().getValue().longValue() == 0;
      }
    }, "upTimeMs==0");
  }

}
