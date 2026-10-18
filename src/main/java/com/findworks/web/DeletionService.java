package com.findworks.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * FindWorks v1 deletion policy. The legacy schema remains forward-only, but no
 * application call may schedule, mark, or purge product content.
 */
@Service
public class DeletionService {

    public ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    public void processRetention() {
        // Intentionally inert for v1.
    }

    public void processPurge() {
        // Intentionally inert for v1.
    }
}
