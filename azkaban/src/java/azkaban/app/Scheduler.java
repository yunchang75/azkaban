/*
 * Copyright 2010 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.app;

import azkaban.util.process.ProcessFailureException;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import azkaban.flow.*;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.Duration;
import org.joda.time.DurationFieldType;
import org.joda.time.Hours;
import org.joda.time.Minutes;
import org.joda.time.ReadablePartial;
import org.joda.time.ReadablePeriod;
import org.joda.time.Seconds;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.PeriodFormat;

import azkaban.common.utils.Props;
import azkaban.common.utils.Utils;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

/**
 * A scheduler that kicks off jobs at a given time on a repeating schedule.
 * 
 * @author jkreps
 */
public class Scheduler {

    private static DateTimeFormatter FILE_DATEFORMAT = DateTimeFormat.forPattern("yyyy-MM-dd.HH.mm.ss.SSS");

    private final JobManager _jobManager;
    private final Mailman _mailman;
    private final Map<String, ScheduledJob> _scheduled;
    private final Map<String, ScheduledJobAndInstance> _executing;
    private final Multimap<String, ScheduledJob> _completed;
    private final DateTimeFormatter _dateFormat = DateTimeFormat.forPattern("MM-dd-yyyy HH:mm:ss:SSS");
    // private final ClassLoader _baseClassLoader;
    private final String _jobSuccessEmail;
    private final String _jobFailureEmail;
    private final File _scheduleFile;
    private final File _scheduleBackupFile;

    private static Logger logger = Logger.getLogger(Scheduler.class);

    private final ScheduledThreadPoolExecutor _executor;
    private final FlowManager allKnownFlows;

    private Properties _runtimeProps = null;

    public Scheduler(JobManager jobManager,
                     FlowManager allKnownFlows,
                     Mailman mailman,
                     String jobSuccessEmail,
                     String jobFailureEmail,
                     ClassLoader classLoader,
                     File scheduleFile,
                     File backupScheduleFile,
                     int numThreads) {
        this.allKnownFlows = allKnownFlows;
        Multimap<String, ScheduledJob> typedMultiMap = HashMultimap.create();

        _scheduleFile = scheduleFile;
        _scheduleBackupFile = backupScheduleFile;
        _jobManager = Utils.nonNull(jobManager);
        _mailman = mailman;
        _completed = Multimaps.synchronizedMultimap(typedMultiMap);
        _scheduled = new ConcurrentHashMap<String, ScheduledJob>();
        _executing = new ConcurrentHashMap<String, ScheduledJobAndInstance>();
        // _baseClassLoader = classLoader;
        _jobSuccessEmail = jobSuccessEmail;
        _jobFailureEmail = jobFailureEmail;
        _executor = new ScheduledThreadPoolExecutor(numThreads, new SchedulerThreadFactory());

        // Don't, by default, keep running scheduled tasks after shutdown.
        _executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        loadSchedule();
    }

    /**
     * set runtime properties
     * 
     * @param p
     */
    public void setRuntimeProperties(Properties p) {
        _runtimeProps = p;
    }

    /**
     * get runtime property
     * 
     * @param name property name
     * @return property value
     */
    public String getRuntimeProperty(String name) {
        return (_runtimeProps == null) ? null : _runtimeProps.getProperty(name);
    }

    /**
     * set runtime property
     * 
     * @param name property name
     * @param value property value
     */
    public void setRuntimeProperty(String name, String value) {
        if(_runtimeProps == null) {
            _runtimeProps = new Properties();
        }
        _runtimeProps.setProperty(name, value);
    }

    private void loadSchedule() {
        if(_scheduleFile != null && _scheduleBackupFile != null) {
            if(_scheduleFile.exists()) {
                loadFromFile(_scheduleFile);
            } else if(_scheduleBackupFile.exists()) {
                _scheduleBackupFile.renameTo(_scheduleFile);
                loadFromFile(_scheduleFile);

                logger.warn("Scheduler attempting to recover from backup file.");
            } else {
                logger.warn("No schedule files found looking for "
                            + _scheduleFile.getAbsolutePath());
            }
        }
    }

