package org.smartregister.anc.job;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.evernote.android.job.Job;
import com.evernote.android.job.JobCreator;

/**
 * Created by ndegwamartin on 05/09/2018.
 */
public class AncJobCreator implements JobCreator {
    @Nullable
    @Override
    public Job create(@NonNull String tag) {
        switch (tag) {
            case SyncServiceJob.TAG:
                return new SyncServiceJob();
            case ExtendedSyncServiceJob.TAG:
                return new ExtendedSyncServiceJob();
            case ImageUploadServiceJob.TAG:
                return new ImageUploadServiceJob();
            case PullUniqueIdsServiceJob.TAG:
                return new PullUniqueIdsServiceJob();
            case ValidateSyncDataServiceJob.TAG:
                return new ValidateSyncDataServiceJob();
            case ViewConfigurationsServiceJob.TAG:
                return new ViewConfigurationsServiceJob();
            case SyncSettingsServiceJob.TAG:
                return new SyncSettingsServiceJob();
            default:
                Log.d(AncJobCreator.class.getCanonicalName(), "Looks like you tried to create a job " + tag + " that is not declared in the Anc Job Creator");
                return null;
        }
    }
}
