package dev.livingsky;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Network-backed update (periodic forecast refresh, or one-shot after a tap / move / boot). */
public class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        int fetch = params.getExtras().getInt("fetch", Updater.FETCH_IF_STALE);
        Updater.runAsync(this, params.getJobId() == Scheduler.JOB_PERIODIC ? "periodic" : "job", fetch,
                () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