    private void loadFromFile(File schedulefile) {
        Props schedule = null;
        try {
            schedule = new Props(null, schedulefile.getAbsolutePath());
        } catch(Exception e) {
            throw new RuntimeException("Error loading schedule from " + schedulefile);
        }

        for(String key: schedule.getKeySet()) {
            ScheduledJob job = parseScheduledJob(key, schedule.get(key));
            if(job != null) {
                this.schedule(job, false);
            }
        }
    }

    private ScheduledJob parseScheduledJob(String name, String job) {
        String[] pieces = job.split("\\s+");

        if(pieces.length != 3) {
            logger.warn("Error loading schedule from file " + name);
            return null;
        }

        DateTime time = FILE_DATEFORMAT.parseDateTime(pieces[0]);
        ReadablePeriod period = parsePeriodString(name, pieces[1]);
        Boolean dependency = Boolean.parseBoolean(pieces[2]);
        if(dependency == null) {
            dependency = false;
        }
        if(period == null) {
            if(time.isAfterNow()) {
                return new ScheduledJob(name, time, period, dependency);
            } else {
                logger.warn("Non recurring job scheduled in past. Will not reschedule " + name);
                return null;
            }
        }

        // Update the time with the period.
        DateTime date = updatedTime(time, period);
        return new ScheduledJob(name, date, period, dependency);
    }

    /**
     * Schedule this job to run one time at the specified date
     * 
     * @param jobId An id of the job to run
     * @param date The date at which to kick off the job
     */
    public ScheduledFuture<?> schedule(String jobId, DateTime date, boolean ignoreDep) {
        logger.info("Scheduling job '" + jobId + "' for " + _dateFormat.print(date));
        return schedule(new ScheduledJob(jobId, _jobManager, date, ignoreDep), true);
    }

