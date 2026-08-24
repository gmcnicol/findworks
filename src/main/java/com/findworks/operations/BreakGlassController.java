package com.findworks.operations;

import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@ConditionalOnExpression("'${findworks.process-role:local}' == 'local' || '${findworks.process-role:local}' == 'web'")
final class BreakGlassController {

    private final OperationsRepository operations;

    BreakGlassController(OperationsRepository operations) {
        this.operations = operations;
    }

    @GetMapping("/operations/break-glass/{requestId}")
    String review(Principal principal, @PathVariable UUID requestId, Model model) {
        model.addAttribute("request", operations.reviewBreakGlass(requestId, principal.getName()));
        return "break-glass-review";
    }

    @PostMapping("/operations/break-glass/{requestId}")
    String decide(Principal principal, @PathVariable UUID requestId,
            @RequestParam String decision) {
        if (!java.util.Set.of("approve", "reject").contains(decision)) {
            throw new IllegalArgumentException("Choose approve or reject.");
        }
        operations.decideBreakGlass(requestId, "approve".equals(decision), principal.getName());
        return "redirect:/operations/break-glass/" + requestId;
    }
}
