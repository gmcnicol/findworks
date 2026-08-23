package com.findworks.interview;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class InvitationDeliveryWorker {

    private final InvitationRepository repository;
    private final InvitationDeliveryProvider provider;
    private final InvitationProperties properties;
    private final InvitationToken tokens;

    InvitationDeliveryWorker(InvitationRepository repository, InvitationDeliveryProvider provider,
            InvitationProperties properties, InvitationToken tokens) {
        this.repository = repository;
        this.provider = provider;
        this.properties = properties;
        this.tokens = tokens;
    }

    @Scheduled(cron = "${findworks.invitation.worker-cron:*/1 * * * * *}")
    public void runNext() {
        var work = repository.claimNext();
        if (work == null) {
            return;
        }
        try {
            var token = tokens.token(work.invitationId(), work.tokenKeyId());
            var link = properties.origin().resolve("/i/" + token).toString();
            var accepted = provider.submit(new InvitationDeliveryProvider.Delivery(
                    properties.requiredSender(), work.recipientEmail(),
                    "Your FindWorks Interview Mission invitation",
                    "Hello " + work.intervieweeName() + ",\n\nUse this private seven-day link to contribute:\n"
                            + link + "\n",
                    "invitation-" + work.invitationId()));
            repository.accepted(work, accepted.providerMessageId());
        } catch (InvitationDeliveryProvider.Failure failure) {
            repository.failed(work, failure.errorClass());
        } catch (Exception error) {
            repository.failed(work, "provider_unavailable");
        }
    }
}
