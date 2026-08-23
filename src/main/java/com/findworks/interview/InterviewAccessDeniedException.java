package com.findworks.interview;

final class InterviewAccessDeniedException extends RuntimeException {

    InterviewAccessDeniedException() {
        super("This interview access is unavailable. Ask the Investigator for a reissued invitation.");
    }
}
