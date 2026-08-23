package com.findworks.interview;

import org.springframework.stereotype.Component;

@Component
final class UnavailableInvitationDeliveryProvider implements InvitationDeliveryProvider {

    @Override
    public Accepted submit(Delivery delivery) throws Failure {
        throw new Failure("provider_unconfigured");
    }
}
