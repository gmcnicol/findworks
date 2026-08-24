package com.findworks.retention;

import com.findworks.interview.InvitationDeliveryProvider;
import com.findworks.interview.InvitationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class RetentionWorker {

    private final RetentionRepository repository;
    private final InvitationDeliveryProvider provider;
    private final InvitationProperties invitations;

    RetentionWorker(RetentionRepository repository, InvitationDeliveryProvider provider,
            InvitationProperties invitations) {
        this.repository = repository;
        this.provider = provider;
        this.invitations = invitations;
    }

    @Scheduled(cron = "${findworks.retention.worker-cron:0 0 * * * *}")
    public void run() {
        repository.schedule();
        sendWarning();
        purgeNext();
        repository.pruneAudit();
    }

    public void sendWarning() {
        var warning = repository.claimWarning();
        if (warning == null) {
            return;
        }
        try {
            provider.submit(new InvitationDeliveryProvider.Delivery(
                    invitations.requiredSender(), warning.recipient(),
                    "FindWorks Discovery deletion warning",
                    "Your Discovery '" + warning.discoveryTitle() + "' is scheduled for deletion on "
                            + warning.dueAt() + ". Contact support before then to extend retention.",
                    "retention-warning-" + warning.discoveryId() + "-" + warning.dueAt()));
            repository.warningSent(warning);
        } catch (InvitationDeliveryProvider.Failure failure) {
            repository.warningFailed(warning, failure.errorClass());
        } catch (Exception failure) {
            repository.warningFailed(warning, "provider_unavailable");
        }
    }

    public void purgeNext() {
        var work = repository.claimPurge();
        if (work == null) {
            return;
        }
        try {
            repository.purge(work);
        } catch (RuntimeException failure) {
            repository.purgeFailed(work);
        }
    }
}
