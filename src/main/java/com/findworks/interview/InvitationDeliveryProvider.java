package com.findworks.interview;

public interface InvitationDeliveryProvider {

    Accepted submit(Delivery delivery) throws Failure;

    record Delivery(String sender, String recipient, String subject, String textBody, String idempotencyKey) {}

    record Accepted(String providerMessageId) {
        public Accepted {
            if (providerMessageId == null || providerMessageId.isBlank()) {
                throw new IllegalArgumentException("Provider acceptance needs an opaque message ID.");
            }
        }
    }

    final class Failure extends Exception {
        private final String errorClass;

        public Failure(String errorClass) {
            super(errorClass);
            if (!java.util.Set.of("provider_unconfigured", "provider_rejected", "provider_unavailable")
                    .contains(errorClass)) {
                throw new IllegalArgumentException("Unsupported provider failure class.");
            }
            this.errorClass = errorClass;
        }

        public String errorClass() {
            return errorClass;
        }
    }
}