    /**
     * Schedule this flow to run one time at the specified date
     * 
     * @param holder The execution of the flow to run
     */
    public ScheduledFuture<?> scheduleNow(FlowExecutionHolder holder) {
        ExecutableFlow flow = holder.getFlow();
        logger.info("Scheduling job '" + flow.getName() + "' for now");

        final ScheduledJob schedJob = new ScheduledJob(flow.getName(),
                                                       _jobManager,
                                                       new DateTime(),
                                                       true);

        // mark the job as scheduled
        _scheduled.put(flow.getName(), schedJob);

        return _executor.schedule(new ScheduledFlow(holder, schedJob), 1, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule this flow to run one time at the specified date
     * 
     * @param holder The execution of the flow to run
     */
    public ScheduledFuture<?> scheduleNow(ExecutableFlow flow) {
        final Props parentProps = produceParentProperties(flow);
        FlowExecutionHolder holder = new FlowExecutionHolder(flow, parentProps);
        logger.info("Scheduling job '" + flow.getName() + "' for now");

        final ScheduledJob schedJob = new ScheduledJob(flow.getName(),
                                                       _jobManager,
                                                       new DateTime(),
                                                       true);

        // mark the job as scheduled
        _scheduled.put(flow.getName(), schedJob);

        return _executor.schedule(new ScheduledFlow(holder, schedJob), 1, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Schedule this job to run on a recurring basis beginning at the given
     * dateTime and repeating every period units of time forever
     * 
     * @param jobId The id for the job to schedule
     * @param dateTime The date on which to first start the job
     * @param period The period on which the job repeats
     */
    public ScheduledFuture<?> schedule(String jobId,
                                       DateTime dateTime,
                                       ReadablePeriod period,
                                       boolean ignoreDep) {
        logger.info("Scheduling job '" + jobId + "' for " + _dateFormat.print(dateTime)
                    + " with a period of " + PeriodFormat.getDefault().print(period));
        return schedule(new ScheduledJob(jobId, dateTime, period, ignoreDep), true);
    }

    /**
     * Schedule the given job to run at the next occurance of the partially
     * specified date, and repeating on the given period. For example if the
     * partial date is 12:00pm then the job will kick of the next time it is
     * 12:00pm
     * 
     * @param jobId An id for the job
     * @param partial A description of the date to run on
     * @param period The period on which the job should repeat
     */
    public ScheduledFuture<?> schedule(String jobId,
                                       ReadablePartial partial,
                                       ReadablePeriod period,
                                       boolean ignoreDep) {
        // compute the next occurrence of this date
        DateTime now = new DateTime();
        DateTime date = now.withFields(partial);
        if(period != null) {
            date = updatedTime(date, period);
        } else if(now.isAfter(date)) {
            // Will try to schedule non recurring for tomorrow
            date = date.plusDays(1);
        }

        if(now.isAfter(date)) {
            // Schedule is non recurring.
            logger.info("Scheduled Job " + jobId + " was originally scheduled for "
                        + _dateFormat.print(date));
            return null;
        }

        logger.info("Scheduling job '"
                    + jobId
                    + "' for "
                    + _dateFormat.print(date)
                    + (period != null ? " with a period of "
                                        + PeriodFormat.getDefault().print(period) : ""));
        return schedule(new ScheduledJob(jobId, date, period, ignoreDep), true);
    }

    private ScheduledFuture<?> schedule(final ScheduledJob schedJob, boolean saveResults) {
        // fail fast if there is a problem with this job
        _jobManager.validateJob(schedJob.getId());

        Duration wait = new Duration(new DateTime(), schedJob.getScheduledExecution());
        if(wait.getMillis() < -1000) {
            logger.warn("Job " + schedJob.getId() + " is scheduled for "
                        + DateTimeFormat.shortDateTime().print(schedJob.getScheduledExecution())
                        + " which is " + (PeriodFormat.getDefault().print(wait.toPeriod()))
                        + " in the past, adjusting scheduled date to now.");
            wait = new Duration(0);
        }

        // mark the job as scheduled
        _scheduled.put(schedJob.getId(), schedJob);

        if(saveResults) {
            try {
                saveSchedule();
            } catch(IOException e) {
                throw new RuntimeException("Error saving schedule after scheduling job "
                                           + schedJob.getId());
            }
        }

        ScheduledRunnable runnable = new ScheduledRunnable(schedJob);
        schedJob.setScheduledRunnable(runnable);
        return _executor.schedule(runnable, wait.getMillis(), TimeUnit.MILLISECONDS);
    }

    private DateTime updatedTime(DateTime scheduledDate, ReadablePeriod period) {
        DateTime now = new DateTime();
        DateTime date = new DateTime(scheduledDate);
        int count = 0;
        while(now.isAfter(date)) {
            if(count > 100000) {
                throw new IllegalStateException("100000 increments of period did not get to present time.");
            }

            if(period == null) {
                break;
            } else {
                date = date.plus(period);
            }

            count += 1;
        }

        return date;
    }

    private void saveSchedule() throws IOException {
        // Save if different
        if(_scheduleFile != null && _scheduleBackupFile != null) {
            // Delete the backup if it exists and a current file exists.
            if(_scheduleBackupFile.exists() && _scheduleFile.exists()) {
                _scheduleBackupFile.delete();
            }

            // Rename the schedule if it exists.
            if(_scheduleFile.exists()) {
                _scheduleFile.renameTo(_scheduleBackupFile);
            }

            Props prop = createScheduleProps();
            // Create the new schedule file

            prop.storeLocal(_scheduleFile);
        }
    }

    private Props createScheduleProps() {
        Props props = new Props();
        for(ScheduledJob job: _scheduled.values()) {
            String name = job.getId();
            ReadablePeriod period = job.getPeriod();
            String periodStr = createPeriodString(period);

            DateTime time = job.getScheduledExecution();
            String nextScheduledStr = time.toString(FILE_DATEFORMAT);

            String dependency = String.valueOf(job.isDependencyIgnored());

            props.put(name, nextScheduledStr + " " + periodStr + " " + dependency);
        }

        return props;
    }

    private ReadablePeriod parsePeriodString(String jobname, String periodStr) {
        ReadablePeriod period;
        char periodUnit = periodStr.charAt(periodStr.length() - 1);
        if(periodUnit == 'n') {
            return null;
        }

        int periodInt = Integer.parseInt(periodStr.substring(0, periodStr.length() - 1));
        switch(periodUnit) {
            case 'd':
                period = Days.days(periodInt);
                break;
            case 'h':
                period = Hours.hours(periodInt);
                break;
            case 'm':
                period = Minutes.minutes(periodInt);
                break;
            case 's':
                period = Seconds.seconds(periodInt);
                break;
            default:
                throw new IllegalArgumentException("Invalid schedule period unit '" + periodUnit
                                                   + "' for job " + jobname);
        }

        return period;
    }

    private String createPeriodString(ReadablePeriod period) {
        String periodStr = "n";

        if(period == null) {
            return "n";
        }

        if(period.get(DurationFieldType.days()) > 0) {
            int days = period.get(DurationFieldType.days());
            periodStr = days + "d";
        } else if(period.get(DurationFieldType.hours()) > 0) {
            int hours = period.get(DurationFieldType.hours());
            periodStr = hours + "h";
        } else if(period.get(DurationFieldType.minutes()) > 0) {
            int minutes = period.get(DurationFieldType.minutes());
            periodStr = minutes + "m";
        } else if(period.get(DurationFieldType.seconds()) > 0) {
            int seconds = period.get(DurationFieldType.seconds());
            periodStr = seconds + "s";
        }

        return periodStr;
    }

    /*
     * Wrap a single exception with the name of the scheduled job
     */
    private void sendErrorEmail(ScheduledJob job,
                                Throwable exception,
                                String senderAddress,
                                List<String> emailList) {
        Map<String, Throwable> map = new HashMap<String, Throwable>();
        map.put(job.getId(), exception);
        sendErrorEmail(job, map, senderAddress, emailList);
    }

    /*
     * Send error email
     * 
     * @param job scheduled job
     * 
     * @param exceptions exceptions thrown by failed jobs
     * 
     * @param senderAddress email address of sender
     * 
     * @param emailList email addresses of receivers
     */
    private void sendErrorEmail(ScheduledJob job,
                                Map<String, Throwable> exceptions,
                                String senderAddress,
                                List<String> emailList) {
        if((emailList == null || emailList.isEmpty()) && _jobFailureEmail != null)
            emailList = Arrays.asList(_jobFailureEmail);

        if(emailList != null && _mailman != null) {
            try {

                StringBuffer body = new StringBuffer("The job '"
                                                     + job.getId()
                                                     + "' running on "
                                                     + InetAddress.getLocalHost().getHostName()
                                                     + " has failed with the following errors: \r\n\r\n");
                int errorNo = 1;
                String logUrlPrefix = _runtimeProps != null ? _runtimeProps.getProperty(AppCommon.DEFAULT_LOG_URL_PREFIX)
                                                           : null;
                if(logUrlPrefix == null && _runtimeProps != null) {
                    logUrlPrefix = _runtimeProps.getProperty(AppCommon.LOG_URL_PREFIX);
                }

                final int lastLogLineNum = 60;
                for(Map.Entry<String, Throwable> entry: exceptions.entrySet()) {
                    final String jobId = entry.getKey();
                    final Throwable exception = entry.getValue();

                    /* append job exception */
                    String error = (exception instanceof ProcessFailureException) ? ((ProcessFailureException) exception).getLogSnippet()
                                                                                 : Utils.stackTrace(exception);
                    body.append(" Job " + errorNo + ". " + jobId + ":\n" + error + "\n");

                    /* append log file link */
                    JobExecution jobExec = _jobManager.loadMostRecentJobExecution(jobId);
                    if(jobExec == null) {
                        body.append("Job execution object is null for jobId:" + jobId + "\n\n");
                    }

                    String logPath = jobExec != null ? jobExec.getLog() : null;
                    if(logPath == null) {
                        body.append("Log path is null. \n\n");
                    } else {
                        body.append("See log in " + logUrlPrefix + logPath + "\n\n" + "The last "
                                    + lastLogLineNum + " lines in the log are:\n");

                        /* append last N lines of the log file */
                        String logFilePath = this._jobManager.getLogDir() + File.separator
                                             + logPath;
                        Vector<String> lastNLines = Utils.tail(logFilePath, 60);

                        if(lastNLines != null) {
                            for(String line: lastNLines) {
                                body.append(line + "\n");
                            }
                        }
                    }

                    errorNo++;
                }

                // logger.error("\n\n error email body: \n" + body.toString() +
                // "\n");

                _mailman.sendEmailIfPossible(senderAddress,
                                             emailList,
                                             "Job '" + job.getId() + "' has failed!",
                                             body.toString());

            } catch(UnknownHostException uhe) {
                logger.error(uhe);
            }
        }
    }

    private void sendSuccessEmail(ScheduledJob job,
                                  Duration duration,
                                  String senderAddress,
                                  List<String> emailList) {
        if((emailList == null || emailList.isEmpty()) && _jobSuccessEmail != null) {
            emailList = Arrays.asList(_jobSuccessEmail);
        }

        if(emailList != null && _mailman != null) {
            try {
                _mailman.sendEmailIfPossible(senderAddress,
                                             emailList,
                                             "Job '" + job.getId() + "' has completed on "
                                                     + InetAddress.getLocalHost().getHostName()
                                                     + "!",
                                             "The job '"
                                                     + job.getId()
                                                     + "' completed in "
                                                     + PeriodFormat.getDefault()
                                                                   .print(duration.toPeriod())
                                                     + ".");
            } catch(UnknownHostException uhe) {
                logger.error(uhe);
            }
        }
    }

    public void cancel(String name) throws Exception {
        ScheduledJobAndInstance instance = _executing.get(name);
        if(instance == null) {
            throw new IllegalArgumentException("'" + name + "' is not currently running.");
        }
        instance.getExecutableFlow().cancel();
    }

    public boolean isScheduled(String name) {
        return _scheduled.containsKey(name);
    }

    public Collection<ScheduledJob> getScheduledJobs() {
        return _scheduled.values();
    }

    public boolean isExecuting(String name) {
        return _executing.containsKey(name);
    }

    public Collection<ScheduledJobAndInstance> getExecutingJobs() {
        return _executing.values();
    }

    public Multimap<String, ScheduledJob> getCompleted() {
        return _completed;
    }

    public boolean unschedule(String name) {
        ScheduledJob job = _scheduled.remove(name);
        if(job != null) {
            job.markInvalid();
            Runnable runnable = job.getScheduledRunnable();
            _executor.remove(runnable);
        }
        try {
            saveSchedule();
        } catch(IOException e) {
            throw new RuntimeException("Error saving schedule after unscheduling job " + name);
        }

        return job != null;
    }

    /**
     * A thread factory that sets the correct classloader for the thread
     */
    public class SchedulerThreadFactory implements ThreadFactory {

        private final AtomicInteger threadCount = new AtomicInteger(0);

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("scheduler-thread-" + threadCount.getAndIncrement());
            return t;
        }
    }

    public class ScheduledJobAndInstance {

        private final ExecutableFlow flow;
        private final ScheduledJob _scheduledJob;

        private ScheduledJobAndInstance(ExecutableFlow flow, ScheduledJob scheduledJob) {
            this.flow = flow;
            _scheduledJob = scheduledJob;
        }

        public ExecutableFlow getExecutableFlow() {
            return flow;
        }

        public ScheduledJob getScheduledJob() {
            return _scheduledJob;
        }
    }

    /**
     * A runnable adapter for a Job
     */
    private class ScheduledRunnable implements Runnable {

        private final ScheduledJob _scheduledJob;
        private final boolean _ignoreDep;

        private ScheduledRunnable(ScheduledJob schedJob) {
            this._scheduledJob = schedJob;
            this._ignoreDep = schedJob.isDependencyIgnored();
        }

        public void run() {
            List<String> emailList = null;
            String senderAddress = null;
            try {
                if(_scheduledJob.isInvalid()) {
                    return;
                }

                JobDescriptor desc = _jobManager.loadJobDescriptors(null, null, _ignoreDep)
                                                .get(_scheduledJob.getId());
                emailList = desc.getEmailNotificationList();

                final List<String> finalEmailList = emailList;

                final ExecutableFlow flowToRun = allKnownFlows.createNewExecutableFlow(_scheduledJob.getId());

                if(_ignoreDep) {
                    for(ExecutableFlow subFlow: flowToRun.getChildren()) {
                        subFlow.markCompleted();
                    }
                }

                senderAddress = desc.getSenderEmail();
                final String senderEmail = senderAddress;

                final Props parentProps = produceParentProperties(flowToRun);

                // mark the job as executing
                _scheduled.remove(_scheduledJob.getId());
                _scheduledJob.setStarted(new DateTime());
                _executing.put(flowToRun.getName(), new ScheduledJobAndInstance(flowToRun,
                                                                                _scheduledJob));
                flowToRun.execute(parentProps, new FlowCallback() {

                    @Override
                    public void progressMade() {
                        allKnownFlows.saveExecutableFlow(new FlowExecutionHolder(flowToRun,
                                                                                 parentProps));
                    }

                    @Override
                    public void completed(Status status) {
                        _scheduledJob.setEnded(new DateTime());

                        try {
                            allKnownFlows.saveExecutableFlow(new FlowExecutionHolder(flowToRun,
                                                                                     parentProps));
                            switch(status) {
                                case SUCCEEDED:
                                    sendSuccessEmail(_scheduledJob,
                                                     _scheduledJob.getExecutionDuration(),
                                                     senderEmail,
                                                     finalEmailList);
                                    break;
                                case FAILED:
                                    sendErrorEmail(_scheduledJob,
                                                   flowToRun.getExceptions(),
                                                   senderEmail,
                                                   finalEmailList);
                                    break;
                                default:
                                    sendErrorEmail(_scheduledJob,
                                                   new RuntimeException(String.format("Got an unknown status[%s]",
                                                                                      status)),
                                                   senderEmail,
                                                   finalEmailList);
                            }
                        } catch(RuntimeException e) {
                            logger.warn("Exception caught while saving flow/sending emails", e);
                            throw e;
                        } finally {
                            // mark the job as completed
                            _executing.remove(_scheduledJob.getId());
                            _completed.put(_scheduledJob.getId(), _scheduledJob);

                            // if this is a recurring job, schedule the next
                            // execution as well
                            if(_scheduledJob.isRecurring() && !_scheduledJob.isInvalid()) {
                                DateTime nextRun = _scheduledJob.getScheduledExecution()
                                                                .plus(_scheduledJob.getPeriod());
                                // This call will also save state.
                                schedule(_scheduledJob.getId(),
                                         nextRun,
                                         _scheduledJob.getPeriod(),
                                         _ignoreDep);
                            } else {
                                try {
                                    saveSchedule();
                                } catch(IOException e) {
                                    logger.warn("Error trying to update schedule.");
                                }
                            }
                        }
                    }
                });

                allKnownFlows.saveExecutableFlow(new FlowExecutionHolder(flowToRun, parentProps));
            } catch(Throwable t) {
                if(emailList != null) {
                    sendErrorEmail(_scheduledJob, t, senderAddress, emailList);
                }
                _scheduled.remove(_scheduledJob.getId());
                _executing.remove(_scheduledJob.getId());
                logger.warn(String.format("An exception almost made it back to the ScheduledThreadPool from job[%s]",
                                          _scheduledJob),
                            t);
            }
        }
    }

    /**
     * A runnable adapter for a Job
     */
    private class ScheduledFlow implements Runnable {

        private final ScheduledJob _scheduledJob;
        private final FlowExecutionHolder holder;

        private ScheduledFlow(FlowExecutionHolder holder, ScheduledJob scheduledJob) {
            this.holder = holder;
            this._scheduledJob = scheduledJob;
        }

        public void run() {
            final ExecutableFlow flow = holder.getFlow();
            logger.info("Starting run of " + flow.getName());

            List<String> emailList = null;
            String senderAddress = null;
            try {
                emailList = _jobManager.getJobDescriptor(flow.getName()).getEmailNotificationList();
                final List<String> finalEmailList = emailList;

                senderAddress = _jobManager.getJobDescriptor(flow.getName()).getSenderEmail();
                final String senderEmail = senderAddress;

                // mark the job as executing
                _scheduled.remove(_scheduledJob.getId());
                _scheduledJob.setStarted(new DateTime());
                _executing.put(flow.getName(), new ScheduledJobAndInstance(flow, _scheduledJob));
                flow.execute(holder.getParentProps(), new FlowCallback() {

                    @Override
                    public void progressMade() {
                        allKnownFlows.saveExecutableFlow(holder);
                    }

                    @Override
                    public void completed(Status status) {
                        _scheduledJob.setEnded(new DateTime());

                        try {
                            allKnownFlows.saveExecutableFlow(holder);
                            switch(status) {
                                case SUCCEEDED:
                                    sendSuccessEmail(_scheduledJob,
                                                     _scheduledJob.getExecutionDuration(),
                                                     senderEmail,
                                                     finalEmailList);
                                    break;
                                case FAILED:
                                    sendErrorEmail(_scheduledJob,
                                                   flow.getExceptions(),
                                                   senderEmail,
                                                   finalEmailList);
                                    break;
                                default:
                                    sendErrorEmail(_scheduledJob,
                                                   new RuntimeException(String.format("Got an unknown status[%s]",
                                                                                      status)),
                                                   senderEmail,
                                                   finalEmailList);
                            }
                        } catch(RuntimeException e) {
                            logger.warn("Exception caught while saving flow/sending emails", e);
                            throw e;
                        } finally {
                            // mark the job as completed
                            _executing.remove(_scheduledJob.getId());
                            _completed.put(_scheduledJob.getId(), _scheduledJob);
                        }
                    }
                });

                allKnownFlows.saveExecutableFlow(holder);
            } catch(Throwable t) {
                if(emailList != null) {
                    sendErrorEmail(_scheduledJob, t, senderAddress, emailList);
                }
                _scheduled.remove(_scheduledJob.getId());
                _executing.remove(_scheduledJob.getId());
                logger.warn(String.format("An exception almost made it back to the ScheduledThreadPool from job[%s]",
                                          _scheduledJob),
                            t);
            }
        }
    }

    private Props produceParentProperties(final ExecutableFlow flow) {
        Props parentProps = new Props();

        parentProps.put("azkaban.flow.id", flow.getId());
        parentProps.put("azkaban.flow.uuid", UUID.randomUUID().toString());

        DateTime loadTime = new DateTime();

        parentProps.put("azkaban.flow.start.timestamp", loadTime.toString());
        parentProps.put("azkaban.flow.start.year", loadTime.toString("yyyy"));
        parentProps.put("azkaban.flow.start.month", loadTime.toString("MM"));
        parentProps.put("azkaban.flow.start.day", loadTime.toString("dd"));
        parentProps.put("azkaban.flow.start.hour", loadTime.toString("HH"));
        parentProps.put("azkaban.flow.start.minute", loadTime.toString("mm"));
        parentProps.put("azkaban.flow.start.seconds", loadTime.toString("ss"));
        parentProps.put("azkaban.flow.start.milliseconds", loadTime.toString("SSS"));
        parentProps.put("azkaban.flow.start.timezone", loadTime.toString("ZZZZ"));
        return parentProps;
    }
}
