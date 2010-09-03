package azkaban.jobs;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import azkaban.app.JobDescriptor;
import azkaban.common.utils.Props;
import azkaban.util.process.AzkabanProcess;
import azkaban.util.process.AzkabanProcessBuilder;


/**
 * A job that passes all the job properties as command line arguments in "long" format, 
 * e.g. --key1 value1 --key2 value2 ...
 * 
 * @author jkreps
 *
 */
public abstract class LongArgJob extends AbstractProcessJob {
    
    private static final long KILL_TIME_MS = 5000;
    private final AzkabanProcessBuilder builder;
    private volatile AzkabanProcess process;

    public LongArgJob(String[] command, JobDescriptor desc) {
        this(command, desc, new HashSet<String>(0));
    }
    
    public LongArgJob(String[] command, JobDescriptor desc, Set<String> suppressedKeys) {
        //super(command, desc);
         super(desc);
        //String cwd = descriptor.getProps().getString(WORKING_DIR, new File(descriptor.getFullPath()).getParent());
       
        this.builder = new AzkabanProcessBuilder(command).
            setEnv(getProps().getMapByPrefix(ENV_PREFIX)).
            setWorkingDir(getCwd()).
            setLogger(getLog());
        appendProps(suppressedKeys);
    }

    public void run() throws Exception {
        
        resolveProps();
        
        long startMs = System.currentTimeMillis();
        info("Command: " + builder.getCommandString());
        if(builder.getEnv().size() > 0)
            info("Environment variables: " + builder.getEnv());
        info("Working directory: " + builder.getWorkingDir());
        
       File [] propFiles = initPropsFiles( );
       //System.err.println("outputfile=" + propFiles[1]);
        
        boolean success = false;
        this.process = builder.build();
        try {
            this.process.run();
            success = true;
        }
        catch   (Exception e) {
            for (File file: propFiles)  if (file != null && file.exists()) file.delete();
            throw new RuntimeException (e);
        }
        finally {
            this.process = null;
            info("Process completed " + (success? "successfully" : "unsuccessfully") + " in " + ((System.currentTimeMillis() - startMs) / 1000) + " seconds.");
        }
        
        // Get the output properties from this job.
        generateProperties(propFiles[1]);
                
        for (File file: propFiles)
            if (file != null && file.exists()) file.delete();
    }
    
    
    
    /**
     * This gives access to the process builder used to construct the process. An overriding class can use this to 
     * add to the command being executed.
     */
    protected AzkabanProcessBuilder getBuilder() {
        return this.builder;
    }
    
    @Override
    public void cancel() throws InterruptedException {
        if(process == null)
            throw new IllegalStateException("Not started.");
        boolean killed = process.softKill(KILL_TIME_MS, TimeUnit.MILLISECONDS);
        if(!killed) {
            warn("Kill with signal TERM failed. Killing with KILL signal.");
            process.hardKill();
        }
    }
    
    @Override
    public double getProgress() {
        return process != null && process.isComplete()? 1.0 : 0.0;
    }

    private void appendProps(Set<String> suppressed) {
        AzkabanProcessBuilder builder = this.getBuilder();
        Props props = getProps();
        for(String key: props.getKeySet())
            if(!suppressed.contains(key))
                builder.addArg("--" + key, props.get(key));
    }
   

}
